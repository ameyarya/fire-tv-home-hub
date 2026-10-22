// Voice control for the home, on an M5Stack StickS3.
//
// Hold button A and say a command; let go. The speech is recognised on the stick itself (Espressif's ESP-SR,
// MultiNet 7 English, with a fixed list of phrases from commands.cpp): no cloud, no Mac, no wake word.
// Plugs are switched directly (Kasa, found on the Wi-Fi by name), so they work with the TV off. TV keys go
// to the Smart Home app's phone-remote API on the TV, so those need the TV awake.
//
// main/secrets.h (written by setup.sh, never committed) holds the Wi-Fi name and password and the TV's
// address and phone-remote secret.

#include <M5Unified.h>

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include <esp_event.h>
#include <esp_heap_caps.h>
#include <esp_http_client.h>
#include <esp_log.h>
#include <esp_timer.h>
#include <esp_netif.h>
#include <esp_wifi.h>
#include <freertos/event_groups.h>
#include <nvs_flash.h>

#include "esp_mn_iface.h"
#include "esp_mn_models.h"
#include "esp_mn_speech_commands.h"
#include "model_path.h"

#include "commands.h"
#include "kasa.h"
#include "secrets.h"

static const char* TAG = "stick";
static const int RATE = 16000;
static const int MAX_SECONDS = 4;
static const int SEGMENT = 1024;  // 64 ms per mic recording
static const int MIC_GAIN = 3;
// Matches below SURE are shown as a question (B: yes, A: say it again) instead of acted on; below FLOOR they're
// ignored. On this stick, right guesses scored 0.28-0.74 and wrong ones 0.16-0.21.
static const float SURE = 0.40f, FLOOR = 0.25f;

struct Heard {
    int id;
    float prob;
};

static esp_mn_iface_t* multinet;
static model_iface_data_t* mn;
static int16_t* audio;  // MAX_SECONDS of mono 16 kHz, in PSRAM
static std::vector<kasa::Plug> plugs;
static EventGroupHandle_t wifi_events;

static uint32_t millis() { return (uint32_t) (esp_timer_get_time() / 1000); }

// ---- screen --------------------------------------------------------------------------------------

static void show(const char* big, const char* small = "", uint16_t color = TFT_WHITE) {
    auto& d = M5.Display;
    d.fillScreen(TFT_BLACK);
    d.setTextDatum(middle_center);
    d.setTextColor(color);
    d.setFont(&fonts::FreeSansBold12pt7b);
    d.drawString(big, d.width() / 2, d.height() / 2 - (small[0] ? 14 : 0));
    if (small[0]) {
        d.setFont(&fonts::FreeSans9pt7b);
        d.setTextColor(TFT_LIGHTGREY);
        d.drawString(small, d.width() / 2, d.height() / 2 + 22);
    }
}

static void idle() { show("Hold A", "and say a command"); }

static void mic_on();

/** The mic and speaker share the audio chip: the mic pauses for the beep and comes straight back. */
static void beep(int hz, int ms) {
    M5.Mic.end();
    M5.Speaker.begin();
    M5.Speaker.setVolume(110);
    M5.Speaker.tone(hz, ms);
    while (M5.Speaker.isPlaying()) vTaskDelay(1);
    M5.Speaker.end();
    mic_on();
}

// ---- Wi-Fi ---------------------------------------------------------------------------------------

static void on_wifi(void*, esp_event_base_t base, int32_t id, void*) {
    if (base == WIFI_EVENT && (id == WIFI_EVENT_STA_START || id == WIFI_EVENT_STA_DISCONNECTED)) {
        xEventGroupClearBits(wifi_events, 1);
        esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        xEventGroupSetBits(wifi_events, 1);
    }
}

static bool wifi_up(int ms) {
    return xEventGroupWaitBits(wifi_events, 1, pdFALSE, pdTRUE, pdMS_TO_TICKS(ms)) & 1;
}

static void wifi_start() {
    wifi_events = xEventGroupCreate();
    esp_netif_init();
    esp_event_loop_create_default();
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    esp_wifi_init(&init);
    esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_wifi, nullptr);
    esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_wifi, nullptr);
    wifi_config_t cfg{};
    strlcpy((char*) cfg.sta.ssid, WIFI_SSID, sizeof cfg.sta.ssid);
    strlcpy((char*) cfg.sta.password, WIFI_PASS, sizeof cfg.sta.password);
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_set_config(WIFI_IF_STA, &cfg);
    esp_wifi_set_ps(WIFI_PS_MIN_MODEM);
    esp_wifi_start();
}

// ---- actions -------------------------------------------------------------------------------------

static const kasa::Plug* plug(const char* alias) {
    for (auto& p : plugs) if (strcasecmp(p.alias.c_str(), alias) == 0) return &p;
    return nullptr;
}

