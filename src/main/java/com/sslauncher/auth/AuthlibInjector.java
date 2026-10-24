package com.sslauncher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.Http;
import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Downloads and prepares authlib-injector, the Java agent that lets Minecraft
 * authenticate against a third-party Yggdrasil server such as a Blessing Skin site.
 */
public final class AuthlibInjector {
    private static final String LATEST_OFFICIAL =
            "https://authlib-injector.yushi.moe/artifact/latest.json";
    private static final String LATEST_CACHE = "authlib-injector-latest.json";
    private static final String LATEST_MARKER = "authlib-injector-latest.official";
    private static final String MIRROR_PREFIX = "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/";

    private static Path cachedJar;
    private static String cachedVersion = "";

    private AuthlibInjector() {
    }

    public record Injection(Path jar, String serverUrl, String prefetched, String version) {
    }

    /** Metadata JSON of the skin site, Base64 encoded as authlib-injector expects. */
    public static String prefetch(String serverUrl) throws IOException, InterruptedException {
        String metadata = YggdrasilAuth.metadata(serverUrl);
        return Base64.getEncoder().withoutPadding()
                .encodeToString(metadata.getBytes(StandardCharsets.UTF_8));
    }

    public static Path ensureJar(Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (cachedJar != null && Files.exists(cachedJar)) {
            return cachedJar;
        }
        Latest latest = latest(progress, cancelled);
        Path dest = AppPaths.CACHE.resolve("authlib-injector-" + latest.version + ".jar");
        // A jar cached by an older build was not necessarily verified. Compare
        // it with the digest from the official metadata before trusting it.
        if (Files.exists(dest) && matches(dest, latest.sha256)) {
            cachedJar = dest;
            cachedVersion = latest.version;
            return dest;
        }
        Files.deleteIfExists(dest);
        List<String> urls = new ArrayList<>();
        urls.add(latest.mirrorUrl);
        urls.add(latest.downloadUrl);
        urls.add("https://github.com/yushijinhun/authlib-injector/releases/download/v"
                + latest.version + "/authlib-injector-" + latest.version + ".jar");
        urls.removeIf(u -> u == null || u.isBlank());
        progress.setStage("下载 authlib-injector");
        progress.setIndeterminate(false);
        // Every candidate is checked against the official SHA-256; a mirror
        // that serves different bytes is skipped and the next URL is tried.
        Downloader.downloadAll(List.of(new Downloader.Item("authlib-injector " + latest.version,
                urls, dest, null, latest.sha256, 0)), progress, 1, cancelled);
        if (!matches(dest, latest.sha256)) {
            Files.deleteIfExists(dest);
            throw new IOException("authlib-injector 校验失败，已拒绝使用未通过官方校验的文件");
        }
        cachedJar = dest;
        cachedVersion = latest.version;
        Log.info("authlib-injector " + latest.version + " 已就绪: " + dest);
        return dest;
    }

    private static boolean matches(Path jar, String expectedSha256) {
        try {
            return Files.isRegularFile(jar) && Files.size(jar) > 10_000
                    && Http.sha256(jar).equalsIgnoreCase(expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }

    /** Ensures both the agent jar and the server metadata prefetch string. */
    public static Injection prepare(String serverUrl, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path jar = ensureJar(progress, cancelled);
        progress.setStage("读取第三方登录服务器配置");
        String prefetch = null;
        try {
            prefetch = prefetch(serverUrl);
        } catch (IOException e) {
            // Prefetching is an optimisation; authlib-injector can fetch the
            // metadata itself at game start if the skin site is slow.
            Log.warn("第三方登录服务器元数据预取失败，将在游戏内重试: " + e.getMessage());
        }
        return new Injection(jar, YggdrasilAuth.normalizeServer(serverUrl), prefetch, cachedVersion);
    }

    private static Latest latest(Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (progress.isCancelled() || cancelled.getAsBoolean()) {
            throw new InterruptedException("已取消");
        }
        // The digest must come from the project's official HTTPS metadata.
        // BMCLAPI's copy was previously trusted, which made the checksum
        // self-certifying for anyone who could influence that mirror.
        Path cache = AppPaths.CACHE.resolve(LATEST_CACHE);
        Path marker = AppPaths.CACHE.resolve(LATEST_MARKER);
        String json;
        try {
            json = Http.getDirect(LATEST_OFFICIAL, Map.of("Accept", "application/json"));
            try {
                AppPaths.atomicWrite(cache, json.getBytes(StandardCharsets.UTF_8));
                AppPaths.atomicWrite(marker, "official".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.warn("无法缓存 authlib-injector 官方元数据: " + e.getMessage());
            }
        } catch (IOException e) {
            // Only metadata previously fetched from the official host is
            // trusted; an unmarked cache could have come from a mirror.
            if (!Files.exists(cache) || !Files.exists(marker)) {
                throw e;
            }
            Log.warn("authlib-injector 官方元数据获取失败，使用上次的官方缓存: " + e.getMessage());
            json = Files.readString(cache, StandardCharsets.UTF_8);
        }
        JsonObject obj;
        try {
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("authlib-injector 官方版本信息无效: " + e.getMessage(), e);
        }
        Latest latest = new Latest();
        latest.version = obj.has("version") && !obj.get("version").isJsonNull()
                ? obj.get("version").getAsString() : "";
        if (!latest.version.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("authlib-injector 官方版本号无效，已拒绝使用");
        }
        latest.downloadUrl = obj.has("download_url") && !obj.get("download_url").isJsonNull()
                ? obj.get("download_url").getAsString() : "";
        if (!latest.downloadUrl.startsWith("https://")) {
            throw new IOException("authlib-injector 官方下载地址无效，已拒绝使用");
        }
        if (obj.has("checksums") && obj.get("checksums").isJsonObject()
                && obj.getAsJsonObject("checksums").has("sha256")
                && !obj.getAsJsonObject("checksums").get("sha256").isJsonNull()) {
            latest.sha256 = obj.getAsJsonObject("checksums").get("sha256").getAsString()
                    .trim().toLowerCase(Locale.ROOT);
        }
        if (!latest.sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("authlib-injector 官方元数据缺少有效的 SHA-256，"
                    + "已拒绝下载未校验的文件");
        }
        latest.mirrorUrl = mirrorOf(latest.downloadUrl);
        return latest;
    }

    private static String mirrorOf(String downloadUrl) {
        if (downloadUrl == null) {
            return "";
        }
        String marker = "authlib-injector.yushi.moe/";
        int idx = downloadUrl.indexOf(marker);
        if (idx < 0) {
            return "";
        }
        return MIRROR_PREFIX + downloadUrl.substring(idx + marker.length());
    }

    private static class Latest {
        String version = "1.2.8";
        String downloadUrl = "";
        String mirrorUrl = "";
        String sha256 = "";
    }
}
