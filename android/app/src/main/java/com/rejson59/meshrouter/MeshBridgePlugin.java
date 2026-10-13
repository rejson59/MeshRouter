package com.rejson59.meshrouter;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Android LAN mesh MVP (API 26+).
 *
 * Host: Android LocalOnlyHotspot + authenticated LAN control server.
 * Member: WifiNetworkSpecifier (API 29+) or legacy WifiConfiguration (26-28)
 *         + heartbeat + authenticated probe relay on port 8788.
 * Both: real battery/operator/connection data and opt-in cellular speed test.
 *
 * Android does NOT grant third-party apps permission to create an open
 * internet-sharing hotspot or transparently bond traffic from other apps.
 * This plugin never claims to do either. LocalOnlyHotspot is LOCAL ONLY.
 */
@CapacitorPlugin(
    name = "MeshBridge",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION }),
        @Permission(alias = "nearby", strings = { Manifest.permission.NEARBY_WIFI_DEVICES })
    }
)
public class MeshBridgePlugin extends Plugin {
    private static final int CONTROL_PORT = 8787;
    private static final int RELAY_PORT = 8788;
    private static final int PROBE_BYTES = 128 * 1024;
    private static final long PEER_TTL_MS = 15000;
    private static final long DEFAULT_RELAY_BUDGET = 25L * 1024 * 1024;
    private static final long MAX_RELAY_BUDGET = 100L * 1024 * 1024;

    private final Object lock = new Object();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final String nodeId = randomToken();

    private WifiManager wifi;
    private ConnectivityManager connectivity;
    private volatile WifiManager.LocalOnlyHotspotReservation reservation;
    private volatile ConnectivityManager.NetworkCallback joinCallback;
    private Network wifiNetwork;
    private MeshHttp.Server controlServer;
    private MeshHttp.Server relayServer;
    private ScheduledFuture<?> heartbeatTask;
    private int legacyNetworkId = -1;

    private volatile boolean connected = false;
    private volatile String role = "";
    private volatile String meshName = "";
    private volatile String displayName = "";
    private volatile String ssid = "";
    private volatile String password = "";
    private volatile String hostIp = "";
    private volatile String token = "";
    private volatile String lastError = "";
    private volatile JSONObject remoteSnapshot = new JSONObject();
    private volatile double downloadMbps = 0;
    private volatile double uploadMbps = 0;
    private volatile int pingMs = 0;
    private volatile long measuredAt = 0;
    private volatile long relayBytes = 0;
    private volatile boolean relayEnabled = false;
    private volatile long relayBudgetBytes = DEFAULT_RELAY_BUDGET;
    private final AtomicLong relaySpentBytes = new AtomicLong(0);
    private final java.util.concurrent.Semaphore relaySlot = new java.util.concurrent.Semaphore(1);
    private final AtomicLong lastRelayAt = new AtomicLong(0);
    private MeshDownloadManager downloader;

    private static final class Peer {
        JSONObject node;
        String ip;
        long seen;
        Peer(JSONObject node, String ip) {
            this.node = node;
            this.ip = ip;
            this.seen = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public void load() {
        wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivity = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        downloader = new MeshDownloadManager(getContext(), () -> {
            NetworkLease lease = acquireCellular();
            return new MeshDownloadManager.Lease() {
                @Override public Network network() { return lease.network; }
                @Override public void close() { lease.close(connectivity); }
            };
        }, this::availableRemotes, () -> token, () -> connected && "host".equals(role),
            new MeshDownloadManager.HostBudget() {
                @Override public long remaining() {
                    return relayEnabled ? Math.max(0, relayBudgetBytes - relaySpentBytes.get()) : 0;
                }
                @Override public void spend(int bytes) {
                    relaySpentBytes.addAndGet(bytes);
                    relayBytes += bytes;
                }
            });
    }

    @PluginMethod
    public void getDeviceInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("model", Build.MODEL == null ? "Android" : Build.MODEL);
        result.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
        result.put("operator", operator());
        Battery b = battery();
        result.put("batteryLevel", b.level);
        result.put("charging", b.charging);
        result.put("signal", signal());
        result.put("hasCellular", hasCellularHardware());
        call.resolve(result);
    }

    @PluginMethod
    public void getBattery(PluginCall call) {
        Battery b = battery();
        JSObject result = new JSObject();
        result.put("level", b.level);
        result.put("charging", b.charging);
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionForAliases(new String[] { "location", "nearby" }, call, "onPermissions");
        } else {
            requestPermissionForAlias("location", call, "onPermissions");
        }
    }

    @PermissionCallback
    private void onPermissions(PluginCall call) {
        JSObject result = new JSObject();
        boolean location = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean nearby = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(getContext(), Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        result.put("location", location);
        result.put("nearby", nearby);
        result.put("wifi", wifi != null && getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI));
        call.resolve(result);
    }

