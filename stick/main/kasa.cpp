#include "kasa.h"

#include <cstring>
#include <lwip/sockets.h>
#include <esp_log.h>

namespace kasa {

static const char* TAG = "kasa";

static std::string crypt(const std::string& in, bool encrypt) {
    std::string out(in.size(), '\0');
    unsigned char key = 171;
    for (size_t i = 0; i < in.size(); i++) {
        unsigned char c = in[i];
        unsigned char x = c ^ key;
        out[i] = (char) x;
        key = encrypt ? x : c;
    }
    return out;
}

// The value of "key":"..." or "key":n in a small JSON reply (enough for alias and relay_state).
static std::string field(const std::string& json, const char* key) {
    std::string k = std::string("\"") + key + "\":";
    size_t at = json.find(k);
    if (at == std::string::npos) return "";
    at += k.size();
    if (json[at] == '"') {
        size_t end = json.find('"', at + 1);
        return end == std::string::npos ? "" : json.substr(at + 1, end - at - 1);
    }
    size_t end = json.find_first_of(",}", at);
    return json.substr(at, end - at);
}

std::vector<Plug> discover() {
    std::vector<Plug> plugs;
    int s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s < 0) return plugs;
    int yes = 1;
    setsockopt(s, SOL_SOCKET, SO_BROADCAST, &yes, sizeof yes);
    timeval tv{0, 200 * 1000};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    sockaddr_in to{};
    to.sin_family = AF_INET;
    to.sin_port = htons(9999);
    to.sin_addr.s_addr = htonl(INADDR_BROADCAST);
    std::string msg = crypt(R"({"system":{"get_sysinfo":{}}})", true);
    for (int round = 0; round < 3; round++) {
        sendto(s, msg.data(), msg.size(), 0, (sockaddr*) &to, sizeof to);
        for (int i = 0; i < 5; i++) {
            char buf[2048];
            sockaddr_in from{};
            socklen_t fl = sizeof from;
            int n = recvfrom(s, buf, sizeof buf, 0, (sockaddr*) &from, &fl);
            if (n <= 0) continue;
            std::string json = crypt(std::string(buf, n), false);
            std::string ip = inet_ntoa(from.sin_addr);
            bool seen = false;
            for (auto& p : plugs) seen |= p.ip == ip;
            if (seen || field(json, "alias").empty()) continue;
            plugs.push_back({field(json, "alias"), ip, field(json, "relay_state") == "1"});
            ESP_LOGI(TAG, "found %s at %s", plugs.back().alias.c_str(), ip.c_str());
        }
    }
    close(s);
    return plugs;
}

bool set(const std::string& ip, bool on) {
    int s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s < 0) return false;
    timeval tv{2, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    sockaddr_in to{};
    to.sin_family = AF_INET;
    to.sin_port = htons(9999);
    inet_aton(ip.c_str(), &to.sin_addr);
    bool ok = false;
    if (connect(s, (sockaddr*) &to, sizeof to) == 0) {
        std::string body = crypt(on ? R"({"system":{"set_relay_state":{"state":1}}})"
                                    : R"({"system":{"set_relay_state":{"state":0}}})", true);
        uint32_t len = htonl(body.size());
        std::string pkt(reinterpret_cast<char*>(&len), 4);
        pkt += body;
        if (send(s, pkt.data(), pkt.size(), 0) == (int) pkt.size()) {
            char buf[512];
            int n = recv(s, buf, sizeof buf, 0);
            ok = n > 4 && crypt(std::string(buf + 4, n - 4), false).find("\"err_code\":0") != std::string::npos;
        }
    }
    close(s);
    return ok;
}

}  // namespace kasa
