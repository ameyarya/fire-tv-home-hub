package com.homecontrol.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Local control of TP-Link Kasa plugs over their original LAN protocol, the one python-kasa calls
 * "XOR": JSON scrambled with a running XOR (key 171), sent over TCP port 9999 behind a 4-byte length,
 * and over UDP 9999 without it for discovery. No cloud and no login. Plugs such as the EP25, HS103 and
 * KP115 on firmware that still speaks it; newer firmware that only speaks KLAP is not supported.
 *
 * Blocking calls; run them off the main thread.
 */
final class Kasa {

    static final int PORT = 9999;
    static final int TIMEOUT_MS = 2500;
    static final String SYSINFO = "{\"system\":{\"get_sysinfo\":{}}}";

    private Kasa() {
    }

    static byte[] encrypt(String json) {
        byte[] in = json.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[in.length];
        int key = 171;
        for (int i = 0; i < in.length; i++) {
            key = (key ^ in[i]) & 0xFF;
            out[i] = (byte) key;
        }
        return out;
    }

    static String decrypt(byte[] in, int len) {
        byte[] out = new byte[len];
        int key = 171;
        for (int i = 0; i < len; i++) {
            int c = in[i] & 0xFF;
            out[i] = (byte) (key ^ c);
            key = c;
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    /** host or host:port; the port defaults to 9999. */
    static InetSocketAddress address(String host) {
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            return new InetSocketAddress(host.substring(0, colon), Integer.parseInt(host.substring(colon + 1)));
        }
        return new InetSocketAddress(host, PORT);
    }

    static JSONObject query(String host, String json) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(address(host), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            byte[] body = encrypt(json);
            OutputStream o = s.getOutputStream();
            o.write(new byte[]{(byte) (body.length >>> 24), (byte) (body.length >>> 16), (byte) (body.length >>> 8), (byte) body.length});
            o.write(body);
            o.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int len = in.readInt();
            if (len <= 0 || len > 256 * 1024) throw new IOException("not a Kasa device");
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new JSONObject(decrypt(buf, len));
        } catch (JSONException e) {
            throw new IOException("not a Kasa device");
        }
    }

    /** What a host turned out to be. Throws IOException when nothing answers or it is not a Kasa plug. */
    static Shelly.Info probe(String host) throws IOException {
        return parseInfo(query(host, SYSINFO));
    }

    static Shelly.Info parseInfo(JSONObject reply) throws IOException {
        JSONObject sys = reply.optJSONObject("system");
        JSONObject info = sys != null ? sys.optJSONObject("get_sysinfo") : null;
        if (info == null || !info.has("relay_state")) throw new IOException("not a Kasa plug");
        String alias = info.optString("alias", "").trim();
        Shelly.Info i = new Shelly.Info(Shelly.Info.KASA, 0, info.optString("model", "Kasa").replaceAll("\\(.*$", ""),
                alias.isEmpty() ? null : alias, true, false);
        i.uid = info.optString("deviceId", info.optString("mac", ""));
        return i;
    }

    static Shelly.Status status(Home.Device d) throws IOException {
        return parseStatus(query(d.host, "{\"system\":{\"get_sysinfo\":{}},\"emeter\":{\"get_realtime\":{}}}"));
    }

    static Shelly.Status parseStatus(JSONObject reply) throws IOException {
        JSONObject sys = reply.optJSONObject("system");
        JSONObject info = sys != null ? sys.optJSONObject("get_sysinfo") : null;
        if (info == null) throw new IOException("no sysinfo");
        double watts = Double.NaN;
        JSONObject em = reply.optJSONObject("emeter");
        JSONObject rt = em != null ? em.optJSONObject("get_realtime") : null;
        if (rt != null && rt.optInt("err_code", 0) == 0) {
            // Newer firmware reports milliwatts, older watts.
            if (rt.has("power_mw")) watts = rt.optDouble("power_mw") / 1000.0;
            else if (rt.has("power")) watts = rt.optDouble("power");
        }
        return new Shelly.Status(info.optInt("relay_state") == 1, watts, Double.NaN, Double.NaN);
    }

