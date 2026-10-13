package com.rejson59.meshrouter;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Real application-level multi-uplink transfer. A host splits a public HTTPS
 * file into exact byte ranges and asks consenting Android peers to fetch some
 * ranges over their own cellular Networks. It reassembles, hashes and saves
 * the file. No VPN, no system-wide connection bonding, no blind speed claims.
 */
final class MeshDownloadManager {
    static final int RELAY_PORT = 8788;
    private static final long MAX_TIME_MS = 10 * 60 * 1000L;

    interface Lease extends AutoCloseable { Network network(); @Override void close(); }
    interface CellularProvider { Lease acquire() throws Exception; }
    interface HostBudget {
        long remaining(); // zero unless host has explicitly opted in
        void spend(int bytes); // charge every payload byte, including failed attempts
    }
    static final class Remote {
        final String id, name, ip;
        final long remaining;
        final double measuredMbps;
        final int battery;
        final boolean charging;
        Remote(String id, String name, String ip, long remaining, double measuredMbps, int battery, boolean charging) {
            this.id = id; this.name = name; this.ip = ip; this.remaining = remaining;
            this.measuredMbps = measuredMbps; this.battery = battery; this.charging = charging;
        }
    }

    private static final class Job {
        final String id, url, expectedHash;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicLong completed = new AtomicLong(0);
        final Map<String, AtomicLong> contributors = new ConcurrentHashMap<>();
        volatile String status = "probing", error = "", sha256 = "", fileName = "", savedTo = "";
        volatile long total = 0, durationMs = 0;
        volatile double mbps = 0;
        volatile int links = 0;
        volatile Uri uri;
        volatile File legacyFile;
        Job(String url, String expectedHash) {
            this.id = UUID.randomUUID().toString(); this.url = url; this.expectedHash = expectedHash;
        }
        boolean running() { return "probing".equals(status) || "downloading".equals(status) || "saving".equals(status); }
    }

    private final Context context;
    private final CellularProvider cellular;
    private final Supplier<List<Remote>> peers;
    private final Supplier<String> token;
    private final BooleanSupplier active;
    private final HostBudget hostBudget;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private volatile Job current;

    MeshDownloadManager(Context context, CellularProvider cellular, Supplier<List<Remote>> peers,
                        Supplier<String> token, BooleanSupplier active, HostBudget hostBudget) {
        this.context = context.getApplicationContext();
        this.cellular = cellular;
        this.peers = peers;
        this.token = token;
        this.active = active;
        this.hostBudget = hostBudget;
    }

    synchronized JSONObject start(String url, String sha256) throws IOException {
        if (!active.getAsBoolean()) throw new IOException("Pobieranie może uruchomić tylko host aktywnej siatki.");
        RangePlan.validateUrl(url);
        String hash = RangePlan.validateSha256(sha256);
        if (current != null && current.running()) throw new IOException("Jedno pobieranie już trwa. Możesz je anulować.");
        Job job = new Job(url.trim(), hash);
        current = job;
        coordinator.execute(() -> run(job));
        return status();
    }

    void cancel() { Job job = current; if (job != null && job.running()) job.cancelled.set(true); }
    void shutdown() { cancel(); coordinator.shutdownNow(); }

    JSONObject status() {
        Job job = current;
        if (job == null) return null;
        JSONObject out = new JSONObject();
        try {
            out.put("id", job.id);
            out.put("status", job.status);
            out.put("error", job.error);
            out.put("total", job.total);
            out.put("bytes", job.completed.get());
            out.put("links", job.links);
            out.put("fileName", job.fileName);
            out.put("sha256", job.sha256);
            out.put("savedTo", job.savedTo);
            out.put("mbps", job.mbps);
            out.put("durationMs", job.durationMs);
            JSONArray contributors = new JSONArray();
            for (Map.Entry<String, AtomicLong> entry : job.contributors.entrySet()) {
                contributors.put(new JSONObject().put("name", entry.getKey()).put("bytes", entry.getValue().get()));
            }
            out.put("contributors", contributors);
        } catch (JSONException ignored) {}
        return out;
    }

    synchronized void cancelAndClear() {
        cancel();
        current = null;
    }

