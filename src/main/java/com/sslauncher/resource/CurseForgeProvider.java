package com.sslauncher.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Http;
import com.sslauncher.core.Log;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CurseForge official API client.
 *
 * <p>The search and file endpoints require a developer API key. The launcher
 * ships one embedded (see {@link CurseForgeKey}), so users do not configure
 * anything.</p>
 */
public final class CurseForgeProvider {
    private static final String BASE = "https://api.curseforge.com/v1";
    private static final int GAME_ID = 432; // Minecraft
    private static final Set<String> LOADERS = Set.of("forge", "fabric", "quilt", "neoforge");

    private CurseForgeProvider() {
    }

    public record RemoteFile(String fileName, String downloadUrl, String sha1, long size) {
    }

    public static boolean isConfigured() {
        try {
            String key = CurseForgeKey.apiKey();
            return key != null && !key.isBlank();
        } catch (RuntimeException e) {
            Log.warn("内置 CurseForge API Key 不可用: " + e.getMessage());
            return false;
        }
    }

    public static List<ResourceProject> search(SearchQuery query)
            throws IOException, InterruptedException {
        Map<String, String> headers = headers();
        List<String> params = new ArrayList<>();
        params.add("gameId=" + GAME_ID);
        params.add("classId=" + classId(query.kind()));
        params.add("index=" + query.offset());
        params.add("pageSize=" + query.limit());
        if (!query.text().isBlank()) {
            params.add("searchFilter=" + encode(query.text()));
        }
        if (!query.gameVersion().isBlank()) {
            params.add("gameVersion=" + encode(query.gameVersion()));
        }
        int loaderType = loaderType(query.loader());
        if (query.kind().needsLoader() && loaderType > 0
                && !"quilt".equals(ResourceVersion.normalizeLoader(query.loader()))) {
            params.add("modLoaderType=" + loaderType);
        }
        switch (query.sort()) {
            case "downloads" -> {
                params.add("sortField=6");
                params.add("sortOrder=desc");
            }
            case "updated" -> {
                params.add("sortField=3");
                params.add("sortOrder=desc");
            }
            default -> {
                params.add("sortField=2");
                params.add("sortOrder=desc");
            }
        }
        String url = BASE + "/mods/search?" + String.join("&", params);
        JsonObject root;
        try {
            root = parseObject(Http.get(url, headers));
        } catch (Http.HttpException e) {
            if (e.status == 403) {
                throw new IOException("CurseForge 拒绝了搜索请求（403）。Key 可以读取项目，"
                        + "但当前 Key 没有搜索权限。请在 console.curseforge.com 重新生成 Key；"
                        + "如果仍然失败，需要提交 CurseForge 3rd Party API 申请。", e);
            }
            throw friendly(e);
        }
        JsonArray data = array(root, "data");
        List<ResourceProject> projects = new ArrayList<>();
        if (data == null) {
            return projects;
        }
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject mod = element.getAsJsonObject();
            long id = lng(mod, "id");
            if (id <= 0) {
                continue;
            }
            String slug = str(mod, "slug");
            ResourceKind kind = kindFromClassId((int) lng(mod, "classId"), query.kind());
            Set<String> gameVersions = new LinkedHashSet<>();
            Set<String> loaders = new LinkedHashSet<>();
            JsonArray indexes = array(mod, "latestFilesIndexes");
            if (indexes != null) {
                for (JsonElement index : indexes) {
                    if (!index.isJsonObject()) {
                        continue;
                    }
                    JsonObject entry = index.getAsJsonObject();
                    String gameVersion = str(entry, "gameVersion");
                    if (!gameVersion.isBlank()) {
                        gameVersions.add(gameVersion);
                    }
                    String loader = loaderName((int) lng(entry, "modLoader"));
                    if (!loader.isBlank()) {
                        loaders.add(loader);
                    }
                }
            }
            if (gameVersions.isEmpty()) {
                JsonArray all = array(mod, "gameVersions");
                if (all != null) {
                    for (String value : stringList(all)) {
                        if (looksLikeGameVersion(value)) {
                            gameVersions.add(value);
                        } else if (LOADERS.contains(value.toLowerCase(Locale.ROOT))) {
                            loaders.add(value.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            // The search endpoint already guarantees a matching file for the
            // requested filters; latestFilesIndexes only lists the newest five
            // files, so it can omit the version we searched for.
            if (!query.gameVersion().isBlank()) {
                gameVersions.add(query.gameVersion());
            }
            String queryLoader = ResourceVersion.normalizeLoader(query.loader());
            if (query.kind().needsLoader() && loaderType > 0 && !"quilt".equals(queryLoader)) {
                loaders.add(queryLoader);
            }
            String author = firstAuthor(mod);
            JsonObject links = object(mod, "links");
            String pageUrl = links == null ? "" : str(links, "websiteUrl");
            if (pageUrl.isBlank()) {
                pageUrl = "https://www.curseforge.com/minecraft/" + slugPath(kind) + "/" + slug;
            }
            JsonObject logo = object(mod, "logo");
            projects.add(new ResourceProject(ResourceSource.CURSEFORGE,
                    String.valueOf(id), slug, str(mod, "name"), author,
                    str(mod, "summary"), logoUrl(logo), pageUrl,
                    lng(mod, "downloadCount"), lng(mod, "thumbsUpCount"),
                    time(str(mod, "dateModified")), gameVersions, loaders, kind));
        }
        return projects;
    }

    public static List<ResourceVersion> versions(String projectId, ResourceKind kind,
                                                 String gameVersion, String loader)
            throws IOException, InterruptedException {
        Map<String, String> headers = headers();
        List<String> params = new ArrayList<>();
        params.add("pageSize=50");
        params.add("index=0");
        if (gameVersion != null && !gameVersion.isBlank()) {
            params.add("gameVersion=" + encode(gameVersion));
        }
        int loaderType = loaderType(loader);
        if (kind != null && kind.needsLoader() && loaderType > 0
                && !"quilt".equals(ResourceVersion.normalizeLoader(loader))) {
            params.add("modLoaderType=" + loaderType);
        }
        String url = BASE + "/mods/" + encodePath(projectId) + "/files?"
                + String.join("&", params);
        JsonObject root;
        try {
            root = parseObject(Http.get(url, headers));
        } catch (Http.HttpException e) {
            throw friendly(e);
        }
        List<ResourceVersion> versions = new ArrayList<>();
        JsonArray data = array(root, "data");
        if (data == null) {
            return versions;
        }
        for (JsonElement element : data) {
            if (element.isJsonObject()) {
                ResourceVersion version = parseFile(projectId, element.getAsJsonObject());
                if (version != null) {
                    versions.add(version);
                }
            }
        }
        versions.sort(Comparator.comparingLong(ResourceVersion::publishedAt).reversed());
        return versions;
    }

    /** Resolves a single project file, falling back to the file listing on 404. */
    public static RemoteFile file(long projectId, long fileId)
            throws IOException, InterruptedException {
        Map<String, String> headers = headers();
        try {
            JsonObject root = parseObject(Http.get(
                    BASE + "/mods/" + projectId + "/files/" + fileId, headers));
            JsonObject data = object(root, "data");
            if (data != null) {
                return remoteFile(data);
            }
        } catch (Http.HttpException e) {
            if (e.status != 404) {
                throw friendly(e);
            }
        }
        // A pack can reference a file that has been replaced. Search a few
        // pages of the project's file list before giving up.
        for (int page = 0; page < 3; page++) {
            JsonObject root = parseObject(Http.get(BASE + "/mods/" + projectId
                    + "/files?pageSize=50&index=" + (page * 50), headers));
            JsonArray data = array(root, "data");
            if (data == null) {
                break;
            }
            for (JsonElement element : data) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject file = element.getAsJsonObject();
                if (lng(file, "id") == fileId) {
                    return remoteFile(file);
                }
            }
            if (data.size() < 50) {
                break;
            }
        }
        throw new IOException("CurseForge 文件 " + fileId + " 已不存在");
    }

    public static Map<String, String> resolveModNames(Collection<String> projectIds) {
        Map<String, String> names = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        for (String value : projectIds) {
            try {
                if (value != null && !value.isBlank()) {
                    ids.add(Long.parseLong(value.trim()));
                }
            } catch (NumberFormatException ignored) {
                // Ignore non-numeric ids.
            }
        }
        for (int start = 0; start < ids.size(); start += 50) {
            List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + 50));
            JsonObject body = new JsonObject();
            JsonArray array = new JsonArray();
            for (long id : chunk) {
                array.add(id);
            }
            body.add("modIds", array);
            try {
                JsonObject root = parseObject(Http.postJson(BASE + "/mods",
                        body.toString(), headers()));
                JsonArray data = array(root, "data");
                if (data == null) {
                    continue;
                }
                for (JsonElement element : data) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject mod = element.getAsJsonObject();
                    names.put(String.valueOf(lng(mod, "id")), str(mod, "name"));
                }
            } catch (Exception e) {
                Log.warn("CurseForge 前置名称解析失败: " + e.getMessage());
            }
        }
        return names;
    }

