package com.rejson59.meshrouter;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ChunkAssemblerTest {
    @Test public void joinsParallelWorkersExactly() throws Exception {
        byte[] source = source(2 * RangePlan.CHUNK_BYTES + 51);
        byte[] result = new byte[source.length];
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        List<ChunkAssembler.Worker> workers = Arrays.asList(
            new ChunkAssembler.Worker("a", -1, chunk -> { a.incrementAndGet(); return slice(source, chunk); }),
            new ChunkAssembler.Worker("b", -1, chunk -> { b.incrementAndGet(); return slice(source, chunk); })
        );
        ChunkAssembler.download(source.length, workers,
            (offset, bytes) -> System.arraycopy(bytes, 0, result, (int) offset, bytes.length),
            (worker, count) -> {}, () -> false, 10000);
        assertArrayEquals(source, result);
        assertTrue(a.get() >= 1);
        assertTrue(b.get() >= 1);
    }

    @Test public void retriesOnOtherPeerAfterFailure() throws Exception {
        byte[] source = source(3 * RangePlan.CHUNK_BYTES + 9);
        byte[] result = new byte[source.length];
        AtomicInteger failed = new AtomicInteger();
        List<ChunkAssembler.Worker> workers = Arrays.asList(
            new ChunkAssembler.Worker("bad", -1, chunk -> { failed.incrementAndGet(); throw new IOException("peer left"); }),
            new ChunkAssembler.Worker("good", -1, chunk -> slice(source, chunk))
        );
        ChunkAssembler.download(source.length, workers,
            (offset, bytes) -> System.arraycopy(bytes, 0, result, (int) offset, bytes.length),
            (worker, count) -> {}, () -> false, 10000);
        assertTrue(failed.get() >= 1);
        assertArrayEquals(source, result);
    }

    @Test public void respectsWorkerBudgetAndStillCompletes() throws Exception {
        byte[] source = source(2 * RangePlan.CHUNK_BYTES + 17);
        byte[] result = new byte[source.length];
        AtomicInteger limited = new AtomicInteger();
        List<ChunkAssembler.Worker> workers = Arrays.asList(
            new ChunkAssembler.Worker("limited", RangePlan.CHUNK_BYTES,
                chunk -> { limited.addAndGet(chunk.length); return slice(source, chunk); }),
            new ChunkAssembler.Worker("backup", -1, chunk -> slice(source, chunk))
        );
        ChunkAssembler.download(source.length, workers,
            (offset, bytes) -> System.arraycopy(bytes, 0, result, (int) offset, bytes.length),
            (worker, count) -> {}, () -> false, 10000);
        assertEquals(RangePlan.CHUNK_BYTES, limited.get());
        assertArrayEquals(source, result);
    }

    @Test public void reportsMissingWorkIfAllWorkersFail() {
        List<ChunkAssembler.Worker> workers = Arrays.asList(
            new ChunkAssembler.Worker("a", -1, chunk -> { throw new IOException("offline"); }),
            new ChunkAssembler.Worker("b", -1, chunk -> { throw new IOException("offline"); })
        );
        try {
            ChunkAssembler.download(3 * RangePlan.CHUNK_BYTES, workers,
                (offset, bytes) -> {}, (worker, count) -> {}, () -> false, 10000);
            fail("Reported success with missing chunks");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Nie udało"));
        }
    }

    @Test public void cancellationIsNotSuccess() {
        List<ChunkAssembler.Worker> workers = Arrays.asList(
            new ChunkAssembler.Worker("a", -1, chunk -> new byte[chunk.length])
        );
        try {
            ChunkAssembler.download(10, workers,
                (offset, bytes) -> {}, (worker, count) -> {}, () -> true, 10000);
            fail("Reported success after cancellation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("anulowane"));
        }
    }

    private static byte[] source(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) ((i * 37 + 13) % 251);
        return data;
    }

    private static byte[] slice(byte[] bytes, RangePlan.Chunk chunk) {
        return Arrays.copyOfRange(bytes, (int) chunk.start, (int) chunk.end() + 1);
    }
}
