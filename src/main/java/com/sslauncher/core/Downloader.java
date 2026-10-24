package com.sslauncher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Parallel, mirrored, checksum-verified batch downloader. */
public final class Downloader {
    /**
     * BMCLAPI's Maven proxy rejects bursts with HTTP 403. Library downloads
     * finish quickly, so capping their concurrency costs little and avoids
     * hammering the mirror into rate limiting.
     */
    private static final Semaphore MAVEN_SLOTS = new Semaphore(6, true);

    /**
     * One file to download. {@code sha256} is preferred when present; otherwise
     * {@code sha1} is checked. A null/blank hash means the caller has no trusted
     * digest (only acceptable for content whose metadata was itself verified).
     * {@code verifyExisting} re-hashes an already present file, which installs
     * use to repair files that an older, unverified build may have accepted.
     */
    public record Item(String label, List<String> urls, Path dest, String sha1, String sha256,
                       long size, boolean verifyExisting) {
        public Item {
            sha1 = blankToNull(sha1);
            sha256 = blankToNull(sha256);
        }

        /** Source-compatible constructor: size-cached, no re-hash of existing files. */
        public Item(String label, List<String> urls, Path dest, String sha1, long size) {
            this(label, urls, dest, sha1, null, size, false);
        }

        public Item(String label, List<String> urls, Path dest, String sha1, String sha256, long size) {
            this(label, urls, dest, sha1, sha256, size, false);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    private Downloader() {
    }

    public static void downloadAll(List<Item> items, Progress progress, int requestedThreads,
                                   BooleanSupplier cancelled) throws IOException, InterruptedException {
        List<Item> missing = new ArrayList<>();
        java.util.Set<Path> destinations = new java.util.HashSet<>();
        long total = 0;
        for (Item item : items) {
            // Several asset names can map to the same object, and callers may
            // pass duplicates. Never let two threads write the same .part file.
            Path key = item.dest().toAbsolutePath().normalize();
            if (!destinations.add(key)) {
                continue;
            }
            if (isPresent(item)) {
                continue;
            }
            missing.add(item);
            total += item.size() > 0 ? item.size() : 1_048_576L;
        }
        progress.setTotal(total);
        progress.setDone(0);
        if (missing.isEmpty()) {
            progress.setDone(total);
            return;
        }

        int budget = Math.max(1, requestedThreads);
        int threads = Math.max(1, Math.min(budget, missing.size()));
        // With only a few files in flight, give large ones several connections
        // so a single throttled stream does not set the pace for everything.
        int segments = missing.size() <= budget
                ? Math.max(1, Math.min(8, budget / missing.size())) : 1;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ss-downloader");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger index = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        try {
            for (Item item : missing) {
                pool.submit(() -> {
                    if (cancelled.getAsBoolean() || progress.isCancelled()) {
                        return;
                    }
                    int number = index.incrementAndGet();
                    progress.setDetail("(" + number + "/" + missing.size() + ") " + item.label());
                    downloadOne(item, progress, failures, segments);
                });
            }
            pool.shutdown();
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || progress.isCancelled()) {
                    pool.shutdownNow();
                    throw new InterruptedException("已取消");
                }
            }
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }

        if (!failures.isEmpty()) {
            throw new IOException("有 " + failures.size() + " 个文件下载失败，例如: " + failures.get(0));
        }
        if (progress.isCancelled() || cancelled.getAsBoolean()) {
            throw new InterruptedException("已取消");
        }
    }

    private static boolean isPresent(Item item) {
        try {
            if (!Files.exists(item.dest())) {
                return false;
            }
            boolean sizeOk = item.size() > 0
                    ? Files.size(item.dest()) == item.size()
                    : Files.size(item.dest()) > 0;
            if (!sizeOk || !item.verifyExisting()) {
                return sizeOk;
            }
            if (item.sha256() != null) {
                return Http.sha256(item.dest()).equalsIgnoreCase(item.sha256());
            }
            if (item.sha1() != null) {
                return Http.sha1(item.dest()).equalsIgnoreCase(item.sha1());
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void downloadOne(Item item, Progress progress, List<String> failures,
                                    int segments) {
        IOException lastError = null;
        boolean segmented = segments > 1 && item.size() >= Http.SEGMENT_MIN_SIZE;
        for (int round = 0; round < 4; round++) {
            if (progress.isCancelled()) {
                return;
            }
            boolean throttled = false;
            for (String url : item.urls()) {
                if (progress.isCancelled()) {
                    return;
                }
                boolean maven = Mirror.isMirror(url) && url.contains("/maven/");
                try {
                    if (maven) {
                        MAVEN_SLOTS.acquire();
                    }
                    try {
                        if (segmented && Http.isContentCdn(url)) {
                            try {
                                Http.downloadSegmented(item.dest(), url, item.sha1(), item.sha256(),
                                        item.size(), segments, progress);
                                return;
                            } catch (Http.RangeUnsupportedException e) {
                                segmented = false;
                            } catch (InterruptedException e) {
                                throw e;
                            } catch (IOException e) {
                                lastError = e;
                                Log.warn("分段下载失败 " + url + " -> " + e.getMessage());
                            }
                        }
                        Http.downloadTo(item.dest(), url, item.sha1(), item.sha256(), progress);
                        return;
                    } finally {
                        if (maven) {
                            MAVEN_SLOTS.release();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    lastError = e instanceof IOException io ? io : new IOException(e);
                    if (e instanceof Http.HttpException he && (he.status == 403 || he.status == 429)) {
                        throttled = true;
                    }
                    // Http rolls back the bytes of the failed attempt itself.
                    Log.warn("下载失败 " + url + " -> " + e.getMessage());
                }
            }
            try {
                // Rate limits need a real pause; unrelated failures just retry.
                Thread.sleep((throttled ? 5_000L : 400L) * (round + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        failures.add(item.label() + " (" + (lastError == null ? "未知错误" : lastError.getMessage()) + ")");
    }
}
