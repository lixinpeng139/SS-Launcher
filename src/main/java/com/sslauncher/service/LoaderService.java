package com.sslauncher.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Http;
import com.sslauncher.core.Log;
import com.sslauncher.core.Mirror;
import com.sslauncher.model.LoaderType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Fabric / Quilt meta APIs and the Forge / NeoForge Maven
 * repositories to list the loader versions available for a game version.
 */
public final class LoaderService {
    private static final String FABRIC_META = "https://meta.fabricmc.net";
    private static final String QUILT_META = "https://meta.quiltmc.org";
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private LoaderService() {
    }

    public record LoaderVersion(String id, String label, boolean recommended) {
    }

    /** Ordered newest first; the first entry is a sensible default. */
    public static List<LoaderVersion> versions(LoaderType type, String mcVersion)
            throws IOException, InterruptedException {
        if (type == null || type == LoaderType.VANILLA) {
            return List.of(new LoaderVersion(mcVersion, mcVersion, true));
        }
        return switch (type) {
            case FABRIC -> fabricVersions(mcVersion);
            case QUILT -> quiltVersions(mcVersion);
            case FORGE -> forgeVersions(mcVersion);
            case NEOFORGE -> neoForgeVersions(mcVersion);
            default -> List.of();
        };
    }

    private static List<LoaderVersion> fabricVersions(String mcVersion) throws IOException, InterruptedException {
        String path = "/v2/versions/loader/" + YggdrasilEncode(mcVersion);
        String json = getText(Mirror.fabricMetaCandidates(path, FABRIC_META + path));
        List<LoaderVersion> out = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            JsonObject loader = entry.has("loader") ? entry.getAsJsonObject("loader") : entry;
            if (!loader.has("version")) {
                continue;
            }
            String version = loader.get("version").getAsString();
            out.add(new LoaderVersion(version, version, false));
        }
        return finish(out);
    }

    private static List<LoaderVersion> quiltVersions(String mcVersion) throws IOException, InterruptedException {
        // BMCLAPI does not proxy Quilt's meta API; use the official endpoint.
        String json = getText(List.of(
                QUILT_META + "/v3/versions/loader/" + YggdrasilEncode(mcVersion)));
        List<LoaderVersion> out = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            JsonObject loader = entry.has("loader") ? entry.getAsJsonObject("loader") : entry;
            if (!loader.has("version")) {
                continue;
            }
            String version = loader.get("version").getAsString();
            out.add(new LoaderVersion(version, version, false));
        }
        return finish(out);
    }

    private static boolean prerelease(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        return v.contains("alpha") || v.contains("beta") || v.contains("snapshot")
                || v.contains("-rc") || v.contains("-pre");
    }

    /**
     * Orders stable loaders first (newest first within each group) and marks
     * the newest stable entry as recommended. Betas stay selectable below.
     */
    private static List<LoaderVersion> finish(List<LoaderVersion> versions) {
        versions.sort(Comparator.comparing((LoaderVersion v) -> prerelease(v.id()))
                .thenComparing(LoaderVersion::id, (a, b) -> compareVersions(b, a)));
        boolean hasStable = versions.stream().anyMatch(v -> !prerelease(v.id()));
        List<LoaderVersion> result = new ArrayList<>(versions.size());
        boolean marked = false;
        for (LoaderVersion v : versions) {
            boolean rec = !marked && (!hasStable || !prerelease(v.id()));
            if (rec) {
                marked = true;
            }
            result.add(new LoaderVersion(v.id(), v.label(), rec));
        }
        return result;
    }

    private static List<LoaderVersion> forgeVersions(String mcVersion) throws IOException, InterruptedException {
        String metadataPath = "net/minecraftforge/forge/maven-metadata.xml";
        List<String> filtered = loaderVersions(Mirror.forgeMetadataCandidates(metadataPath),
                v -> v.startsWith(mcVersion + "-"));
        filtered.sort(Comparator.comparing((String v) -> v.substring(mcVersion.length() + 1),
                LoaderService::compareVersions).reversed());
        String recommended = recommendedForge(mcVersion);
        List<LoaderVersion> out = new ArrayList<>();
        for (String full : filtered) {
            String forge = full.substring(mcVersion.length() + 1);
            boolean rec = forge.equals(recommended);
            out.add(new LoaderVersion(forge, forge, rec));
        }
        out.sort(Comparator.comparing((LoaderVersion v) -> !v.recommended())
                .thenComparing(LoaderVersion::id, (a, b) -> compareVersions(b, a)));
        if (!out.isEmpty() && out.stream().noneMatch(LoaderVersion::recommended)) {
            LoaderVersion first = out.get(0);
            out.set(0, new LoaderVersion(first.id(), first.label(), true));
        }
        return out;
    }

    private static String recommendedForge(String mcVersion) {
        // BMCLAPI's promotions endpoint redirects to a dead host (~21s); the
        // official endpoint answers in ~2s and is available to all users.
        List<String> urls = List.of(
                "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
        for (String url : urls) {
            try {
                JsonObject json = JsonParser.parseString(Http.get(url)).getAsJsonObject();
                if (json.has("promos") && json.get("promos").isJsonObject()) {
                    JsonObject promos = json.getAsJsonObject("promos");
                    String key = mcVersion + "-recommended";
                    if (promos.has(key)) {
                        return promos.get(key).getAsString();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static List<LoaderVersion> neoForgeVersions(String mcVersion) throws IOException, InterruptedException {
        boolean legacy = "1.20.1".equals(mcVersion);
        String group = legacy ? "net/neoforged/forge" : "net/neoforged/neoforge";
        String metadataPath = group + "/maven-metadata.xml";
        // Legacy NeoForge publishes Maven versions as 1.20.1-47.1.x but users
        // pick the 47.1.x part. Newer releases use 21.1.x / 26.3.x directly.
        String prefix = legacy ? mcVersion + "-47.1."
                : mcVersion.startsWith("1.") ? neoPrefix(mcVersion) + "." : mcVersion + ".";
        List<String> all = loaderVersions(Mirror.neoForgeMetadataCandidates(metadataPath),
                legacy
                        ? v -> v.startsWith(prefix) || v.startsWith("47.1.")
                        : v -> v.startsWith(prefix));
        LinkedHashSet<String> full = new LinkedHashSet<>();
        LinkedHashSet<String> bare = new LinkedHashSet<>();
        for (String v : all) {
            if (legacy) {
                if (v.startsWith(prefix)) {
                    full.add(v.substring(mcVersion.length() + 1));
                } else if (v.startsWith("47.1.")) {
                    bare.add(v);
                }
            } else if (v.startsWith(prefix)) {
                full.add(v);
            }
        }
        // BMCLAPI occasionally carries a bare "47.1.x" that has no installer
        // on either mirror. Only fall back to those when no fully qualified
        // version (1.20.1-47.1.x) was published.
        List<String> sorted = new ArrayList<>(full.isEmpty() ? bare : full);
        sorted.sort(Comparator.comparing((String v) -> v, LoaderService::compareVersions).reversed());
        List<LoaderVersion> out = new ArrayList<>();
        for (String v : sorted) {
            out.add(new LoaderVersion(v, v, false));
        }
        return finish(out);
    }

    private static String neoPrefix(String mcVersion) {
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 2) {
            return mcVersion;
        }
        int minor;
        int patch = 0;
        try {
            minor = Integer.parseInt(parts[1]);
            if (parts.length > 2) {
                patch = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException e) {
            return mcVersion;
        }
        return minor + "." + patch;
    }

    /**
     * Reads the first source that actually contains versions for the requested
     * game version. Sources are already ordered fastest-first, so stopping at
     * the first hit avoids waiting for dead mirrors (BMCLAPI redirects and
     * maven.neoforged.net can each block for ~20s).
     *
     * @param accept filters raw Maven versions for the requested game version
     */
    private static List<String> loaderVersions(List<String> urls,
                                               java.util.function.Predicate<String> accept)
            throws IOException, InterruptedException {
        IOException last = null;
        boolean responded = false;
        for (String url : urls) {
            try {
                List<String> versions = fetchVersions(url);
                responded = true;
                List<String> matched = new ArrayList<>();
                for (String version : versions) {
                    if (accept.test(version)) {
                        matched.add(version);
                    }
                }
                if (!matched.isEmpty()) {
                    return matched;
                }
            } catch (IOException e) {
                last = e;
                Log.warn("获取加载器元数据失败 " + url + ": " + e.getMessage());
            }
        }
        if (!responded && last != null) {
            throw last;
        }
        return List.of();
    }

    private static List<String> fetchVersions(String url) throws IOException, InterruptedException {
        Map<String, String> headers = Map.of("Accept", "text/xml, application/xml, */*");
        // Bypass Http.get's mirror rewrite so each source is really read.
        var response = Http.getResponse(url, headers);
        if (response.statusCode() / 100 != 2) {
            throw new Http.HttpException(response.statusCode(), response.body(), url);
        }
        String xml = response.body();
        List<String> versions = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return versions;
        }
        Matcher m = VERSION_TAG.matcher(xml);
        while (m.find()) {
            String version = m.group(1).trim();
            if (!version.isEmpty()) {
                versions.add(version);
            }
        }
        return versions;
    }

    // ---------------------------------------------------------- install sources

    /** Full Maven version, e.g. {@code 1.20.1-47.4.0} for Forge. */
    public static String artifactVersion(LoaderType type, String mcVersion, String loaderVersion) {
        boolean legacyNeoForge = type == LoaderType.NEOFORGE && "1.20.1".equals(mcVersion);
        return type == LoaderType.FORGE || legacyNeoForge ? mcVersion + "-" + loaderVersion : loaderVersion;
    }

    public static String neoForgeGroup(String mcVersion) {
        return "1.20.1".equals(mcVersion) ? "net/neoforged/forge" : "net/neoforged/neoforge";
    }

    /** Official Maven path of the Forge/NeoForge installer, or {@code null}. */
    static String installerPath(LoaderType type, String mcVersion, String loaderVersion) {
        if (type == LoaderType.FORGE) {
            String full = artifactVersion(type, mcVersion, loaderVersion);
            return "net/minecraftforge/forge/" + full + "/forge-" + full + "-installer.jar";
        }
        if (type == LoaderType.NEOFORGE) {
            String group = neoForgeGroup(mcVersion);
            String artifact = group.substring(group.lastIndexOf('/') + 1);
            String version = artifactVersion(type, mcVersion, loaderVersion);
            return group + "/" + version + "/" + artifact + "-" + version + "-installer.jar";
        }
        return null;
    }

    /** Official HTTPS URL of the installer jar, or {@code null}. */
    public static String installerOfficialUrl(LoaderType type, String mcVersion, String loaderVersion) {
        String path = installerPath(type, mcVersion, loaderVersion);
        if (path == null) {
            return null;
        }
        return type == LoaderType.FORGE
                ? "https://maven.minecraftforge.net/" + path
                : "https://maven.neoforged.net/releases/" + path;
    }

    /** Candidate URLs of the official Forge/NeoForge installer jar. */
    public static List<String> installerCandidates(LoaderType type, String mcVersion, String loaderVersion) {
        String path = installerPath(type, mcVersion, loaderVersion);
        if (path == null) {
            return List.of();
        }
        return Mirror.mavenCandidates(path, installerOfficialUrl(type, mcVersion, loaderVersion));
    }

    /**
     * Official sources of the installer's {@code .sha1} file. These URLs are
     * fetched directly (never through a mirror), because the digest is what
     * makes the mirror-downloaded jar trustworthy.
     */
    public static List<String> installerSha1Candidates(LoaderType type, String mcVersion,
                                                       String loaderVersion) {
        String path = installerPath(type, mcVersion, loaderVersion);
        if (path == null) {
            return List.of();
        }
        if (type == LoaderType.FORGE) {
            return List.of(
                    "https://maven.minecraftforge.net/" + path + ".sha1",
                    "https://files.minecraftforge.net/maven/" + path + ".sha1");
        }
        return List.of(
                Mirror.NEOFORGE_CDN + "/releases/" + path + ".sha1",
                "https://maven.neoforged.net/releases/" + path + ".sha1");
    }

    /** Extracts the 40-hex SHA-1 from a {@code .sha1} response. */
    static String parseOfficialSha1(String body, String source) throws IOException {
        if (body == null) {
            throw new IOException("官方校验值为空: " + source);
        }
        String token = body.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!token.matches("[0-9a-f]{40}")) {
            throw new IOException("官方校验值格式无效: " + source);
        }
        return token;
    }

    public static String installerFileName(LoaderType type, String mcVersion, String loaderVersion) {
        if (type == LoaderType.FORGE) {
            return "forge-" + mcVersion + "-" + loaderVersion + "-installer.jar";
        }
        if (type == LoaderType.NEOFORGE) {
            String artifact = "1.20.1".equals(mcVersion) ? "forge" : "neoforge";
            return artifact + "-" + artifactVersion(type, mcVersion, loaderVersion) + "-installer.jar";
        }
        return "installer.jar";
    }

    /**
     * Official sources of the loader profile JSON. A profile names the main
     * class and the libraries that will be executed, so it is trusted metadata:
     * it is fetched from the official meta API only, never from a mirror (a
     * mirror may still serve the library jars named by the verified profile).
     */
    public static List<String> profileCandidates(LoaderType type, String mcVersion, String loaderVersion) {
        if (type == LoaderType.FABRIC) {
            String path = "/v2/versions/loader/" + YggdrasilEncode(mcVersion) + "/"
                    + YggdrasilEncode(loaderVersion) + "/profile/json";
            return List.of(FABRIC_META + path);
        }
        if (type == LoaderType.QUILT) {
            String path = "/v3/versions/loader/" + YggdrasilEncode(mcVersion) + "/"
                    + YggdrasilEncode(loaderVersion) + "/profile/json";
            return List.of(QUILT_META + path);
        }
        return List.of();
    }

    // ---------------------------------------------------------------- helpers

    private static String getText(List<String> urls) throws IOException, InterruptedException {
        IOException last = null;
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/xml, */*");
        for (String url : urls) {
            try {
                String body = Http.get(url, headers);
                if (body != null && !body.isBlank()) {
                    return body;
                }
            } catch (IOException e) {
                last = e;
                Log.warn("获取加载器元数据失败 " + url + ": " + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("无法获取加载器版本列表");
    }

    private static String YggdrasilEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Natural comparison for dotted numeric versions; non-numeric parts compare as text. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            int cmp;
            boolean nx = x.matches("\\d+");
            boolean ny = y.matches("\\d+");
            if (nx && ny) {
                cmp = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else {
                cmp = x.compareToIgnoreCase(y);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.compareToIgnoreCase(b);
    }

    public static String displayVersion(String label) {
        return label == null ? "" : label.replaceAll("(?i)^.*-", "");
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
