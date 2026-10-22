package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeTest {

    private static Home sample() {
        Home h = new Home();
        h.devices.add(new Home.Device("d1", "Lamp", "192.168.1.40", 2, true, false));
        h.devices.add(new Home.Device("d2", "TV plug", "192.168.1.41:8080", 1, true, false));
        h.devices.add(new Home.Device("d3", "Bedroom", "192.168.1.42", 3, false, true));
        h.cameras.add(new Home.Camera("c1", "Living room", "rtsp://u:p@192.168.1.249:554/live0"));
        Home.Scene s = new Home.Scene("s1", "Movie night");
        s.actions.add(new Home.Action("d1", false));
        s.actions.add(new Home.Action("d2", true));
        h.scenes.add(s);
        return h;
    }

    @Test
    public void roundTripKeepsEverything() {
        Home h = Home.fromJson(sample().toJson());
        assertEquals(3, h.devices.size());
        Home.Device tv = h.device("d2");
        assertEquals("TV plug", tv.name);
        assertEquals("192.168.1.41:8080", tv.host);
        assertEquals(1, tv.gen);
        assertTrue(h.device("d3").hasSensor);
        assertFalse(h.device("d3").hasSwitch);
        assertEquals("rtsp://u:p@192.168.1.249:554/live0", h.cameras.get(0).url);
        assertEquals(2, h.scenes.get(0).actions.size());
        assertFalse(h.scenes.get(0).actions.get(0).on);
        assertTrue(h.scenes.get(0).actions.get(1).on);
    }

    @Test
    public void removingADeviceTakesItOutOfScenes() {
        Home h = sample();
        h.removeDevice("d1");
        assertNull(h.device("d1"));
        assertEquals(1, h.scenes.get(0).actions.size());
        assertEquals("d2", h.scenes.get(0).actions.get(0).deviceId);
    }

    @Test
    public void aSceneWithNothingLeftIsRemoved() {
        Home h = sample();
        h.removeDevice("d1");
        h.removeDevice("d2");
        assertTrue(h.scenes.isEmpty());
    }

    @Test
    public void damagedOrEmptyDataGivesAnEmptyHome() {
        assertTrue(Home.fromJson(null).devices.isEmpty());
        assertTrue(Home.fromJson("").devices.isEmpty());
        assertTrue(Home.fromJson("{not json").devices.isEmpty());
        assertTrue(Home.fromJson("[]").devices.isEmpty());
    }

    @Test
    public void scenesPointingAtMissingDevicesAreDropped() {
        String json = "{\"devices\":[],\"scenes\":[{\"id\":\"s\",\"name\":\"x\",\"actions\":[{\"device\":\"gone\",\"on\":true}]}]}";
        assertTrue(Home.fromJson(json).scenes.isEmpty());
    }

    @Test
    public void devicesWithoutAHostAreSkipped() {
        Home h = Home.fromJson("{\"devices\":[{\"id\":\"a\",\"name\":\"x\"},{\"id\":\"b\",\"host\":\"10.0.0.2\"}]}");
        assertEquals(1, h.devices.size());
        assertEquals("10.0.0.2", h.devices.get(0).name);   // the host stands in for a missing name
    }

    @Test
    public void lookUpByHostIgnoresCase() {
        Home h = new Home();
        h.devices.add(new Home.Device("x", "Plug", "Shelly-Plug.local", 2, true, false));
        assertEquals("x", h.deviceAt("shelly-plug.local").id);
    }
}
