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
    // A thread per connection: a camera stream holds its connection for as long as someone watches.
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private byte[] page;

    RemoteServer(Context c) {
        app = c.getApplicationContext();
        remote = Remote.get(app);
    }

    /** http on 8765, by the TV's address. */
    synchronized void start() {
        if (server != null) return;
        try {
            server = new ServerSocket(Remote.PORT);
        } catch (IOException e) {
            remote.lastError = "port " + Remote.PORT + ": " + e.getMessage();
            return;
        }
        ServerSocket s = server;
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException ignored) {
                }
            }
        }, "remote-http");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(20_000);   // finding devices takes ~8 s (reading only: streams write for as long as needed)
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
            route(parts[0], parts[1], new String(body, StandardCharsets.UTF_8), sock.getOutputStream());
        } catch (IOException | JSONException | RuntimeException ignored) {
        }
    }

    private void route(String method, String target, String body, OutputStream out) throws IOException, JSONException {
        String path = target.split("\\?")[0];
        // Open to anyone on the Wi-Fi (no data in them): icons and the app manifest.
        if (method.equals("GET") && path.matches("/icon-(180|192|512)\\.png")) {
            replyBytes(out, 200, "image/png", asset(path.substring(1)));
            return;
        }
        if (method.equals("GET") && path.equals("/manifest.webmanifest")) {
            reply(out, 200, "application/manifest+json", MANIFEST);
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
            reply(out, 200, "application/json", remote.status().put("cameras", CameraRelay.states()).toString());
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
        } else if (method.equals("GET") && action.equals("camera.mjpg")) {   // the camera live, for <img>
            Home.Camera cam = HomeApi.camera(app, param(query, "id"));
            if (cam == null) {
                reply(out, 404, "text/plain", "no such camera");
                return;
            }
            stream(CameraRelay.get(app, cam), out);
        } else if (method.equals("POST") && action.equals("camera-show")) {
            answer(out, HomeApi.showCamera(app, param(query, "id")));
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
        } else if (method.equals("GET") && action.equals("screen.jpg")) {   // the TV's screen, for the TV screen view
            byte[] jpeg = remote.screen();
            if (jpeg == null) reply(out, 503, "text/plain", remote.lastError);
            else replyBytes(out, 200, "image/jpeg", jpeg);
        } else if (method.equals("POST") && action.equals("gesture")) {
            String[] v = body.trim().split(",");
            if (v.length == 2 || v.length == 4) {
                float[] g = new float[v.length];
                try {
                    for (int i = 0; i < v.length; i++) g[i] = Float.parseFloat(v[i]);
                    remote.gesture(g);
                    reply(out, 204, "text/plain", "");
                } catch (NumberFormatException e) {
                    reply(out, 400, "text/plain", "x,y or x,y,x2,y2");
                }
            } else reply(out, 400, "text/plain", "x,y or x,y,x2,y2");
        } else if (method.equals("GET") && action.equals("settings")) {
            reply(out, 200, "application/json", HomeApi.settings(app).toString());
        } else if (method.equals("POST") && action.equals("settings")) {
            answer(out, HomeApi.saveSettings(app, body));
        } else if (method.equals("POST") && action.equals("rename")) {
            answer(out, HomeApi.rename(app, param(query, "kind"), param(query, "id"), body));
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

    /** MJPEG: each new frame as one part of a multipart response, until the viewer goes away. */
    private static void stream(CameraRelay relay, OutputStream out) throws IOException {
        relay.join();
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            long[] number = {0};
            long last = 0;
            int misses = 0;
            while (misses < 3) {   // ~45 s with no picture: give up
                byte[] j;
                try {
                    j = relay.next(last, 15_000, number);
                } catch (InterruptedException e) {
                    return;
                }
                if (j == null) {
                    misses++;
                    continue;
                }
                misses = 0;
                last = number[0];
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + j.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(j);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } finally {
            relay.leave();
        }
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
