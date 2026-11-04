#include "ir.h"

#include <algorithm>
#include <cstring>
#include <strings.h>

#include <driver/rmt_rx.h>
#include <driver/rmt_tx.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include <driver/gpio.h>
#include <esp_log.h>
#include <esp_rom_sys.h>
#include <nvs.h>

// The receiver (G42) hands over the signal already stripped of its carrier, low while the remote's LED is on; the
// LED (G46) is driven with a 38 kHz carrier, which nearly every home remote uses. Both go through the RMT
// peripheral, the only way the StickS3's receiver can be read.
namespace ir {

static const char* TAG = "ir";
static const gpio_num_t TX = GPIO_NUM_46, RX = GPIO_NUM_42;
static const int MAX_SYMBOLS = 192;      // the RX channel's memory: a long air-conditioner frame fits
static const int MIN_PULSES = 16;        // fewer is noise, or a "still held" repeat code
static const int MAX_BUTTONS = 32;

static rmt_channel_handle_t rx_ch, tx_ch;
static rmt_encoder_handle_t copy;
static QueueHandle_t received;
static SemaphoreHandle_t busy;           // one learn or send at a time
static rmt_symbol_word_t frame[MAX_SYMBOLS];

static bool IRAM_ATTR on_received(rmt_channel_handle_t, const rmt_rx_done_event_data_t* e, void* q) {
    BaseType_t woke = pdFALSE;
    xQueueSendFromISR((QueueHandle_t) q, e, &woke);
    return woke == pdTRUE;
}

bool begin() {
    busy = xSemaphoreCreateMutex();
    received = xQueueCreate(1, sizeof(rmt_rx_done_event_data_t));
    rmt_rx_channel_config_t rx{};
    rx.gpio_num = RX;
    rx.clk_src = RMT_CLK_SRC_DEFAULT;
    rx.resolution_hz = 1000000;   // 1 µs ticks
    rx.mem_block_symbols = MAX_SYMBOLS;
    esp_err_t err = rmt_new_rx_channel(&rx, &rx_ch);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "receiver: %s", esp_err_to_name(err));
        rx_ch = nullptr;
        return false;
    }
    rmt_rx_event_callbacks_t cb{};
    cb.on_recv_done = on_received;
    rmt_rx_register_event_callbacks(rx_ch, &cb, received);
    rmt_enable(rx_ch);

    rmt_tx_channel_config_t tx{};
    tx.gpio_num = TX;
    tx.clk_src = RMT_CLK_SRC_DEFAULT;
    tx.resolution_hz = 1000000;
    tx.mem_block_symbols = 48;
    tx.trans_queue_depth = 4;
    err = rmt_new_tx_channel(&tx, &tx_ch);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "LED: %s", esp_err_to_name(err));
        tx_ch = nullptr;
        return false;
    }
    ESP_LOGI(TAG, "ready: LED G%d, receiver G%d (idle level %d)", TX, RX, gpio_get_level(RX));
    rmt_carrier_config_t carrier{};
    carrier.frequency_hz = 38000;
    carrier.duty_cycle = 0.33f;
    rmt_apply_carrier(tx_ch, &carrier);
    rmt_copy_encoder_config_t cc{};
    rmt_new_copy_encoder(&cc, &copy);
    rmt_enable(tx_ch);
    return true;
}

/** The received symbols as on/off lengths, starting with on (the receiver is low while the LED is on). */
static std::vector<uint16_t> pulses(const rmt_symbol_word_t* s, size_t n) {
    std::vector<uint16_t> out;
    bool want_on = true;
    auto add = [&](int level, int length) {
        if (length == 0) return;
        bool on = level == 0;
        if (out.empty() && !on) return;   // idle before the first pulse
        if (on == want_on) {
            out.push_back((uint16_t) length);
            want_on = !want_on;
        } else if (!out.empty()) {
            out.back() = (uint16_t) std::min(65535, out.back() + length);
        }
    };
    for (size_t i = 0; i < n; i++) {
        add(s[i].level0, s[i].duration0);
        add(s[i].level1, s[i].duration1);
    }
    if (!out.empty() && out.size() % 2 == 0) out.pop_back();   // a trailing gap says nothing
    return out;
}

static bool near(int v, int target) { return v > target * 6 / 10 && v < target * 14 / 10; }

