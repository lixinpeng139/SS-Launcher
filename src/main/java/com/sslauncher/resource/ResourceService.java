package com.sslauncher.resource;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.core.Mirror;
import com.sslauncher.core.Progress;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.model.VersionMeta;
import com.sslauncher.service.VersionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Aggregates Modrinth and CurseForge, resolves dependencies and downloads files. */
public final class ResourceService {
    private static final int PAGE_LIMIT = 20;
    private static final Pattern GAME_VERSION = Pattern.compile("(1\\.\\d+(?:\\.\\d+)?)");
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ss-resource-api");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, String> GAME_VERSION_CACHE = new ConcurrentHashMap<>();

    private ResourceService() {
    }

    public record SearchResult(List<ResourceProject> projects, List<String> notices,
                               boolean hasMore) {
    }

    public record Compatibility(boolean gameVersionOk, boolean loaderOk) {
        public boolean ok() {
            return gameVersionOk && loaderOk;
        }

        public String label() {
            if (gameVersionOk && loaderOk) {
                return "兼容";
            }
            if (!gameVersionOk && !loaderOk) {
                return "版本与加载器都不匹配";
            }
            return gameVersionOk ? "加载器不匹配" : "游戏版本不匹配";
        }
    }

    public static SearchResult search(SearchQuery query) throws IOException, InterruptedException {
        if (query.source() == SourceFilter.CURSEFORGE && !CurseForgeProvider.isConfigured()) {
            throw new IOException("内置 CurseForge 配置不可用，请重新安装启动器。");
        }
        List<String> notices = new ArrayList<>();
        boolean wantModrinth = query.source() != SourceFilter.CURSEFORGE;
        boolean wantCurseForge = query.source() != SourceFilter.MODRINTH
                && CurseForgeProvider.isConfigured();
        if (query.source() == SourceFilter.ALL && !CurseForgeProvider.isConfigured()) {
            notices.add("内置 CurseForge 配置不可用，本次只搜索 Modrinth。");
        }

        Future<List<ResourceProject>> modrinthFuture = wantModrinth
                ? POOL.submit(() -> ModrinthProvider.search(query)) : null;
        Future<List<ResourceProject>> curseForgeFuture = wantCurseForge
                ? POOL.submit(() -> CurseForgeProvider.search(query)) : null;

        List<ResourceProject> modrinth = new ArrayList<>();
        List<ResourceProject> curseForge = new ArrayList<>();
        IOException firstError = null;

        if (modrinthFuture != null) {
            try {
                modrinth = modrinthFuture.get();
            } catch (InterruptedException e) {
                cancel(modrinthFuture);
                throw e;
            } catch (ExecutionException e) {
                firstError = asIOException(e);
                notices.add("Modrinth 搜索失败：" + firstError.getMessage());
                Log.warn("Modrinth 搜索失败: " + firstError.getMessage());
            }
        }
        if (curseForgeFuture != null) {
            try {
                curseForge = curseForgeFuture.get();
            } catch (InterruptedException e) {
                cancel(curseForgeFuture);
                throw e;
            } catch (ExecutionException e) {
                IOException error = asIOException(e);
                if (firstError == null) {
                    firstError = error;
                }
                notices.add("CurseForge 搜索失败：" + error.getMessage());
                Log.warn("CurseForge 搜索失败: " + error.getMessage());
            }
        }

        if (firstError != null && modrinth.isEmpty() && curseForge.isEmpty()) {
            throw firstError;
        }

        List<ResourceProject> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int max = Math.max(modrinth.size(), curseForge.size());
        for (int i = 0; i < max; i++) {
            if (i < modrinth.size()) {
                addUnique(merged, seen, modrinth.get(i));
            }
            if (i < curseForge.size()) {
                addUnique(merged, seen, curseForge.get(i));
            }
        }
        boolean hasMore = modrinth.size() >= query.limit() || curseForge.size() >= query.limit();
        return new SearchResult(merged, notices, hasMore);
    }