static bool switch_plug(const char* alias, bool on) {
    const kasa::Plug* p = plug(alias);
    if (p && kasa::set(p->ip, on)) return true;
    plugs = kasa::discover();  // not found, or it moved to a new address
    p = plug(alias);
    return p && kasa::set(p->ip, on);
}

static bool tv_key(int code) {
    char url[160];
    snprintf(url, sizeof url, "http://%s:8765/api/%s/key?c=%d", TV_ADDRESS, TV_SECRET, code);
    esp_http_client_config_t cfg{};
    cfg.url = url;
    cfg.method = HTTP_METHOD_POST;
    cfg.timeout_ms = 3000;
    esp_http_client_handle_t h = esp_http_client_init(&cfg);
    esp_err_t err = esp_http_client_perform(h);
    int status = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);
    return err == ESP_OK && status == 204;
}

static void run(const Command& c) {
    show(c.done, "…");
    bool ok = false;
    const char* why = "";
    if (!wifi_up(5000)) {
        why = "No Wi-Fi";
    } else if (c.act == Act::PLUG) {
        ok = switch_plug(c.plug, c.on);
        why = "Plug not answering";
    } else if (c.act == Act::ALL) {
        if (plugs.empty()) plugs = kasa::discover();
        ok = !plugs.empty();
        for (auto& p : plugs) ok &= switch_plug(p.alias.c_str(), c.on);
        why = "A plug didn't answer";
    } else {
        ok = tv_key(c.key);
        why = "TV not answering (asleep?)";
    }
    if (ok) {
        show(c.done, "done", TFT_GREEN);
        beep(1800, 70);
    } else {
        show(c.done, why, TFT_ORANGE);
        beep(400, 250);
    }
    ESP_LOGI(TAG, "%s: %s", c.done, ok ? "ok" : why);
}

// ---- listening -----------------------------------------------------------------------------------

/** A weak match: "Plug 1 on?" B says yes. A (the talk button) means "say it again", so it can't confirm by
 *  accident; nothing for 6 s means no. Returns 1 yes, 0 no, -1 say it again. */
