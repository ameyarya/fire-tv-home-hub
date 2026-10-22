#pragma once
#include <vector>

// What the stick understands. Each command has a few ways of saying it (all given to the recognizer) and one
// action. Plug names are the Kasa app's names; scenes mirror Smart Home's "eve" (both on) and "night" (both off).
enum class Act { PLUG, ALL, TV_KEY };

struct Command {
    int id;
    std::vector<const char*> phrases;
    Act act;
    const char* plug;   // Act::PLUG: the plug's Kasa name
    bool on;            // PLUG / ALL
    int key;            // TV_KEY: an Android key code, sent to the TV's Smart Home phone-remote API
    const char* done;   // shown on the screen
};

const std::vector<Command>& commands();
const Command* command(int id);
