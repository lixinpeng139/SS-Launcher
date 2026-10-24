package com.sslauncher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.Http;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.core.Mirror;
import com.sslauncher.core.Progress;
import com.sslauncher.core.ZipUtil;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.model.VersionManifest;
import com.sslauncher.model.VersionMeta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Downloads game files and runs the official loaders' installers. */
public final class InstallService {
    private InstallService() {
    }

    // ---------------------------------------------------------------- vanilla

    public static String installVanilla(String versionId, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        VersionManifest manifest = VersionService.manifest();
        VersionManifest.Entry entry = VersionManifest.Entry.byId(manifest).get(versionId);
        if (entry == null) {
            throw new IOException("版本列表中找不到 " + versionId);
        }
        Path json = VersionService.versionJsonPath(versionId);
        // The digest comes from the official manifest over HTTPS. Verify a
        // cached JSON too: an earlier build may have downloaded it from a
        // mirror without checking it. The version JSON selects the main class
        // and libraries, so it is executable metadata, not just a hint.
        if (entry.sha1 == null || entry.sha1.isBlank()) {
            throw new IOException("官方版本清单缺少 " + versionId + " 的校验值，已拒绝安装");
        }
        if (Files.exists(json) && !matchesSha1(json, entry.sha1)) {
            Log.warn("版本信息与本机缓存不符，重新下载: " + versionId);
            Files.deleteIfExists(json);
        }
        if (!Files.exists(json)) {
            progress.setStage("下载版本信息");
            Downloader.downloadAll(List.of(new Downloader.Item(versionId + ".json",
                    Mirror.officialFirst(entry.url), json, entry.sha1, 0)), progress, 1, cancelled);
        }
        if (!Files.exists(json)) {
            throw new IOException("版本信息下载失败");
        }
        if (!matchesSha1(json, entry.sha1)) {
            Files.deleteIfExists(json);
            throw new IOException("版本信息校验失败，已拒绝使用: " + versionId);
        }
        VersionService.ResolvedVersion version = VersionService.load(versionId);
        downloadClientJar(version, progress, cancelled);
        downloadLibraries(version, progress, cancelled);
        AssetService.ensureAssets(version.assetIndex, progress, cancelled);
        AssetService.ensureLoggingConfig(version, progress, cancelled);
        Log.info("原版 " + versionId + " 已就绪");
        return versionId;
    }