std::vector<uint16_t> clean(const std::vector<uint16_t>& signal, std::string* code) {
    if (code) code->clear();
    // 32 bits: each a ~560 µs pulse, then a ~560 µs gap (0) or ~1690 µs gap (1), least significant bit first.
    for (size_t at = 0; at + 64 < signal.size() + 1 && at + 64 <= signal.size(); at += 2) {
        uint32_t bits = 0;
        bool ok = true;
        for (int b = 0; b < 32 && ok; b++) {
            int on = signal[at + 2 * b], off = signal[at + 2 * b + 1];
            if (!near(on, 560)) ok = false;
            else if (near(off, 1690)) bits |= 1u << b;
            else if (!near(off, 560)) ok = false;
        }
        uint8_t cmd = (bits >> 16) & 0xff, inv = bits >> 24;
        if (!ok || (uint8_t) ~cmd != inv) continue;
        std::vector<uint16_t> out = {9000, 4500};
        for (int b = 0; b < 32; b++) {
            out.push_back(560);
            out.push_back(bits >> b & 1 ? 1690 : 560);
        }
        out.push_back(560);
        if (code) {
            char c[16];
            snprintf(c, sizeof c, "%02X/%02X", (unsigned) (bits & 0xff), (unsigned) cmd);
            *code = c;
        }
        return out;
    }
    return signal;
}

bool learn(std::vector<uint16_t>& signal, int timeout_ms) {
    if (!rx_ch || xSemaphoreTake(busy, pdMS_TO_TICKS(timeout_ms)) != pdTRUE) return false;
    rmt_receive_config_t rc{};
    rc.signal_range_min_ns = 1000;          // shorter is a glitch
    rc.signal_range_max_ns = 15000000;      // a gap this long ends the frame
    TickType_t until = xTaskGetTickCount() + pdMS_TO_TICKS(timeout_ms);
    bool got = false;
    xQueueReset(received);
    while (!got && xTaskGetTickCount() < until) {
        if (rmt_receive(rx_ch, frame, sizeof frame, &rc) != ESP_OK) break;
        rmt_rx_done_event_data_t e;
        if (xQueueReceive(received, &e, until - xTaskGetTickCount()) != pdTRUE) {
            rmt_disable(rx_ch);   // stops the receive still waiting
            rmt_enable(rx_ch);
            break;
        }
        signal = clean(pulses(e.received_symbols, e.num_symbols));
        ESP_LOGI(TAG, "received %d symbols, %d pulses", (int) e.num_symbols, (int) signal.size());
        got = (int) signal.size() >= MIN_PULSES;
    }
    xSemaphoreGive(busy);
    return got;
}

int probe(int ms) {
    int changes = 0, was = gpio_get_level(RX);
    for (int t = 0; t < ms; t++) {
        for (int i = 0; i < 40; i++) {   // every 25 µs: a remote's shortest pulse is about 500 µs
            int now = gpio_get_level(RX);
            if (now != was) changes++;
            was = now;
            esp_rom_delay_us(25);
        }
        vTaskDelay(1);   // 1 ms tick: let the rest of the stick run
    }
    ESP_LOGI(TAG, "probe: %d changes in %d ms (idle level %d)", changes, ms, gpio_get_level(RX));
    return changes;
}

/** The signal as RMT symbols: on with the carrier, then off. */
static std::vector<rmt_symbol_word_t> symbols(const std::vector<uint16_t>& signal) {
    std::vector<rmt_symbol_word_t> s;
    for (size_t i = 0; i < signal.size(); i += 2) {
        rmt_symbol_word_t w{};
        w.level0 = 1;
        w.duration0 = std::min<int>(signal[i], 32767);
        w.level1 = 0;
        w.duration1 = i + 1 < signal.size() ? std::min<int>(signal[i + 1], 32767) : 1000;
        s.push_back(w);
    }
    return s;
}

bool loopback(const std::vector<uint16_t>& signal, std::vector<uint16_t>& heard) {
    heard.clear();
    if (!rx_ch || !tx_ch || signal.empty() || xSemaphoreTake(busy, pdMS_TO_TICKS(3000)) != pdTRUE) return false;
    rmt_receive_config_t rc{};
    rc.signal_range_min_ns = 1000;
    rc.signal_range_max_ns = 15000000;
    xQueueReset(received);
    auto s = symbols(signal);
    rmt_transmit_config_t tc{};
    bool sent = rmt_receive(rx_ch, frame, sizeof frame, &rc) == ESP_OK
                && rmt_transmit(tx_ch, copy, s.data(), s.size() * sizeof s[0], &tc) == ESP_OK
                && rmt_tx_wait_all_done(tx_ch, 1000) == ESP_OK;
    rmt_rx_done_event_data_t e;
    if (xQueueReceive(received, &e, pdMS_TO_TICKS(300)) == pdTRUE) {
        heard = pulses(e.received_symbols, e.num_symbols);
    } else {
        rmt_disable(rx_ch);
        rmt_enable(rx_ch);
    }
    xSemaphoreGive(busy);
    return sent;
}