    /** Public CDN layout used when the API omits {@code downloadUrl}. */
    public static String cdnUrl(long fileId, String fileName) {
        String first = String.valueOf(fileId / 1000);
        String second = String.format(Locale.ROOT, "%03d", fileId % 1000);
        String encoded;
        try {
            encoded = URLEncoder.encode(fileName == null ? "" : fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            encoded = "";
        }
        return "https://mediafilez.forgecdn.net/files/" + first + "/" + second + "/" + encoded;
    }

    public static int classId(ResourceKind kind) {
        return switch (kind) {
            case MOD -> 6;
            case RESOURCE_PACK -> 12;
            case MODPACK -> 4471;
            case SHADER -> 6552;
        };
    }

    public static int loaderType(String loader) {
        return switch (ResourceVersion.normalizeLoader(loader)) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> 0;
        };
    }

    private static ResourceVersion parseFile(String projectId, JsonObject file) {
        long id = lng(file, "id");
        String fileName = str(file, "fileName");
        if (id <= 0 || fileName.isBlank()) {
            return null;
        }
        String downloadUrl = str(file, "downloadUrl");
        if (downloadUrl.isBlank()) {
            downloadUrl = cdnUrl(id, fileName);
        }
        JsonObject hashes = object(file, "hashes");
        String sha1 = hashes == null ? "" : str(hashes, "sha1");
        List<String> gameVersions = new ArrayList<>();
        List<String> loaders = new ArrayList<>();
        JsonArray values = array(file, "gameVersions");
        if (values != null) {
            for (String value : stringList(values)) {
                if (looksLikeGameVersion(value)) {
                    gameVersions.add(value);
                } else if (LOADERS.contains(value.toLowerCase(Locale.ROOT))) {
                    loaders.add(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        List<ResourceDependency> dependencies = new ArrayList<>();
        JsonArray deps = array(file, "dependencies");
        if (deps != null) {
            for (JsonElement element : deps) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject dep = element.getAsJsonObject();
                long modId = lng(dep, "modId");
                if (modId > 0) {
                    dependencies.add(new ResourceDependency(ResourceSource.CURSEFORGE,
                            String.valueOf(modId), "", DependencyType.fromCurseForge(
                            (int) lng(dep, "relationType")), ""));
                }
            }
        }
        String displayName = str(file, "displayName");
        if (displayName.isBlank()) {
            displayName = fileName;
        }
        int releaseType = (int) lng(file, "releaseType");
        String channel = switch (releaseType) {
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "release";
        };
        return new ResourceVersion(ResourceSource.CURSEFORGE, projectId, String.valueOf(id),
                displayName, displayName, channel, gameVersions, loaders,
                fileName, downloadUrl, sha1, lng(file, "fileLength"),
                time(str(file, "fileDate")), dependencies);
    }

    private static RemoteFile remoteFile(JsonObject file) throws IOException {
        if (file == null) {
            throw new IOException("CurseForge 未返回文件信息");
        }
        long id = lng(file, "id");
        String fileName = str(file, "fileName");
        if (id <= 0 || fileName.isBlank()) {
            throw new IOException("CurseForge 文件信息不完整");
        }
        String url = str(file, "downloadUrl");
        if (url.isBlank()) {
            url = cdnUrl(id, fileName);
        }
        JsonObject hashes = object(file, "hashes");
        String sha1 = hashes == null ? "" : str(hashes, "sha1");
        return new RemoteFile(fileName, url, sha1, lng(file, "fileLength"));
    }

    private static Map<String, String> headers() throws IOException {
        String key;
        try {
            key = CurseForgeKey.apiKey();
        } catch (RuntimeException e) {
            throw new IOException("内置 CurseForge API Key 不可用，请重新安装启动器。", e);
        }
        if (key == null || key.isBlank()) {
            throw new IOException("内置 CurseForge API Key 不可用，请重新安装启动器。");
        }
        return Map.of("x-api-key", key.trim(), "Accept", "application/json");
    }

    private static IOException friendly(Http.HttpException e) {
        if (e.status == 401 || e.status == 403) {
            return new IOException("CurseForge 拒绝了内置 API Key（可能已失效或额度用尽），请更新启动器。", e);
        }
        if (e.status == 429) {
            return new IOException("CurseForge 请求过于频繁，请稍后再试。", e);
        }
        return e;
    }

    private static ResourceKind kindFromClassId(int classId, ResourceKind fallback) {
        return switch (classId) {
            case 6 -> ResourceKind.MOD;
            case 12 -> ResourceKind.RESOURCE_PACK;
            case 6552 -> ResourceKind.SHADER;
            case 4471 -> ResourceKind.MODPACK;
            default -> fallback;
        };
    }

    private static String slugPath(ResourceKind kind) {
        return switch (kind) {
            case MOD -> "mc-mods";
            case RESOURCE_PACK -> "texture-packs";
            case SHADER -> "shaders";
            case MODPACK -> "modpacks";
        };
    }

    private static String firstAuthor(JsonObject mod) {
        JsonArray authors = array(mod, "authors");
        if (authors != null) {
            for (JsonElement element : authors) {
                if (element.isJsonObject()) {
                    String name = str(element.getAsJsonObject(), "name");
                    if (!name.isBlank()) {
                        return name;
                    }
                }
            }
        }
        return "";
    }

    private static String loaderName(int type) {
        return switch (type) {
            case 1 -> "forge";
            case 4 -> "fabric";
            case 5 -> "quilt";
            case 6 -> "neoforge";
            default -> "";
        };
    }

    private static boolean looksLikeGameVersion(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        char first = value.charAt(0);
        return Character.isDigit(first);
    }

    static JsonObject parseObject(String body) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                return root.getAsJsonObject();
            }
        } catch (Exception e) {
            throw new IOException("CurseForge 返回了无法解析的数据", e);
        }
        throw new IOException("CurseForge 返回的数据格式异常");
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

    /** Prefer the 256 px thumbnail; the full logo is often several times larger. */
    private static String logoUrl(JsonObject logo) {
        if (logo == null) {
            return "";
        }
        String thumbnail = str(logo, "thumbnailUrl");
        return thumbnail.isBlank() ? str(logo, "url") : thumbnail;
    }

    private static long lng(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static List<String> stringList(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (JsonElement element : array) {
            try {
                if (element != null && !element.isJsonNull()) {
                    values.add(element.getAsString());
                }
            } catch (Exception ignored) {
                // Skip malformed entries.
            }
        }
        return values;
    }

    private static long time(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception ignored) {
            // Fall through to the offset-aware parser.
        }
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }
}