    private static boolean matchesSha1(Path file, String expected) {
        try {
            return Files.isRegularFile(file) && Http.sha1(file).equalsIgnoreCase(expected);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean clientJarMatches(Path jar, VersionMeta.Download client) {
        try {
            if (!Files.isRegularFile(jar) || Files.size(jar) == 0) {
                return false;
            }
            if (client.sha256 != null && !client.sha256.isBlank()) {
                return Http.sha256(jar).equalsIgnoreCase(client.sha256);
            }
            if (client.sha1 != null && !client.sha1.isBlank()) {
                return Http.sha1(jar).equalsIgnoreCase(client.sha1);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void downloadClientJar(VersionService.ResolvedVersion version, Progress progress,
                                          BooleanSupplier cancelled) throws IOException, InterruptedException {
        String jarId = version.jarVersionId();
        Path dest = VersionService.versionJarPath(jarId);
        if (version.downloads == null || version.downloads.client == null
                || version.downloads.client.url == null) {
            if (Files.exists(dest) && Files.size(dest) > 0) {
                return;
            }
            Log.warn("版本 " + jarId + " 没有客户端 jar 下载信息");
            return;
        }
        VersionMeta.Download client = version.downloads.client;
        // An older build could have accepted an unverified jar from a mirror.
        // Re-check it against the digest in the (now official) version JSON.
        if (clientJarMatches(dest, client)) {
            return;
        }
        Files.deleteIfExists(dest);
        progress.setStage("下载游戏本体");
        Downloader.downloadAll(List.of(new Downloader.Item(jarId + ".jar",
                        Mirror.candidates(client.url), dest, client.sha1, client.sha256, client.size)),
                progress, 1, cancelled);
    }

    private static void downloadLibraries(VersionService.ResolvedVersion version, Progress progress,
                                          BooleanSupplier cancelled) throws IOException, InterruptedException {
        List<Downloader.Item> items = LibraryService.downloadItems(version, true);
        if (items.isEmpty()) {
            return;
        }
        progress.setStage("下载依赖库");
        Downloader.downloadAll(items, progress,
                Math.max(4, Math.min(8, Config.get().downloadThreads)), cancelled);
    }

    // ---------------------------------------------------------------- loaders

    public static String installLoader(LoaderType type, String mcVersion, String loaderVersion,
                                       Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (type == null || type == LoaderType.VANILLA) {
            return installVanilla(mcVersion, progress, cancelled);
        }
        progress.setStage("准备原版 " + mcVersion);
        installVanilla(mcVersion, progress, cancelled);
        return switch (type) {
            case FABRIC, QUILT -> installProfileLoader(type, mcVersion, loaderVersion, progress, cancelled);
            case FORGE, NEOFORGE -> installForgeLike(type, mcVersion, loaderVersion, progress, cancelled);
            default -> mcVersion;
        };
    }

    public static Instance install(String mcVersion, LoaderType type, String loaderVersion,
                                   String displayName, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String versionId = installLoader(type, mcVersion, loaderVersion, progress, cancelled);
        progress.setStage("创建实例");
        Instance instance = InstanceStore.get().create(versionId, type, displayName);
        progress.setDetail(instance.name + " 已创建");
        return instance;
    }

    private static String installProfileLoader(LoaderType type, String mcVersion, String loaderVersion,
                                               Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        progress.setStage("获取 " + type.label + " 安装配置");
        List<String> urls = LoaderService.profileCandidates(type, mcVersion, loaderVersion);
        String json = null;
        IOException last = null;
        for (String url : urls) {
            if (progress.isCancelled() || cancelled.getAsBoolean()) {
                throw new InterruptedException("已取消");
            }
            try {
                // Official meta only: the profile names the classes to run.
                json = Http.getDirect(url, Map.of("Accept", "application/json"));
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (json == null) {
            throw last != null ? last : new IOException("无法获取 " + type.label + " 安装配置");
        }
        String versionId;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            versionId = obj.has("id") && !obj.get("id").isJsonNull()
                    ? obj.get("id").getAsString()
                    : type.id + "-loader-" + loaderVersion + "-" + mcVersion;
        } catch (Exception e) {
            throw new IOException("加载器配置无效: " + e.getMessage(), e);
        }
        Path dest = VersionService.versionJsonPath(versionId);
        Files.createDirectories(dest.getParent());
        AppPaths.atomicWrite(dest, json.getBytes(StandardCharsets.UTF_8));
        Log.info("已写入 " + type.label + " 版本配置: " + dest);

        VersionService.ResolvedVersion resolved = VersionService.load(versionId);
        downloadLibraries(resolved, progress, cancelled);
        AssetService.ensureAssets(resolved.assetIndex, progress, cancelled);
        AssetService.ensureLoggingConfig(resolved, progress, cancelled);
        progress.setStage(type.label + " 安装完成");
        return versionId;
    }

    private static String installForgeLike(LoaderType type, String mcVersion, String loaderVersion,
                                           Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String fileName = LoaderService.installerFileName(type, mcVersion, loaderVersion);
        Path installer = AppPaths.CACHE.resolve("installers").resolve(fileName);
        String installerSha1 = officialInstallerSha1(type, mcVersion, loaderVersion, progress, cancelled);
        if (!installerMatches(installer, installerSha1)) {
            Files.deleteIfExists(installer);
            progress.setStage("下载 " + type.label + " 安装器");
            List<String> urls = LoaderService.installerCandidates(type, mcVersion, loaderVersion);
            Downloader.downloadAll(List.of(new Downloader.Item(type.label + " 安装器",
                    urls, installer, installerSha1, 0)), progress, 1, cancelled);
        }
        if (!installerMatches(installer, installerSha1)) {
            Files.deleteIfExists(installer);
            throw new IOException(type.label + " 安装器校验失败，已拒绝运行。"
                    + "请检查网络或镜像设置后重试");
        }

        byte[] versionJsonBytes = ZipUtil.readEntry(installer, "version.json");
        if (versionJsonBytes == null) {
            throw new IOException("安装器中缺少 version.json，文件可能已损坏");
        }
        VersionMeta installerVersion = Json.fromJson(new String(versionJsonBytes, StandardCharsets.UTF_8),
                VersionMeta.class);
        if (installerVersion == null || installerVersion.id == null) {
            throw new IOException("安装器版本信息无效");
        }
        String versionId = installerVersion.id;

        progress.setStage("预下载 " + type.label + " 依赖");
        List<VersionMeta.Library> libraries = new ArrayList<>();
        if (installerVersion.libraries != null) {
            libraries.addAll(installerVersion.libraries);
        }
        byte[] profileBytes = ZipUtil.readEntry(installer, "install_profile.json");
        if (profileBytes != null) {
            try {
                VersionMeta profile = Json.fromJson(new String(profileBytes, StandardCharsets.UTF_8),
                        VersionMeta.class);
                if (profile != null && profile.libraries != null) {
                    libraries.addAll(profile.libraries);
                }
            } catch (Exception e) {
                Log.warn("install_profile.json 解析失败: " + e.getMessage());
            }
        }
        VersionService.ResolvedVersion fake = new VersionService.ResolvedVersion();
        fake.libraries = libraries;
        downloadLibraries(fake, progress, cancelled);

        ensureLauncherProfiles();
        int javaMajor;
        try {
            // The vanilla version JSON carries the authoritative javaVersion
            // (newer game versions already require Java 25).
            javaMajor = VersionService.load(mcVersion).requiredJavaMajor();
        } catch (IOException e) {
            javaMajor = Math.max(8, VersionService.inferJavaMajor(
                    installerVersion.inheritsFrom != null ? installerVersion.inheritsFrom : mcVersion));
        }
        JavaService.JavaRuntime java = JavaService.ensure(javaMajor, progress, cancelled);

        progress.setStage("运行 " + type.label + " 安装器");
        progress.setIndeterminate(true);
        // Check once more immediately before execution: the jar came from a
        // mirror, and only the official digest proves it is the real installer.
        if (!installerMatches(installer, installerSha1)) {
            Files.deleteIfExists(installer);
            throw new IOException(type.label + " 安装器在执行前校验失败，已拒绝运行");
        }
        java.util.Set<String> knownVersions = versionIds();
        long startedAt = System.currentTimeMillis();
        int exit = runInstaller(java.exe(), installer, progress, cancelled);
        if (exit != 0) {
            throw new IOException(type.label + " 安装器退出码 " + exit + "，请查看 logs/launcher.log");
        }
        Path versionJson = VersionService.versionJsonPath(versionId);
        if (!Files.exists(versionJson)) {
            // Some installer builds write a slightly different id; adopt the
            // newest JSON that was produced by this run and belongs to the
            // loader, never the vanilla version that already existed.
            versionId = findNewestVersionJson(type, mcVersion, startedAt, knownVersions);
            if (versionId == null) {
                throw new IOException(type.label + " 安装器没有生成版本配置，请查看 logs/launcher.log");
            }
        }
        progress.setIndeterminate(false);
        progress.setStage("校验 " + type.label + " 文件");
        VersionService.ResolvedVersion resolved = VersionService.load(versionId);
        downloadLibraries(resolved, progress, cancelled);
        AssetService.ensureAssets(resolved.assetIndex, progress, cancelled);
        AssetService.ensureLoggingConfig(resolved, progress, cancelled);
        // Forge-like loaders need a version-named copy of the inherited jar.
        VersionService.ensureVersionJar(resolved);
        downloadClientJar(resolved, progress, cancelled);
        Log.info(type.label + " 安装完成: " + versionId);
        return versionId;
    }

    private static boolean installerMatches(Path installer, String expectedSha1) {
        try {
            return Files.isRegularFile(installer) && Files.size(installer) >= 100_000
                    && Http.sha1(installer).equalsIgnoreCase(expectedSha1);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads the installer's SHA-1 from the loader's official Maven repository.
     * Mirrors can serve the (large) installer, but must not be able to choose
     * the digest that certifies it. Installation fails closed when no official
     * digest is available rather than running an unverified jar.
     */
    private static String officialInstallerSha1(LoaderType type, String mcVersion, String loaderVersion,
                                                Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        progress.setStage("获取 " + type.label + " 官方校验值");
        IOException last = null;
        for (String url : LoaderService.installerSha1Candidates(type, mcVersion, loaderVersion)) {
            if (cancelled.getAsBoolean() || progress.isCancelled()) {
                throw new InterruptedException("已取消");
            }
            try {
                String body = Http.getDirect(url, Map.of("Accept", "text/plain, */*"));
                return LoaderService.parseOfficialSha1(body, url);
            } catch (IOException e) {
                last = e;
                Log.warn("获取官方校验值失败 " + url + ": " + e.getMessage());
            }
        }
        throw new IOException("无法从官方源获取 " + type.label + " 安装器校验值，"
                + "已拒绝下载或运行未校验的安装器。请检查网络连接后重试"
                + (last == null ? "" : "（" + last.getMessage() + "）"), last);
    }

    private static int runInstaller(Path java, Path installer, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Files.createDirectories(AppPaths.ROOT);
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", installer.toString(),
                "--installClient", AppPaths.ROOT.toString());
        builder.directory(AppPaths.ROOT.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
        Process process = builder.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Log.info("[installer] " + line);
                    if (!line.isBlank()) {
                        progress.setDetail(line.length() > 140 ? line.substring(0, 140) + "…" : line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "ss-installer-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + 20 * 60_000L;
        while (process.isAlive()) {
            if (cancelled.getAsBoolean() || progress.isCancelled()) {
                process.destroyForcibly();
                throw new InterruptedException("已取消安装");
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly();
                throw new IOException("安装器运行超时");
            }
            process.waitFor(300, TimeUnit.MILLISECONDS);
        }
        reader.join(3000);
        return process.exitValue();
    }

    private static void ensureLauncherProfiles() throws IOException {
        Path file = AppPaths.ROOT.resolve("launcher_profiles.json");
        if (Files.exists(file)) {
            return;
        }
        String json = "{\"profiles\":{},\"settings\":{\"language\":\"zh_CN\"},\"version\":3}";
        AppPaths.atomicWrite(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private static java.util.Set<String> versionIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (!Files.isDirectory(AppPaths.VERSIONS)) {
            return ids;
        }
        try (var stream = Files.list(AppPaths.VERSIONS)) {
            for (Path dir : stream.toList()) {
                if (Files.exists(dir.resolve(dir.getFileName() + ".json"))) {
                    ids.add(dir.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
        }
        return ids;
    }

    /**
     * Finds a version JSON that this installer run created. Installers may
     * normalise the id (and older ones did not always use the id from
     * version.json), so fall back to the newest new JSON that inherits from the
     * requested game version or carries the loader's name.
     */
    private static String findNewestVersionJson(LoaderType type, String mcVersion, long startedAt,
                                                java.util.Set<String> known) throws IOException {
        if (!Files.isDirectory(AppPaths.VERSIONS)) {
            return null;
        }
        String typeId = type.id.toLowerCase(java.util.Locale.ROOT);
        Path newest = null;
        long newestTime = 0;
        try (var stream = Files.list(AppPaths.VERSIONS)) {
            for (Path dir : stream.toList()) {
                String id = dir.getFileName().toString();
                Path json = dir.resolve(id + ".json");
                if (!Files.exists(json) || known.contains(id) || id.equals(mcVersion)) {
                    continue;
                }
                long t = Files.getLastModifiedTime(json).toMillis();
                if (t < startedAt - 5_000L || t <= newestTime) {
                    continue;
                }
                try {
                    VersionMeta meta = Json.read(json, VersionMeta.class);
                    boolean ours = meta != null && (mcVersion.equals(meta.inheritsFrom)
                            || id.toLowerCase(java.util.Locale.ROOT).contains(typeId));
                    if (ours) {
                        newest = json;
                        newestTime = t;
                    }
                } catch (IOException e) {
                    Log.warn("忽略无法解析的版本配置 " + json + ": " + e.getMessage());
                }
            }
        }
        if (newest == null) {
            return null;
        }
        return newest.getFileName().toString().replace(".json", "");
    }

    /** Convenience used by the UI: imports an already installed version folder. */
    public static String importVersion(Path jsonFile) throws IOException {
        String fileName = jsonFile.getFileName().toString();
        if (!fileName.endsWith(".json")) {
            throw new IOException("请选择版本 JSON 文件");
        }
        String id = fileName.substring(0, fileName.length() - ".json".length());
        Path dest = VersionService.versionJsonPath(id);
        Files.createDirectories(dest.getParent());
        Files.copy(jsonFile, dest, StandardCopyOption.REPLACE_EXISTING);
        return id;
    }
}
