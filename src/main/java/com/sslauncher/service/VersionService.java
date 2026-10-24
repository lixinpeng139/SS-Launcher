package com.sslauncher.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Http;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.model.VersionManifest;
import com.sslauncher.model.VersionMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Mojang's version manifest, loads version JSONs and merges inherited
 * loader profiles (Fabric / Quilt / Forge / NeoForge).
 */
public final class VersionService {
    public static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String MANIFEST_URL_ALT =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /**
     * Marks a locally cached manifest as having been fetched from the official
     * origin. Caches written by older builds (possibly through a mirror) carry
     * no marker and are therefore never trusted, even when offline.
     */
    private static final String MANIFEST_MARKER = "version_manifest_v2.official";
    private static final Pattern ID_PATTERN = Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?");
    /** Year-based release (26.3) or snapshot (26w01a) ids. */
    private static final Pattern YEAR_PATTERN = Pattern.compile("^(\\d{2})(?:\\.|w)");

    private static VersionManifest cachedManifest;
    private static long manifestFetchedAt;

    private VersionService() {
    }

    public static synchronized VersionManifest manifest() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (cachedManifest != null && now - manifestFetchedAt < 10 * 60_000L) {
            return cachedManifest;
        }
        Path cache = AppPaths.CACHE.resolve("version_manifest_v2.json");
        Path marker = AppPaths.CACHE.resolve(MANIFEST_MARKER);
        IOException last = null;
        // The manifest contains the SHA-1 of every version JSON. It is fetched
        // from the official HTTPS origin only; a mirror must never be able to
        // supply both a version JSON and the digest that certifies it.
        for (String url : List.of(MANIFEST_URL, MANIFEST_URL_ALT)) {
            try {
                String json = Http.getDirect(url, Map.of("Accept", "application/json"));
                VersionManifest m = Json.fromJson(json, VersionManifest.class);
                if (m == null || m.versions == null || m.versions.isEmpty()) {
                    throw new IOException("官方版本清单内容为空");
                }
                cachedManifest = m;
                manifestFetchedAt = now;
                try {
                    AppPaths.atomicWrite(cache, json.getBytes(StandardCharsets.UTF_8));
                    AppPaths.atomicWrite(marker, "official".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.warn("无法写入版本清单缓存: " + e.getMessage());
                }
                return m;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                last = e instanceof IOException io ? io : new IOException(e);
                Log.warn("官方版本清单获取失败 " + url + ": " + e.getMessage());
            }
        }
        // Only fall back to a cache that a previous official fetch produced.
        if (Files.exists(cache) && Files.exists(marker)) {
            try {
                VersionManifest m = Json.read(cache, VersionManifest.class);
                if (m != null && m.versions != null && !m.versions.isEmpty()) {
                    Log.warn("使用上次从官方源获取的版本清单缓存");
                    cachedManifest = m;
                    manifestFetchedAt = now;
                    return m;
                }
            } catch (IOException e) {
                Log.error("本地版本清单损坏", e);
            }
        }
        throw new IOException("无法从官方源获取 Minecraft 版本列表，请检查网络连接后重试"
                + (last == null ? "" : "（" + last.getMessage() + "）"), last);
    }

    public static Path versionDir(String versionId) {
        return AppPaths.VERSIONS.resolve(versionId);
    }

    public static Path versionJsonPath(String versionId) {
        return versionDir(versionId).resolve(versionId + ".json");
    }

    public static Path versionJarPath(String versionId) {
        return versionDir(versionId).resolve(versionId + ".jar");
    }

    public static boolean isInstalled(String versionId) {
        return Files.exists(versionJsonPath(versionId));
    }

