package com.rejson59.meshrouter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Parallel, work-stealing byte-range assembly. Pure Java so scheduling and
 * failure recovery can be tested without an Android device or a mock speed
 * gauge. Every accepted chunk has an exact length and unique offset.
 */
final class ChunkAssembler {
    private ChunkAssembler() {}

    interface Fetcher { byte[] fetch(RangePlan.Chunk chunk) throws Exception; }
    interface Sink { void write(long offset, byte[] data) throws IOException; }
    interface Progress { void completed(String worker, int bytes); }

    static final class Worker {
        final String id;
        final Fetcher fetcher;
        long remainingBudget; // -1 = unmetered by the mesh (host explicitly initiated this transfer)
        Worker(String id, long remainingBudget, Fetcher fetcher) {
            this.id = id;
            this.remainingBudget = remainingBudget;
            this.fetcher = fetcher;
        }
    }

    static void download(long total, List<Worker> workers, Sink sink, Progress progress,
                         BooleanSupplier cancelled, long timeoutMs) throws IOException {
        if (workers == null || workers.isEmpty() || workers.size() > 5) throw new IOException("Brak dostępnych łączy komórkowych.");
        if (timeoutMs < 1000) throw new IllegalArgumentException("timeoutMs");
        List<RangePlan.Chunk> chunks = RangePlan.split(total, RangePlan.CHUNK_BYTES);
        BlockingQueue<RangePlan.Chunk> queue = new LinkedBlockingQueue<>(chunks);
        AtomicInteger outstanding = new AtomicInteger(chunks.size());
        AtomicReference<IOException> fatal = new AtomicReference<>();
        AtomicReference<Exception> lastFetchError = new AtomicReference<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ExecutorService pool = Executors.newFixedThreadPool(workers.size());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Worker worker : workers) {
                // Reserve one starting fragment per link, so no fast thread
                // drains the queue before slower peers begin contributing.
                RangePlan.Chunk first = queue.poll();
                if (first == null) break;
                futures.add(pool.submit(() -> {
                    RangePlan.Chunk chunk = first;
                    int consecutiveErrors = 0;
                    while (outstanding.get() > 0 && fatal.get() == null && !cancelled.getAsBoolean() &&
                           !Thread.currentThread().isInterrupted()) {
                        if (System.nanoTime() >= deadline) {
                            fatal.compareAndSet(null, new IOException("Upłynął limit czasu pobierania (10 min)."));
                            return;
                        }
                        if (chunk == null) {
                            try { chunk = queue.poll(350, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                            if (chunk == null) continue;
                        }
                        if (worker.remainingBudget >= 0 && worker.remainingBudget < chunk.length) {
                            queue.offer(chunk);
                            return; // quota ended; another link may finish
                        }
                        try {
                            byte[] data = worker.fetcher.fetch(chunk);
                            if (data == null || data.length != chunk.length) {
                                throw new IOException("Węzeł zwrócił niepełny fragment.");
                            }
                            if (cancelled.getAsBoolean()) { queue.offer(chunk); return; }
                            try { sink.write(chunk.start, data); }
                            catch (IOException e) { fatal.compareAndSet(null, e); return; }
                            if (worker.remainingBudget >= 0) worker.remainingBudget -= data.length;
                            outstanding.decrementAndGet();
                            progress.completed(worker.id, data.length);
                            chunk = null;
                            consecutiveErrors = 0;
                        } catch (Exception e) {
                            lastFetchError.set(e);
                            chunk.attempts++;
                            if (chunk.attempts > workers.size() * 2 + 1) {
                                fatal.compareAndSet(null, new IOException("Zbyt wiele błędów pobierania fragmentu.", e));
                                return;
                            }
                            queue.offer(chunk);
                            chunk = null;
                            if (++consecutiveErrors >= 2) return;
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                try { future.get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Pobieranie przerwane.", e); }
                catch (ExecutionException e) { throw new IOException("Błąd pobierania.", e.getCause()); }
            }
            if (cancelled.getAsBoolean()) throw new IOException("Pobieranie anulowane.");
            if (fatal.get() != null) throw fatal.get();
            if (outstanding.get() != 0) {
                Exception last = lastFetchError.get();
                throw new IOException("Nie udało się pobrać " + outstanding.get() + " fragmentów. " +
                    (last == null ? "Sprawdź limity danych i zasięg telefonów." : last.getMessage()), last);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
