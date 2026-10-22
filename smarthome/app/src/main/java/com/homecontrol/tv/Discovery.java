package com.homecontrol.tv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds Shelly devices on the Wi-Fi with mDNS, the way they announce themselves: Gen2+ as
 * {@code _shelly._tcp}, Gen1 as {@code shelly…} under {@code _http._tcp}. Each answer is probed over
 * HTTP before it counts. Runs for {@link #SCAN_MS}, then reports every host:port it found once.
 */
final class Discovery {

    interface Callback {
        void onFound(List<String> hosts);
    }

    static final long SCAN_MS = 7000;
    private static final String[] TYPES = {"_shelly._tcp.", "_http._tcp."};

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    // NsdManager resolves one service at a time on older Android; queue them.
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final Set<String> hosts = new LinkedHashSet<>();
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();

    Discovery(Context ctx) {
        nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    void scan(Callback cb) {
        for (String type : TYPES) {
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onServiceFound(NsdServiceInfo s) {
                    String name = s.getServiceName().toLowerCase(Locale.ROOT);
                    if (type.startsWith("_http") && !name.startsWith("shelly")) return;
                    resolver.execute(() -> resolve(s));
                }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceLost(NsdServiceInfo s) { }
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
            };
            listeners.add(l);
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
            } catch (RuntimeException ignored) {
                listeners.remove(l);
            }
        }
        main.postDelayed(() -> {
            stop();
            List<String> found;
            synchronized (hosts) {
                found = new ArrayList<>(hosts);
            }
            cb.onFound(found);
        }, SCAN_MS);
    }

    void stop() {
        for (NsdManager.DiscoveryListener l : listeners) {
            try {
                nsd.stopServiceDiscovery(l);
            } catch (RuntimeException ignored) {
            }
        }
        listeners.clear();
        resolver.shutdownNow();
    }

    private void resolve(NsdServiceInfo s) {
        final Object lock = new Object();
        final boolean[] done = {false};
        try {
            nsd.resolveService(s, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                    finish();
                }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    InetAddress a = i.getHost();
                    if (a != null) {
                        String host = a.getHostAddress() + (i.getPort() == 80 ? "" : ":" + i.getPort());
                        synchronized (hosts) {
                            hosts.add(host);
                        }
                    }
                    finish();
                }
                private void finish() {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            synchronized (lock) {
                if (!done[0]) lock.wait(3000);
            }
        } catch (InterruptedException | RuntimeException ignored) {
        }
    }
}