    /**
     * Forge and NeoForge put every classpath jar on the module path except the
     * names listed in {@code -DignoreList}. Their profiles expect the game jar
     * to sit at {@code versions/&lt;loader&gt;/&lt;loader&gt;.jar}, which the official
     * installers create for the vanilla launcher. A loader that inherits the
     * vanilla jar therefore needs its own copy; otherwise that jar becomes an
     * automatic module and clashes with FML's {@code minecraft} module.
     */
    public static Path ensureVersionJar(ResolvedVersion version) throws IOException {
        Path own = versionJarPath(version.id);
        if (Files.exists(own) && Files.size(own) > 0) {
            return own;
        }
        if (!isForgeLike(version)) {
            return null;
        }
        for (String id : version.chain) {
            if (id.equals(version.id)) {
                continue;
            }
            Path inherited = versionJarPath(id);
            if (!Files.exists(inherited) || Files.size(inherited) == 0) {
                continue;
            }
            Files.createDirectories(own.getParent());
            try {
                // Hard links cost no extra space when both live on one volume.
                Files.createLink(own, inherited);
            } catch (Exception e) {
                Files.copy(inherited, own, StandardCopyOption.REPLACE_EXISTING);
            }
            Log.info("已为 " + version.id + " 创建游戏本体副本");
            return own;
        }
        return null;
    }

