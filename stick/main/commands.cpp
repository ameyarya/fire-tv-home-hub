#include "commands.h"

static const std::vector<Command> LIST = {
    {1, {"turn on plug one", "plug one on", "switch on plug one", "switch plug one on", "turn plug one on",
         "first plug on", "turn on the first plug"}, Act::PLUG, "Plug 1", true, 0, "Plug 1 on"},
    {2, {"turn off plug one", "plug one off", "switch off plug one", "switch plug one off", "turn plug one off",
         "first plug off", "turn off the first plug"}, Act::PLUG, "Plug 1", false, 0, "Plug 1 off"},
    {3, {"turn on plug two", "plug two on", "switch on plug two", "switch plug two on", "turn plug two on",
         "second plug on", "turn on the second plug"}, Act::PLUG, "Plug 2", true, 0, "Plug 2 on"},
    {4, {"turn off plug two", "plug two off", "switch off plug two", "switch plug two off", "turn plug two off",
         "second plug off", "turn off the second plug"}, Act::PLUG, "Plug 2", false, 0, "Plug 2 off"},
    {5, {"turn on everything", "everything on", "turn on all plugs", "all plugs on", "turn everything on",
         "lights on", "turn on the lights", "switch everything on"}, Act::ALL, nullptr, true, 0, "All on"},
    {6, {"turn off everything", "everything off", "turn off all plugs", "all plugs off", "turn everything off",
         "lights off", "turn off the lights", "switch everything off", "kill the plugs"},
     Act::ALL, nullptr, false, 0, "All off"},
    {7, {"good evening", "evening mode", "i am home"}, Act::ALL, nullptr, true, 0, "Evening: all on"},
    {8, {"good night", "night mode", "bed time", "time for bed", "i am going to bed"},
     Act::ALL, nullptr, false, 0, "Night: all off"},
    {9, {"volume up", "tv volume up", "louder", "turn it up", "turn up the volume", "make it louder"},
     Act::TV_KEY, nullptr, false, 24, "Volume up"},
    {10, {"volume down", "tv volume down", "quieter", "turn it down", "turn down the volume", "make it quieter"},
     Act::TV_KEY, nullptr, false, 25, "Volume down"},
    {11, {"mute", "mute the tv"}, Act::TV_KEY, nullptr, false, 164, "Mute"},
    {12, {"pause", "pause the tv", "stop the show", "play the show"}, Act::TV_KEY, nullptr, false, 85,
     "Play / pause"},
    {13, {"turn off the tv", "tv off", "switch off the tv", "turn the tv off"}, Act::TV_KEY, nullptr, false, 223,
     "TV off"},
};

const std::vector<Command>& commands() { return LIST; }

const Command* command(int id) {
    for (const auto& c : LIST) if (c.id == id) return &c;
    return nullptr;
}