    /**
     * Fetches file versions and resolves readable names for their prerequisites.
     */
    public static List<ResourceVersion> versions(ResourceProject project, String gameVersion,
                                                 String loader)
            throws IOException, InterruptedException {
        if (project == null) {
            return List.of();
        }
        List<ResourceVersion> versions = switch (project.source()) {
            case MODRINTH -> ModrinthProvider.versions(project.id(), project.kind(),
                    gameVersion, loader);
            case CURSEFORGE -> CurseForgeProvider.versions(project.id(), project.kind(),
                    gameVersion, loader);
        };
        Set<String> dependencyIds = new LinkedHashSet<>();
        for (ResourceVersion version : versions) {
            for (ResourceDependency dependency : version.dependencies()) {
                if (dependency.projectId() != null && !dependency.projectId().isBlank()) {
                    dependencyIds.add(dependency.projectId());
                }
            }
        }
        if (dependencyIds.isEmpty()) {
            return versions;
        }
        Map<String, String> names = switch (project.source()) {
            case MODRINTH -> ModrinthProvider.resolveProjectNames(dependencyIds);
            case CURSEFORGE -> CurseForgeProvider.resolveModNames(dependencyIds);
        };
        if (names.isEmpty()) {
            return versions;
        }
        List<ResourceVersion> resolved = new ArrayList<>(versions.size());
        for (ResourceVersion version : versions) {
            List<ResourceDependency> dependencies = new ArrayList<>();
            for (ResourceDependency dependency : version.dependencies()) {
                String name = names.getOrDefault(dependency.projectId(), "");
                dependencies.add(dependency.withName(name));
            }
            resolved.add(version.withDependencies(dependencies));
        }
        return resolved;
    }

    /**
     * The base Minecraft version of an instance: the last non-inheriting
     * version in the loader profile chain, with a regex fallback for ids such
     * as {@code fabric-loader-0.15.11-1.21.1}.
     */
    public static String instanceGameVersion(Instance instance) {
        if (instance == null) {
            return "";
        }
        String cached = GAME_VERSION_CACHE.get(instance.versionId);
        if (cached != null) {
            return cached;
        }
        String result = "";
        try {
            VersionService.ResolvedVersion resolved = VersionService.load(instance.versionId);
            for (int i = resolved.chain.size() - 1; i >= 0; i--) {
                String id = resolved.chain.get(i);
                Path json = VersionService.versionJsonPath(id);
                if (!Files.exists(json)) {
                    continue;
                }
                VersionMeta meta = Json.read(json, VersionMeta.class);
                if (meta != null && (meta.inheritsFrom == null || meta.inheritsFrom.isBlank())) {
                    result = meta.id == null || meta.id.isBlank() ? id : meta.id;
                    break;
                }
            }
        } catch (Exception e) {
            Log.warn("无法解析实例 " + instance.versionId + " 的基础版本: " + e.getMessage());
        }
        if (result.isBlank()) {
            Matcher matcher = GAME_VERSION.matcher(instance.versionId == null ? "" : instance.versionId);
            if (matcher.find()) {
                result = matcher.group(1);
            } else if (instance.loaderType() == LoaderType.VANILLA
                    && instance.versionId != null
                    && instance.versionId.matches("\\d{2}\\.\\d+(?:\\.\\d+)?")) {
                result = instance.versionId;
            }
        }
        if (!result.isBlank()) {
            GAME_VERSION_CACHE.put(instance.versionId, result);
        }
        return result;
    }

    public static String instanceLoader(Instance instance) {
        if (instance == null) {
            return "";
        }
        LoaderType type = instance.loaderType();
        return type == LoaderType.VANILLA ? "" : type.id;
    }

    public static Compatibility compatibility(Instance instance, ResourceVersion version,
                                              ResourceKind kind) {
        if (instance == null || version == null) {
            return new Compatibility(true, true);
        }
        String gameVersion = instanceGameVersion(instance);
        boolean gameOk = gameVersion.isBlank() || version.supportsGameVersion(gameVersion);
        boolean loaderOk = true;
        if (kind == ResourceKind.MOD) {
            if (instance.loaderType() == LoaderType.VANILLA) {
                loaderOk = false;
            } else {
                loaderOk = version.loaders() == null || version.loaders().isEmpty()
                        || version.supportsLoader(instance.loaderType().id);
            }
        } else if (kind == ResourceKind.MODPACK) {
            loaderOk = version.loaders() == null || version.loaders().isEmpty()
                    || version.supportsLoader(instance.loaderType().id);
        }
        return new Compatibility(gameOk, loaderOk);
    }