    private static boolean isForgeLike(ResolvedVersion version) {
        if (version.mainClass != null && version.mainClass.contains("bootstraplauncher")) {
            return true;
        }
        if (version.arguments != null && version.arguments.jvm != null) {
            for (var element : version.arguments.jvm) {
                if (element == null || !element.isJsonPrimitive()) {
                    continue;
                }
                String arg = element.getAsString();
                if (arg.startsWith("-DignoreList=") && arg.contains("${version_name}")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Reads and merges a version JSON (following inheritsFrom). */
    public static ResolvedVersion load(String versionId) throws IOException {
        return load(versionId, new ArrayDeque<>());
    }

    private static ResolvedVersion load(String versionId, Deque<String> chain) throws IOException {
        if (chain.size() > 8) {
            throw new IOException("版本继承层级过深: " + versionId);
        }
        if (chain.contains(versionId)) {
            throw new IOException("版本继承出现循环: " + versionId);
        }
        Path json = versionJsonPath(versionId);
        if (!Files.exists(json)) {
            throw new IOException("版本 " + versionId + " 尚未安装");
        }
        VersionMeta meta = Json.read(json, VersionMeta.class);
        if (meta == null) {
            throw new IOException("版本 JSON 无效: " + versionId);
        }
        if (meta.id == null) {
            meta.id = versionId;
        }
        chain.addLast(versionId);
        ResolvedVersion result;
        if (meta.inheritsFrom != null && !meta.inheritsFrom.isBlank() && isInstalled(meta.inheritsFrom)) {
            ResolvedVersion parent = load(meta.inheritsFrom, chain);
            result = merge(parent, meta, versionId);
        } else {
            result = new ResolvedVersion();
            result.id = versionId;
            result.mainClass = meta.mainClass;
            result.libraries = new ArrayList<>(meta.libraries == null ? List.of() : meta.libraries);
            result.arguments = meta.arguments;
            result.minecraftArguments = meta.minecraftArguments;
            result.assetIndex = meta.assetIndex;
            result.assets = meta.assets;
            result.javaVersion = meta.javaVersion;
            result.logging = meta.logging;
            result.chain = new ArrayList<>();
            result.downloads = meta.downloads;
        }
        result.chain.add(0, versionId);
        result.versionType = meta.type;
        return result;
    }

    private static ResolvedVersion merge(ResolvedVersion parent, VersionMeta child, String childId) {
        ResolvedVersion r = new ResolvedVersion();
        r.id = childId;
        r.mainClass = notBlank(child.mainClass) ? child.mainClass : parent.mainClass;
        r.minecraftArguments = child.minecraftArguments != null ? child.minecraftArguments : parent.minecraftArguments;
        r.assetIndex = child.assetIndex != null ? child.assetIndex : parent.assetIndex;
        r.assets = child.assets != null ? child.assets : parent.assets;
        r.javaVersion = child.javaVersion != null ? child.javaVersion : parent.javaVersion;
        r.logging = child.logging != null && child.logging.client != null ? child.logging : parent.logging;
        r.versionType = child.type != null ? child.type : parent.versionType;
        r.chain = new ArrayList<>(parent.chain);
        r.downloads = child.downloads != null && child.downloads.client != null ? child.downloads : parent.downloads;

        LinkedHashMap<String, VersionMeta.Library> libs = new LinkedHashMap<>();
        for (VersionMeta.Library l : parent.libraries) {
            if (l != null && l.name != null) {
                libs.put(l.name, l);
            }
        }
        if (child.libraries != null) {
            for (VersionMeta.Library l : child.libraries) {
                if (l != null && l.name != null) {
                    libs.put(l.name, l); // child overrides, keeps original position
                }
            }
        }
        r.libraries = new ArrayList<>(libs.values());

        r.arguments = new VersionMeta.Arguments();
        if (parent.arguments != null) {
            r.arguments.game.addAll(parent.arguments.game == null ? List.of() : parent.arguments.game);
            r.arguments.jvm.addAll(parent.arguments.jvm == null ? List.of() : parent.arguments.jvm);
        }
        if (child.arguments != null) {
            r.arguments.game.addAll(child.arguments.game == null ? List.of() : child.arguments.game);
            r.arguments.jvm.addAll(child.arguments.jvm == null ? List.of() : child.arguments.jvm);
        }
        return r;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static final class ResolvedVersion {
        public String id;
        public String mainClass;
        public String minecraftArguments;
        public VersionMeta.AssetIndex assetIndex;
        public String assets;
        public VersionMeta.JavaVersion javaVersion;
        public VersionMeta.Logging logging = new VersionMeta.Logging();
        public List<VersionMeta.Library> libraries = new ArrayList<>();
        public VersionMeta.Arguments arguments = new VersionMeta.Arguments();
        public VersionMeta.Downloads downloads = new VersionMeta.Downloads();
        public String versionType;
        public List<String> chain = new ArrayList<>();

        /** The version whose client jar should be used (first installed one in the chain). */
        public String jarVersionId() {
            for (String id : chain) {
                if (Files.exists(versionJarPath(id))) {
                    return id;
                }
            }
            return id;
        }

        public int requiredJavaMajor() {
            if (javaVersion != null && javaVersion.majorVersion > 0) {
                return javaVersion.majorVersion;
            }
            return inferJavaMajor(id);
        }
    }

    public static int inferJavaMajor(String versionId) {
        if (versionId == null) {
            return 8;
        }
        String v = versionId.toLowerCase(Locale.ROOT);
        Matcher m = ID_PATTERN.matcher(v);
        if (!m.find()) {
            Matcher year = YEAR_PATTERN.matcher(v);
            if (year.find()) {
                // Mojang's 26.x line requires Java 25 (java-runtime-epsilon).
                return Integer.parseInt(year.group(1)) >= 26 ? 25 : 21;
            }
            // Modern snapshots need a modern runtime; truly old versions fall back to Java 8.
            if (v.contains("snapshot")) {
                return 21;
            }
            return 8;
        }
        int minor = Integer.parseInt(m.group(1));
        int patch = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (minor >= 21) {
            return 21;
        }
        if (minor == 20 && patch >= 5) {
            return 21;
        }
        if (minor >= 18) {
            return 17;
        }
        if (minor == 17) {
            return 16;
        }
        return 8;
    }

    // ---------------------------------------------------------------- rules

    public static boolean rulesAllow(List<VersionMeta.Rule> rules) {
        return rulesAllow(rules, Map.of());
    }

    public static boolean rulesAllow(List<VersionMeta.Rule> rules, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (VersionMeta.Rule rule : rules) {
            if (rule != null && ruleApplies(rule, features)) {
                allowed = rule.isAllow();
            }
        }
        return allowed;
    }

    private static boolean ruleApplies(VersionMeta.Rule rule, Map<String, Boolean> features) {
        if (rule.os != null) {
            VersionMeta.Os os = rule.os;
            if (os.name != null && !os.name.equalsIgnoreCase(AppPaths.osName())) {
                return false;
            }
            if (os.arch != null) {
                String arch = switch (os.arch.toLowerCase(Locale.ROOT)) {
                    case "x86", "i386", "32" -> "x86";
                    default -> os.arch;
                };
                if (!arch.equalsIgnoreCase(AppPaths.osArch()) && !("x64".equalsIgnoreCase(arch) && "x64".equalsIgnoreCase(AppPaths.osArch()))) {
                    return false;
                }
            }
            if (os.version != null && !os.version.isBlank()) {
                String systemVersion = System.getProperty("os.version", "");
                try {
                    if (!Pattern.compile(os.version).matcher(systemVersion).find()) {
                        return false;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (rule.features != null) {
            for (Map.Entry<String, Boolean> e : rule.features.entrySet()) {
                boolean actual = features.getOrDefault(e.getKey(), Boolean.FALSE);
                if (e.getValue() == null || actual != e.getValue()) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- arguments

    public static List<String> resolveArguments(List<JsonElement> entries, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (JsonElement entry : entries) {
            if (entry == null || entry.isJsonNull()) {
                continue;
            }
            if (entry.isJsonPrimitive()) {
                out.add(substitute(entry.getAsString(), vars));
                continue;
            }
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject obj = entry.getAsJsonObject();
            List<VersionMeta.Rule> rules = List.of();
            if (obj.has("rules")) {
                rules = List.of(Json.GSON.fromJson(obj.get("rules"), VersionMeta.Rule[].class));
            }
            if (!rulesAllow(rules)) {
                continue;
            }
            JsonElement value = obj.get("value");
            if (value == null) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                out.add(substitute(value.getAsString(), vars));
            } else if (value.isJsonArray()) {
                for (JsonElement v : value.getAsJsonArray()) {
                    if (v.isJsonPrimitive()) {
                        out.add(substitute(v.getAsString(), vars));
                    }
                }
            }
        }
        return out;
    }

    public static String substitute(String value, Map<String, String> vars) {
        if (value == null) {
            return "";
        }
        String out = value;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        // clear any placeholders we do not support so Minecraft doesn't choke on them
        out = out.replaceAll("\\$\\{[^}]+}", "");
        return out;
    }

    // ---------------------------------------------------------------- libraries

    /** Path relative to libraries/, e.g. com/mojang/logging/1.1.1/logging-1.1.1.jar */
    public static String mavenPath(String mavenName) {
        if (mavenName == null || mavenName.isBlank()) {
            return null;
        }
        String coordinate = mavenName;
        String extension = "jar";
        int at = coordinate.indexOf('@');
        if (at >= 0) {
            extension = coordinate.substring(at + 1);
            coordinate = coordinate.substring(0, at);
        }
        String[] parts = coordinate.split(":");
        if (parts.length < 3) {
            return null;
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 && !parts[3].isBlank() ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + extension;
    }

    public static String libraryPath(VersionMeta.Library library) {
        if (library == null) {
            return null;
        }
        if (library.downloads != null && library.downloads.artifact != null
                && library.downloads.artifact.path != null && !library.downloads.artifact.path.isBlank()) {
            return library.downloads.artifact.path;
        }
        return mavenPath(library.name);
    }

    public static String libraryUrl(VersionMeta.Library library, String path) {
        if (library == null || path == null) {
            return null;
        }
        String url = null;
        if (library.downloads != null && library.downloads.artifact != null && library.downloads.artifact.url != null) {
            url = library.downloads.artifact.url;
        }
        if (url == null && library.url != null && !library.url.isBlank()) {
            url = library.url.endsWith("/") ? library.url + path : library.url + "/" + path;
        }
        if (url == null) {
            url = "https://libraries.minecraft.net/" + path;
        }
        return url;
    }

    public static String nativeClassifier(VersionMeta.Library library) {
        if (library == null || library.natives == null || library.natives.isEmpty()) {
            return null;
        }
        String template = library.natives.get(AppPaths.osName());
        if (template == null && "osx".equals(AppPaths.osName())) {
            template = library.natives.get("macos");
        }
        if (template == null) {
            return null;
        }
        String arch = switch (AppPaths.osArch()) {
            case "x64" -> "64";
            case "x86" -> "32";
            case "arm64" -> "arm64";
            default -> AppPaths.osArch();
        };
        return template.replace("${arch}", arch);
    }
}
