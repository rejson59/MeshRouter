package com.rejson59.meshrouter;

import android.net.Network;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** HTTPS byte-range requests pinned to THIS phone's cellular Network. */
final class CellularRange {
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 16000;
    private CellularRange() {}

    static final class Metadata {
        final long total;
        final String etag;
        final String name;
        Metadata(long total, String etag, String name) {
            this.total = total; this.etag = etag; this.name = name;
        }
    }

    static Metadata probe(Network cellular, String url) throws IOException {
        try (RangeResponse res = openRange(cellular, url, 0, 0, "")) {
            long total = RangePlan.totalFromContentRange(res.connection.getHeaderField("Content-Range"), 0, 0);
            if (res.stream.read() == -1) throw new IOException("Źródło nie zwróciło bajtu testowego.");
            String rawEtag = res.connection.getHeaderField("ETag");
            String etag = rawEtag != null && !rawEtag.startsWith("W/") && rawEtag.length() <= 150 ? rawEtag : "";
            return new Metadata(total, etag, RangePlan.safeFileName(url));
        }
    }

    static byte[] fetch(Network cellular, String url, long offset, int length, long total,
                        String etag, BooleanSupplier keepGoing, LongSupplier remainingBytes,
                        java.util.function.IntConsumer onBytes)
                        throws IOException {
        if (length < 1 || length > RangePlan.CHUNK_BYTES || offset < 0 ||
            total < 1 || total > RangePlan.MAX_FILE_BYTES || offset + length > total) {
            throw new IOException("Fragment jest poza dozwolonym zakresem.");
        }
        if (!keepGoing.getAsBoolean()) throw new IOException("Przekazywanie danych wyłączone.");
        try (RangeResponse res = openRange(cellular, url, offset, offset + length - 1, etag)) {
            long actualTotal = RangePlan.totalFromContentRange(res.connection.getHeaderField("Content-Range"), offset, offset + length - 1);
            if (actualTotal != total) throw new IOException("Rozmiar pliku zmienił się w trakcie pobierania.");
            String responseEtag = res.connection.getHeaderField("ETag");
            if (!etag.isEmpty() && (responseEtag == null || !etag.equals(responseEtag))) {
                throw new IOException("Plik zmienił się (ETag). Przerwano składanie fragmentów.");
            }
            long declared = res.connection.getContentLengthLong();
            if (declared >= 0 && declared != length) throw new IOException("Serwer podał nieprawidłową długość fragmentu.");
            byte[] output = new byte[length];
            int received = 0;
            while (received < length) {
                if (!keepGoing.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Pobieranie przerwane.");
                // Count every downloaded payload byte (even if the fragment later
                // fails and has to be retried); never read past the user cap.
                long available = remainingBytes.getAsLong();
                if (available <= 0) throw new IOException("Limit danych został wykorzystany.");
                int count = res.stream.read(output, received, (int) Math.min(Math.min(8192, length - received), available));
                if (count < 0) throw new IOException("Niepełny fragment: połączenie z serwerem zostało przerwane.");
                received += count;
                onBytes.accept(count);
            }
            return output;
        }
    }

    private static RangeResponse openRange(Network cellular, String urlText, long start, long end,
                                           String etag) throws IOException {
        URI current = RangePlan.validateUrl(urlText);
        for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
            validatePublicHost(cellular, current.getHost());
            HttpURLConnection conn = (HttpURLConnection) cellular.openConnection(current.toURL());
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "MeshRouter/0.2 (Android; ranged download)");
            if (etag != null && !etag.isEmpty()) conn.setRequestProperty("If-Range", etag);
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("Przekierowanie bez adresu docelowego.");
                    URI next = current.resolve(location);
                    current = RangePlan.validateUrl(next.toString());
                    conn.disconnect();
                    continue;
                }
                if (code != 206) throw new IOException("Serwer musi obsługiwać HTTP Range (206); zwrócił " + code + ".");
                String encoding = conn.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("Skompresowana odpowiedź nie obsługuje pewnego składania bajtów.");
                }
                return new RangeResponse(conn, conn.getInputStream());
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }
        }
        throw new IOException("Zbyt wiele przekierowań URL.");
    }

    private static void validatePublicHost(Network network, String host) throws IOException {
        // A shared invitation is not permission to access devices on a local
        // network. Resolve through the *cellular* Network before connecting.
        InetAddress[] addresses = network.getAllByName(host);
        if (addresses.length == 0) throw new IOException("Nie znaleziono serwera pliku.");
        for (InetAddress addr : addresses) {
            if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
                addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                throw new IOException("Adres kieruje do sieci lokalnej — niedozwolone.");
            }
            byte[] raw = addr.getAddress();
            if (raw.length == 4) {
                int first = raw[0] & 0xff, second = raw[1] & 0xff;
                if (first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)) {
                    throw new IOException("Adres źródła nie jest publiczny.");
                }
            } else if (raw.length == 16 && ((raw[0] & 0xfe) == 0xfc)) {
                throw new IOException("Prywatny adres IPv6 jest niedozwolony.");
            }
        }
    }

    private static final class RangeResponse implements AutoCloseable {
        final HttpURLConnection connection;
        final InputStream stream;
        RangeResponse(HttpURLConnection connection, InputStream stream) { this.connection = connection; this.stream = stream; }
        @Override public void close() throws IOException { try { stream.close(); } finally { connection.disconnect(); } }
    }
}
