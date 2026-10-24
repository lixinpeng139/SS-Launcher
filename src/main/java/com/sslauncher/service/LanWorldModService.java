package com.sslauncher.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;
import com.sslauncher.core.ZipUtil;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.resource.ContentInstallService;
import com.sslauncher.resource.DependencyType;
import com.sslauncher.resource.ModrinthProvider;
import com.sslauncher.resource.ResourceDependency;
import com.sslauncher.resource.ResourceKind;
import com.sslauncher.resource.ResourceProject;
import com.sslauncher.resource.ResourceService;
import com.sslauncher.resource.ResourceSource;
import com.sslauncher.resource.ResourceVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects and installs the LAN World Plug-n-Play mod (Modrinth project
 * {@code mcwifipnp}), which lets a host turn off online-mode verification for
 * a LAN world so players without a Mojang account can join.
 */
public final class LanWorldModService {
    public static final String PROJECT_ID = "RTWpcTBp";
    public static final String PROJECT_SLUG = "mcwifipnp";
    public static final String TITLE = "LAN World Plug-n-Play";
    public static final String MOD_ID = "mcwifipnp";

    /** Fabric API is declared as a required dependency of the Fabric builds. */
    private static final Map<String, Set<String>> DEPENDENCY_MOD_IDS = Map.of(
            "P7dR8mSH", Set.of("fabric_api"),
            "qvIfYCYJ", Set.of("quilted_fabric_api", "qfapi"));
    private static final Map<String, String> DEPENDENCY_TITLES = Map.of(
            "P7dR8mSH", "Fabric API",
            "qvIfYCYJ", "Quilted Fabric API");

    /**
     * Loaders offered when the instance is vanilla. Quilt is deliberately left
     * out: it can load Fabric mods, and offering four options for a niche
     * loader just makes the choice harder.
     */
    private static final List<LoaderType> OFFERED_LOADERS = List.of(
            LoaderType.FABRIC, LoaderType.FORGE, LoaderType.NEOFORGE);

    private static final Pattern TOML_MOD_ID = Pattern.compile(
            "(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");

    private LanWorldModService() {
    }

    /** What the dialog needs to know before offering the install button. */
    public record Plan(String gameVersion, List<LoaderType> loaders) {
        public boolean installable() {
            return !loaders.isEmpty();
        }
    }

    public record InstallResult(LoaderType loader, List<String> files) {
    }

    // --------------------------------------------------------------- detection

    /** True when an enabled mod jar in the instance's mods folder is mcwifipnp. */
    public static boolean isInstalled(Instance instance) {
        return instance != null && isInstalled(instance.gameDir().resolve("mods"));
    }

    public static boolean isInstalled(Path modsDir) {
        if (installedModIds(modsDir).contains(MOD_ID)) {
            return true;
        }
        for (Path jar : enabledJars(modsDir)) {
            String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains(MOD_ID)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads mod ids from Fabric, Quilt, Forge and NeoForge metadata. Filename
     * checks are only a fallback, because players often rename jars.
     */
    public static Set<String> installedModIds(Path modsDir) {
        Set<String> ids = new LinkedHashSet<>();
        for (Path jar : enabledJars(modsDir)) {
            ids.addAll(readModIds(jar));
        }
        return ids;
    }

    private static List<Path> enabledJars(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> stream = Files.list(modsDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jar") && Files.isRegularFile(path)) {
                    jars.add(path);
                }
            }
        } catch (IOException e) {
            Log.warn("读取模组目录失败 " + modsDir + ": " + e.getMessage());
        }
        return jars;
    }

