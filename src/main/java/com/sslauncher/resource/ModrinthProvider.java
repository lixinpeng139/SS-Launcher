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
 * Keyless client for the public Modrinth v2 API.
 *
 * <p>Search, version listings and dependency metadata all work without an API
 * key. Download URLs point directly at Modrinth's CDN.</p>
 */
public final class ModrinthProvider {
    private static final String BASE = "https://api.modrinth.com/v2";
    private static final Map<String, String> HEADERS = Map.of("Accept", "application/json");
    private static final Set<String> LOADERS = Set.of("fabric", "quilt", "forge", "neoforge",
            "liteloader", "modloader", "rift", "risugami");
    private static final Set<String> QUILT_LOADERS = Set.of("quilt", "fabric");

    private ModrinthProvider() {
    }

    public static List<ResourceProject> search(SearchQuery query)
            throws IOException, InterruptedException {
        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:" + query.kind().modrinthType + "\"]");
        if (!query.gameVersion().isBlank()) {
            facets.add("[\"versions:" + escapeJson(query.gameVersion()) + "\"]");
        }
        if (query.kind().needsLoader() && !query.loader().isBlank()) {
            List<String> loaders = loaderFacets(query.loader());
            if (!loaders.isEmpty()) {
                StringBuilder group = new StringBuilder("[");
                for (int i = 0; i < loaders.size(); i++) {
                    if (i > 0) {
                        group.append(',');
                    }
                    group.append("\"categories:").append(escapeJson(loaders.get(i))).append('"');
                }
                group.append(']');
                facets.add(group.toString());
            }
        }
        String facetJson = "[" + String.join(",", facets) + "]";
        String index = switch (query.sort()) {
            case "downloads" -> "downloads";
            case "updated" -> "updated";
            case "follows" -> "follows";
            default -> "relevance";
        };
        String url = BASE + "/search?query=" + encode(query.text())
                + "&facets=" + encode(facetJson)
                + "&index=" + index
                + "&limit=" + query.limit()
                + "&offset=" + query.offset();
        JsonObject root = parseObject(Http.get(url, HEADERS));
        JsonArray hits = array(root, "hits");
        List<ResourceProject> projects = new ArrayList<>();
        if (hits == null) {
            return projects;
        }
        for (JsonElement element : hits) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject hit = element.getAsJsonObject();
            String id = str(hit, "project_id");
            if (id.isBlank()) {
                id = str(hit, "id");
            }
            if (id.isBlank()) {
                continue;
            }
            ResourceKind kind = ResourceKind.fromModrinthType(str(hit, "project_type"));
            String slug = str(hit, "slug");
            Set<String> versions = new LinkedHashSet<>(stringList(array(hit, "versions")));
            Set<String> categories = new LinkedHashSet<>(stringList(array(hit, "categories")));
            Set<String> loaders = new LinkedHashSet<>();
            for (String category : categories) {
                if (LOADERS.contains(category.toLowerCase(Locale.ROOT))) {
                    loaders.add(category.toLowerCase(Locale.ROOT));
                }
            }
            String pageUrl = "https://modrinth.com/" + kind.pageSegment + "/"
                    + encodePath(slug.isBlank() ? id : slug);
            projects.add(new ResourceProject(ResourceSource.MODRINTH, id, slug,
                    str(hit, "title"), str(hit, "author"), str(hit, "description"),
                    str(hit, "icon_url"), pageUrl,
                    lng(hit, "downloads"), lng(hit, "follows"), time(str(hit, "date_modified")),
                    versions, loaders, kind));
        }
        return projects;
    }

    /**
     * Lists files for a project, optionally narrowed to one game version and
     * loader. Modrinth applies both filters server-side.
     */
    public static List<ResourceVersion> versions(String projectId, ResourceKind kind,
                                                 String gameVersion, String loader)
            throws IOException, InterruptedException {
        List<String> params = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank()) {
            params.add("game_versions=" + encode("[\"" + escapeJson(gameVersion) + "\"]"));
        }
        List<String> loaders = loaderFacets(loader);
        if (kind != null && kind.needsLoader() && !loaders.isEmpty()) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < loaders.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('"').append(escapeJson(loaders.get(i))).append('"');
            }
            json.append(']');
            params.add("loaders=" + encode(json.toString()));
        }
        String url = BASE + "/project/" + encodePath(projectId) + "/version";
        if (!params.isEmpty()) {
            url += "?" + String.join("&", params);
        }
        JsonElement root = JsonParser.parseString(Http.get(url, HEADERS));
        if (!root.isJsonArray()) {
            return List.of();
        }
        List<ResourceVersion> versions = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            JsonObject file = primaryFile(array(version, "files"));
            if (file == null) {
                continue;
            }
            String fileName = str(file, "filename");
            String downloadUrl = str(file, "url");
            if (downloadUrl.isBlank() || fileName.isBlank()) {
                continue;
            }
            JsonObject hashes = object(file, "hashes");
            String sha1 = hashes == null ? "" : str(hashes, "sha1");
            List<ResourceDependency> dependencies = new ArrayList<>();
            JsonArray deps = array(version, "dependencies");
            if (deps != null) {
                for (JsonElement depElement : deps) {
                    if (!depElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject dep = depElement.getAsJsonObject();
                    String depProject = str(dep, "project_id");
                    if (depProject.isBlank()) {
                        continue;
                    }
                    dependencies.add(new ResourceDependency(ResourceSource.MODRINTH,
                            depProject, str(dep, "version_id"),
                            DependencyType.fromModrinth(str(dep, "dependency_type")), ""));
                }
            }
            versions.add(new ResourceVersion(ResourceSource.MODRINTH, projectId,
                    str(version, "id"), str(version, "name"), str(version, "version_number"),
                    str(version, "version_type"),
                    stringList(array(version, "game_versions")),
                    stringList(array(version, "loaders")),
                    fileName, downloadUrl, sha1, lng(file, "size"),
                    time(str(version, "date_published")), dependencies));
        }
        versions.sort(Comparator.comparingLong(ResourceVersion::publishedAt).reversed());
        return versions;
    }

    /** Resolves dependency project ids to readable titles in one request. */
    public static Map<String, String> resolveProjectNames(Collection<String> projectIds) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String id : projectIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        List<String> all = new ArrayList<>(ids);
        for (int start = 0; start < all.size(); start += 100) {
            List<String> chunk = all.subList(start, Math.min(all.size(), start + 100));
            String json = chunk.stream().map(id -> "\"" + escapeJson(id) + "\"")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            try {
                JsonElement root = JsonParser.parseString(Http.get(
                        BASE + "/projects?ids=" + encode(json), HEADERS));
                if (!root.isJsonArray()) {
                    continue;
                }
                for (JsonElement element : root.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject project = element.getAsJsonObject();
                    String id = str(project, "id");
                    String title = str(project, "title");
                    if (!id.isBlank() && !title.isBlank()) {
                        names.put(id, title);
                    }
                }
            } catch (Exception e) {
                Log.warn("Modrinth 前置名称解析失败: " + e.getMessage());
            }
        }
        return names;
    }

    private static List<String> loaderFacets(String loader) {
        String normalized = ResourceVersion.normalizeLoader(loader);
        if (normalized.isBlank()) {
            return List.of();
        }
        if ("quilt".equals(normalized)) {
            return new ArrayList<>(QUILT_LOADERS);
        }
        return List.of(normalized);
    }

    private static JsonObject primaryFile(JsonArray files) {
        if (files == null) {
            return null;
        }
        JsonObject first = null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            if (first == null) {
                first = file;
            }
            if (bool(file, "primary")) {
                return file;
            }
        }
        if (first != null && !str(first, "url").isBlank()) {
            return first;
        }
        return null;
    }

    static JsonObject parseObject(String body) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                return root.getAsJsonObject();
            }
        } catch (Exception e) {
            throw new IOException("Modrinth 返回了无法解析的数据", e);
        }
        throw new IOException("Modrinth 返回的数据格式异常");
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

    static long time(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception ignored) {
            // Modrinth normally returns Z-suffixed ISO timestamps.
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

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
