package com.homecontrol.tv;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Local HTTP control of Shelly devices, no cloud. Gen2+ (Plus, Pro, Gen3, Gen4) speak RPC over HTTP
 * ({@code /rpc/Method}); Gen1 has {@code /shelly}, {@code /status} and {@code /relay/0}. Only channel 0
 * is used: one switch and one sensor per device, which covers plugs and the H&T sensors.
 *
 * Blocking calls; run them off the main thread.
 */
final class Shelly {

    static final int TIMEOUT_MS = 2500;

    /** What a host turned out to be (a Shelly, or a Kasa plug: see Kasa.probe). */
    static final class Info {
        static final String SHELLY = Home.SHELLY, KASA = Home.KASA;
        final String brand;
        /** The device's permanent identity (see Home.Device.uid); empty when it reported none. */
        String uid = "";
        final int gen;
        final String model;
        /** The name set in the device's own web page, or null. */
        final String name;
        final boolean hasSwitch;
        final boolean hasSensor;

        Info(int gen, String model, String name, boolean hasSwitch, boolean hasSensor) {
            this(SHELLY, gen, model, name, hasSwitch, hasSensor);
        }

        Info(String brand, int gen, String model, String name, boolean hasSwitch, boolean hasSensor) {
            this.brand = brand;
            this.gen = gen;
            this.model = model;
            this.name = name;
            this.hasSwitch = hasSwitch;
            this.hasSensor = hasSensor;
        }
    }

    /** A snapshot of one device. Fields a device doesn't have stay NaN / false. */
    static final class Status {
        final boolean on;
        final double watts;
        final double tempC;
        final double humidity;

        Status(boolean on, double watts, double tempC, double humidity) {
            this.on = on;
            this.watts = watts;
            this.tempC = tempC;
            this.humidity = humidity;
        }
    }

    private Shelly() {
    }

    /** Asks a host what it is. Throws IOException when nothing answers or it is not a Shelly. */
    static Info probe(String host) throws IOException {
        JSONObject id = get(host, "/shelly");
        if (id.has("gen") && id.optInt("gen") >= 2) {
            JSONObject st = get(host, "/rpc/Shelly.GetStatus");
            return parseGen2(id, st);
        }
        if (!id.has("type") && !id.has("mac")) throw new IOException("not a Shelly device");
        return parseGen1(id, get(host, "/status"));
    }

    static Info parseGen2(JSONObject id, JSONObject status) {
        String name = id.isNull("name") ? null : id.optString("name", null);
        Info i = new Info(id.optInt("gen", 2), id.optString("model", id.optString("app", "Shelly")),
                name == null || name.isEmpty() ? null : name,
                status.has("switch:0"), status.has("temperature:0") || status.has("humidity:0"));
        i.uid = id.optString("id", id.optString("mac", ""));
        return i;
    }

    static Info parseGen1(JSONObject id, JSONObject status) {
        boolean relay = status.optJSONArray("relays") != null && status.optJSONArray("relays").length() > 0;
        boolean sensor = status.has("tmp") || status.has("hum");
        Info i = new Info(1, id.optString("type", "Shelly"), null, relay, sensor);
        i.uid = id.optString("mac", "");
        return i;
    }

    static Status status(Home.Device d) throws IOException {
        return d.gen >= 2 ? parseStatusGen2(get(d.host, "/rpc/Shelly.GetStatus")) : parseStatusGen1(get(d.host, "/status"));
    }

    static Status parseStatusGen2(JSONObject st) {
        JSONObject sw = st.optJSONObject("switch:0");
        JSONObject t = st.optJSONObject("temperature:0");
        JSONObject h = st.optJSONObject("humidity:0");
        return new Status(sw != null && sw.optBoolean("output"),
                sw != null ? sw.optDouble("apower", Double.NaN) : Double.NaN,
                t != null ? t.optDouble("tC", Double.NaN) : Double.NaN,
                h != null ? h.optDouble("rh", Double.NaN) : Double.NaN);
    }

    static Status parseStatusGen1(JSONObject st) {
        boolean on = false;
        double watts = Double.NaN;
        if (st.optJSONArray("relays") != null && st.optJSONArray("relays").length() > 0) {
            on = st.optJSONArray("relays").optJSONObject(0).optBoolean("ison");
        }
        if (st.optJSONArray("meters") != null && st.optJSONArray("meters").length() > 0) {
            watts = st.optJSONArray("meters").optJSONObject(0).optDouble("power", Double.NaN);
        }
        JSONObject tmp = st.optJSONObject("tmp");
        JSONObject hum = st.optJSONObject("hum");
        return new Status(on, watts, tmp != null ? tmp.optDouble("tC", Double.NaN) : Double.NaN,
                hum != null ? hum.optDouble("value", Double.NaN) : Double.NaN);
    }

    static void set(Home.Device d, boolean on) throws IOException {
        if (d.gen >= 2) get(d.host, "/rpc/Switch.Set?id=0&on=" + on);
        else get(d.host, "/relay/0?turn=" + (on ? "on" : "off"));
    }

    static JSONObject get(String host, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + path).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("device has a password set");
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                // Status replies are small; anything huge is not a Shelly.
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 256 * 1024) throw new IOException("reply too large");
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("not JSON: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }
}
