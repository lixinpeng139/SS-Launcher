package com.sslauncher.ui;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Http;
import com.sslauncher.core.Log;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Loads Modrinth / CurseForge project icons off the JavaFX thread.
 *
 * <p>Icons are decoded once, kept in a small in-memory LRU and mirrored to
 * {@code cache/icons/} so a search page does not re-download them every time
 * it is rendered or the launcher restarted. Failed URLs are retried after a
 * short cooldown; until an icon arrives, callers keep their letter placeholder.</p>
 */
public final class ProjectIcons {
    /** Longest edge used when decoding; tiles are 44 px, so this covers HiDPI. */
    private static final int DECODE_EDGE = 128;
    private static final int MEMORY_LIMIT = 240;
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, runnable -> {
        Thread thread = new Thread(runnable, "ss-icon");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<String, Image> MEMORY =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > MEMORY_LIMIT;
                }
            };
    /** Failed URLs are retried once this cooldown has passed. */
    private static final long FAILURE_COOLDOWN_MS = 120_000L;
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Image>> PENDING = new ConcurrentHashMap<>();

    private ProjectIcons() {
    }

    /** The icon if it is already in memory, otherwise {@code null}. */
    public static Image cached(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        synchronized (MEMORY) {
            return MEMORY.get(url.trim());
        }
    }

    /**
     * Loads an icon asynchronously. {@code onLoaded} runs on the JavaFX thread
     * and is only called when decoding succeeded; on failure it is never
     * called, so the caller keeps its placeholder.
     */
    public static void load(String url, Consumer<Image> onLoaded) {
        if (url == null || url.isBlank() || onLoaded == null) {
            return;
        }
        String key = url.trim();
        if (!isHttp(key)) {
            return;
        }
        Image cached = cached(key);
        if (cached != null) {
            Platform.runLater(() -> onLoaded.accept(cached));
            return;
        }
        Long failedAt = FAILED.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) {
            return;
        }
        CompletableFuture<Image> future = PENDING.computeIfAbsent(key,
                value -> CompletableFuture.supplyAsync(() -> fetch(value), POOL));
        future.whenComplete((image, error) -> {
            PENDING.remove(key, future);
            if (image == null) {
                FAILED.put(key, System.currentTimeMillis());
                return;
            }
            FAILED.remove(key);
            synchronized (MEMORY) {
                MEMORY.put(key, image);
            }
            Platform.runLater(() -> onLoaded.accept(image));
        });
    }

    private static boolean isHttp(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Image fetch(String url) {
        try {
            byte[] bytes = readCache(url);
            Image image = bytes == null ? null : decode(bytes);
            if (image != null) {
                return image;
            }
            bytes = download(url);
            if (bytes == null) {
                return null;
            }
            image = decode(bytes);
            if (image != null) {
                writeCache(url, bytes);
            }
            return image;
        } catch (RuntimeException e) {
            Log.warn("图标加载失败 " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static Image decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            // ImageIO is used instead of javafx.scene.image.Image because
            // Modrinth serves many icons as WebP, which JavaFX 21 cannot read.
            // TwelveMonkeys registers a WebP reader for ImageIO.
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return null;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            double scale = Math.min(1.0, (double) DECODE_EDGE / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            if (targetWidth != width || targetHeight != height) {
                BufferedImage scaled = new BufferedImage(targetWidth, targetHeight,
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = scaled.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
                graphics.dispose();
                source = scaled;
            }
            int[] pixels = source.getRGB(0, 0, targetWidth, targetHeight,
                    null, 0, targetWidth);
            WritableImage image = new WritableImage(targetWidth, targetHeight);
            image.getPixelWriter().setPixels(0, 0, targetWidth, targetHeight,
                    PixelFormat.getIntArgbInstance(), pixels, 0, targetWidth);
            return image;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] download(String url) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", Http.USER_AGENT)
                    .header("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return null;
        }
        // The icon CDNs reset or throttle individual connections fairly often
        // from some networks. A few attempts with a growing pause hide those
        // blips; permanent failures (404) are returned immediately.
        int attempts = 4;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                HttpResponse<byte[]> response = Http.CONTENT_CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    byte[] body = response.body();
                    return body == null || body.length == 0 || body.length > MAX_BYTES ? null : body;
                }
                if (status != 429 && status < 500) {
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException e) {
                if (attempt == attempts - 1) {
                    return null;
                }
                if (Boolean.getBoolean("ss.debugIcons")) {
                    Log.warn("图标下载重试 " + url + ": " + e);
                }
            }
            if (attempt < attempts - 1) {
                try {
                    Thread.sleep(250L * (attempt + 1) * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static Path cacheFile(String url) {
        Path dir = AppPaths.CACHE;
        if (dir == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            return dir.resolve("icons").resolve(HexFormat.of().formatHex(hash) + ".img");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static byte[] readCache(String url) {
        try {
            Path file = cacheFile(url);
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            long size = Files.size(file);
            if (size <= 0 || size > MAX_BYTES) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeCache(String url, byte[] bytes) {
        try {
            Path file = cacheFile(url);
            if (file == null) {
                return;
            }
            Files.createDirectories(file.getParent());
            AppPaths.atomicWrite(file, bytes);
        } catch (IOException | RuntimeException e) {
            // The icon is already in memory; a failed disk write is harmless.
        }
    }
}