    @PluginMethod
    public void startHotspot(PluginCall call) {
        if (wifi == null) { call.reject("Brak modułu Wi-Fi na tym urządzeniu."); return; }
        if (!hasRuntimePermissions()) { call.reject("Zezwól na lokalizację i urządzenia w pobliżu w ustawieniach aplikacji."); return; }
        synchronized (lock) {
            if (connected || reservation != null) { call.reject("Najpierw opuść obecną siatkę."); return; }
            meshName = trim(call.getString("ssid", "MeshRouter"), 24);
            displayName = trim(call.getString("name", "Ty"), 20);
            token = randomToken();
            lastError = "";
        }
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicReference<WifiManager.LocalOnlyHotspotReservation> opened = new AtomicReference<>();
        scheduler.schedule(() -> {
            if (settled.compareAndSet(false, true)) call.reject("Android nie uruchomił hotspotu (sprawdź Wi-Fi i usługi lokalizacji).");
        }, 18, TimeUnit.SECONDS);
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    if (settled.get()) { r.close(); return; }
                    io.execute(() -> {
                        try {
                            String[] credentials = hotspotCredentials(r);
                            String address = findHotspotIp();
                            MeshHttp.Server server = new MeshHttp.Server(CONTROL_PORT, MeshBridgePlugin.this::handleControl);
                            synchronized (lock) {
                                reservation = r;
                                opened.set(r);
                                controlServer = server;
                                ssid = credentials[0];
                                password = credentials[1];
                                hostIp = address;
                                role = "host";
                                connected = true;
                                peers.clear();
                            }
                            if (settled.compareAndSet(false, true)) call.resolve(snapshot());
                            else stopInternal();
                        } catch (Exception e) {
                            r.close();
                            if (settled.compareAndSet(false, true)) call.reject("Nie udało się uruchomić siatki: " + safeError(e));
                        }
                    });
                }

                @Override
                public void onStopped() {
                    if (reservation != null && reservation == opened.get() && "host".equals(role)) {
                        lastError = "Hotspot został wyłączony przez Androida.";
                        stopInternal();
                    }
                }

