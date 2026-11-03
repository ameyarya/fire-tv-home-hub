package com.homecontrol.tv;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * Answers multicast DNS for {@code home.local} with the TV's address, so the phone page opens at
 * http://home.local:8765. Android's own service discovery can't claim a host name, so this is a small responder
 * of its own: it answers only address questions for that one name, and announces it when it starts.
 */
final class HomeName {

    static final String NAME = "home.local";
    private static final int PORT = 5353, TTL_S = 120, TYPE_A = 1, TYPE_ANY = 255, CLASS_IN = 1;
    private static final String GROUP = "224.0.0.251";

    private final Context app;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;

    HomeName(Context c) {
        app = c.getApplicationContext();
    }

    synchronized void start() {
        if (socket != null) return;
        try {
            lock = ((WifiManager) app.getSystemService(Context.WIFI_SERVICE)).createMulticastLock("SmartHome:name");
            lock.acquire();
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);   // the system's own mDNS service listens here too
            s.bind(new InetSocketAddress(PORT));
            s.joinGroup(InetAddress.getByName(GROUP));
            socket = s;
        } catch (IOException | RuntimeException e) {
            stop();
            return;
        }
        MulticastSocket s = socket;
        Thread t = new Thread(() -> run(s), "home-name");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        if (socket != null) socket.close();
        socket = null;
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }

    private void run(MulticastSocket s) {
        InetAddress group;
        try {
            group = InetAddress.getByName(GROUP);
        } catch (IOException e) {
            return;
        }
        // Announce twice, a second apart, as the spec asks, so caches pick up a changed address.
        for (int i = 0; i < 2; i++) {
            byte[] ip = ownAddress();
            if (ip != null) send(s, answer(0, ip), group, PORT);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
        byte[] buf = new byte[1500];
        while (!s.isClosed()) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (IOException e) {
                continue;
            }
            int asked = asks(buf, p.getLength());
            if (asked < 0) continue;
            byte[] ip = ownAddress();
            if (ip == null) continue;
            // A "unicast response" question, or one from an ordinary resolver's port, gets its answer directly.
            boolean direct = (asked & 0x8000) != 0 || p.getPort() != PORT;
            int id = direct ? ((buf[0] & 0xff) << 8) | (buf[1] & 0xff) : 0;
            if (direct) send(s, answer(id, ip), p.getAddress(), p.getPort());
            else send(s, answer(0, ip), group, PORT);
        }
    }

    private static void send(MulticastSocket s, byte[] msg, InetAddress to, int port) {
        try {
            s.send(new DatagramPacket(msg, msg.length, to, port));
        } catch (IOException ignored) {
        }
    }

    /** The TV's own IPv4 address on the home network, or null when it has none. */
    private static byte[] ownAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getAddress();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * Whether an mDNS message asks for home.local's address: -1 if not, otherwise the question's class field
     * (its top bit is the "answer me directly" flag). Answers and malformed messages are -1.
     */
    static int asks(byte[] m, int len) {
        if (len < 12 || (m[2] & 0x80) != 0) return -1;   // a response, not a query
        int questions = ((m[4] & 0xff) << 8) | (m[5] & 0xff);
        int at = 12;
        for (int q = 0; q < questions; q++) {
            StringBuilder name = new StringBuilder();
            int end = readName(m, len, at, name, 0);
            if (end < 0 || end + 4 > len) return -1;
            int type = ((m[end] & 0xff) << 8) | (m[end + 1] & 0xff);
            int cls = ((m[end + 2] & 0xff) << 8) | (m[end + 3] & 0xff);
            at = end + 4;
            if ((type == TYPE_A || type == TYPE_ANY) && (cls & 0x7fff) == CLASS_IN
                    && name.toString().toLowerCase(Locale.US).equals(NAME)) return cls;
        }
        return -1;
    }

    /** Reads a DNS name at `at` into `out`; returns where the name ends in the message, or -1. */
    private static int readName(byte[] m, int len, int at, StringBuilder out, int depth) {
        if (depth > 8) return -1;
        while (at < len) {
            int n = m[at] & 0xff;
            if (n == 0) return at + 1;
            if ((n & 0xc0) == 0xc0) {   // compressed: the rest is elsewhere in the message
                if (at + 1 >= len) return -1;
                int ptr = ((n & 0x3f) << 8) | (m[at + 1] & 0xff);
                return readName(m, len, ptr, out, depth + 1) < 0 ? -1 : at + 2;
            }
            if (at + 1 + n > len) return -1;
            if (out.length() > 0) out.append('.');
            out.append(new String(m, at + 1, n, java.nio.charset.StandardCharsets.US_ASCII));
            at += 1 + n;
        }
        return -1;
    }

    /** A response giving home.local's address. */
    static byte[] answer(int id, byte[] ip) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        int[] header = {id >> 8, id, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0};   // authoritative answer, one record
        for (int x : header) b.write(x & 0xff);
        for (String label : NAME.split("\\.")) {
            b.write(label.length());
            b.write(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, label.length());
        }
        b.write(0);
        int[] rest = {0, TYPE_A, 0x80, CLASS_IN, 0, 0, TTL_S >> 8, TTL_S & 0xff, 0, 4};   // cache-flush, TTL, length
        for (int x : rest) b.write(x & 0xff);
        b.write(ip, 0, 4);
        return b.toByteArray();
    }
}
