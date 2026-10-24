package com.sslauncher.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Shared HTTP client with retry, mirror fallback and streaming downloads. */
public final class Http {
    // Keep the user agent short: several Chinese mirrors' WAFs reject
    // parenthesised/browser-style strings with HTTP 403.
    public static final String USER_AGENT = "SSLauncher/1.0.0";
    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    /**
     * Modrinth and ForgeCDN throttle each TCP connection hard from parts of
     * China, so their files and icons use HTTP/1.1 where every concurrent
     * request gets its own connection instead of sharing one HTTP/2 stream.
     */
    public static final HttpClient CONTENT_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    /** Files at least this large are split across several connections. */
    public static final long SEGMENT_MIN_SIZE = 8L * 1024 * 1024;
    /** Files that stop sending data are abandoned instead of blocking a batch. */
    static volatile long stallTimeoutMs = 45_000L;
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "ss-http-watchdog");
                t.setDaemon(true);
                return t;
            });

    private Http() {
    }

    public static final class HttpException extends IOException {
        public final int status;
        public final String body;

        public HttpException(int status, String body, String url) {
            super("HTTP " + status + " <- " + url + (body == null || body.isBlank() ? "" : " :: " + truncate(body, 240)));
            this.status = status;
            this.body = body;
        }

        private static String truncate(String s, int max) {
            String oneLine = s.replace('\n', ' ').replace('\r', ' ');
            return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
        }
    }

    public static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static HttpResponse<String> getResponse(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .GET();
        if (headers != null) {
            headers.forEach(b::header);
        }
        return send(b.build());
    }

    /** GET with automatic mirror fallback; throws on a non-2xx response. */
    public static String get(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        IOException last = null;
        for (String candidate : Mirror.candidates(url)) {
            try {
                HttpResponse<String> resp = getResponse(candidate, headers);
                if (resp.statusCode() / 100 == 2) {
                    return resp.body();
                }
                last = new HttpException(resp.statusCode(), resp.body(), candidate);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("没有可用的下载地址: " + url);
    }

    public static String get(String url) throws IOException, InterruptedException {
        return get(url, Map.of("Accept", "application/json, text/plain, */*"));
    }

    /**
     * GET that never rewrites the URL through a mirror. Use this for security
     * metadata (version manifests, checksums, loader profiles): the content may
     * still be fetched from mirrors, but the information that decides whether
     * that content is trusted must come from the official origin over HTTPS.
     */
    public static String getDirect(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpResponse<String> resp = getResponse(url, headers);
        if (resp.statusCode() / 100 != 2) {
            throw new HttpException(resp.statusCode(), resp.body(), url);
        }
        return resp.body();
    }

    public static String getDirect(String url) throws IOException, InterruptedException {
        return getDirect(url, Map.of("Accept", "application/json, text/plain, */*"));
    }

    public static String postJson(String url, String json) throws IOException, InterruptedException {
        return postJson(url, json, Map.of());
    }

    public static String postJson(String url, String json, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpResponse<String> resp = send(builder.build());
        if (resp.statusCode() / 100 != 2) {
            throw new HttpException(resp.statusCode(), resp.body(), url);
        }
        return resp.body();
    }

    public static HttpResponse<String> postFormRaw(String url, Map<String, String> form)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    public static String postForm(String url, Map<String, String> form)
            throws IOException, InterruptedException {
        HttpResponse<String> resp = postFormRaw(url, form);
        if (resp.statusCode() / 100 != 2) {
            throw new HttpException(resp.statusCode(), resp.body(), url);
        }
        return resp.body();
    }

    public static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** True for the content CDNs that throttle a single connection. */
    public static boolean isContentCdn(String url) {
        return url != null && (url.startsWith("https://cdn.modrinth.com/")
                || url.startsWith("https://mediafilez.forgecdn.net/")
                || url.startsWith("https://edge.forgecdn.net/")
                || url.startsWith("https://mod.mcimirror.top/"));
    }

    private static HttpClient clientFor(String url) {
        return isContentCdn(url) ? CONTENT_CLIENT : CLIENT;
    }

    /**
     * Streams {@code url} into {@code dest} (via a .part file) and verifies SHA-1.
     * Progress is reported in bytes. A connection that stops sending data is
     * abandoned after {@link #stallTimeoutMs} instead of holding a worker
     * until the request timeout.
     */
    public static void downloadTo(Path dest, String url, String sha1, Progress progress)
            throws IOException, InterruptedException {
        downloadTo(dest, url, sha1, null, progress, clientFor(url));
    }

    /**
     * Streams {@code url} into {@code dest} and verifies the stronger of the
     * supplied digests (SHA-256 when given, otherwise SHA-1).
     */
    public static void downloadTo(Path dest, String url, String sha1, String sha256, Progress progress)
            throws IOException, InterruptedException {
        downloadTo(dest, url, sha1, sha256, progress, clientFor(url));
    }

    static void downloadTo(Path dest, String url, String sha1, Progress progress,
                           HttpClient client)
            throws IOException, InterruptedException {
        downloadTo(dest, url, sha1, null, progress, client);
    }

    static void downloadTo(Path dest, String url, String sha1, String sha256, Progress progress,
                           HttpClient client)
            throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        long written = 0;
        boolean verified = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(30))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                try (InputStream ignored = resp.body()) {
                    throw new HttpException(resp.statusCode(), "", url);
                }
            }
            try (OutputStream out = Files.newOutputStream(part);
                 StallGuard in = new StallGuard(resp.body())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) {
                        continue;
                    }
                    out.write(buf, 0, n);
                    written += n;
                    if (progress != null) {
                        progress.addDone(n);
                        if (progress.isCancelled()) {
                            throw new InterruptedException("已取消");
                        }
                    }
                }
                if (in.stalled()) {
                    throw new IOException("连接长时间无数据，已放弃");
                }
            }
            finishFile(part, dest, sha1, sha256);
            verified = true;
        } finally {
            if (!verified) {
                Files.deleteIfExists(part);
                rollback(progress, written);
            }
        }
    }

    /**
     * Downloads a file in {@code segments} parallel range requests. Each
     * request has its own connection, so a stalled or throttled connection
     * only delays one chunk. Falls back to {@link #downloadTo} when the server
     * ignores range requests.
     */
    public static void downloadSegmented(Path dest, String url, String sha1, long size,
                                         int segments, Progress progress)
            throws IOException, InterruptedException {
        downloadSegmented(dest, url, sha1, null, size, segments, progress);
    }

    public static void downloadSegmented(Path dest, String url, String sha1, String sha256, long size,
                                         int segments, Progress progress)
            throws IOException, InterruptedException {
        if (size <= 0 || segments < 2) {
            downloadTo(dest, url, sha1, sha256, progress);
            return;
        }
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        int count = Math.max(2, Math.min(8, segments));
        long chunk = (size + count - 1) / count;
        List<Path> pieces = new ArrayList<>();
        List<IOException> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean unsupported = new AtomicBoolean();
        AtomicLong written = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(count, r -> {
            Thread t = new Thread(r, "ss-segment");
            t.setDaemon(true);
            return t;
        });
        boolean assembled = false;
        try {
            for (int i = 0; i < count; i++) {
                long start = i * chunk;
                long end = Math.min(size, start + chunk) - 1;
                if (start > end) {
                    break;
                }
                Path piece = dest.resolveSibling(dest.getFileName() + ".part" + i);
                pieces.add(piece);
                pool.submit(() -> {
                    try {
                        downloadRange(piece, url, start, end, progress, written);
                    } catch (RangeUnsupportedException e) {
                        unsupported.set(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        errors.add(e);
                    }
                });
            }
            pool.shutdown();
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (progress != null && progress.isCancelled()) {
                    pool.shutdownNow();
                    throw new InterruptedException("已取消");
                }
            }
            if (unsupported.get() || !errors.isEmpty()) {
                if (unsupported.get()) {
                    throw new RangeUnsupportedException(url);
                }
                throw errors.get(0);
            }
            try (OutputStream out = Files.newOutputStream(part)) {
                for (Path piece : pieces) {
                    Files.copy(piece, out);
                }
            }
            if (Files.size(part) != size) {
                throw new IOException("分段下载长度不符: 期望 " + size + "，实际 " + Files.size(part));
            }
            finishFile(part, dest, sha1, sha256);
            assembled = true;
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
            for (Path piece : pieces) {
                Files.deleteIfExists(piece);
            }
            if (!assembled) {
                Files.deleteIfExists(part);
                rollback(progress, written.get());
            }
        }
    }

    private static void downloadRange(Path piece, String url, long start, long end,
                                      Progress progress, AtomicLong written)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=" + start + "-" + end)
                .GET()
                .build();
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (progress != null && progress.isCancelled()) {
                throw new InterruptedException("已取消");
            }
            long local = 0;
            try {
                HttpResponse<InputStream> resp = CONTENT_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() == 200) {
                    try (InputStream ignored = resp.body()) {
                        // The server sent the whole file instead of the range.
                    }
                    throw new RangeUnsupportedException(url);
                }
                if (resp.statusCode() != 206) {
                    try (InputStream ignored = resp.body()) {
                        throw new HttpException(resp.statusCode(), "", url);
                    }
                }
                try (OutputStream out = Files.newOutputStream(piece);
                     StallGuard in = new StallGuard(resp.body())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n == 0) {
                            continue;
                        }
                        out.write(buf, 0, n);
                        local += n;
                        written.addAndGet(n);
                        if (progress != null) {
                            progress.addDone(n);
                            if (progress.isCancelled()) {
                                throw new InterruptedException("已取消");
                            }
                        }
                    }
                    if (in.stalled()) {
                        throw new IOException("连接长时间无数据，已放弃");
                    }
                }
                if (Files.size(piece) != end - start + 1) {
                    throw new IOException("分段大小不符: " + Files.size(piece)
                            + " / " + (end - start + 1));
                }
                return;
            } catch (RangeUnsupportedException | InterruptedException e) {
                rollback(progress, local);
                written.addAndGet(-local);
                throw e;
            } catch (IOException e) {
                rollback(progress, local);
                written.addAndGet(-local);
                lastError = e;
                Log.warn("分段下载失败 " + url + " [" + start + "-" + end + "]: " + e.getMessage());
                Thread.sleep(400L * (attempt + 1));
            }
        }
        throw lastError == null ? new IOException("分段下载失败: " + url) : lastError;
    }

    private static void rollback(Progress progress, long bytes) {
        if (progress != null && bytes > 0) {
            progress.addDone(-bytes);
        }
    }

    /** Verifies the part file and moves it into place. */
    private static void finishFile(Path part, Path dest, String sha1, String sha256) throws IOException {
        if (sha256 != null && !sha256.isBlank()) {
            String actual = sha256(part);
            if (!actual.equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(part);
                throw new IOException("SHA-256 校验失败: " + dest.getFileName()
                        + " (期望 " + sha256 + ", 实际 " + actual + ")");
            }
        } else if (sha1 != null && !sha1.isBlank()) {
            String actual = sha1(part);
            if (!actual.equalsIgnoreCase(sha1)) {
                Files.deleteIfExists(part);
                throw new IOException("SHA1 校验失败: " + dest.getFileName()
                        + " (期望 " + sha1 + ", 实际 " + actual + ")");
            }
        }
        try {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    /** Signals that the server ignored a range request. */
    static final class RangeUnsupportedException extends IOException {
        RangeUnsupportedException(String url) {
            super("服务器不支持分段下载: " + url);
        }
    }

    /**
     * Closes the body stream when no bytes arrive for {@link #stallTimeoutMs};
     * a blocked read then fails so the caller can retry instead of hanging.
     */
    private static final class StallGuard implements AutoCloseable {
        private final InputStream in;
        private final ScheduledFuture<?> task;
        private volatile long lastRead = System.nanoTime();
        private volatile boolean stalled;

        StallGuard(InputStream in) {
            this.in = in;
            this.task = WATCHDOG.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - lastRead > stallTimeoutMs * 1_000_000L) {
                    stalled = true;
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // The reader will observe the closed stream.
                    }
                }
            }, 1, 2, TimeUnit.SECONDS);
        }

        int read(byte[] buf) throws IOException {
            int n = in.read(buf);
            if (n > 0) {
                lastRead = System.nanoTime();
            }
            return n;
        }

        boolean stalled() {
            return stalled;
        }

        @Override
        public void close() throws IOException {
            task.cancel(false);
            in.close();
        }
    }

    public static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1");
    }

    public static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    private static String digest(Path file, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static List<String> notEmpty(String... values) {
        return java.util.Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList();
    }
}
