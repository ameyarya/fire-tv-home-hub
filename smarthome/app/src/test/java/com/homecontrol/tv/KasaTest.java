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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** The Kasa protocol against python-kasa's bytes and against a fake EP25 that speaks it on localhost. */
public class KasaTest {

    /** python-kasa 0.11: XorEncryption.encrypt('{"system":{"get_sysinfo":{}}}'), length prefix included. */
    static final String PYTHON_KASA_SYSINFO = "0000001dd0f281f88bff9af7d5ef94b6d1b4c09fec95e68fe187e8caf08bf68bf6";

    private ServerSocket tcp;
    private DatagramSocket udp;
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean on = true;
    private volatile String alias = "Plug 1";
    private volatile boolean emeter = true;
    private volatile boolean milliwatts = true;

    private String sysinfo() {
        return "{\"system\":{\"get_sysinfo\":{\"alias\":\"" + alias + "\",\"model\":\"EP25(US)\",\"relay_state\":" + (on ? 1 : 0)
                + ",\"feature\":\"TIM:ENE\",\"err_code\":0}}}";
    }

    private String reply(String request) throws Exception {
        JSONObject q = new JSONObject(request);
        if (q.has("system") && q.getJSONObject("system").has("set_relay_state")) {
            on = q.getJSONObject("system").getJSONObject("set_relay_state").getInt("state") == 1;
            return "{\"system\":{\"set_relay_state\":{\"err_code\":0}}}";
        }
        String rt = !emeter ? "{\"err_code\":-1,\"err_msg\":\"module not support\"}"
                : milliwatts ? "{\"power_mw\":" + (on ? 9100 : 0) + ",\"voltage_mv\":123000,\"err_code\":0}"
                : "{\"power\":" + (on ? 9.1 : 0) + ",\"voltage\":123.0,\"err_code\":0}";
        String s = sysinfo();
        return q.has("emeter") ? s.substring(0, s.length() - 1) + ",\"emeter\":{\"get_realtime\":" + rt + "}}" : s;
    }