bool send(const std::vector<uint16_t>& signal, int times) {
    if (!tx_ch || signal.empty() || xSemaphoreTake(busy, pdMS_TO_TICKS(3000)) != pdTRUE) return false;
    auto s = symbols(clean(signal));   // buttons learned before clean() existed get it here
    rmt_transmit_config_t tc{};
    esp_err_t err = ESP_OK;
    for (int t = 0; t < std::max(1, std::min(times, 20)) && err == ESP_OK; t++) {
        err = rmt_transmit(tx_ch, copy, s.data(), s.size() * sizeof s[0], &tc);
        if (err == ESP_OK) err = rmt_tx_wait_all_done(tx_ch, 1000);
        if (t + 1 < times) vTaskDelay(pdMS_TO_TICKS(40));
    }
    bool ok = err == ESP_OK;
    ESP_LOGI(TAG, "sent %d pulses x%d (%d %d %d %d …): %s", (int) signal.size(), times, signal[0],
             signal.size() > 1 ? signal[1] : 0, signal.size() > 2 ? signal[2] : 0, signal.size() > 3 ? signal[3] : 0,
             esp_err_to_name(err));
    xSemaphoreGive(busy);
    return ok;
}

// ---- learned buttons: NVS keys b0..b31, each "<name>\0" then the pulse lengths ----------------------

static std::string key(int i) { return "b" + std::to_string(i); }

/** Reads button i; false when the slot is empty. */
static bool read(nvs_handle_t h, int i, std::string* name, std::vector<uint16_t>* signal) {
    size_t len = 0;
    if (nvs_get_blob(h, key(i).c_str(), nullptr, &len) != ESP_OK || len < 2) return false;
    std::vector<uint8_t> b(len);
    if (nvs_get_blob(h, key(i).c_str(), b.data(), &len) != ESP_OK) return false;
    size_t end = strnlen((const char*) b.data(), len);
    if (end >= len) return false;
    if (name) name->assign((const char*) b.data(), end);
    if (signal) {
        size_t n = (len - end - 1) / 2;
        signal->resize(n);
        memcpy(signal->data(), b.data() + end + 1, n * 2);
    }
    return true;
}

/** The slot holding this name, else -1; `free_slot` gets the first empty one. */
static int find(nvs_handle_t h, const std::string& name, int* free_slot = nullptr) {
    if (free_slot) *free_slot = -1;
    for (int i = 0; i < MAX_BUTTONS; i++) {
        std::string n;
        if (!read(h, i, &n, nullptr)) {
            if (free_slot && *free_slot < 0) *free_slot = i;
        } else if (strcasecmp(n.c_str(), name.c_str()) == 0) {
            return i;
        }
    }
    return -1;
}

std::vector<std::string> names() {
    std::vector<std::string> out;
    nvs_handle_t h;
    if (nvs_open("ir", NVS_READONLY, &h) != ESP_OK) return out;
    for (int i = 0; i < MAX_BUTTONS; i++) {
        std::string n;
        if (read(h, i, &n, nullptr)) out.push_back(n);
    }
    nvs_close(h);
    return out;
}

bool save(const std::string& name, const std::vector<uint16_t>& signal) {
    if (name.empty() || name.size() > 40 || signal.empty()) return false;
    nvs_handle_t h;
    if (nvs_open("ir", NVS_READWRITE, &h) != ESP_OK) return false;
    int free_slot, i = find(h, name, &free_slot);
    if (i < 0) i = free_slot;
    bool ok = false;
    if (i >= 0) {
        std::vector<uint8_t> b(name.begin(), name.end());
        b.push_back(0);
        const uint8_t* p = (const uint8_t*) signal.data();
        b.insert(b.end(), p, p + signal.size() * 2);
        ok = nvs_set_blob(h, key(i).c_str(), b.data(), b.size()) == ESP_OK && nvs_commit(h) == ESP_OK;
    }
    nvs_close(h);
    return ok;
}

bool load(const std::string& name, std::vector<uint16_t>& signal) {
    nvs_handle_t h;
    if (nvs_open("ir", NVS_READONLY, &h) != ESP_OK) return false;
    int i = find(h, name);
    bool ok = i >= 0 && read(h, i, nullptr, &signal);
    nvs_close(h);
    return ok;
}

bool remove(const std::string& name) {
    nvs_handle_t h;
    if (nvs_open("ir", NVS_READWRITE, &h) != ESP_OK) return false;
    int i = find(h, name);
    bool ok = i >= 0 && nvs_erase_key(h, key(i).c_str()) == ESP_OK && nvs_commit(h) == ESP_OK;
    nvs_close(h);
    return ok;
}

}  // namespace ir
