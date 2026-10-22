package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RefindTest {

    private static Home.Device plug(String id, String uid, String host) {
        Home.Device d = new Home.Device(id, id, host, 0, true, false);
        d.brand = Home.KASA;
        d.uid = uid;
        return d;
    }

    @Test
    public void offlineDeviceMovesToItsNewAddress() {
        Home h = new Home();
        h.devices.add(plug("p1", "UID-1", "192.168.1.198"));
        Map<String, String> found = new HashMap<>();
        found.put("UID-1", "192.168.1.205");
        List<Home.Device> moved = Refind.apply(h, Collections.singleton("p1"), found);
        assertEquals(1, moved.size());
        assertEquals("192.168.1.205", h.device("p1").host);
    }

    @Test
    public void onlineDevicesAreNeverMoved() {
        Home h = new Home();
        h.devices.add(plug("p1", "UID-1", "192.168.1.198"));
        Map<String, String> found = Collections.singletonMap("UID-1", "192.168.1.205");
        assertTrue(Refind.apply(h, Collections.emptySet(), found).isEmpty());
        assertEquals("192.168.1.198", h.device("p1").host);
    }

    @Test
    public void anotherDeviceAtThatAddressKeepsIt() {
        Home h = new Home();
        h.devices.add(plug("p1", "UID-1", "192.168.1.198"));
        h.devices.add(plug("p2", "UID-2", "192.168.1.205"));
        Map<String, String> found = Collections.singletonMap("UID-1", "192.168.1.205");
        assertTrue(Refind.apply(h, Collections.singleton("p1"), found).isEmpty());
    }

    @Test
    public void devicesWithoutIdentityStayPut() {
        Home h = new Home();
        h.devices.add(plug("p1", "", "192.168.1.198"));
        Map<String, String> found = Collections.singletonMap("", "192.168.1.205");
        assertTrue(Refind.apply(h, Collections.singleton("p1"), found).isEmpty());
    }

    @Test
    public void plugsSwappingAddressesBothFollow() {
        Home h = new Home();
        h.devices.add(plug("p1", "UID-1", "192.168.1.197"));
        h.devices.add(plug("p2", "UID-2", "192.168.1.198"));
        Map<String, String> found = new HashMap<>();
        found.put("UID-1", "192.168.1.250");
        found.put("UID-2", "192.168.1.251");
        Set<String> both = new HashSet<>(Arrays.asList("p1", "p2"));
        assertEquals(2, Refind.apply(h, both, found).size());
        assertEquals("192.168.1.250", h.device("p1").host);
        assertEquals("192.168.1.251", h.device("p2").host);
    }

    @Test
    public void identifyAsksEachHostAndSkipsSilentOnes() {
        Refind.Prober prober = host -> {
            if (host.endsWith(".9")) throw new IOException("no answer");
            Shelly.Info i = new Shelly.Info(Home.KASA, 0, "EP25", host, true, false);
            i.uid = "UID@" + host;
            return i;
        };
        Map<String, String> m = Refind.identify(Arrays.asList("10.0.0.5", "10.0.0.9"), prober);
        assertEquals(Collections.singletonMap("UID@10.0.0.5", "10.0.0.5"), m);
    }

    @Test
    public void scansAreRateLimited() {
        Set<String> lost = Collections.singleton("p1");
        assertTrue(Refind.due(lost, 100_000, 0));
        assertFalse(Refind.due(lost, 100_000, 70_000));
        assertFalse(Refind.due(Collections.emptySet(), 100_000, 0));
    }

    /**
     * Read-only, real network (KASA_LIVE=1): a real plug saved at a wrong address is found again by its
     * identity. Nothing is switched; the plugs are only asked who they are.
     */
    @Test
    public void liveRefindRealPlug() throws IOException {
        if (System.getenv("KASA_LIVE") == null) return;
        Set<String> hosts = Kasa.discover(4000);
        String real = hosts.iterator().next();
        Shelly.Info info = Kasa.probe(real);
        Home h = new Home();
        h.devices.add(plug("p", info.uid, "192.0.2.77"));   // an address nothing answers at
        Map<String, String> found = Refind.scan(hosts, Collections.emptyList());
        List<Home.Device> moved = Refind.apply(h, Collections.singleton("p"), found);
        System.out.println("LIVE refind: " + info.name + " uid " + info.uid.substring(0, 8) + "... from 192.0.2.77 -> "
                + h.device("p").host + " (moved " + moved.size() + ")");
        assertEquals(real, h.device("p").host);
    }

    @Test
    public void identityIsSavedAndLoaded() {
        Home h = new Home();
        h.devices.add(plug("p1", "8006AAAABBBBCCCCDDDDEEEEFFFF000011112222", "192.168.1.197"));
        Home back = Home.fromJson(h.toJson());
        assertEquals("8006AAAABBBBCCCCDDDDEEEEFFFF000011112222", back.device("p1").uid);
        assertEquals("p1", back.deviceByUid("8006AAAABBBBCCCCDDDDEEEEFFFF000011112222").id);
    }
}
