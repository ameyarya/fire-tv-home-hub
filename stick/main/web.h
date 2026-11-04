#pragma once
#include <cstdint>

// The stick's own page and API at http://stick.local (or its address), for its IR remote:
//   GET  /                       the page: learned buttons, send them, teach new ones
//   GET  /ir                     {"buttons":["On","Off",…]}
//   POST /ir/learn?name=On       waits up to 15 s for that button on the real remote, then keeps it
//   POST /ir/send?name=On&times=3
//   POST /ir/delete?name=On
// Like the Kasa plugs, it's open to the home Wi-Fi only.
namespace web {

/** What the stick's screen shows while the page uses it: big text, small text, colour. */
using Status = void (*)(const char* big, const char* small, uint16_t color);

void start(Status status);

}  // namespace web