    /**
     * Energy used today and this month in Wh, from the plug's own meter ({NaN, NaN} for plugs without one).
     * Older firmware reports kWh as "energy", newer Wh as "energy_wh".
     */
    static double[] energy(Home.Device d, java.time.LocalDate today) throws IOException {
        JSONObject r = query(d.host, "{\"emeter\":{\"get_daystat\":{\"month\":" + today.getMonthValue() + ",\"year\":" + today.getYear()
                + "},\"get_monthstat\":{\"year\":" + today.getYear() + "}}}");
        return parseEnergy(r, today);
    }

    static double[] parseEnergy(JSONObject r, java.time.LocalDate today) {
        double day = Double.NaN, month = Double.NaN;
        JSONObject em = r.optJSONObject("emeter");
        if (em == null) return new double[]{day, month};
        org.json.JSONArray days = em.optJSONObject("get_daystat") != null ? em.optJSONObject("get_daystat").optJSONArray("day_list") : null;
        for (int i = 0; days != null && i < days.length(); i++) {
            JSONObject e = days.optJSONObject(i);
            if (e != null && e.optInt("day") == today.getDayOfMonth() && e.optInt("month") == today.getMonthValue()) day = wh(e);
        }
        if (days != null && Double.isNaN(day)) day = 0;   // the meter answered; nothing used yet today
        org.json.JSONArray months = em.optJSONObject("get_monthstat") != null ? em.optJSONObject("get_monthstat").optJSONArray("month_list") : null;
        for (int i = 0; months != null && i < months.length(); i++) {
            JSONObject e = months.optJSONObject(i);
            if (e != null && e.optInt("month") == today.getMonthValue()) month = wh(e);
        }
        if (months != null && Double.isNaN(month)) month = 0;
        return new double[]{day, month};
    }

    private static double wh(JSONObject e) {
        return e.has("energy_wh") ? e.optDouble("energy_wh") : e.optDouble("energy", 0) * 1000.0;
    }

    static void set(Home.Device d, boolean on) throws IOException {
        JSONObject r = query(d.host, "{\"system\":{\"set_relay_state\":{\"state\":" + (on ? 1 : 0) + "}}}");
        JSONObject sys = r.optJSONObject("system");
        JSONObject res = sys != null ? sys.optJSONObject("set_relay_state") : null;
        if (res == null || res.optInt("err_code", -1) != 0) throw new IOException("plug refused");
    }

    /**
     * Broadcasts get_sysinfo on UDP and returns every address that answered like a Kasa plug. Sent to
     * each network's own broadcast address (192.168.1.255) as well as 255.255.255.255: with more than
     * one interface up, the generic one can leave by the wrong one and reach no plug.
     */
    static Set<String> discover(int timeoutMs) {
        List<String> targets = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    if (a.getBroadcast() != null) targets.add(a.getBroadcast().getHostAddress());
                }
            }
        } catch (IOException ignored) {
        }
        targets.add("255.255.255.255");
        return discover(timeoutMs, targets, PORT);
    }

    static Set<String> discover(int timeoutMs, String target, int port) {
        return discover(timeoutMs, Collections.singletonList(target), port);
    }

    static Set<String> discover(int timeoutMs, List<String> targets, int port) {
        Set<String> found = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            byte[] q = encrypt(SYSINFO);
            // UDP can drop a packet; ask three times.
            for (int i = 0; i < 3; i++) {
                for (String target : targets) {
                    try {
                        s.send(new DatagramPacket(q, q.length, InetAddress.getByName(target), port));
                    } catch (IOException ignored) {   // one unreachable network must not stop the rest
                    }
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                s.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                try {
                    parseInfo(new JSONObject(decrypt(p.getData(), p.getLength())));
                    String host = p.getAddress().getHostAddress();
                    found.add(port == PORT ? host : host + ":" + port);
                } catch (IOException | JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return found;
    }
}