    /** Compatibility of a search hit, using the metadata included in search results. */
    public static Compatibility projectCompatibility(Instance instance, ResourceProject project) {
        if (instance == null || project == null) {
            return new Compatibility(true, true);
        }
        String gameVersion = instanceGameVersion(instance);
        boolean gameOk = gameVersion.isBlank() || project.supportsGameVersion(gameVersion);
        boolean loaderOk = true;
        if (project.kind() == ResourceKind.MOD) {
            if (instance.loaderType() == LoaderType.VANILLA) {
                loaderOk = false;
            } else {
                loaderOk = project.loaders() == null || project.loaders().isEmpty()
                        || project.supportsLoader(instance.loaderType().id);
            }
        } else if (project.kind() == ResourceKind.MODPACK) {
            loaderOk = project.loaders() == null || project.loaders().isEmpty()
                    || project.supportsLoader(instance.loaderType().id);
        }
        return new Compatibility(gameOk, loaderOk);
    }

    /** Downloads a chosen file into the launcher cache and returns its path. */
    public static Path download(ResourceProject project, ResourceVersion version, Progress progress,
                                BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (project == null || version == null) {
            throw new IOException("没有选择要下载的资源");
        }
        if (version.downloadUrl() == null || version.downloadUrl().isBlank()) {
            throw new IOException("该项目没有可用的下载地址");
        }
        String fileName = safeFileName(version.fileName(), project.title(), version.versionNumber());
        Path directory = AppPaths.CACHE.resolve("resources")
                .resolve(project.source().id())
                .resolve(safeSegment(project.id(), "project"))
                .resolve(safeSegment(version.versionId(), "version"));
        Path destination = directory.resolve(fileName);
        progress.setStage("下载 " + project.title());
        progress.setIndeterminate(false);
        progress.setDetail(version.name().isBlank() ? fileName : version.name());
        Downloader.Item item = new Downloader.Item(project.title() + " " + version.versionNumber(),
                Mirror.resourceCandidates(version.downloadUrl()), destination,
                version.sha1(), version.size());
        // One file is in flight, so the thread budget becomes parallel chunks
        // of that file; the Modrinth CDN throttles every single connection.
        Downloader.downloadAll(List.of(item), progress,
                Math.max(4, Math.min(16, Config.get().downloadThreads)), cancelled);
        progress.setStage("下载完成");
        return destination;
    }

    public static int pageLimit() {
        return PAGE_LIMIT;
    }

    public static String safeFileName(String raw, String fallbackTitle, String fallbackVersion) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").trim();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            String stem = (fallbackTitle == null || fallbackTitle.isBlank() ? "resource" : fallbackTitle)
                    .replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").trim();
            String suffix = fallbackVersion == null || fallbackVersion.isBlank()
                    ? "" : "-" + fallbackVersion.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").trim();
            name = (stem.isBlank() ? "resource" : stem) + suffix + ".jar";
        }
        if (name.length() > 140) {
            String extension = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 10) {
                extension = name.substring(dot);
            }
            name = name.substring(0, Math.max(1, 140 - extension.length())) + extension;
        }
        return name;
    }

    private static String safeSegment(String value, String fallback) {
        String segment = value == null ? "" : value
                .replaceAll("[^A-Za-z0-9._-]", "_").trim();
        return segment.isBlank() ? fallback : segment;
    }

    private static void addUnique(List<ResourceProject> target, Set<String> seen,
                                  ResourceProject project) {
        if (project == null) {
            return;
        }
        String key = normalizeTitle(project.title()) + "|" + project.kind();
        if (seen.add(key)) {
            target.add(project);
        }
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private static IOException asIOException(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException(cause == null ? e.getMessage() : cause.getMessage(), cause);
    }

    private static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    /** Clears cached base versions after installing a new version. */
    public static void clearCache() {
        GAME_VERSION_CACHE.clear();
    }

    /** Finds an instance by id; used by dialogs that outlive a page refresh. */
    public static Instance instanceById(String id) {
        return InstanceStore.get().byId(id);
    }
}
