package com.rejson59.meshrouter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict bounds/protocol checks shared by the Android downloader and JVM tests. */
final class RangePlan {
    static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    static final int CHUNK_BYTES = 512 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern HASH = Pattern.compile("[a-fA-F0-9]{64}");

    private RangePlan() {}

    static final class Chunk {
        final long start;
        final int length;
        int attempts;
        Chunk(long start, int length) { this.start = start; this.length = length; }
        long end() { return start + length - 1L; }
    }

    static List<Chunk> split(long total, int chunkSize) {
        if (total <= 0 || total > MAX_FILE_BYTES || chunkSize < 1 || chunkSize > CHUNK_BYTES) {
            throw new IllegalArgumentException("Plik musi mieć od 1 B do 32 MB, a fragment najwyżej 512 KB.");
        }
        List<Chunk> result = new ArrayList<>();
        for (long offset = 0; offset < total; offset += chunkSize) {
            result.add(new Chunk(offset, (int) Math.min(chunkSize, total - offset)));
        }
        return result;
    }

    static long totalFromContentRange(String value, long expectedStart, long expectedEnd) throws IOException {
        Matcher match = CONTENT_RANGE.matcher(value == null ? "" : value.trim());
        if (!match.matches()) throw new IOException("Serwer nie obsługuje pobierania fragmentami (brak Content-Range).");
        try {
            long start = Long.parseLong(match.group(1));
            long end = Long.parseLong(match.group(2));
            long total = Long.parseLong(match.group(3));
            if (total < 1 || total > MAX_FILE_BYTES || start != expectedStart || end != expectedEnd ||
                start < 0 || end < start || end >= total) {
                throw new IOException("Serwer zwrócił niezgodny zakres bajtów.");
            }
            return total;
        } catch (NumberFormatException e) {
            throw new IOException("Nieprawidłowy Content-Range.", e);
        }
    }

    static URI validateUrl(String text) throws IOException {
        if (text == null || text.length() < 12 || text.length() > 2048) throw new IOException("Adres pliku jest za długi lub pusty.");
        try {
            URI url = new URI(text.trim());
            String host = url.getHost();
            if (!"https".equalsIgnoreCase(url.getScheme()) || host == null || host.length() > 253 ||
                !host.contains(".") || url.getRawUserInfo() != null || url.getRawFragment() != null ||
                (url.getPort() != -1 && url.getPort() != 443) ||
                host.matches("[0-9.]+") || host.contains(":")) {
                throw new IOException("Dozwolony jest wyłącznie publiczny adres HTTPS (port 443, bez hasła).");
            }
            return url;
        } catch (URISyntaxException e) {
            throw new IOException("Nieprawidłowy adres pliku.", e);
        }
    }

    static String validateSha256(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) return "";
        String hash = value.trim();
        if (!HASH.matcher(hash).matches()) throw new IOException("SHA-256 musi zawierać 64 znaki szesnastkowe.");
        return hash.toLowerCase(Locale.ROOT);
    }

    static String safeFileName(String url) {
        try {
            String path = validateUrl(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (name.isEmpty() || name.startsWith(".") || name.length() > 80) return "meshrouter-file.bin";
            return name;
        } catch (Exception e) { return "meshrouter-file.bin"; }
    }
}
