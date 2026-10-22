package com.homecontrol.tv;

import java.io.IOException;

/** Sends each call to the right protocol for the device's brand. Blocking; run off the main thread. */
final class Devices {
    private Devices() {
    }

    /** Tries Shelly (HTTP), then Kasa (port 9999). Throws when neither answers. */
    static Shelly.Info probe(String host) throws IOException {
        try {
            return Shelly.probe(host);
        } catch (IOException shellyFailed) {
            return Kasa.probe(host);
        }
    }

    static Shelly.Status status(Home.Device d) throws IOException {
        return Home.KASA.equals(d.brand) ? Kasa.status(d) : Shelly.status(d);
    }

    /** {today, this month} in Wh; NaN where the device doesn't keep it (Shelly keeps only a running total). */
    static double[] energy(Home.Device d) throws IOException {
        if (Home.KASA.equals(d.brand)) return Kasa.energy(d, java.time.LocalDate.now());
        return new double[]{Double.NaN, Double.NaN};
    }

    static void set(Home.Device d, boolean on) throws IOException {
        if (Home.KASA.equals(d.brand)) Kasa.set(d, on);
        else Shelly.set(d, on);
    }
}
