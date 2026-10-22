package com.homecontrol.tv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds cameras on the local network: anything that answers an RTSP OPTIONS request on port 554.
 * Cameras don't announce themselves, so every address of the TV's own /24 network is asked, 32 at a
 * time with a short timeout (a few seconds in all). Blocking; run it off the main thread.
 */
final class CameraScan {

    static final int RTSP_PORT = 554;

    private CameraScan() {
    }

    /** Every other address on this device's IPv4 networks, /24 at most (larger networks are cut to their /24). */
    static List<String> neighbours() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    if (!(a.getAddress() instanceof Inet4Address)) continue;
                    byte[] ip = a.getAddress().getAddress();
                    for (int last = 1; last < 255; last++) {
                        if (last == (ip[3] & 0xFF)) continue;
                        out.add((ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + last);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** host or host:port entries that answered as RTSP servers, in the order given. */
    static List<String> scan(List<String> hosts, int timeoutMs) {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<Boolean>> answers = new ArrayList<>();
        for (String h : hosts) answers.add(pool.submit(() -> isRtsp(h, timeoutMs)));
        List<String> found = new ArrayList<>();
        for (int i = 0; i < hosts.size(); i++) {
            try {
                if (answers.get(i).get()) found.add(hosts.get(i));
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        try {
            pool.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return found;
    }

    static boolean isRtsp(String host, int timeoutMs) {
        try (Socket s = new Socket()) {
            java.net.InetSocketAddress at = host.contains(":")
                    ? new java.net.InetSocketAddress(host.substring(0, host.indexOf(':')), Integer.parseInt(host.substring(host.indexOf(':') + 1)))
                    : new java.net.InetSocketAddress(InetAddress.getByName(host), RTSP_PORT);
            s.connect(at, timeoutMs);
            s.setSoTimeout(timeoutMs * 2);
            OutputStream o = s.getOutputStream();
            o.write(("OPTIONS rtsp://" + host + "/ RTSP/1.0\r\nCSeq: 1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            String first = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return first != null && first.startsWith("RTSP/1.0");
        } catch (Exception e) {
            return false;
        }
    }

    /** The stream address offered for a camera found at host; eufy's path, changeable with home.sh camera. */
    static String defaultUrl(String host) {
        return "rtsp://" + host + (host.contains(":") ? "" : ":" + RTSP_PORT) + "/live0";
    }
}
