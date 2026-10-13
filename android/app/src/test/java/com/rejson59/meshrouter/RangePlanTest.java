package com.rejson59.meshrouter;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class RangePlanTest {
    @Test public void splitsWithoutGapsOrOverlaps() {
        List<RangePlan.Chunk> chunks = RangePlan.split(2L * RangePlan.CHUNK_BYTES + 13, RangePlan.CHUNK_BYTES);
        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).start);
        assertEquals(RangePlan.CHUNK_BYTES - 1, chunks.get(0).end());
        assertEquals(RangePlan.CHUNK_BYTES, chunks.get(1).start);
        assertEquals(2L * RangePlan.CHUNK_BYTES, chunks.get(2).start);
        assertEquals(13, chunks.get(2).length);
        assertEquals(2L * RangePlan.CHUNK_BYTES + 12, chunks.get(2).end());
        assertEquals(64, RangePlan.split(RangePlan.MAX_FILE_BYTES, RangePlan.CHUNK_BYTES).size());
    }

    @Test public void rejectsEmptyAndOversizeFile() {
        try { RangePlan.split(0, RangePlan.CHUNK_BYTES); fail(); } catch (IllegalArgumentException expected) {}
        try { RangePlan.split(RangePlan.MAX_FILE_BYTES + 1L, RangePlan.CHUNK_BYTES); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void validatesExactHttpContentRange() throws Exception {
        assertEquals(1024, RangePlan.totalFromContentRange("bytes 0-0/1024", 0, 0));
        assertEquals(1024, RangePlan.totalFromContentRange("bytes 100-199/1024", 100, 199));
        rejectRange("bytes 0-100/1024", 1, 100);
        rejectRange("bytes */1024", 0, 0);
        rejectRange("bytes 0-0/999999999", 0, 0);
        rejectRange("bytes 3-2/100", 3, 2);
    }

    private static void rejectRange(String header, long start, long end) {
        try { RangePlan.totalFromContentRange(header, start, end); fail("accepted " + header); }
        catch (IOException expected) {}
    }

    @Test public void requiresPublicHttpsAndSafeFilename() throws Exception {
        String valid = "https://github.com/rejson59/MeshRouter/releases/download/v0.1.0-preview/MeshRouter-debug.apk";
        assertEquals("github.com", RangePlan.validateUrl(valid).getHost());
        assertEquals("MeshRouter-debug.apk", RangePlan.safeFileName(valid));
        for (String bad : new String[] {
            "http://github.com/a.apk", "https://127.0.0.1/a.apk", "https://user:secret@github.com/a.apk",
            "https://github.com:8443/a.apk", "file:///tmp/a.apk", "https://localhost/a.apk"
        }) {
            try { RangePlan.validateUrl(bad); fail("accepted " + bad); }
            catch (IOException expected) {}
        }
        assertEquals("meshrouter-file.bin", RangePlan.safeFileName("https://github.com/"));
    }

    @Test public void optionalSha256MustBeHex() throws Exception {
        assertEquals("", RangePlan.validateSha256(" "));
        assertEquals("a".repeat(64), RangePlan.validateSha256("A".repeat(64)));
        try { RangePlan.validateSha256("123"); fail(); } catch (IOException expected) {}
    }
}
