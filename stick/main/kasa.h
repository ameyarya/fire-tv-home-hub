#pragma once
#include <string>
#include <vector>

// Kasa smart plugs on the home Wi-Fi, straight from the stick (no TV or app needed): found by a UDP broadcast
// on port 9999, switched over TCP 9999. Both use Kasa's local protocol: JSON, XOR-"encrypted" with autokey 171.
namespace kasa {

struct Plug {
    std::string alias;  // the name set in the Kasa app, e.g. "Plug 1"
    std::string ip;
    bool on;
};

// Broadcasts and collects answers for about a second.
std::vector<Plug> discover();
// Switches one plug; false when it didn't answer.
bool set(const std::string& ip, bool on);

}  // namespace kasa
