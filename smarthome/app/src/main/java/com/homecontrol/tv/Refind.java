package com.homecontrol.tv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds devices again after the router gives them a new address. Every device reports a permanent
 * identity (Kasa deviceId, Shelly id or MAC, kept in Home.Device.uid); when one stops answering, the
 * app scans the network, asks each answer who it is, and moves the device to wherever its identity
 * now lives. Devices added before identities were kept learn theirs the next time they answer.
 *
 * Plain Java; the scanning itself is passed in, so it is tested on the JVM.
 */
final class Refind {

    /** Don't scan more often than this while something stays offline. */
    static final long MIN_INTERVAL_MS = 60_000;

    interface Prober {
        Shelly.Info probe(String host) throws IOException;
    }

    private Refind() {
    }

    /** Asks every host who it is: identity -> host. Hosts that don't answer, or report no identity, are left out. */
    static Map<String, String> identify(Collection<String> hosts, Prober prober) {
        Map<String, String> found = new HashMap<>();
        for (String host : hosts) {
            try {
                Shelly.Info i = prober.probe(host);
                if (i.uid != null && !i.uid.isEmpty()) found.put(i.uid, host);
            } catch (IOException ignored) {
            }
        }
        return found;
    }

    /**
     * Moves each offline device whose identity turned up at another address. Returns the devices it
     * moved; the caller saves the Home when the list isn't empty. Online devices are never moved, and an
     * address already used by another device is never taken.
     */
    static List<Home.Device> apply(Home home, Set<String> offlineIds, Map<String, String> uidToHost) {
        List<Home.Device> moved = new ArrayList<>();
        for (Home.Device d : home.devices) {
            if (!offlineIds.contains(d.id) || d.uid.isEmpty()) continue;
            String host = uidToHost.get(d.uid);
            if (host == null || host.equalsIgnoreCase(d.host)) continue;
            Home.Device other = home.deviceAt(host);
            if (other != null && other != d) continue;
            d.host = host;
            moved.add(d);
        }
        return moved;
    }

    /** True when a scan is worth running now. */
    static boolean due(Set<String> offlineIds, long now, long lastScan) {
        return !offlineIds.isEmpty() && now - lastScan >= MIN_INTERVAL_MS;
    }

    /** Hosts worth asking for one brand's devices: Kasa answers the broadcast, Shelly answers mDNS. */
    static Map<String, String> scan(Collection<String> kasaHosts, Collection<String> shellyHosts) {
        Map<String, String> found = new HashMap<>(identify(kasaHosts, Kasa::probe));
        found.putAll(identify(shellyHosts, Shelly::probe));
        return found;
    }
}