    @Before
    public void start() throws IOException {
        tcp = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!tcp.isClosed()) {
                try (Socket c = tcp.accept()) {
                    DataInputStream in = new DataInputStream(c.getInputStream());
                    int len = in.readInt();
                    if (len <= 0 || len > 65536) continue;   // not our protocol (an HTTP probe, say)
                    byte[] b = new byte[len];
                    in.readFully(b);
                    String req = Kasa.decrypt(b, len);
                    received.add(req);
                    byte[] out = Kasa.encrypt(reply(req));
                    OutputStream o = c.getOutputStream();
                    o.write(new byte[]{0, 0, (byte) (out.length >>> 8), (byte) out.length});
                    o.write(out);
                    o.flush();
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        udp = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        Thread u = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (!udp.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udp.receive(p);
                    if (!Kasa.decrypt(p.getData(), p.getLength()).equals(Kasa.SYSINFO)) continue;
                    byte[] out = Kasa.encrypt(sysinfo());
                    udp.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                } catch (Exception ignored) {
                }
            }
        });
        u.setDaemon(true);
        u.start();
    }

    @After
    public void stop() throws IOException {
        tcp.close();
        udp.close();
    }

    private String host() {
        return "127.0.0.1:" + tcp.getLocalPort();
    }

    private Home.Device device() {
        Home.Device d = new Home.Device("k", "Plug 1", host(), 0, true, false);
        d.brand = Home.KASA;
        return d;
    }

    @Test
    public void encryptionMatchesPythonKasa() {
        byte[] body = Kasa.encrypt(Kasa.SYSINFO);
        StringBuilder hex = new StringBuilder(String.format("%08x", body.length));
        for (byte b : body) hex.append(String.format("%02x", b & 0xFF));
        assertEquals(PYTHON_KASA_SYSINFO, hex.toString());
    }

    @Test
    public void decryptUndoesEncrypt() {
        String s = "{\"alias\":\"Küche – lamp\"}";
        byte[] e = Kasa.encrypt(s);
        assertEquals(s, Kasa.decrypt(e, e.length));
    }

    @Test
    public void probeReadsAliasAndModel() throws IOException {
        Shelly.Info i = Kasa.probe(host());
        assertEquals(Home.KASA, i.brand);
        assertEquals("Plug 1", i.name);
        assertEquals("EP25", i.model);   // the region suffix "(US)" is dropped
        assertTrue(i.hasSwitch);
        assertFalse(i.hasSensor);
    }

    @Test
    public void probeWithoutAliasFallsBackToModelName() throws IOException {
        alias = "  ";
        Shelly.Info i = Kasa.probe(host());
        assertNull(i.name);
        assertEquals("EP25 1", MainActivity.deviceFrom("192.168.1.1", i).name);
    }

    @Test
    public void statusReadsStateAndMilliwatts() throws IOException {
        Shelly.Status s = Kasa.status(device());
        assertTrue(s.on);
        assertEquals(9.1, s.watts, 0.001);
    }

    @Test
    public void statusReadsOlderWattFirmware() throws IOException {
        milliwatts = false;
        assertEquals(9.1, Kasa.status(device()).watts, 0.001);
    }

    @Test
    public void plugWithoutEnergyMeterHasNoWatts() throws IOException {
        emeter = false;
        Shelly.Status s = Kasa.status(device());
        assertTrue(s.on);
        assertTrue(Double.isNaN(s.watts));
    }

    @Test
    public void setTurnsThePlugOffAndOn() throws IOException {
        Kasa.set(device(), false);
        assertFalse(on);
        assertFalse(Kasa.status(device()).on);
        Kasa.set(device(), true);
        assertTrue(on);
        assertTrue(received.contains("{\"system\":{\"set_relay_state\":{\"state\":0}}}"));
    }

    @Test
    public void devicesRoutesByBrand() throws IOException {
        Devices.set(device(), false);
        assertFalse(on);
        assertFalse(Devices.status(device()).on);
    }

    @Test
    public void devicesProbeFallsBackToKasa() throws IOException {
        // No Shelly answers here (the fake ignores HTTP), so Devices.probe must try Kasa next.
        Shelly.Info i = Devices.probe(host());
        assertEquals(Home.KASA, i.brand);
        Home.Device d = MainActivity.deviceFrom(host(), i);
        assertEquals(Home.KASA, d.brand);
        assertEquals("Plug 1", d.name);
    }

    @Test
    public void probeRejectsSilence() {
        try {
            Kasa.probe("127.0.0.1:1");
            fail();
        } catch (IOException expected) {
        }
    }

    @Test
    public void discoveryFindsThePlug() {
        Set<String> found = Kasa.discover(1500, "127.0.0.1", udp.getLocalPort());
        assertEquals(Collections.singleton("127.0.0.1:" + udp.getLocalPort()), found);
    }

    @Test
    public void discoveryReturnsNothingWhenNobodyAnswers() {
        long t0 = System.currentTimeMillis();
        assertTrue(Kasa.discover(800, "127.0.0.1", 1).isEmpty());
        assertTrue(System.currentTimeMillis() - t0 < 2000);
    }

    @Test
    public void brandSurvivesSaveAndLoad() {
        Home h = new Home();
        h.devices.add(device());
        Home back = Home.fromJson(h.toJson());
        assertEquals(Home.KASA, back.devices.get(0).brand);
    }

    @Test
    public void devicesSavedBeforeKasaSupportAreShelly() {
        Home h = Home.fromJson("{\"devices\":[{\"id\":\"a\",\"name\":\"Lamp\",\"host\":\"10.0.0.2\",\"gen\":3}]}");
        assertEquals(Home.SHELLY, h.devices.get(0).brand);
    }

    @Test
    public void hostParsing() {
        assertEquals(9999, Kasa.address("192.168.1.198").getPort());
        assertEquals(9998, Kasa.address("127.0.0.1:9998").getPort());
        assertEquals("192.168.1.198", Kasa.address("192.168.1.198").getHostString());
    }

    /**
     * Read-only check against real plugs on the network: KASA_LIVE=1 ./gradlew testReleaseUnitTest.
     * Never switches anything.
     */
    @Test
    public void livePlugsReadOnly() throws IOException {
        if (System.getenv("KASA_LIVE") == null) return;
        String direct = System.getenv("KASA_LIVE");
        if (direct.contains(".")) {   // KASA_LIVE=<ip>: read that plug directly too
            try {
                System.out.println("LIVE direct " + direct + " -> " + Kasa.probe(direct).name);
            } catch (IOException e) {
                System.out.println("LIVE direct " + direct + " -> " + e);
            }
        }
        Set<String> hosts = Kasa.discover(4000);
        System.out.println("LIVE discovered " + hosts);
        assertFalse("no Kasa plug answered the broadcast", hosts.isEmpty());
        for (String h : hosts) {
            Shelly.Info i = Kasa.probe(h);
            Home.Device d = MainActivity.deviceFrom(h, i);
            d.brand = Home.KASA;
            Shelly.Status s = Kasa.status(d);
            System.out.println("LIVE " + h + " name=" + d.name + " model=" + i.model + " on=" + s.on + " watts=" + s.watts);
        }
    }
}
