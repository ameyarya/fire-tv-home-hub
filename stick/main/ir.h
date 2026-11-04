#pragma once
#include <cstdint>
#include <string>
#include <vector>

// The StickS3's infrared: it learns a button from any remote (the raw signal, whatever the protocol) and sends
// it back. A signal is its pulse lengths in microseconds, on and off in turn, starting with on.
namespace ir {

bool begin();

/**
 * The signal rebuilt cleanly when it holds an NEC code (most home remotes), with that code in `code`
 * ("address/command", e.g. "00/18"); otherwise the signal as it is and `code` empty. Recordings often have a
 * ragged start, which a device ignores, but the 32 bits after it are enough to make a perfect copy.
 */
std::vector<uint16_t> clean(const std::vector<uint16_t>& signal, std::string* code = nullptr);

/** Waits up to timeout_ms for a remote's button and records it; false when nothing (or only noise) came. */
bool learn(std::vector<uint16_t>& signal, int timeout_ms);

/** How many times the receiver's output changed in `ms` (any remote pressed at it shows hundreds): a check. */
int probe(int ms);

/** Sends a signal while the stick's own receiver listens; `heard` gets what it picked up (empty: nothing). */
bool loopback(const std::vector<uint16_t>& signal, std::vector<uint16_t>& heard);

/** Sends a signal `times` times (a held button: dimming steps), about 40 ms apart. */
bool send(const std::vector<uint16_t>& signal, int times = 1);

// Learned buttons, kept on the stick (NVS) by name.
std::vector<std::string> names();
bool save(const std::string& name, const std::vector<uint16_t>& signal);
bool load(const std::string& name, std::vector<uint16_t>& signal);
bool remove(const std::string& name);

}  // namespace ir
