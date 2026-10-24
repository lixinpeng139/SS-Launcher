package com.sslauncher.service;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.Http;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.core.Mirror;
import com.sslauncher.core.Progress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Locates installed JDKs and, when necessary, downloads Mojang's official
 * Java runtimes so every Minecraft version can be launched out of the box.
 */
public final class JavaService {
    private static final String RUNTIME_INDEX =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    /** Only a cache written by an official fetch is trusted. */
    private static final String RUNTIME_INDEX_MARKER = "java-runtime-index.official";

    private static List<JavaRuntime> cached;
    private static long cachedAt;

    private JavaService() {
    }

    public record JavaRuntime(Path exe, int major, String version, String vendor, String source) {
        public String label() {
            return "Java " + major + "  ·  " + (version == null ? "" : version) + (vendor == null || vendor.isBlank() ? "" : "  ·  " + vendor);
        }
    }

    public static synchronized List<JavaRuntime> detect(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && cached != null && now - cachedAt < 60_000) {
            return cached;
        }
        Map<String, JavaRuntime> found = new LinkedHashMap<>();
        Set<Path> candidates = new LinkedHashSet<>();
        String configured = Config.get().javaPath;
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            addJava(candidates, p, "设置指定");
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addJava(candidates, Path.of(javaHome), "JAVA_HOME");
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String part : pathEnv.split(";")) {
                if (part.isBlank()) {
                    continue;
                }
                Path p = Path.of(part, "java.exe");
                if (Files.exists(p)) {
                    candidates.add(p);
                }
            }
        }
        for (Path parent : commonJavaDirs()) {
            scanJavaDir(parent, candidates);
        }
        for (Path runtimeDir : runtimeDirs()) {
            scanJavaDir(runtimeDir, candidates);
        }
        for (Path exe : candidates) {
            if (!Files.exists(exe)) {
                continue;
            }
            try {
                Path real = exe.toRealPath();
                if (found.values().stream().anyMatch(r -> r.exe().equals(real))) {
                    continue;
                }
                JavaRuntime runtime = probe(real);
                if (runtime != null) {
                    found.put(real.toString(), runtime);
                }
            } catch (Exception ignored) {
            }
        }
        List<JavaRuntime> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparingInt(JavaRuntime::major).thenComparing(r -> r.exe().toString()));
        cached = list;
        cachedAt = now;
        for (JavaRuntime r : list) {
            Log.info("Java: " + r.label() + " -> " + r.exe());
        }
        return list;
    }

    public static List<JavaRuntime> detect() {
        return detect(false);
    }

    private static void addJava(Set<Path> out, Path path, String source) {
        if (path == null) {
            return;
        }
        if (Files.isDirectory(path)) {
            Path exe = path.resolve("bin").resolve("java.exe");
            if (Files.exists(exe)) {
                out.add(exe);
            }
            Path javaw = path.resolve("java.exe");
            if (Files.exists(javaw)) {
                out.add(javaw);
            }
        } else if (Files.exists(path)) {
            out.add(path);
        }
    }

    private static List<Path> commonJavaDirs() {
        List<Path> dirs = new ArrayList<>();
        addIfExists(dirs, "C:\\Program Files\\Java");
        addIfExists(dirs, "C:\\Program Files\\Eclipse Adoptium");
        addIfExists(dirs, "C:\\Program Files\\Microsoft");
        addIfExists(dirs, "C:\\Program Files\\Amazon Corretto");
        addIfExists(dirs, "C:\\Program Files\\Zulu");
        addIfExists(dirs, "C:\\Program Files\\BellSoft");
        addIfExists(dirs, "C:\\Program Files\\Semeru");
        addIfExists(dirs, "C:\\Program Files\\AdoptOpenJDK");
        addIfExists(dirs, "C:\\Program Files\\Temurin");
        addIfExists(dirs, "C:\\Program Files\\JetBrains");
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            addIfExists(dirs, local + "\\Programs\\Eclipse Adoptium");
            addIfExists(dirs, local + "\\Programs\\Microsoft");
        }
        String user = System.getProperty("user.home");
        if (user != null) {
            addIfExists(dirs, user + "\\.jdks");
            addIfExists(dirs, user + "\\scoop\\apps");
        }
        return dirs;
    }

    private static List<Path> runtimeDirs() {
        // Detection can run before Config.load() in headless helpers; init() is idempotent.
        AppPaths.init();
        return List.of(AppPaths.RUNTIMES);
    }

    private static void addIfExists(List<Path> out, String path) {
        Path p = Path.of(path);
        if (Files.isDirectory(p)) {
            out.add(p);
        }
    }

    private static void scanJavaDir(Path parent, Set<Path> out) {
        if (!Files.isDirectory(parent)) {
            return;
        }
        addJava(out, parent, "scan");
        try (var stream = Files.walk(parent, 4)) {
            stream.filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase("java.exe"))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName() != null
                            && p.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    .limit(80)
                    .forEach(p -> out.add(p));
        } catch (IOException ignored) {
        }
    }

    public static JavaRuntime probe(Path exe) {
        try {
            Process process = new ProcessBuilder(exe.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines++ < 200) {
                    sb.append(line).append('\n');
                }
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = sb.toString();
            int major = parseMajor(output);
            String version = match(output, "java.version = ");
            String vendor = match(output, "java.vendor = ");
            if (major <= 0) {
                return null;
            }
            return new JavaRuntime(exe, major, version, vendor, "local");
        } catch (Exception e) {
            return null;
        }
    }

    private static String match(String output, String prefix) {
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.startsWith(prefix)) {
                return t.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static int parseMajor(String output) {
        String version = match(output, "java.version = ");
        if (version.isBlank()) {
            for (String line : output.split("\\R")) {
                if (line.contains("version \"")) {
                    int a = line.indexOf('"');
                    int b = line.indexOf('"', a + 1);
                    if (b > a) {
                        version = line.substring(a + 1, b);
                        break;
                    }
                }
            }
        }
        if (version.isBlank()) {
            return -1;
        }
        try {
            if (version.startsWith("1.")) {
                int dot = version.indexOf('.', 2);
                return Integer.parseInt(dot > 0 ? version.substring(2, dot) : version.substring(2));
            }
            int dot = version.indexOf('.');
            String major = dot > 0 ? version.substring(0, dot) : version;
            major = major.replaceAll("[^0-9].*$", "");
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Finds a runtime compatible with the requested class-file version. */
    public static JavaRuntime compatible(int requiredMajor) {
        List<JavaRuntime> all = detect();
        JavaRuntime exact = null;
        JavaRuntime higher = null;
        for (JavaRuntime r : all) {
            if (r.major() == requiredMajor) {
                if (exact == null) {
                    exact = r;
                }
            } else if (r.major() > requiredMajor && (higher == null || r.major() < higher.major())) {
                higher = r;
            }
        }
        if (exact != null) {
            return exact;
        }
        // Java 17 can run 1.17 (Java 16) games, and Java 21 can run 1.18+
        // games. Avoid reaching too far ahead: legacy Java 8 games break on
        // modern JDKs, and brand new JDKs can break older LWJGL versions.
        if (requiredMajor >= 16 && higher != null && higher.major() <= requiredMajor + 5) {
            return higher;
        }
        return null;
    }

    /** Returns a usable runtime, downloading Mojang's official runtime when needed. */
    public static JavaRuntime ensure(int requiredMajor, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        JavaRuntime existing = compatible(requiredMajor);
        if (existing != null) {
            return existing;
        }
        return downloadRuntime(requiredMajor, false, progress, cancelled);
    }

    /**
     * Returns a full JDK (with {@code javac}) for compiling mods, downloading
     * Mojang's official Java runtime when no local JDK matches.
     */
    public static JavaRuntime ensureJdk(int requiredMajor, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        for (JavaRuntime runtime : detect(true)) {
            if (runtime.major() == requiredMajor && hasJavac(runtime)) {
                return runtime;
            }
        }
        return downloadRuntime(requiredMajor, true, progress, cancelled);
    }

    /** True when the runtime directory also contains a Java compiler. */
    public static boolean hasJavac(JavaRuntime runtime) {
        if (runtime == null || runtime.exe() == null) {
            return false;
        }
        boolean windows = "windows".equals(AppPaths.osName());
        Path bin = runtime.exe().getParent();
        Path home = bin == null ? null : bin.getParent();
        if (home == null || bin == null) {
            return false;
        }
        return Files.isRegularFile(bin.resolve(windows ? "javac.exe" : "javac"))
                && Files.isRegularFile(home.resolve("bin").resolve(windows ? "java.exe" : "java"));
    }

    private static JavaRuntime downloadRuntime(int requiredMajor, boolean requireJdk,
                                               Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String component = componentFor(requiredMajor);
        progress.setStage("准备 Java " + requiredMajor + " 运行环境");
        RuntimeIndexEntry entry = findRuntimeEntry(component, requiredMajor);
        if (entry == null) {
            throw new IOException("无法找到适用于 Java " + requiredMajor + " 的官方运行时");
        }
        Path dir = AppPaths.RUNTIMES.resolve(component);
        Path exe = dir.resolve("bin").resolve("java.exe");
        if (Files.exists(exe)) {
            JavaRuntime probe = probe(exe);
            if (probe != null && probe.major() == requiredMajor
                    && (!requireJdk || hasJavac(probe))) {
                return probe;
            }
        }
        progress.setStage("下载 Java " + requiredMajor + " 运行时");
        progress.setIndeterminate(false);
        // Runtime files are executed, so their manifest must come from Mojang
        // over HTTPS. Mirrors may still serve the (SHA-1 verified) files.
        RuntimeManifest manifest = Json.fromJson(
                Http.getDirect(entry.manifest.url, Map.of("Accept", "application/json")),
                RuntimeManifest.class);
        if (manifest == null || manifest.files == null) {
            throw new IOException("Java 运行时清单无效");
        }
        List<Downloader.Item> items = new ArrayList<>();
        for (Map.Entry<String, RuntimeFile> e : manifest.files.entrySet()) {
            RuntimeFile file = e.getValue();
            if (file == null || file.downloads == null || file.downloads.raw == null
                    || file.downloads.raw.url == null) {
                continue;
            }
            if ("directory".equals(file.type)) {
                continue;
            }
            Path dest = dir.resolve(e.getKey().replace('/', java.io.File.separatorChar)).normalize();
            if (!dest.startsWith(dir)) {
                continue;
            }
            items.add(new Downloader.Item("Java: " + e.getKey(),
                    Mirror.candidates(file.downloads.raw.url), dest,
                    file.downloads.raw.sha1, file.downloads.raw.size));
        }
        Downloader.downloadAll(items, progress, 12, cancelled);
        JavaRuntime probe = probe(exe);
        if (probe == null) {
            throw new IOException("Java 运行时下载完成，但无法启动 " + exe);
        }
        if (requireJdk && !hasJavac(probe)) {
            throw new IOException("下载的 Java 运行时不包含编译器（javac），无法编译模组");
        }
        cached = null;
        return probe;
    }

    private static String componentFor(int major) {
        if (major <= 8) {
            return "jre-legacy";
        }
        if (major <= 16) {
            return "java-runtime-alpha";
        }
        if (major <= 20) {
            return "java-runtime-gamma";
        }
        if (major <= 24) {
            return "java-runtime-delta";
        }
        return "java-runtime-epsilon";
    }

    private static RuntimeIndexEntry findRuntimeEntry(String component, int requiredMajor) throws IOException, InterruptedException {
        String json;
        Path cache = AppPaths.CACHE.resolve("java-runtime-index.json");
        Path marker = AppPaths.CACHE.resolve(RUNTIME_INDEX_MARKER);
        boolean fresh = Files.exists(cache) && Files.exists(marker)
                && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 24 * 3600_000L;
        if (fresh) {
            json = Files.readString(cache, StandardCharsets.UTF_8);
        } else {
            try {
                // Never let a mirror choose the manifest (and therefore the
                // hashes) of the Java runtime that will be executed.
                json = Http.getDirect(RUNTIME_INDEX, Map.of("Accept", "application/json"));
                AppPaths.atomicWrite(cache, json.getBytes(StandardCharsets.UTF_8));
                AppPaths.atomicWrite(marker, "official".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // An older official cache is still trustworthy; a cache from a
                // build that fetched through mirrors (no marker) is not.
                if (!Files.exists(cache) || !Files.exists(marker)) {
                    throw e;
                }
                Log.warn("官方 Java 运行时清单获取失败，使用上次的官方缓存: " + e.getMessage());
                json = Files.readString(cache, StandardCharsets.UTF_8);
            }
        }
        RuntimeIndex index = Json.fromJson(json, RuntimeIndex.class);
        if (index == null) {
            return null;
        }
        String platform = platformKey();
        Map<String, List<RuntimeIndexEntry>> byComponent = index.get(platform);
        if (byComponent == null) {
            return null;
        }
        List<RuntimeIndexEntry> entries = byComponent.get(component);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        RuntimeIndexEntry best = null;
        for (RuntimeIndexEntry e : entries) {
            if (e.manifest == null || e.manifest.url == null) {
                continue;
            }
            int vm = majorOf(e.version == null ? null : e.version.name);
            if (vm == requiredMajor) {
                return e;
            }
            if (best == null && vm > 0) {
                best = e;
            }
        }
        return best != null ? best : entries.get(0);
    }

    private static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        try {
            String v = version.startsWith("1.") ? version.substring(2) : version;
            int dot = v.indexOf('.');
            String major = dot > 0 ? v.substring(0, dot) : v;
            major = major.replaceAll("[^0-9].*$", "");
            return Integer.parseInt(major);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String platformKey() {
        String os = AppPaths.osName();
        if ("windows".equals(os)) {
            return "arm64".equals(AppPaths.osArch()) ? "windows-arm64" : "windows-x64";
        }
        if ("osx".equals(os)) {
            return "arm64".equals(AppPaths.osArch()) ? "mac-os-arm64" : "mac-os";
        }
        return "linux";
    }

    // ---------------------------------------------------------------- json models

    private static class RuntimeIndex extends LinkedHashMap<String, Map<String, List<RuntimeIndexEntry>>> {
    }

    private static class RuntimeIndexEntry {
        ManifestRef manifest;
        VersionInfo version;
    }

    private static class ManifestRef {
        String url;
        String sha1;
        long size;
    }

    private static class VersionInfo {
        String name;
        String released;
    }

    private static class RuntimeManifest {
        Map<String, RuntimeFile> files;
    }

    private static class RuntimeFile {
        String type;
        RuntimeDownloads downloads;
        boolean executable;
        String target;
    }

    private static class RuntimeDownloads {
        RuntimeDownload raw;
        RuntimeDownload lzma;
    }

    private static class RuntimeDownload {
        String sha1;
        long size;
        String url;
    }
}
