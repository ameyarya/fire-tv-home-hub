package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parsing against replies captured from the Shelly API docs, and the HTTP client against a local server. */
public class ShellyTest {

    // Gen3 plug (Shelly.GetDeviceInfo via /shelly, and Shelly.GetStatus), trimmed.
    static final String GEN2_ID = "{\"name\":\"Lamp\",\"id\":\"shellyplugsg3-aabbcc\",\"mac\":\"AABBCC\",\"model\":\"S3PL-00112EU\",\"gen\":3,\"app\":\"PlugSG3\"}";
    static final String GEN2_STATUS = "{\"switch:0\":{\"id\":0,\"source\":\"HTTP_in\",\"output\":true,\"apower\":42.5,\"voltage\":231.2},\"sys\":{}}";
    static final String HT_STATUS = "{\"temperature:0\":{\"id\":0,\"tC\":21.4,\"tF\":70.5},\"humidity:0\":{\"id\":0,\"rh\":47.2},\"sys\":{}}";
    // Gen1 Plug S.
    static final String GEN1_ID = "{\"type\":\"SHPLG-S\",\"mac\":\"AABBCCDDEEFF\",\"auth\":false,\"fw\":\"20230913-112003/v1.14.0\"}";
    static final String GEN1_STATUS = "{\"relays\":[{\"ison\":false,\"has_timer\":false}],\"meters\":[{\"power\":0.0,\"is_valid\":true}]}";

    private ServerSocket server;
    private final List<String> requests = new ArrayList<>();
    private volatile String shelly = GEN2_ID, status = GEN2_STATUS;

    @Before
    public void start() throws IOException {
        // A minimal HTTP/1.0 server: Android's unit-test classpath has no com.sun.net.httpserver.
        server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket c = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String uri = in.readLine().split(" ")[1];
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) { }
                    requests.add(uri);
                    String body;
                    int code = 200;
                    if (uri.equals("/shelly")) body = shelly;
                    else if (uri.startsWith("/rpc/Shelly.GetStatus") || uri.equals("/status")) body = status;
                    else if (uri.startsWith("/rpc/Switch.Set") || uri.startsWith("/relay/0")) body = "{\"was_on\":false}";
                    else if (uri.equals("/locked")) { code = 401; body = "{}"; }
                    else { code = 404; body = "{}"; }
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream o = c.getOutputStream();
                    o.write(("HTTP/1.0 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    o.write(b);
                    o.flush();
                } catch (IOException | RuntimeException ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @After
    public void stop() throws IOException {
        server.close();
    }

    private String host() {
        return "127.0.0.1:" + server.getLocalPort();
    }

    @Test
    public void probeGen3Plug() throws IOException {
        Shelly.Info i = Shelly.probe(host());
        assertEquals(3, i.gen);
        assertEquals("Lamp", i.name);
        assertTrue(i.hasSwitch);
        assertFalse(i.hasSensor);
    }

    @Test
    public void probeHumidityTemperatureSensor() throws IOException {
        shelly = GEN2_ID.replace("\"name\":\"Lamp\"", "\"name\":null");
        status = HT_STATUS;
        Shelly.Info i = Shelly.probe(host());
        assertNull(i.name);
        assertFalse(i.hasSwitch);
        assertTrue(i.hasSensor);
    }

    @Test
    public void probeGen1Plug() throws IOException {
        shelly = GEN1_ID;
        status = GEN1_STATUS;
        Shelly.Info i = Shelly.probe(host());
        assertEquals(1, i.gen);
        assertEquals("SHPLG-S", i.model);
        assertTrue(i.hasSwitch);
    }

    @Test
    public void probeRejectsSomethingElse() {
        shelly = "{\"hello\":\"world\"}";
        try {
            Shelly.probe(host());
            fail("accepted a non-Shelly reply");
        } catch (IOException expected) {
        }
    }

    @Test
    public void probeFailsFastWhenNothingListens() {
        long t0 = System.currentTimeMillis();
        try {
            Shelly.probe("127.0.0.1:1");
            fail();
        } catch (IOException expected) {
        }
        assertTrue(System.currentTimeMillis() - t0 < Shelly.TIMEOUT_MS * 2 + 500);
    }

    @Test
    public void passwordProtectedDeviceSaysSo() {
        try {
            Shelly.get(host(), "/locked");
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("password"));
        }
    }

    @Test
    public void statusGen2() throws IOException {
        Shelly.Status s = Shelly.status(new Home.Device("x", "Lamp", host(), 3, true, false));
        assertTrue(s.on);
        assertEquals(42.5, s.watts, 0.001);
        assertTrue(Double.isNaN(s.tempC));
    }

    @Test
    public void statusSensor() throws Exception {
        Shelly.Status s = Shelly.parseStatusGen2(new JSONObject(HT_STATUS));
        assertEquals(21.4, s.tempC, 0.001);
        assertEquals(47.2, s.humidity, 0.001);
        assertFalse(s.on);
    }

    @Test
    public void statusGen1() throws Exception {
        Shelly.Status s = Shelly.parseStatusGen1(new JSONObject(GEN1_STATUS));
        assertFalse(s.on);
        assertEquals(0.0, s.watts, 0.001);
    }

    @Test
    public void setUsesTheRightApiPerGeneration() throws IOException {
        Shelly.set(new Home.Device("a", "A", host(), 3, true, false), true);
        Shelly.set(new Home.Device("b", "B", host(), 1, true, false), false);
        assertTrue(requests.contains("/rpc/Switch.Set?id=0&on=true"));
        assertTrue(requests.contains("/relay/0?turn=off"));
    }

    @Test
    public void deviceNameFallsBackToModelAndAddress() {
        Shelly.Info i = new Shelly.Info(3, "S3PL-00112EU", null, true, false);
        assertEquals("S3PL-00112EU 41", MainActivity.deviceFrom("192.168.1.41", i).name);
        Shelly.Info named = new Shelly.Info(3, "S3PL-00112EU", "Lamp", true, false);
        assertEquals("Lamp", MainActivity.deviceFrom("192.168.1.41", named).name);
    }

    @Test
    public void sensorOnlyDeviceHasNoSwitch() {
        Shelly.Info i = new Shelly.Info(3, "S3SN-0U12A", null, false, true);
        Home.Device d = MainActivity.deviceFrom("192.168.1.50", i);
        assertFalse(d.hasSwitch);
        assertTrue(d.hasSensor);
    }
}
