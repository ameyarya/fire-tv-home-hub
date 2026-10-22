package com.homecontrol.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone remote's tiny web server on port 8765: the page (assets/remote.html, with Remote and Home tabs)
 * at /r/<secret>, and its API under /api/<secret>/ (keys and typing for Remote; HomeApi for Home). Plain HTTP on the home network; anything without the current secret gets 404.
 */
final class RemoteServer {

    private final Context app;
    private final Remote remote;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket server, secure;
    private byte[] page;

    RemoteServer(Context c) {
        app = c.getApplicationContext();
        remote = Remote.get(app);
    }

    /** http on 8765 (the page redirects to https once there's a certificate; the API stays, for the voice stick), https on 8443. */
    synchronized void start() {
        if (server == null) {
            try {
                server = new ServerSocket(Remote.PORT);
                listen(server, false);
            } catch (IOException e) {
                remote.lastError = "port " + Remote.PORT + ": " + e.getMessage();
            }
        }
        if (secure == null && Remote.hasHttps(app)) {
            try {
                java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
                ks.load(new java.io.ByteArrayInputStream(Remote.p12(app)), Remote.P12_PASSWORD.toCharArray());
                javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                        javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, Remote.P12_PASSWORD.toCharArray());
                javax.net.ssl.SSLContext tls = javax.net.ssl.SSLContext.getInstance("TLS");
                tls.init(kmf.getKeyManagers(), null, null);
                secure = tls.getServerSocketFactory().createServerSocket(Remote.HTTPS_PORT);
                listen(secure, true);
            } catch (Exception e) {
                remote.lastError = "https: " + e.getMessage();
            }
        }
    }

    private void listen(ServerSocket s, boolean tls) {
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c, tls));
                } catch (IOException ignored) {
                }
            }
        }, tls ? "remote-https" : "remote-http");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        for (ServerSocket s : new ServerSocket[]{server, secure}) {
            try {
                if (s != null) s.close();
            } catch (IOException ignored) {
            }
        }
        server = secure = null;
    }

    private void handle(Socket c, boolean tls) {
        try (Socket sock = c) {
            sock.setSoTimeout(20_000);   // finding devices takes ~8 s
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String request = line(in);
            if (request == null) return;
            Map<String, String> headers = new HashMap<>();
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(Locale.US), h.substring(i + 1).trim());
            }
            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (len < 0 || len > 32768) {
                reply(sock.getOutputStream(), 413, "text/plain", "too long");
                return;
            }
            byte[] body = new byte[len];
            for (int got = 0; got < len; ) {
                int n = in.read(body, got, len - got);
                if (n < 0) break;
                got += n;
            }
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            route(parts[0], parts[1], new String(body, StandardCharsets.UTF_8), sock.getOutputStream(), tls);
        } catch (IOException | JSONException | RuntimeException ignored) {
        }
    }

    private void route(String method, String target, String body, OutputStream out, boolean tls) throws IOException, JSONException {
        String path = target.split("\\?")[0];
        // Open to anyone on the Wi-Fi (no data in them): icons, the app manifest, and the certificate setup.
        if (method.equals("GET") && path.matches("/icon-(180|192|512)\\.png")) {
            replyBytes(out, 200, "image/png", asset(path.substring(1)));
            return;
        }
        if (method.equals("GET") && path.equals("/manifest.webmanifest")) {
            reply(out, 200, "application/manifest+json", MANIFEST);
            return;
        }
        if (method.equals("GET") && (path.equals("/ca.pem") || path.equals("/ca.crt")) && Remote.hasHttps(app)) {
            replyBytes(out, 200, "application/x-x509-ca-cert", Remote.caPem(app).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (method.equals("GET") && path.equals("/setup")) {
            reply(out, 200, "text/html; charset=utf-8", setupPage());
            return;
        }
        // The page itself moves to https once there's a certificate; the API stays on http for the voice stick.
        if (!tls && Remote.hasHttps(app) && method.equals("GET") && (path.equals("/") || path.startsWith("/r/"))) {
            String to = "https://" + Remote.HOST + ".local:" + Remote.HTTPS_PORT + target;
            out.write(("HTTP/1.1 302 Found\r\nLocation: " + to + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
        String[] p = path.split("/");   // "", "r"|"api", token, action
        if (path.equals("/") || path.isEmpty()) {   // the page; it pairs with the code if it has no secret yet
            reply(out, 200, "text/html; charset=utf-8", page());
            return;
        }
        if (method.equals("POST") && path.equals("/api/pair")) {
            pair(body.trim(), out);
            return;
        }
        if (p.length < 3 || !secretOk(p[2])) {
            reply(out, 404, "text/html; charset=utf-8", "<meta name=viewport content='width=device-width'>"
                    + "<body style='font:17px system-ui;background:#000;color:#ccc;padding:24px'>This link is old or wrong. "
                    + "On the TV, open Smart Home → Settings → Phone remote and scan the code again.");
            return;
        }
        if (p[1].equals("r")) {
            reply(out, 200, "text/html; charset=utf-8", page());
            return;
        }
        if (!p[1].equals("api") || p.length < 4) {
            reply(out, 404, "text/plain", "not found");
            return;
        }
        String action = p[3];
        if (method.equals("GET") && action.equals("status")) {
            reply(out, 200, "application/json", remote.status().toString());
        } else if (method.equals("GET") && action.equals("apps")) {
            reply(out, 200, "application/json", remote.appsJson().toString());
        } else if (method.equals("POST") && action.equals("key")) {
            String c = param(query, "c");
            int code = "power".equals(c) ? remote.powerKey() : parse(c);
            if (!Remote.allowedKey(code)) {
                reply(out, 400, "text/plain", "not a remote key");
                return;
            }
            remote.key(code);
            reply(out, 204, "text/plain", "");
        } else if (method.equals("POST") && action.equals("type")) {
            remote.type(body);
            reply(out, 204, "text/plain", "");
        } else if (method.equals("POST") && action.equals("open")) {
            reply(out, remote.open(body) ? 204 : 400, "text/plain", "");
        } else if (method.equals("GET") && action.equals("home")) {          // the Home tab
            reply(out, 200, "application/json", HomeApi.home(app).toString());
        } else if (method.equals("GET") && action.equals("activity")) {
            reply(out, 200, "application/json", HomeApi.activity(app).toString());
        } else if (method.equals("POST") && action.equals("device")) {
            reply(out, HomeApi.device(app, param(query, "id"), "1".equals(param(query, "on"))) ? 204 : 400, "text/plain", "");
        } else if (method.equals("POST") && action.equals("scene")) {
            int ok = HomeApi.scene(app, param(query, "id"));
            reply(out, ok < 0 ? 400 : 200, "application/json", "{\"done\":" + ok + "}");
        } else if (method.equals("POST") && action.equals("automation")) {
            reply(out, HomeApi.automation(app, param(query, "id"), "1".equals(param(query, "on"))) ? 204 : 400, "text/plain", "");
        } else if (method.equals("POST") && action.equals("move")) {
            int by = "-1".equals(param(query, "by")) ? -1 : 1;
            reply(out, HomeApi.move(app, param(query, "kind"), param(query, "id"), by) ? 204 : 400, "text/plain", "");
        } else if (method.equals("POST") && action.equals("automation-save")) {
            String why = HomeApi.saveAutomation(app, body);
            reply(out, why == null ? 204 : 400, "text/plain", why == null ? "" : why);
        } else if (method.equals("POST") && action.equals("automation-delete")) {
            reply(out, HomeApi.deleteAutomation(app, param(query, "id")) ? 204 : 400, "text/plain", "");
        } else if (method.equals("POST") && action.equals("find-devices")) {
            JSONArray added = HomeApi.findDevices(app);
            reply(out, 200, "application/json", added == null ? "[]" : added.toString());
        } else if (method.equals("POST") && action.equals("device-add")) {
            answer(out, HomeApi.addDevice(app, body));
        } else if (method.equals("POST") && action.equals("scene-save")) {
            answer(out, HomeApi.saveScene(app, body));
        } else if (method.equals("POST") && action.equals("scene-delete")) {
            reply(out, HomeApi.deleteScene(app, param(query, "id")) ? 204 : 400, "text/plain", "");
        } else if (method.equals("POST") && action.equals("camera-add")) {
            answer(out, HomeApi.addCamera(app, body));
        } else if (method.equals("POST") && action.equals("room-add")) {
            answer(out, HomeApi.addRoom(app, body));
        } else if (method.equals("POST") && action.equals("app")) {
            reply(out, remote.launch(body.trim()) ? 204 : 400, "text/plain", "");
        } else {
            reply(out, 404, "text/plain", "not found");
        }
    }

    private int wrongCodes;
    private long lockedUntil;

    /** The 4-digit code shown on the TV, for the secret. Five wrong tries lock it for a minute. */
    private synchronized void pair(String code, OutputStream out) throws IOException {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            reply(out, 429, "text/plain", "Too many tries. Wait a minute.");
            return;
        }
        if (MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), Remote.code(app).getBytes(StandardCharsets.UTF_8))) {
            wrongCodes = 0;
            reply(out, 200, "text/plain", Remote.token(app));
            return;
        }
        if (++wrongCodes >= 5) {
            wrongCodes = 0;
            lockedUntil = now + 60_000;
        }
        reply(out, 403, "text/plain", "Wrong code");
    }

    /** 204 when done, else 400 with what's wrong (shown on the phone). */
    private static void answer(OutputStream out, String why) throws IOException {
        reply(out, why == null ? 204 : 400, "text/plain", why == null ? "" : why);
    }

    private boolean secretOk(String given) {
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                Remote.token(app).getBytes(StandardCharsets.UTF_8));
    }

    private static final String MANIFEST = "{\"name\":\"Smart Home\",\"short_name\":\"Smart Home\",\"start_url\":\"/\","
            + "\"display\":\"standalone\",\"background_color\":\"#000000\",\"theme_color\":\"#000000\",\"icons\":["
            + "{\"src\":\"/icon-192.png\",\"sizes\":\"192x192\",\"type\":\"image/png\"},"
            + "{\"src\":\"/icon-512.png\",\"sizes\":\"512x512\",\"type\":\"image/png\"}]}";

    private final java.util.Map<String, byte[]> assets = new java.util.concurrent.ConcurrentHashMap<>();

    private byte[] asset(String name) throws IOException {
        byte[] b = assets.get(name);
        if (b == null) {
            try (InputStream in = app.getAssets().open(name)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                b = o.toByteArray();
            }
            assets.put(name, b);
        }
        return b;
    }

    /** Plain http: how a phone trusts the home certificate once, so https://smart-home.local opens without warnings. */
    private String setupPage() {
        String https = "https://" + Remote.HOST + ".local:" + Remote.HTTPS_PORT;
        if (!Remote.hasHttps(app)) {
            return "<meta name=viewport content='width=device-width'><body style='font:17px system-ui;background:#000;color:#ddd;padding:24px'>"
                    + "HTTPS isn't set up on this TV yet (run ./https-setup.sh on the computer). Use http://"
                    + Remote.HOST + ".local:" + Remote.PORT + " meanwhile.";
        }
        return "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Smart Home setup</title><link rel=apple-touch-icon href=/icon-180.png>"
                + "<body style='font:16px -apple-system,system-ui;background:#000;color:#f2f2f7;padding:20px 18px;max-width:560px;margin:auto;line-height:1.5'>"
                + "<h1 style='font-size:26px'>Trust your home, once</h1>"
                + "<p style='color:#8e8e93'>Smart Home uses its own certificate, from your home's own authority. Install that authority on this "
                + "phone once and " + https + " opens without warnings.</p>"
                + "<p><a href=/ca.crt style='display:block;text-align:center;background:#f2f2f7;color:#000;border-radius:14px;padding:14px;"
                + "font-weight:600;text-decoration:none'>1. Download the certificate</a></p>"
                + "<h2 style='font-size:17px'>iPhone</h2><ol style='color:#c7c7cc'><li>Allow the download, then open Settings → "
                + "<b>Profile Downloaded</b> → Install.</li><li>Settings → General → About → <b>Certificate Trust Settings</b> → turn on "
                + "<b>Smart Home home CA</b>.</li></ol>"
                + "<h2 style='font-size:17px'>Android</h2><ol style='color:#c7c7cc'><li>Settings → Security → Encryption &amp; credentials → "
                + "<b>Install a certificate</b> → CA certificate → pick the downloaded file.</li></ol>"
                + "<p><a href='" + https + "/' style='display:block;text-align:center;background:#30d158;color:#000;border-radius:14px;"
                + "padding:14px;font-weight:600;text-decoration:none'>2. Open Smart Home</a></p>";
    }

    private String page() throws IOException {
        if (page == null) {
            try (InputStream in = app.getAssets().open("remote.html")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                page = b.toByteArray();
            }
        }
        return new String(page, StandardCharsets.UTF_8);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(name)) {
                try {
                    return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') return b.toString().replace("\r", "");
            if (b.length() > 4096) return null;
            b.append((char) c);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void replyBytes(OutputStream out, int code, String type, byte[] b) throws IOException {
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request"
                : code == 403 ? "Forbidden" : code == 413 ? "Too Large" : code == 429 ? "Too Many Requests" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