    void share() throws IOException {
        Job job = current;
        if (job == null || !"complete".equals(job.status)) throw new IOException("Nie ma ukończonego pliku do udostępnienia.");
        Uri uri = job.uri;
        if (uri == null && job.legacyFile != null && job.legacyFile.isFile()) {
            uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", job.legacyFile);
        }
        if (uri == null) throw new IOException("Plik nie jest już dostępny.");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime(job.fileName));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(android.content.ClipData.newRawUri("MeshRouter", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Udostępnij pobrany plik");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    private void run(Job job) {
        File temp = null;
        Lease host = null;
        try {
            if (job.cancelled.get() || !active.getAsBoolean()) throw new IOException("Pobieranie anulowane.");
            // Each remote node advertises an explicit consent and remaining quota.
            List<Remote> remotes = new ArrayList<>(peers.get());
            remotes.removeIf(r -> r.remaining < Math.min(RangePlan.CHUNK_BYTES, 1024) || r.ip == null || r.ip.isEmpty() ||
                (!r.charging && r.battery < 15));
            remotes.sort(Comparator.comparingDouble((Remote r) -> r.measuredMbps).reversed());
            if (remotes.size() > 4) remotes = new ArrayList<>(remotes.subList(0, 4));
            String sessionToken = token.get();
            CellularRange.Metadata metadata = null;
            Exception last = null;
            // Starting a download is NOT permission to consume host cellular
            // data: the host must opt in and is capped just like the peers.
            if (hostBudget.remaining() > 0) {
                try {
                    host = cellular.acquire();
                    metadata = CellularRange.probe(host.network(), job.url);
                    hostBudget.spend(1); // metadata GET reads exactly one byte
                } catch (Exception e) {
                    last = e;
                    if (host != null) { host.close(); host = null; }
                }
            }
            if (metadata == null) {
                for (Remote peer : remotes) {
                    if (job.cancelled.get()) throw new IOException("Pobieranie anulowane.");
                    try {
                        JSONObject response = MeshHttp.callJson(null, peer.ip, RELAY_PORT, "POST", "/metadata",
                            new JSONObject().put("url", job.url), sessionToken);
                        long total = response.getLong("total");
                        if (total < 1 || total > RangePlan.MAX_FILE_BYTES) throw new IOException("Plik przekracza 32 MB.");
                        metadata = new CellularRange.Metadata(total, response.optString("etag", ""), RangePlan.safeFileName(job.url));
                        break;
                    } catch (Exception e) { last = e; }
                }
            }
            if (metadata == null) throw new IOException("Żaden telefon nie może odczytać pliku: " + error(last));
            job.total = metadata.total;
            job.fileName = metadata.name;
            // All contributors have a per-session content-byte budget; the
            // limits include retries and earlier jobs in this mesh session.
            long totalQuota = host == null ? 0 : hostBudget.remaining();
            for (Remote peer : remotes) totalQuota += Math.max(0, peer.remaining);
            if (totalQuota < metadata.total) throw new IOException("Uczestnicy nie zgodzili się na wystarczający limit danych dla tego pliku.");
            File directory = new File(context.getCacheDir(), "meshrouter-downloads");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Nie można utworzyć katalogu tymczasowego.");
            if (directory.getUsableSpace() < metadata.total + 5L * 1024 * 1024) throw new IOException("Brak wolnego miejsca na plik.");
            temp = new File(directory, job.id + ".partial");
            File target = temp;
            List<ChunkAssembler.Worker> workers = new ArrayList<>();
            String etag = metadata.etag;
            long size = metadata.total;
            BooleanSupplier keepGoing = () -> !job.cancelled.get() && active.getAsBoolean();
            if (host != null && hostBudget.remaining() > 0) {
                Network hostNetwork = host.network();
                workers.add(new ChunkAssembler.Worker("Host", hostBudget.remaining(), chunk ->
                    CellularRange.fetch(hostNetwork, job.url, chunk.start, chunk.length, size, etag,
                        () -> keepGoing.getAsBoolean() && hostBudget.remaining() > 0,
                        hostBudget::remaining, hostBudget::spend)));
            }
            for (Remote peer : remotes) {
                JSONObject base = new JSONObject().put("url", job.url).put("total", size).put("etag", etag);
                workers.add(new ChunkAssembler.Worker(peer.name, peer.remaining, chunk -> {
                    if (!keepGoing.getAsBoolean()) throw new IOException("Pobieranie anulowane.");
                    JSONObject request = new JSONObject(base.toString()).put("start", chunk.start).put("length", chunk.length);
                    return MeshHttp.call(null, peer.ip, RELAY_PORT, "POST", "/chunk", request, sessionToken, RangePlan.CHUNK_BYTES);
                }));
            }
            if (workers.isEmpty()) throw new IOException("Brak łącza do pobrania pliku.");
            job.links = workers.size();
            job.status = "downloading";
            long start = SystemClock.elapsedRealtime();
            try (RandomAccessFile file = new RandomAccessFile(target, "rw")) {
                file.setLength(size);
                Object writeLock = new Object();
                ChunkAssembler.download(size, workers,
                    (offset, bytes) -> { synchronized (writeLock) { file.seek(offset); file.write(bytes); } },
                    (name, bytes) -> {
                        job.completed.addAndGet(bytes);
                        job.contributors.computeIfAbsent(name, ignored -> new AtomicLong()).addAndGet(bytes);
                    }, keepGoing, MAX_TIME_MS);
                file.getFD().sync();
            }
            job.durationMs = Math.max(1, SystemClock.elapsedRealtime() - start);
            job.mbps = Math.round((size * 8.0 / job.durationMs / 1000.0) * 10.0) / 10.0;
            if (!keepGoing.getAsBoolean()) throw new IOException("Pobieranie anulowane.");
            job.status = "saving";
            job.sha256 = checksum(target);
            if (!job.expectedHash.isEmpty() && !job.expectedHash.equals(job.sha256)) {
                throw new IOException("Suma SHA-256 nie pasuje. Plik nie został zapisany.");
            }
            save(target, job);
            if (!keepGoing.getAsBoolean()) {
                if (job.uri != null) context.getContentResolver().delete(job.uri, null, null);
                if (job.legacyFile != null) job.legacyFile.delete();
                job.uri = null;
                job.legacyFile = null;
                throw new IOException("Pobieranie anulowane.");
            }
            job.status = "complete";
        } catch (Exception e) {
            job.error = error(e);
            job.status = job.cancelled.get() ? "cancelled" : "failed";
        } finally {
            if (temp != null && temp.exists()) temp.delete();
            if (host != null) host.close();
        }
    }

    private void save(File source, Job job) throws IOException {
        String filename = "MeshRouter-" + System.currentTimeMillis() + "-" + job.fileName;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mime(filename));
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MeshRouter/");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Android odmówił zapisania pliku w Pobranych.");
            try {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IOException("Nie można otworzyć pliku docelowego.");
                    copy(input, output, job.cancelled);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Downloads.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, ready, null, null) != 1) throw new IOException("Nie można zatwierdzić pobranego pliku.");
                job.uri = uri;
                job.savedTo = "Pobrane/MeshRouter/" + filename;
            } catch (Exception e) {
                context.getContentResolver().delete(uri, null, null);
                throw new IOException("Nie udało się zapisać pliku: " + error(e), e);
            }
        } else {
            File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) throw new IOException("Pamięć na pliki jest niedostępna.");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Nie można zapisać pliku.");
            File result = new File(directory, filename);
            try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(result)) {
                copy(input, output, job.cancelled);
            } catch (Exception e) {
                result.delete();
                throw new IOException("Nie udało się zapisać pliku: " + error(e), e);
            }
            job.legacyFile = result;
            job.savedTo = "Pliki aplikacji / Pobrane / " + filename;
        }
    }

    private static void copy(InputStream input, OutputStream output, AtomicBoolean cancelled) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) >= 0) {
            if (cancelled.get()) throw new IOException("Zapisywanie anulowane.");
            output.write(buffer, 0, n);
        }
        output.flush();
    }

    private static String checksum(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private static String mime(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private static String error(Exception e) {
        if (e == null) return "Sprawdź połączenie i zgodę uczestników.";
        String text = e.getMessage();
        if (text == null || text.isEmpty()) text = e.getClass().getSimpleName();
        return text.length() > 220 ? text.substring(0, 220) : text;
    }
}