static int confirm(const Command& c) {
    char q[48];
    snprintf(q, sizeof q, "%s?", c.done);
    show(q, "B: yes    A: say again", TFT_YELLOW);
    beep(1000, 40);
    uint32_t t0 = millis();
    while (millis() - t0 < 6000) {
        M5.update();
        if (M5.BtnB.wasPressed()) return 1;
        if (M5.BtnA.isPressed()) return -1;
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    return 0;
}

// ---- listening: recognised while you speak ---------------------------------------------------------
//
// The mic stays on between commands, so pressing A records at once. Each 64 ms piece goes to the recogniser
// task on the second core as soon as it's recorded; letting go adds a little silence so it knows the phrase
// ended, and the answer is usually ready by then.

static QueueHandle_t to_recogniser, from_recogniser;
static const int END = -1;
static const int TAIL = 32;   // chunks of silence after letting go (32 x 32 ms: about a second)

static void mic_on() {
    auto mc = M5.Mic.config();
    mc.sample_rate = RATE;
    mc.stereo = false;
    mc.magnification = MIC_GAIN;   // M5Unified's default (16) clips a voice at arm's length
    M5.Mic.config(mc);
    M5.Mic.begin();
}

static void recogniser_task(void*) {
    int chunk = multinet->get_samp_chunksize(mn);
    std::vector<int16_t> silence(chunk, 0);
    size_t fed = 0, recorded = 0;
    bool done = false, ended = false;
    Heard result{-1, 0};
    int silent = 0;
    while (true) {
        int msg;
        // Waits for more audio, except after the end, when it feeds silence until the phrase is closed.
        if (xQueueReceive(to_recogniser, &msg, ended ? 0 : portMAX_DELAY) == pdTRUE) {
            if (msg == 0) {   // a new press
                multinet->clean(mn);
                fed = recorded = 0;
                done = ended = false;
                result = {-1, 0};
                silent = 0;
                continue;
            }
            if (msg == END) ended = true;
            else recorded = (size_t) msg;
        }
        while (!done && (fed + chunk <= recorded || (ended && silent < TAIL))) {   // the phrase must be followed by silence to count as ended
            const int16_t* buf = fed + chunk <= recorded ? audio + fed : silence.data();
            if (fed + chunk <= recorded) fed += chunk;
            else silent++;
            esp_mn_state_t st = multinet->detect(mn, (int16_t*) buf);
            if (st == ESP_MN_STATE_DETECTED) {
                esp_mn_results_t* r = multinet->get_results(mn);
                if (r->num > 0) {
                    result = {r->command_id[0], r->prob[0]};
                    ESP_LOGI(TAG, "heard %d (%.2f)", result.id, result.prob);
                }
                done = true;
            } else if (st == ESP_MN_STATE_TIMEOUT) {
                done = true;
            }
        }
        if (ended && (done || silent >= TAIL)) {
            xQueueSend(from_recogniser, &result, portMAX_DELAY);
            ended = false;
            done = true;
        }
    }
}

/** Records while A is held (up to MAX_SECONDS), feeding the recogniser as it goes; returns what it heard. */
static Heard listen_and_recognise() {
    show("Listening…", "let go when done", TFT_CYAN);
    int start = 0;
    xQueueSend(to_recogniser, &start, portMAX_DELAY);
    size_t segments = 0, max = (size_t) RATE * MAX_SECONDS / SEGMENT;
    uint32_t t0 = millis();
    // Two recordings queued at a time, so there's no gap between them.
    M5.Mic.record(audio, SEGMENT, RATE);
    while (segments < max) {
        if (segments + 1 < max) M5.Mic.record(audio + (segments + 1) * SEGMENT, SEGMENT, RATE);
        while (M5.Mic.isRecording() >= 2) vTaskDelay(1);   // segment `segments` is complete
        segments++;
        int recorded = (int) (segments * SEGMENT);
        xQueueSend(to_recogniser, &recorded, portMAX_DELAY);
        M5.update();
        if (!M5.BtnA.isPressed()) break;
    }
    while (M5.Mic.isRecording()) vTaskDelay(1);
    int end = END;
    Heard result{-1, 0};
    xQueueSend(to_recogniser, &end, portMAX_DELAY);
    xQueueReceive(from_recogniser, &result, pdMS_TO_TICKS(3000));
    int peak = 0;
    for (size_t i = 0; i < segments * SEGMENT; i++) peak = std::max(peak, abs((int) audio[i]));
    ESP_LOGI(TAG, "recorded %d ms (peak %d), answer %d ms after letting go", (int) (segments * SEGMENT * 1000 / RATE),
             peak, (int) (millis() - t0) - (int) (segments * SEGMENT * 1000 / RATE));
    return result;
}

static void setup_speech() {
    srmodel_list_t* models = esp_srmodel_init("model");
    char* name = esp_srmodel_filter(models, ESP_MN_PREFIX, ESP_MN_ENGLISH);
    multinet = esp_mn_handle_from_name(name);
    mn = multinet->create(name, 5000);
    multinet->set_det_threshold(mn, FLOOR);   // report weak matches too; SURE decides whether to ask first
    esp_mn_commands_alloc(multinet, mn);
    for (const auto& c : commands())
        for (const char* p : c.phrases) esp_mn_commands_add(c.id, p);
    esp_mn_error_t* bad = esp_mn_commands_update();
    if (bad && bad->num) {
        ESP_LOGW(TAG, "%d phrases not understood by the model:", bad->num);
        for (int i = 0; i < bad->num; i++) ESP_LOGW(TAG, "  \"%s\"", bad->phrases[i]->string);
    }
    ESP_LOGI(TAG, "speech model %s, %d commands", name, (int) commands().size());
}

extern "C" void app_main() {
    auto cfg = M5.config();
    M5.begin(cfg);
    M5.Display.setRotation(1);
    M5.Display.setBrightness(90);
    show("Starting…");
    nvs_flash_init();
    audio = (int16_t*) heap_caps_malloc(sizeof(int16_t) * RATE * MAX_SECONDS, MALLOC_CAP_SPIRAM);
    setup_speech();
    to_recogniser = xQueueCreate(128, sizeof(int));
    from_recogniser = xQueueCreate(1, sizeof(Heard));
    xTaskCreatePinnedToCore(recogniser_task, "recogniser", 8192, nullptr, 3, nullptr, 1);
    mic_on();
    wifi_start();
    show(wifi_up(15000) ? "Finding plugs…" : "No Wi-Fi yet");
    if (wifi_up(0)) plugs = kasa::discover();
    char found[48];
    snprintf(found, sizeof found, "%d plug%s found", (int) plugs.size(), plugs.size() == 1 ? "" : "s");
    show("Hold A", found);
    uint32_t last = millis();
    while (true) {
        M5.update();
        if (M5.BtnA.wasPressed()) {
            M5.Display.setBrightness(90);
            int answer;
            const Command* c;
            do {   // "say it again" from a question listens again straight away
                Heard h = listen_and_recognise();
                c = command(h.id);
                answer = !c ? 0 : h.prob >= SURE ? 1 : confirm(*c);
            } while (answer == -1);
            if (c && answer == 1) run(*c);
            else if (c) show("Cancelled", "", TFT_LIGHTGREY);
            else {
                show("Didn't catch that", "try again", TFT_ORANGE);
                beep(400, 150);
            }
            vTaskDelay(pdMS_TO_TICKS(700));
            idle();
            last = millis();
        }
        if (M5.BtnB.wasPressed()) {   // B: what it understands
            M5.Display.setBrightness(90);
            show("Say: plug one on", "good night · volume up");
            last = millis();
        }
        if (millis() - last > 20000) M5.Display.setBrightness(0);  // screen off when idle
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}