                @Override
                public void onFailed(int reason) {
                    if (settled.compareAndSet(false, true)) call.reject("Android odmówił uruchomienia hotspotu (kod " + reason + "). Sprawdź Wi-Fi, lokalizację i czy nie działa inny hotspot.");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            if (settled.compareAndSet(false, true)) call.reject("Hotspot niedostępny: " + safeError(e));
        }
    }

    @PluginMethod
    public void joinMeshNetwork(PluginCall call) {
        if (!hasRuntimePermissions()) { call.reject("Zezwól na lokalizację i urządzenia w pobliżu."); return; }
        if (wifi == null) { call.reject("Brak modułu Wi-Fi."); return; }
        if (connected || joinCallback != null) { call.reject("Najpierw opuść obecną siatkę."); return; }
        final JSONObject invitation;
        try { invitation = parseInvite(call.getString("invite", "")); }
        catch (Exception e) { call.reject("Nieprawidłowe zaproszenie MeshRouter."); return; }
        displayName = trim(call.getString("name", "Ty"), 20);
        ssid = invitation.optString("ssid", "");
        password = invitation.optString("password", "");
        hostIp = invitation.optString("host", "");
        token = invitation.optString("token", "");
        meshName = invitation.optString("mesh", "MeshRouter");
        lastError = "";

        if (Build.VERSION.SDK_INT >= 29) {
            joinModern(call);
        } else {
            io.execute(() -> joinLegacy(call));
        }
    }

    private void joinModern(PluginCall call) {
        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build();
            NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build();
            AtomicBoolean settled = new AtomicBoolean(false);
            AtomicBoolean attempting = new AtomicBoolean(false);
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (settled.get() || !attempting.compareAndSet(false, true)) return;
                    io.execute(() -> establishJoin(call, network, settled));
                }

                @Override
                public void onUnavailable() {
                    if (settled.compareAndSet(false, true)) {
                        clearJoinRequest();
                        call.reject("Nie udało się połączyć z Wi-Fi. Potwierdź systemowe okno Androida i sprawdź zaproszenie.");
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (joinCallback == this && connected && "member".equals(role)) {
                        lastError = "Utracono połączenie z siecią hosta.";
                        stopInternal();
                    }
                }
            };
            joinCallback = callback;
            connectivity.requestNetwork(req, callback, 25000);
        } catch (Exception e) {
            clearJoinRequest();
            call.reject("Android nie pozwolił dołączyć: " + safeError(e));
        }
    }

    private void joinLegacy(PluginCall call) {
        try {
            if (!wifi.isWifiEnabled()) throw new IOException("Włącz Wi-Fi w ustawieniach telefonu.");
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = quote(ssid);
            config.preSharedKey = quote(password);
            int id = wifi.addNetwork(config);
            if (id < 0 || !wifi.enableNetwork(id, true) || !wifi.reconnect()) {
                throw new IOException("Nie można połączyć ze wskazaną siecią Wi-Fi.");
            }
            legacyNetworkId = id;
            Network network = null;
            for (int i = 0; i < 30 && network == null; i++) {
                SystemClock.sleep(500);
                WifiInfo info = wifi.getConnectionInfo();
                if (info == null || !ssid.equals(unquote(info.getSSID()))) continue;
                for (Network n : connectivity.getAllNetworks()) {
                    NetworkCapabilities caps = connectivity.getNetworkCapabilities(n);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { network = n; break; }
                }
            }
            if (network == null) throw new IOException("Upłynął czas łączenia z siecią Wi-Fi.");
            establishJoin(call, network, new AtomicBoolean(false));
        } catch (Exception e) {
            clearJoinRequest();
            call.reject("Nie udało się dołączyć: " + safeError(e));
        }
    }

    private void establishJoin(PluginCall call, Network network, AtomicBoolean settled) {
        if (settled.get()) return;
        Exception last = null;
        List<String> candidates = new ArrayList<>();
        candidates.add(hostIp);
        LinkProperties props = connectivity.getLinkProperties(network);
        if (props != null) for (RouteInfo route : props.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (gateway instanceof Inet4Address) {
                String ip = gateway.getHostAddress();
                if (!candidates.contains(ip)) candidates.add(ip);
            }
        }
        for (String fallback : new String[] { "192.168.43.1", "192.168.49.1", "192.168.4.1" }) {
            if (!candidates.contains(fallback)) candidates.add(fallback);
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty() || !candidate.matches("[0-9.]+")) continue;
            try {
                JSONObject payload = new JSONObject().put("node", localNode());
                JSONObject response = MeshHttp.callJson(network, candidate, CONTROL_PORT, "POST", "/hello", payload, token);
                if (!response.optBoolean("connected")) throw new IOException("Host odmówił dołączenia.");
                MeshHttp.Server relay = new MeshHttp.Server(RELAY_PORT, this::handleRelay);
                if (!settled.compareAndSet(false, true)) { relay.stop(); return; }
                synchronized (lock) {
                    wifiNetwork = network;
                    hostIp = candidate;
                    remoteSnapshot = response;
                    relayServer = relay;
                    role = "member";
                    connected = true;
                }
                startHeartbeats();
                call.resolve(snapshot());
                return;
            } catch (Exception e) { last = e; }
        }
        if (settled.compareAndSet(false, true)) {
            clearJoinRequest();
            call.reject("Połączono z Wi-Fi, ale host MeshRouter jest nieosiągalny. " + safeError(last));
        }
    }

    private void startHeartbeats() {
        if (heartbeatTask != null) heartbeatTask.cancel(true);
        heartbeatTask = scheduler.scheduleWithFixedDelay(() -> {
            if (!connected || !"member".equals(role)) return;
            try {
                JSONObject payload = new JSONObject().put("node", localNode());
                JSONObject state = MeshHttp.callJson(wifiNetwork, hostIp, CONTROL_PORT, "POST", "/heartbeat", payload, token);
                remoteSnapshot = state;
                lastError = "";
            } catch (Exception e) {
                lastError = "Brak odpowiedzi od hosta: " + safeError(e);
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    @PluginMethod
    public void getMeshState(PluginCall call) { call.resolve(snapshot()); }

    @PluginMethod
    public void stopHotspot(PluginCall call) { stopInternal(); call.resolve(new JSObject()); }

    @PluginMethod
    public void leaveMesh(PluginCall call) { stopInternal(); call.resolve(new JSObject()); }

    /** Explicit, revocable permission and per-session budget for EVERY contributor, including the host. */
    @PluginMethod
    public void setRelayEnabled(PluginCall call) {
        if (!connected || !("member".equals(role) || "host".equals(role))) {
            call.reject("Najpierw połącz telefon z siatką."); return;
        }
        boolean allow = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        int limitMb = call.getInt("limitMb", 25);
        if (limitMb < 5 || limitMb > 100) { call.reject("Limit musi wynosić 5–100 MB."); return; }
        if (allow && !hasCellularHardware()) { call.reject("Ten telefon nie ma łącza komórkowego."); return; }
        Battery b = battery();
        if (allow && !b.charging && b.level < 0.15) { call.reject("Bateria poniżej 15% — naładuj telefon przed udostępnianiem."); return; }
        if (allow && relaySpentBytes.get() >= limitMb * 1024L * 1024L) {
            call.reject("Wybrany limit został już wykorzystany w tej sesji."); return;
        }
        relayBudgetBytes = limitMb * 1024L * 1024L;
        relayEnabled = allow;
        call.resolve(snapshot());
        // The host learns the changed consent immediately (or on the next
        // 3-second heartbeat if Wi-Fi is briefly unavailable).
        scheduler.execute(() -> {
            if (!connected || !"member".equals(role)) return;
            try {
                remoteSnapshot = MeshHttp.callJson(wifiNetwork, hostIp, CONTROL_PORT,
                    "POST", "/heartbeat", new JSONObject().put("node", localNode()), token);
            } catch (Exception ignored) {}
        });
    }

    @PluginMethod
    public void startCooperativeDownload(PluginCall call) {
        try {
            JSONObject job = downloader.start(call.getString("url", ""), call.getString("sha256", ""));
            JSObject result = new JSObject(); result.put("job", job); call.resolve(result);
        } catch (Exception e) { call.reject("Nie można rozpocząć pobierania: " + safeError(e)); }
    }

    @PluginMethod
    public void cancelCooperativeDownload(PluginCall call) {
        downloader.cancel();
        call.resolve(snapshot());
    }

    @PluginMethod
    public void shareDownloadedFile(PluginCall call) {
        try { downloader.share(); call.resolve(new JSObject()); }
        catch (Exception e) { call.reject("Nie można udostępnić pliku: " + safeError(e)); }
    }

    @PluginMethod
    public void speedTest(PluginCall call) {
        io.execute(() -> {
            NetworkLease lease = null;
            try {
                // Opt-in test: ~1 MB download + 256 KB upload on the CELLULAR
                // link. It must never silently fall back to another peer's Wi-Fi.
                lease = acquireCellular();
                long start = SystemClock.elapsedRealtimeNanos();
                HttpURLConnection conn = openCellular(lease.network, "https://speed.cloudflare.com/__down?bytes=1000000", "GET");
                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("Serwer testowy HTTP " + code);
                long firstByte = SystemClock.elapsedRealtimeNanos();
                int count = 0;
                try (InputStream in = conn.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        count += n;
                        if (count > 1500000) break;
                    }
                } finally { conn.disconnect(); }
                long elapsed = SystemClock.elapsedRealtimeNanos() - start;
                if (count < 100000) throw new IOException("Serwer nie zwrócił danych testowych.");
                double down = round(count * 8.0 / (elapsed / 1e9) / 1e6);
                int ping = (int) Math.max(1, (firstByte - start) / 1000000);
                double up = measureUpload(lease.network);
                downloadMbps = down;
                uploadMbps = up;
                pingMs = ping;
                measuredAt = System.currentTimeMillis();
                JSObject result = new JSObject();
                result.put("download", down);
                result.put("upload", up);
                result.put("ping", ping);
                result.put("measuredAt", measuredAt);
                result.put("source", "cellular");
                call.resolve(result);
                // Push immediately; don't wait for the next 3-second heartbeat.
                if ("member".equals(role)) scheduler.execute(() -> {
                    try {
                        remoteSnapshot = MeshHttp.callJson(wifiNetwork, hostIp, CONTROL_PORT,
                            "POST", "/heartbeat", new JSONObject().put("node", localNode()), token);
                    } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                call.reject("Test nieudany: " + safeError(e) + " (wymagane aktywne dane komórkowe)");
            } finally {
                if (lease != null) lease.close(connectivity);
            }
        });
    }

    /** Host only: transfer 128 KiB from a selected peer's cellular link to the host over Wi-Fi. */
    @PluginMethod
    public void testRelay(PluginCall call) {
        String id = call.getString("nodeId", "");
        if (!connected || !"host".equals(role)) { call.reject("Test ścieżki może uruchomić tylko host."); return; }
        Peer peer = peers.get(id);
        if (peer == null || SystemClock.elapsedRealtime() - peer.seen > PEER_TTL_MS) {
            call.reject("Wybrany telefon jest niedostępny."); return;
        }
        if (!peer.node.optBoolean("relayEnabled") || peer.node.optLong("relayRemainingBytes") < PROBE_BYTES) {
            call.reject("Uczestnik musi najpierw wyrazić zgodę na udostępnianie danych."); return;
        }
        io.execute(() -> {
            try {
                long start = SystemClock.elapsedRealtimeNanos();
                byte[] bytes = MeshHttp.call(null, peer.ip, RELAY_PORT, "GET", "/probe", null, token, PROBE_BYTES + 1024);
                long elapsed = SystemClock.elapsedRealtimeNanos() - start;
                if (bytes.length < 32768) throw new IOException("Odebrano za mało danych.");
                JSObject result = new JSObject();
                result.put("bytes", bytes.length);
                result.put("mbps", round(bytes.length * 8.0 / (elapsed / 1e9) / 1e6));
                result.put("via", peer.node.optString("name", "węzeł"));
                call.resolve(result);
            } catch (Exception e) {
                call.reject("Przekazanie danych nieudane: " + safeError(e));
            }
        });
    }

    private MeshHttp.Response handleControl(MeshHttp.Request req, String ip) throws Exception {
        if ("GET".equals(req.method) && "/".equals(req.path)) {
            String safe = meshName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            return MeshHttp.Response.text(200, "<!doctype html><html lang=pl><meta charset=utf-8>" +
                "<meta name=viewport content='width=device-width,initial-scale=1'><title>MeshRouter</title>" +
                "<style>body{font:18px system-ui;background:#14171d;color:#e8ecf4;text-align:center;padding:50px 20px}" +
                "a{color:#79a9ff}</style><h1>MeshRouter · " + safe + "</h1>" +
                "<p>To lokalna sieć MeshRouter. Aby dołączyć do siatki, poproś hosta o zaproszenie i zainstaluj aplikację.</p>" +
                "<p>Ta sieć lokalna nie udostępnia automatycznie internetu innym aplikacjom.</p>" +
                "<a href='https://github.com/rejson59/MeshRouter'>Pobierz aplikację</a></html>");
        }
        if (!authorized(req.token())) return MeshHttp.Response.json(401, MeshHttp.error("Brak dostępu"));
        if (!connected || !"host".equals(role)) return MeshHttp.Response.json(400, MeshHttp.error("Siatka nie działa"));
        if ("GET".equals(req.method) && "/state".equals(req.path)) return MeshHttp.Response.json(200, publicState());
        if ("POST".equals(req.method) && ("/hello".equals(req.path) || "/heartbeat".equals(req.path))) {
            JSONObject incoming = req.json().getJSONObject("node");
            String id = incoming.optString("id", "");
            String name = incoming.optString("name", "");
            if (!id.matches("[A-Za-z0-9_-]{12,40}") || name.isEmpty() || name.length() > 20) {
                return MeshHttp.Response.json(400, MeshHttp.error("Nieprawidłowy węzeł"));
            }
            // Ignore identity/role flags sent by the client. Prevent unbounded
            // growth from accidental or malicious peers on the local WLAN.
            if (!peers.containsKey(id) && peers.size() >= 16) {
                return MeshHttp.Response.json(400, MeshHttp.error("Sieć jest pełna"));
            }
            peers.put(id, new Peer(sanitizeNode(incoming), ip));
            return MeshHttp.Response.json(200, publicState());
        }
        if ("POST".equals(req.method) && "/leave".equals(req.path)) {
            peers.remove(req.json().optString("id", ""));
            return MeshHttp.Response.json(200, publicState());
        }
        return MeshHttp.Response.json(404, MeshHttp.error("Nie znaleziono"));
    }

    private MeshHttp.Response handleRelay(MeshHttp.Request req, String ip) {
        boolean probe = "GET".equals(req.method) && "/probe".equals(req.path);
        boolean metadata = "POST".equals(req.method) && "/metadata".equals(req.path);
        boolean chunk = "POST".equals(req.method) && "/chunk".equals(req.path);
        if (!probe && !metadata && !chunk) return MeshHttp.Response.json(404, MeshHttp.error("Nie znaleziono"));
        if (!authorized(req.token()) || !ip.equals(hostIp)) return MeshHttp.Response.json(401, MeshHttp.error("Brak dostępu"));
        if (!connected || !"member".equals(role) || !relayEnabled) {
            return MeshHttp.Response.json(403, MeshHttp.error("Uczestnik nie zezwolił na użycie danych komórkowych."));
        }
        // One cellular fetch at a time per consenting member. This avoids
        // concurrent requests overrunning the user-selected data cap.
        if (!relaySlot.tryAcquire()) return MeshHttp.Response.json(429, MeshHttp.error("Telefon jest już zajęty pobieraniem fragmentu."));
        try {
            java.util.function.BooleanSupplier allowed = () -> connected && "member".equals(role) &&
                relayEnabled && relaySpentBytes.get() < relayBudgetBytes && !Thread.currentThread().isInterrupted();
            if (probe) {
                long now = SystemClock.elapsedRealtime();
                long previous = lastRelayAt.get();
                if ((previous != 0 && now - previous < 20000) || !lastRelayAt.compareAndSet(previous, now)) {
                    return MeshHttp.Response.json(429, MeshHttp.error("Poczekaj 20 sekund przed następnym testem."));
                }
                if (relayBudgetBytes - relaySpentBytes.get() < PROBE_BYTES) {
                    return MeshHttp.Response.json(429, MeshHttp.error("Limit danych na telefonie został wykorzystany."));
                }
                NetworkLease lease = acquireCellular();
                try {
                    HttpURLConnection conn = openCellular(lease.network,
                        "https://speed.cloudflare.com/__down?bytes=" + PROBE_BYTES, "GET");
                    try {
                        if (conn.getResponseCode() != 200) throw new IOException("Serwer testowy jest niedostępny.");
                        ByteArrayOutputStream data = new ByteArrayOutputStream(PROBE_BYTES);
                        try (InputStream in = conn.getInputStream()) {
                            byte[] buffer = new byte[8192];
                            int n;
                            while (data.size() < PROBE_BYTES && (n = in.read(buffer, 0,
                                Math.min(buffer.length, PROBE_BYTES - data.size()))) >= 0) {
                                if (!allowed.getAsBoolean()) throw new IOException("Zgoda na przekazywanie została wycofana.");
                                relaySpentBytes.addAndGet(n);
                                data.write(buffer, 0, n);
                            }
                        }
                        if (data.size() < 32768 || !relayEnabled) throw new IOException("Test został przerwany.");
                        relayBytes += data.size();
                        return MeshHttp.Response.bytes(data.toByteArray());
                    } finally { conn.disconnect(); }
                } finally { lease.close(connectivity); }
            }
            JSONObject input = req.json();
            String url = input.optString("url", "");
            RangePlan.validateUrl(url);
            if (metadata) {
                if (!allowed.getAsBoolean()) return MeshHttp.Response.json(429, MeshHttp.error("Limit danych został wykorzystany."));
                NetworkLease lease = acquireCellular();
                try {
                    CellularRange.Metadata info = CellularRange.probe(lease.network, url);
                    relaySpentBytes.incrementAndGet();
                    if (!relayEnabled) throw new IOException("Zgoda została wycofana.");
                    JSONObject response = new JSONObject();
                    response.put("total", info.total).put("etag", info.etag);
                    return MeshHttp.Response.json(200, response);
                } finally { lease.close(connectivity); }
            }
            long start = input.getLong("start"), total = input.getLong("total");
            int length = input.getInt("length");
            String etag = input.optString("etag", "");
            if (etag.length() > 150 || length < 1 || length > RangePlan.CHUNK_BYTES ||
                start < 0 || total < 1 || total > RangePlan.MAX_FILE_BYTES || start + length > total) {
                return MeshHttp.Response.json(400, MeshHttp.error("Nieprawidłowy zakres bajtów."));
            }
            if (relayBudgetBytes - relaySpentBytes.get() < length) {
                return MeshHttp.Response.json(429, MeshHttp.error("Limit danych na telefonie został wykorzystany."));
            }
            NetworkLease lease = acquireCellular();
            try {
                byte[] bytes = CellularRange.fetch(lease.network, url, start, length, total, etag, allowed,
                    () -> Math.max(0, relayBudgetBytes - relaySpentBytes.get()),
                    count -> relaySpentBytes.addAndGet(count));
                if (!relayEnabled) throw new IOException("Zgoda na udostępnianie została wycofana.");
                relayBytes += bytes.length;
                return MeshHttp.Response.bytes(bytes);
            } finally { lease.close(connectivity); }
        } catch (Exception e) {
            return MeshHttp.Response.json(503, MeshHttp.error("Węzeł nie mógł pobrać danych: " + safeError(e)));
        } finally { relaySlot.release(); }
    }

    private List<MeshDownloadManager.Remote> availableRemotes() {
        List<MeshDownloadManager.Remote> result = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Peer> entry : peers.entrySet()) {
            Peer peer = entry.getValue();
            JSONObject node = peer.node;
            if (now - peer.seen > PEER_TTL_MS || !node.optBoolean("relayEnabled") ||
                !node.optBoolean("hasCellular") || node.optLong("relayRemainingBytes") < 1024) continue;
            result.add(new MeshDownloadManager.Remote(entry.getKey(), node.optString("name", "Telefon"),
                peer.ip, node.optLong("relayRemainingBytes"), node.optDouble("download", 0),
                node.optInt("battery", 0), node.optBoolean("charging", false)));
        }
        return result;
    }

    /** State sent to peers: no Wi-Fi password, token or invitation. */
    private JSONObject publicState() {
        JSONObject state = new JSONObject();
        try {
            state.put("connected", true);
            state.put("meshName", meshName);
            JSONArray nodes = new JSONArray();
            nodes.put(localNode());
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<String, Peer> entry : peers.entrySet()) {
                Peer peer = entry.getValue();
                if (now - peer.seen > PEER_TTL_MS) { peers.remove(entry.getKey()); continue; }
                nodes.put(peer.node);
            }
            state.put("nodes", nodes);
        } catch (JSONException ignored) {}
        return state;
    }

    private JSObject snapshot() {
        JSObject out = new JSObject();
        out.put("connected", connected);
        out.put("role", role);
        out.put("meshName", meshName);
        out.put("selfId", nodeId);
        out.put("ssid", ssid);
        out.put("password", "host".equals(role) ? password : "");
        out.put("ip", hostIp);
        out.put("invite", "host".equals(role) && connected ? makeInvite() : "");
        out.put("error", lastError);
        out.put("internetSharing", false);
        out.put("relayEnabled", relayEnabled);
        out.put("relayBudgetBytes", relayBudgetBytes);
        out.put("relaySpentBytes", relaySpentBytes.get());
        out.put("downloadJob", "host".equals(role) && downloader != null ? downloader.status() : JSONObject.NULL);
        out.put("nodes", "host".equals(role) && connected
            ? publicState().optJSONArray("nodes")
            : remoteSnapshot.optJSONArray("nodes") != null ? remoteSnapshot.optJSONArray("nodes") : new JSONArray());
        return out;
    }

    private JSONObject localNode() {
        Battery b = battery();
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", nodeId);
            obj.put("name", displayName.isEmpty() ? "Ty" : displayName);
            obj.put("model", Build.MODEL == null ? "Android" : Build.MODEL);
            obj.put("operator", operator());
            obj.put("battery", Math.round(b.level * 100));
            obj.put("charging", b.charging);
            obj.put("signal", signal());
            obj.put("download", downloadMbps);
            obj.put("upload", uploadMbps);
            obj.put("ping", pingMs);
            obj.put("dataShared", relayBytes / (1024.0 * 1024.0));
            obj.put("relayEnabled", relayEnabled && connected);
            obj.put("relayRemainingBytes", relayEnabled ? Math.max(0, relayBudgetBytes - relaySpentBytes.get()) : 0);
            obj.put("measuredAt", measuredAt);
            obj.put("hasCellular", hasCellularHardware());
        } catch (JSONException ignored) {}
        return obj;
    }

    private static JSONObject sanitizeNode(JSONObject raw) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", raw.getString("id"));
        obj.put("name", trim(raw.getString("name"), 20));
        obj.put("model", trim(raw.optString("model", "Android"), 36));
        obj.put("operator", trim(raw.optString("operator", "—"), 24));
        obj.put("battery", clamp(raw.optDouble("battery", 0), 0, 100));
        obj.put("charging", raw.optBoolean("charging", false));
        obj.put("signal", clamp(raw.optDouble("signal", 0), 0, 100));
        obj.put("download", clamp(raw.optDouble("download", 0), 0, 10000));
        obj.put("upload", clamp(raw.optDouble("upload", 0), 0, 10000));
        obj.put("ping", clamp(raw.optInt("ping", 0), 0, 10000));
        obj.put("dataShared", clamp(raw.optDouble("dataShared", 0), 0, 1000000));
        obj.put("relayEnabled", raw.optBoolean("relayEnabled", false));
        obj.put("relayRemainingBytes", Math.max(0, Math.min(MAX_RELAY_BUDGET, raw.optLong("relayRemainingBytes", 0))));
        obj.put("measuredAt", Math.max(0, raw.optLong("measuredAt", 0)));
        obj.put("hasCellular", raw.optBoolean("hasCellular", false));
        return obj;
    }

    private String makeInvite() {
        try {
            JSONObject data = new JSONObject();
            data.put("v", 1);
            data.put("mesh", meshName);
            data.put("ssid", ssid);
            data.put("password", password);
            data.put("host", hostIp);
            data.put("token", token);
            return "MR1:" + Base64.encodeToString(data.toString().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (JSONException e) { return ""; }
    }

    private static JSONObject parseInvite(String text) throws JSONException {
        int start = text.indexOf("MR1:");
        if (start < 0 || text.length() > 4000) throw new JSONException("Missing invite");
        String encoded = text.substring(start + 4).split("[^A-Za-z0-9_-]", 2)[0];
        JSONObject data = new JSONObject(new String(Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        if (data.optInt("v") != 1 || data.optString("ssid").isEmpty() || data.optString("password").length() < 8 ||
            data.optString("token").length() < 16 || !data.optString("host").matches("[0-9.]{7,15}")) {
            throw new JSONException("Invalid invite");
        }
        return data;
    }

    private void clearJoinRequest() {
        ConnectivityManager.NetworkCallback cb = joinCallback;
        joinCallback = null;
        if (cb != null) try { connectivity.unregisterNetworkCallback(cb); } catch (Exception ignored) {}
        if (legacyNetworkId >= 0 && wifi != null) {
            try { wifi.removeNetwork(legacyNetworkId); } catch (Exception ignored) {}
            legacyNetworkId = -1;
        }
    }

    private void stopInternal() {
        if (downloader != null) downloader.cancelAndClear();
        relayEnabled = false;
        boolean wasMember = "member".equals(role);
        Network network = wifiNetwork;
        String ip = hostIp;
        String secret = token;
        if (heartbeatTask != null) { heartbeatTask.cancel(true); heartbeatTask = null; }
        connected = false;
        role = "";
        if (wasMember && network != null && ip != null && !ip.isEmpty()) {
            io.execute(() -> {
                try { MeshHttp.callJson(network, ip, CONTROL_PORT, "POST", "/leave", new JSONObject().put("id", nodeId), secret); }
                catch (Exception ignored) {}
            });
        }
        if (relayServer != null) { relayServer.stop(); relayServer = null; }
        if (controlServer != null) { controlServer.stop(); controlServer = null; }
        if (reservation != null) {
            WifiManager.LocalOnlyHotspotReservation r = reservation;
            reservation = null;
            r.close();
        }
        clearJoinRequest();
        wifiNetwork = null;
        remoteSnapshot = new JSONObject();
        peers.clear();
        meshName = "";
        ssid = "";
        password = "";
        hostIp = "";
        token = "";
        downloadMbps = 0;
        uploadMbps = 0;
        pingMs = 0;
        measuredAt = 0;
        relayBytes = 0;
        relayBudgetBytes = DEFAULT_RELAY_BUDGET;
        relaySpentBytes.set(0);
        lastRelayAt.set(0);
    }

    @Override
    protected void handleOnDestroy() {
        stopInternal();
        if (downloader != null) downloader.shutdown();
        io.shutdownNow();
        scheduler.shutdownNow();
        super.handleOnDestroy();
    }

    private boolean hasRuntimePermissions() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(getContext(), Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED);
    }

    private static String[] hotspotCredentials(WifiManager.LocalOnlyHotspotReservation r) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.net.wifi.SoftApConfiguration cfg = r.getSoftApConfiguration();
            return new String[] { cfg.getSsid(), cfg.getPassphrase() };
        }
        WifiConfiguration cfg = r.getWifiConfiguration();
        return new String[] { unquote(cfg.SSID), unquote(cfg.preSharedKey) };
    }

    private static String findHotspotIp() {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                String best = null;
                int bestScore = -1;
                while (interfaces.hasMoreElements()) {
                    NetworkInterface nif = interfaces.nextElement();
                    if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                    String name = nif.getName().toLowerCase();
                    if (!(name.contains("wlan") || name.contains("ap") || name.contains("swlan"))) continue;
                    Enumeration<InetAddress> addresses = nif.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress a = addresses.nextElement();
                        if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                        String address = a.getHostAddress();
                        int score = (name.startsWith("ap") || name.contains("softap") || name.contains("swlan") ? 100 : 0)
                            + (address.endsWith(".1") ? 50 : 0)
                            + (address.startsWith("192.168.") ? 20 : 0)
                            + (name.contains("wlan") ? 10 : 0);
                        if (score > bestScore) { best = address; bestScore = score; }
                    }
                }
                if (best != null) return best;
            } catch (Exception ignored) {}
            SystemClock.sleep(250);
        }
        // The member also discovers the DHCP gateway when it joins; this
        // placeholder is not trusted unless the host actually answers there.
        return "192.168.43.1";
    }

    private static String randomToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private boolean authorized(String provided) {
        return !token.isEmpty() && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII));
    }

    private static String trim(String str, int max) {
        if (str == null) return "";
        String clean = str.trim().replaceAll("[\\r\\n\\t]", " ");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String quote(String s) { return "\"" + s.replace("\"", "") + "\""; }
    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s == null ? "" : s;
    }

    private static double clamp(double n, double min, double max) { return Double.isFinite(n) ? Math.min(max, Math.max(min, n)) : 0; }
    private static double round(double n) { return Math.round(n * 10.0) / 10.0; }
    private static String safeError(Exception e) { return e == null ? "Spróbuj ponownie." : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }

    private static final class Battery {
        final double level;
        final boolean charging;
        Battery(double level, boolean charging) { this.level = level; this.charging = charging; }
    }

    private Battery battery() {
        Intent intent = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) return new Battery(0, false);
        int raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return new Battery(scale > 0 ? clamp((double) raw / scale, 0, 1) : 0,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private String operator() {
        try {
            TelephonyManager tm = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            String value = tm == null ? "" : tm.getNetworkOperatorName();
            return value == null || value.isEmpty() ? "Brak sieci komórkowej" : trim(value, 24);
        } catch (Exception e) { return "Brak sieci komórkowej"; }
    }

    private boolean hasCellularHardware() { return getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY); }

    private int signal() {
        // The host's Wi-Fi client RSSI, if present, would describe some other
        // network, not the signal of its own LocalOnlyHotspot.
        if ("host".equals(role)) return 0;
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            int rssi = info == null ? -127 : info.getRssi();
            return rssi <= -100 || rssi >= 0 ? 0 : Math.max(0, Math.min(100, (rssi + 100) * 2));
        } catch (Exception e) { return 0; }
    }

    private static final class NetworkLease {
        final Network network;
        final ConnectivityManager.NetworkCallback callback;
        NetworkLease(Network network, ConnectivityManager.NetworkCallback callback) { this.network = network; this.callback = callback; }
        void close(ConnectivityManager manager) { try { manager.unregisterNetworkCallback(callback); } catch (Exception ignored) {} }
    }

    private NetworkLease acquireCellular() throws Exception {
        NetworkRequest req = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Network> found = new AtomicReference<>();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { found.set(n); ready.countDown(); }
            @Override public void onUnavailable() { ready.countDown(); }
        };
        connectivity.requestNetwork(req, cb, 9000);
        try {
            if (!ready.await(10, TimeUnit.SECONDS) || found.get() == null) throw new IOException("Brak dostępnego połączenia komórkowego.");
            return new NetworkLease(found.get(), cb);
        } catch (Exception e) {
            try { connectivity.unregisterNetworkCallback(cb); } catch (Exception ignored) {}
            throw e;
        }
    }

    private static HttpURLConnection openCellular(Network network, String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) network.openConnection(new URL(url));
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        conn.setUseCaches(false);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "MeshRouter/1.0 (Android)");
        return conn;
    }

    private static double measureUpload(Network network) {
        HttpURLConnection conn = null;
        try {
            conn = openCellular(network, "https://speed.cloudflare.com/__up", "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            byte[] bytes = new byte[256 * 1024];
            new SecureRandom().nextBytes(bytes);
            conn.setFixedLengthStreamingMode(bytes.length);
            long start = SystemClock.elapsedRealtimeNanos();
            try (java.io.OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return 0;
            return round(bytes.length * 8.0 / ((SystemClock.elapsedRealtimeNanos() - start) / 1e9) / 1e6);
        } catch (Exception e) { return 0; }
        finally { if (conn != null) conn.disconnect(); }
    }
}