    private static Set<String> readModIds(Path jar) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            String fabric = textEntry(jar, "fabric.mod.json");
            if (fabric != null) {
                JsonObject root = parse(fabric);
                String id = string(root, "id");
                if (!id.isBlank()) {
                    ids.add(normalizeId(id));
                }
            }
            String quilt = textEntry(jar, "quilt.mod.json");
            if (quilt != null) {
                JsonObject root = parse(quilt);
                String id = string(root, "id");
                JsonObject loader = object(root, "quilt_loader");
                if (id.isBlank() && loader != null) {
                    id = string(loader, "id");
                }
                if (!id.isBlank()) {
                    ids.add(normalizeId(id));
                }
            }
            for (String entry : new String[]{"META-INF/mods.toml",
                    "META-INF/neoforge.mods.toml"}) {
                String toml = textEntry(jar, entry);
                if (toml == null) {
                    continue;
                }
                Matcher matcher = TOML_MOD_ID.matcher(toml);
                while (matcher.find()) {
                    ids.add(normalizeId(matcher.group(1)));
                }
            }
        } catch (RuntimeException e) {
            // A broken or non-mod jar must not hide the rest of the folder.
            Log.warn("无法读取模组信息 " + jar.getFileName() + ": " + e.getMessage());
        }
        return ids;
    }

    private static String textEntry(Path jar, String entry) {
        try {
            byte[] bytes = ZipUtil.readEntry(jar, entry);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonObject parse(String text) {
        JsonElement root = JsonParser.parseString(text);
        return root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Mod ids are case-insensitive; unify separators so qfapi matches QFAPI. */
    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    // ------------------------------------------------------------- resolution

    /**
     * Resolves the base game version and the loaders this instance can use.
     * For a vanilla instance only the common loaders with a compatible mod
     * build are returned; for a modded instance only its current loader.
     */
    public static Plan plan(Instance instance) throws IOException, InterruptedException {
        if (instance == null) {
            throw new IOException("请选择要安装的实例");
        }
        String gameVersion = ResourceService.instanceGameVersion(instance);
        if (gameVersion.isBlank()) {
            throw new IOException("无法识别实例的基础 Minecraft 版本，请重新安装该版本");
        }
        List<LoaderType> supported = availableLoaders(gameVersion);
        LoaderType current = instance.loaderType();
        if (current != LoaderType.VANILLA) {
            return new Plan(gameVersion, supported.contains(current)
                    ? List.of(current) : List.of());
        }
        List<LoaderType> offered = new ArrayList<>();
        for (LoaderType type : OFFERED_LOADERS) {
            if (supported.contains(type)) {
                offered.add(type);
            }
        }
        return new Plan(gameVersion, offered);
    }

    /** Loaders that have at least one mcwifipnp build for this game version. */
    public static List<LoaderType> availableLoaders(String gameVersion)
            throws IOException, InterruptedException {
        List<ResourceVersion> versions = ModrinthProvider.versions(PROJECT_ID,
                ResourceKind.MOD, gameVersion, "");
        Set<LoaderType> found = new LinkedHashSet<>();
        for (ResourceVersion version : versions) {
            for (String loader : version.loaders()) {
                LoaderType type = LoaderType.fromId(loader);
                if (type != LoaderType.VANILLA) {
                    found.add(type);
                }
            }
        }
        List<LoaderType> ordered = new ArrayList<>();
        for (LoaderType type : List.of(LoaderType.FABRIC, LoaderType.FORGE,
                LoaderType.NEOFORGE, LoaderType.QUILT)) {
            if (found.contains(type)) {
                ordered.add(type);
            }
        }
        return ordered;
    }

    /**
     * Chooses the newest compatible file. Modrinth may return Fabric builds
     * when Quilt is requested (Quilt loads Fabric mods), so an exact loader
     * match wins over the chronology.
     */
    static ResourceVersion pickVersion(List<ResourceVersion> versions, LoaderType loader) {
        ResourceVersion fallback = null;
        for (ResourceVersion version : versions) {
            if (version.loaders() != null && version.loaders().stream()
                    .anyMatch(value -> loader.id.equals(
                            ResourceVersion.normalizeLoader(value)))) {
                return version;
            }
            if (loader == LoaderType.QUILT && version.supportsLoader("fabric")) {
                if (fallback == null) {
                    fallback = version;
                }
            } else if (fallback == null) {
                fallback = version;
            }
        }
        return fallback;
    }

    private static ResourceVersion findVersion(String projectId, String gameVersion,
                                               LoaderType loader)
            throws IOException, InterruptedException {
        List<ResourceVersion> versions = ModrinthProvider.versions(projectId,
                ResourceKind.MOD, gameVersion, loader.id);
        return pickVersion(versions, loader);
    }

    // ---------------------------------------------------------------- install

    /**
     * Installs a loader when the instance is vanilla, then the mod and any
     * missing required dependency (Fabric API). Blocking; run off the FX thread.
     */
    public static InstallResult install(Instance instance, LoaderType loader,
                                        Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (instance == null) {
            throw new IOException("请选择要安装的实例");
        }
        String gameVersion = ResourceService.instanceGameVersion(instance);
        if (gameVersion.isBlank()) {
            throw new IOException("无法识别实例的基础 Minecraft 版本，请重新安装该版本");
        }

        LoaderType actual = instance.loaderType();
        if (actual == LoaderType.VANILLA) {
            if (loader == null || loader == LoaderType.VANILLA) {
                throw new IOException("该实例是原版，请先选择并安装一个模组加载器");
            }
            progress.setStage("获取 " + loader.label + " 版本");
            List<LoaderService.LoaderVersion> versions = LoaderService.versions(loader, gameVersion);
            if (versions.isEmpty()) {
                throw new IOException("Minecraft " + gameVersion + " 没有可用的 " + loader.label + " 版本");
            }
            LoaderService.LoaderVersion chosen = versions.stream()
                    .filter(LoaderService.LoaderVersion::recommended)
                    .findFirst().orElse(versions.get(0));
            checkCancelled(cancelled, progress);
            progress.setStage("安装 " + loader.label + " " + chosen.id());
            String versionId = InstallService.installLoader(loader, gameVersion, chosen.id(),
                    progress, cancelled);
            checkCancelled(cancelled, progress);
            instance.versionId = versionId;
            instance.loader = loader.id;
            InstanceStore.get().save();
            ResourceService.clearCache();
            actual = loader;
            Log.info("联机模组：实例 " + instance.name + " 已切换为 " + loader.label
                    + "（" + versionId + "）");
        }

        progress.setStage("查找联机模组版本");
        ResourceVersion mod = findVersion(PROJECT_ID, gameVersion, actual);
        if (mod == null) {
            throw new IOException("Minecraft " + gameVersion + " 没有兼容 " + actual.label
                    + " 的 " + TITLE + " 版本");
        }
        checkCancelled(cancelled, progress);

        List<String> files = new ArrayList<>();
        installFile(project(PROJECT_ID, PROJECT_SLUG, TITLE), mod, instance, progress, cancelled);
        files.add(mod.fileName());
        Log.info("联机模组：已安装 " + mod.fileName() + " 到 " + instance.name);

        for (ResourceDependency dependency : mod.dependencies()) {
            if (dependency.type() != DependencyType.REQUIRED) {
                continue;
            }
            String projectId = dependency.projectId();
            if (projectId == null || projectId.isBlank()
                    || dependencyPresent(instance, projectId)) {
                continue;
            }
            checkCancelled(cancelled, progress);
            String title = DEPENDENCY_TITLES.getOrDefault(projectId, projectId);
            progress.setStage("查找前置 " + title);
            ResourceVersion dependencyVersion = findVersion(projectId, gameVersion, actual);
            if (dependencyVersion == null) {
                throw new IOException("联机模组需要前置 " + title
                        + "，但没有找到兼容的版本");
            }
            installFile(project(projectId, projectId, title), dependencyVersion,
                    instance, progress, cancelled);
            files.add(dependencyVersion.fileName());
            Log.info("联机模组：已安装前置 " + dependencyVersion.fileName());
        }

        progress.setStage("联机模组安装完成");
        progress.setIndeterminate(false);
        return new InstallResult(actual, files);
    }

    private static void installFile(ResourceProject project, ResourceVersion version,
                                    Instance instance, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path downloaded = ResourceService.download(project, version, progress, cancelled);
        checkCancelled(cancelled, progress);
        ContentInstallService.installResource(instance, ResourceKind.MOD, downloaded,
                progress, cancelled);
        checkCancelled(cancelled, progress);
    }

    private static void checkCancelled(BooleanSupplier cancelled, Progress progress)
            throws InterruptedException {
        if ((cancelled != null && cancelled.getAsBoolean()) || progress.isCancelled()) {
            throw new InterruptedException("已取消");
        }
    }

    private static boolean dependencyPresent(Instance instance, String projectId) {
        Set<String> wanted = DEPENDENCY_MOD_IDS.get(projectId);
        if (wanted == null) {
            return false;
        }
        Path modsDir = instance.gameDir().resolve("mods");
        Set<String> ids = installedModIds(modsDir);
        if (!Collections.disjoint(ids, wanted)) {
            return true;
        }
        for (Path jar : enabledJars(modsDir)) {
            String name = jar.getFileName().toString().toLowerCase(Locale.ROOT)
                    .replace('-', '_');
            for (String id : wanted) {
                if (name.contains(id.replace('-', '_'))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ResourceProject project(String projectId, String slug, String title) {
        return new ResourceProject(ResourceSource.MODRINTH, projectId, slug, title, "",
                "", "", "https://modrinth.com/mod/" + slug, 0, 0, 0,
                Set.of(), Set.of(), ResourceKind.MOD);
    }
}
