package com.sslauncher.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Config;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.core.Mirror;
import com.sslauncher.core.Progress;
import com.sslauncher.core.ZipUtil;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.service.GameFiles;
import com.sslauncher.service.InstallService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs downloaded content into an instance and expands Modrinth and
 * CurseForge modpacks into a new, isolated instance.
 */
public final class ContentInstallService {
    private ContentInstallService() {
    }

    public record ModpackInfo(String mcVersion, LoaderType loader, String loaderVersion,
                              String name, String version, int fileCount) {
    }

    /** {@code root} is the manifest's folder inside the archive, ending in {@code /}. */
    public record DetectedModpack(ResourceSource source, ModpackInfo info, String root) {
    }

    private record ManifestLocation(ResourceSource source, String entry, String root, int depth) {
    }

    public record InstalledModpack(Instance instance, ModpackInfo info) {
    }

    private record ResolvedFile(String label, String url, String sha1, long size, String fileName) {
    }

    // ------------------------------------------------------------- single file

    public static Path installResource(Instance target, ResourceKind kind, Path file,
                                       Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (target == null) {
            throw new IOException("请选择要安装到的实例");
        }
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("下载文件不存在：" + file);
        }
        GameFiles.Category category = switch (kind) {
            case MOD -> GameFiles.Category.MODS;
            case RESOURCE_PACK -> GameFiles.Category.RESOURCE_PACKS;
            case SHADER -> GameFiles.Category.SHADER_PACKS;
            case MODPACK -> throw new IOException("整合包不能安装到现有实例");
        };
        BooleanSupplier stop = stop(progress, cancelled);
        if (stop.getAsBoolean()) {
            throw new InterruptedException("已取消");
        }
        progress.setStage("安装到 " + target.name);
        progress.setIndeterminate(true);
        List<Path> added = GameFiles.add(GameFiles.directory(target, category), category,
                List.of(file));
        if (stop.getAsBoolean()) {
            throw new InterruptedException("已取消");
        }
        progress.setIndeterminate(false);
        progress.setStage("安装完成");
        progress.setDetail(file.getFileName() + " → " + target.name);
        return added.isEmpty() ? null : added.get(0);
    }

    // ---------------------------------------------------------------- modpacks

    /** Reads the pack manifest without touching an instance. */
    public static ModpackInfo inspectModpack(Path archive, ResourceSource source)
            throws IOException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new IOException("整合包文件不存在");
        }
        ResourceSource wanted = source == ResourceSource.CURSEFORGE
                ? ResourceSource.CURSEFORGE : ResourceSource.MODRINTH;
        List<ManifestLocation> locations = manifestLocations(archive, wanted);
        if (locations.isEmpty()) {
            throw new IOException(wanted == ResourceSource.CURSEFORGE
                    ? "不是有效的 CurseForge 整合包（缺少 manifest.json）"
                    : "不是有效的 Modrinth 整合包（缺少 modrinth.index.json）");
        }
        return readManifest(archive, locations.get(0));
    }

    /**
     * Detects the pack format from the archive itself, for files dropped into
     * the launcher where the download source is not known in advance. The
     * manifest may sit at the archive root or inside a top-level folder.
     */
    public static DetectedModpack inspectModpack(Path archive) throws IOException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new IOException("整合包文件不存在");
        }
        List<ManifestLocation> locations = manifestLocations(archive, null);
        if (locations.isEmpty()) {
            throw new IOException("不是有效的整合包：压缩包中缺少 modrinth.index.json 或 manifest.json");
        }
        IOException firstError = null;
        for (ManifestLocation location : locations) {
            try {
                return new DetectedModpack(location.source(), readManifest(archive, location),
                        location.root());
            } catch (IOException e) {
                // A stray manifest.json (for example inside bundled assets) must
                // not stop us from trying another manifest in the archive.
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        throw firstError;
    }

    /**
     * Lists pack manifests in the archive, shallowest first, with Modrinth
     * winning ties, and records the folder each manifest lives in.
     */
    private static List<ManifestLocation> manifestLocations(Path archive, ResourceSource wanted)
            throws IOException {
        List<ManifestLocation> found = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                String normalized = entryName.replace('\\', '/');
                ResourceSource source;
                if (isManifest(normalized, "modrinth.index.json")) {
                    source = ResourceSource.MODRINTH;
                } else if (isManifest(normalized, "manifest.json")) {
                    source = ResourceSource.CURSEFORGE;
                } else {
                    continue;
                }
                if (wanted != null && source != wanted) {
                    continue;
                }
                int slash = normalized.lastIndexOf('/');
                String root = slash < 0 ? "" : normalized.substring(0, slash + 1);
                int depth = slash < 0 ? 0
                        : (int) root.chars().filter(c -> c == '/').count();
                found.add(new ManifestLocation(source, entryName, root, depth));
            }
        }
        found.sort(Comparator.comparingInt(ManifestLocation::depth)
                .thenComparingInt(location ->
                        location.source() == ResourceSource.MODRINTH ? 0 : 1));
        return found;
    }

    private static boolean isManifest(String entryName, String leaf) {
        return entryName.equals(leaf) || entryName.endsWith("/" + leaf);
    }

    private static ModpackInfo readManifest(Path archive, ManifestLocation location)
            throws IOException {
        return switch (location.source()) {
            case MODRINTH -> readModrinthIndex(archive, location.entry());
            case CURSEFORGE -> readCurseForgeManifest(archive, location.entry());
        };
    }

    public static InstalledModpack installModpack(ResourceProject project, ResourceVersion version,
                                                  Path archive, String displayName,
                                                  Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (project == null || version == null) {
            throw new IOException("没有选择要安装的整合包");
        }
        DetectedModpack detected = inspectModpack(archive);
        ModpackInfo info = detected.info();
        String name = displayName == null ? "" : displayName.trim();
        if (name.isBlank()) {
            name = project.title() + " " + version.versionNumber();
        }
        BooleanSupplier stop = stop(progress, cancelled);
        progress.setStage("安装 " + info.mcVersion() + " · " + info.loader().label);
        Instance instance = InstallService.install(info.mcVersion(), info.loader(),
                info.loaderVersion(), name, progress, stop);
        try {
            if (detected.source() == ResourceSource.CURSEFORGE) {
                installCurseForgeFiles(instance, archive, detected.root(), progress, stop);
            } else {
                installModrinthFiles(instance, archive, detected.root(), progress, stop);
            }
            writeMetadata(instance, project, version);
            progress.setStage("整合包安装完成");
            progress.setDetail(instance.name);
            return new InstalledModpack(instance, info);
        } catch (InterruptedException e) {
            InstanceStore.get().remove(instance);
            throw e;
        } catch (IOException e) {
            InstanceStore.get().remove(instance);
            throw e;
        } catch (RuntimeException e) {
            InstanceStore.get().remove(instance);
            throw new IOException("整合包安装失败：" + e.getMessage(), e);
        }
    }

    private static void installModrinthFiles(Instance instance, Path archive, String prefix,
                                             Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        JsonObject root = readJsonEntry(archive, prefix + "modrinth.index.json",
                "不是有效的 Modrinth 整合包（缺少 modrinth.index.json）");
        JsonArray files = root.has("files") && root.get("files").isJsonArray()
                ? root.getAsJsonArray("files") : new JsonArray();
        Path gameDir = instance.gameDir().toAbsolutePath().normalize();
        List<Downloader.Item> items = new ArrayList<>();
        Set<Path> destinations = new HashSet<>();
        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            JsonObject env = object(file, "env");
            if (env != null && "unsupported".equalsIgnoreCase(str(env, "client"))) {
                continue;
            }
            String relative = str(file, "path");
            Path dest = safeResolve(gameDir, relative);
            if (dest == null) {
                Log.warn("忽略整合包中的不安全路径: " + relative);
                continue;
            }
            if (!destinations.add(dest)) {
                continue;
            }
            List<String> urls = withMirrors(stringList(array(file, "downloads")));
            if (urls.isEmpty()) {
                Log.warn("整合包文件没有下载地址: " + relative);
                continue;
            }
            JsonObject hashes = object(file, "hashes");
            String sha1 = hashes == null ? "" : str(hashes, "sha1");
            long size = lng(file, "fileSize");
            String label = dest.getFileName().toString();
            items.add(new Downloader.Item(label, urls, dest, sha1, size));
        }
        if (!items.isEmpty()) {
            progress.setStage("下载整合包文件");
            Downloader.downloadAll(items, progress,
                    Math.max(4, Math.min(16, Config.get().downloadThreads)), cancelled);
        }
        progress.setStage("应用整合包覆盖文件");
        ZipUtil.extractSubtree(archive, prefix + "overrides", gameDir);
        ZipUtil.extractSubtree(archive, prefix + "client-overrides", gameDir);
    }

    private static void installCurseForgeFiles(Instance instance, Path archive, String prefix,
                                               Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (!CurseForgeProvider.isConfigured()) {
            throw new IOException("内置 CurseForge 配置不可用，请重新安装启动器。");
        }
        JsonObject manifest = readJsonEntry(archive, prefix + "manifest.json",
                "不是有效的 CurseForge 整合包（缺少 manifest.json）");
        String overrides = str(manifest, "overrides");
        if (overrides.isBlank()) {
            overrides = "overrides";
        }
        JsonArray entries = manifest.has("files") && manifest.get("files").isJsonArray()
                ? manifest.getAsJsonArray("files") : new JsonArray();
        List<long[]> wanted = new ArrayList<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            long projectId = lng(file, "projectID");
            long fileId = lng(file, "fileID");
            if (projectId > 0 && fileId > 0) {
                wanted.add(new long[]{projectId, fileId});
            }
        }
        List<ResolvedFile> resolved = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        if (!wanted.isEmpty()) {
            progress.setStage("获取整合包文件信息");
            int threads = Math.max(1, Math.min(6, wanted.size()));
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread thread = new Thread(r, "ss-pack-resolve");
                thread.setDaemon(true);
                return thread;
            });
            try {
                for (long[] ids : wanted) {
                    pool.submit(() -> {
                        if (cancelled.getAsBoolean()) {
                            return;
                        }
                        try {
                            CurseForgeProvider.RemoteFile file =
                                    CurseForgeProvider.file(ids[0], ids[1]);
                            resolved.add(new ResolvedFile(file.fileName(), file.downloadUrl(),
                                    file.sha1(), file.size(), file.fileName()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            failures.add(ids[0] + "/" + ids[1] + " " + e.getMessage());
                        }
                    });
                }
                pool.shutdown();
                while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        pool.shutdownNow();
                        throw new InterruptedException("已取消");
                    }
                }
            } finally {
                if (!pool.isTerminated()) {
                    pool.shutdownNow();
                }
            }
        }

        Path modsDir = instance.gameDir().toAbsolutePath().normalize().resolve("mods");
        Files.createDirectories(modsDir);
        List<Downloader.Item> items = new ArrayList<>();
        Set<Path> destinations = new HashSet<>();
        for (ResolvedFile file : resolved) {
            String name = ResourceService.safeFileName(file.fileName(), "mod", "");
            Path dest = uniqueTarget(modsDir, name, destinations);
            items.add(new Downloader.Item(name, Mirror.resourceCandidates(file.url()),
                    dest, file.sha1(), file.size()));
        }
        if (!items.isEmpty()) {
            progress.setStage("下载整合包模组");
            Downloader.downloadAll(items, progress,
                    Math.max(4, Math.min(16, Config.get().downloadThreads)), cancelled);
        }
        if (!failures.isEmpty()) {
            Log.warn("整合包有 " + failures.size() + " 个文件无法获取，例如: " + failures.get(0));
            progress.setDetail("已跳过 " + failures.size() + " 个无法获取的文件");
            if (items.isEmpty()) {
                throw new IOException("整合包中的所有文件都无法获取：" + failures.get(0));
            }
        }
        progress.setStage("应用整合包覆盖文件");
        ZipUtil.extractSubtree(archive, joinPrefix(prefix, overrides),
                instance.gameDir().toAbsolutePath().normalize());
    }

    /** Joins the manifest folder and a manifest-relative path, both with {@code /}. */
    private static String joinPrefix(String prefix, String relative) {
        String path = relative == null ? "" : relative.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String base = prefix == null ? "" : prefix.replace('\\', '/');
        if (!base.isEmpty() && !base.endsWith("/")) {
            base += "/";
        }
        return base + path;
    }

    private static void writeMetadata(Instance instance, ResourceProject project,
                                      ResourceVersion version) {
        JsonObject meta = new JsonObject();
        meta.addProperty("source", project.source().name());
        meta.addProperty("projectId", project.id());
        meta.addProperty("projectTitle", project.title());
        meta.addProperty("versionId", version.versionId());
        meta.addProperty("versionName", version.name());
        meta.addProperty("installedAt", System.currentTimeMillis());
        try {
            Json.write(instance.gameDir().resolve("modpack.json"), meta);
        } catch (IOException e) {
            Log.warn("写入整合包信息失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- manifests

    static ModpackInfo readModrinthIndex(Path archive, String entry) throws IOException {
        JsonObject root = readJsonEntry(archive, entry,
                "不是有效的 Modrinth 整合包（缺少 modrinth.index.json）");
        JsonObject dependencies = object(root, "dependencies");
        if (dependencies == null) {
            throw new IOException("整合包缺少 dependencies 信息");
        }
        String mcVersion = str(dependencies, "minecraft");
        if (mcVersion.isBlank()) {
            throw new IOException("整合包没有声明 Minecraft 版本");
        }
        LoaderType loader = LoaderType.VANILLA;
        String loaderVersion = "";
        if (dependencies.has("neoforge")) {
            loader = LoaderType.NEOFORGE;
            loaderVersion = str(dependencies, "neoforge");
        } else if (dependencies.has("forge")) {
            loader = LoaderType.FORGE;
            loaderVersion = str(dependencies, "forge");
        } else if (dependencies.has("quilt-loader")) {
            loader = LoaderType.QUILT;
            loaderVersion = str(dependencies, "quilt-loader");
        } else if (dependencies.has("fabric-loader")) {
            loader = LoaderType.FABRIC;
            loaderVersion = str(dependencies, "fabric-loader");
        } else if (dependencies.has("fabric")) {
            loader = LoaderType.FABRIC;
            loaderVersion = str(dependencies, "fabric");
        }
        requireLoaderVersion(loader, loaderVersion);
        int count = root.has("files") && root.get("files").isJsonArray()
                ? root.getAsJsonArray("files").size() : 0;
        return new ModpackInfo(mcVersion, loader, loaderVersion, str(root, "name"),
                str(root, "versionId"), count);
    }

    static ModpackInfo readCurseForgeManifest(Path archive, String entry) throws IOException {
        JsonObject root = readJsonEntry(archive, entry,
                "不是有效的 CurseForge 整合包（缺少 manifest.json）");
        JsonObject minecraft = object(root, "minecraft");
        if (minecraft == null) {
            throw new IOException("整合包缺少 minecraft 信息");
        }
        String mcVersion = str(minecraft, "version");
        if (mcVersion.isBlank()) {
            throw new IOException("整合包没有声明 Minecraft 版本");
        }
        LoaderType loader = LoaderType.VANILLA;
        String loaderVersion = "";
        JsonArray loaders = array(minecraft, "modLoaders");
        if (loaders != null && !loaders.isEmpty()) {
            JsonObject chosen = null;
            for (JsonElement element : loaders) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = element.getAsJsonObject();
                if (chosen == null) {
                    chosen = candidate;
                }
                if (bool(candidate, "primary")) {
                    chosen = candidate;
                    break;
                }
            }
            String id = chosen == null ? "" : str(chosen, "id");
            int dash = id.indexOf('-');
            if (dash > 0) {
                loader = LoaderType.fromId(id.substring(0, dash));
                loaderVersion = id.substring(dash + 1);
            } else if (!id.isBlank()) {
                loader = LoaderType.fromId(id);
            }
        }
        requireLoaderVersion(loader, loaderVersion);
        int count = root.has("files") && root.get("files").isJsonArray()
                ? root.getAsJsonArray("files").size() : 0;
        return new ModpackInfo(mcVersion, loader, loaderVersion, str(root, "name"),
                str(root, "version"), count);
    }

    private static void requireLoaderVersion(LoaderType loader, String version) throws IOException {
        if (loader != LoaderType.VANILLA && (version == null || version.isBlank())) {
            throw new IOException("整合包没有声明 " + loader.label + " 版本");
        }
    }

    // ------------------------------------------------------------------ utils

    private static JsonObject readJsonEntry(Path archive, String entry, String message)
            throws IOException {
        byte[] bytes;
        try {
            bytes = ZipUtil.readEntry(archive, entry);
        } catch (IOException e) {
            throw new IOException(message + "：" + e.getMessage(), e);
        }
        if (bytes == null) {
            throw new IOException(message);
        }
        try {
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (root.isJsonObject()) {
                return root.getAsJsonObject();
            }
        } catch (Exception e) {
            throw new IOException("整合包清单解析失败: " + e.getMessage(), e);
        }
        throw new IOException("整合包清单格式无效");
    }

    private static Path safeResolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path dest = root.resolve(relative.replace('\\', '/')).normalize();
        if (!dest.startsWith(root) || dest.equals(root)) {
            return null;
        }
        return dest;
    }

    private static Path uniqueTarget(Path directory, String fileName, Set<Path> used) {
        Path target = directory.resolve(fileName);
        if (used.add(target)) {
            return target;
        }
        String stem = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        int n = 2;
        do {
            target = directory.resolve(stem + " (" + n++ + ")" + extension);
        } while (!used.add(target));
        return target;
    }

    private static BooleanSupplier stop(Progress progress, BooleanSupplier cancelled) {
        return () -> (cancelled != null && cancelled.getAsBoolean())
                || (progress != null && progress.isCancelled());
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    private static String str(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static long lng(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    && object.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /** Adds the domestic mirror as a fallback after each official CDN URL. */
    private static List<String> withMirrors(List<String> urls) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String url : urls) {
            out.addAll(Mirror.resourceCandidates(url));
        }
        return new ArrayList<>(out);
    }

    private static List<String> stringList(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (JsonElement element : array) {
            try {
                if (element != null && !element.isJsonNull()) {
                    String value = element.getAsString();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            } catch (Exception ignored) {
                // Skip malformed entries.
            }
        }
        return values;
    }
}
