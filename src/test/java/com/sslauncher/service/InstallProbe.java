package com.sslauncher.service;

import com.sslauncher.auth.AuthService;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.Log;
import com.sslauncher.core.Http;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Progress;
import com.sslauncher.model.Account;
import com.sslauncher.model.AssetIndex;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.model.VersionMeta;
import com.sslauncher.resource.ContentInstallService;
import com.sslauncher.resource.ResourceDependency;
import com.sslauncher.resource.ResourceKind;
import com.sslauncher.resource.ResourceProject;
import com.sslauncher.resource.ResourceService;
import com.sslauncher.resource.ResourceVersion;
import com.sslauncher.resource.SearchQuery;
import com.sslauncher.resource.SourceFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Development-only end-to-end probe for the install and launch pipeline.
 * Run through the Gradle {@code e2e} task, e.g.
 * {@code ./gradlew e2e --args="install fabric 1.20.1"}.
 */
public final class InstallProbe {
    private static final List<String> GAME_LOG = new ArrayList<>();

    private InstallProbe() {
    }

    public static void main(String[] args) throws Exception {
        AppPaths.init();
        Log.init();
        Config.load();
        String source = System.getProperty("ss.source", "auto");
        Config.get().downloadSource = source;
        int threads = Integer.getInteger("ss.threads", 16);
        Config.get().downloadThreads = threads;
        System.out.println("PROBE home=" + AppPaths.ROOT + " source=" + source + " threads=" + threads);

        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "install";
        switch (mode) {
            case "list" -> list(args);
            case "verify" -> verify(args[1]);
            case "install" -> install(args);
            case "launch" -> launch(args);
            case "skin" -> skin(args);
            case "discover" -> discover(args);
            case "resource" -> resource(args);
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    private static void list(String[] args) throws Exception {
        LoaderType type = LoaderType.fromId(args[1]);
        String mc = args[2];
        List<LoaderService.LoaderVersion> versions = LoaderService.versions(type, mc);
        System.out.println("PROBE " + type + " " + mc + " versions=" + versions.size());
        for (int i = 0; i < Math.min(8, versions.size()); i++) {
            LoaderService.LoaderVersion v = versions.get(i);
            System.out.println("  " + v.id() + (v.recommended() ? " (recommended)" : ""));
        }
        if (!versions.isEmpty()) {
            String id = versions.get(0).id();
            List<String> urls = switch (type) {
                case FORGE, NEOFORGE -> LoaderService.installerCandidates(type, mc, id);
                default -> LoaderService.profileCandidates(type, mc, id);
            };
            System.out.println("  first install urls: " + urls);
        }
    }

    private static void install(String[] args) throws Exception {
        LoaderType type = LoaderType.fromId(args[1]);
        String mc = args[2];
        String loaderVersion = args.length > 3 ? args[3] : null;
        if (loaderVersion == null && type != LoaderType.VANILLA) {
            List<LoaderService.LoaderVersion> versions = LoaderService.versions(type, mc);
            if (versions.isEmpty()) {
                throw new IllegalStateException("no " + type + " versions for " + mc);
            }
            LoaderService.LoaderVersion chosen = versions.stream()
                    .filter(LoaderService.LoaderVersion::recommended).findFirst().orElse(versions.get(0));
            loaderVersion = chosen.id();
        }
        System.out.println("PROBE installing " + type + " " + mc + " loader=" + loaderVersion);
        Progress progress = progress();
        long start = System.currentTimeMillis();
        Instance instance = InstallService.install(mc, type, loaderVersion,
                "Probe " + type + " " + mc, progress, () -> false);
        System.out.println("PROBE installed instance=" + instance.id + " version=" + instance.versionId
                + " in " + (System.currentTimeMillis() - start) / 1000 + "s");
        verify(instance.versionId);
        System.out.println("PROBE_OK");
    }

    private static void launch(String[] args) throws Exception {
        String versionId = args[1];
        Instance instance = com.sslauncher.core.InstanceStore.get().all().stream()
                .filter(i -> versionId.equals(i.versionId)).findFirst().orElse(null);
        if (instance == null) {
            instance = com.sslauncher.core.InstanceStore.get().create(versionId, LoaderType.VANILLA,
                    "Probe launch " + versionId);
        }
        Account account = AuthService.loginOffline("ProbeUser");
        Progress progress = progress();
        LaunchService.Launched launched = LaunchService.launch(instance, account, progress, () -> false, line -> {
            synchronized (GAME_LOG) {
                GAME_LOG.add(line);
                if (GAME_LOG.size() > 400) {
                    GAME_LOG.remove(0);
                }
            }
            System.out.println("[game] " + line);
        });
        System.out.println("PROBE launched pid=" + launched.process().pid() + " java=" + launched.java()
                + " classpathEntries=" + launched.command().stream().filter(a -> a.equals("-cp")).count());
        boolean started = false;
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!launched.process().isAlive()) {
                System.out.println("PROBE game exited early code=" + launched.process().exitValue());
                break;
            }
            synchronized (GAME_LOG) {
                for (String line : GAME_LOG) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains("sound engine started") || lower.contains("openal initialized")
                            || lower.contains("created:") && lower.contains("minecraft:textures")
                            || lower.contains("reloading resourcemanager")
                            || lower.contains("started auth server")) {
                        started = true;
                        break;
                    }
                }
            }
            if (started) {
                break;
            }
            Thread.sleep(1000);
        }
        System.out.println("PROBE game started=" + started + " alive=" + launched.process().isAlive()
                + " log=" + launched.logFile());
        if (launched.process().isAlive()) {
            launched.process().destroy();
            launched.process().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (launched.process().isAlive()) {
                launched.process().destroyForcibly();
            }
        }
        System.out.println("PROBE game exit=" + (launched.process().isAlive() ? "still alive" : launched.process().exitValue()));
    }

    private static void verify(String versionId) throws Exception {
        VersionService.ResolvedVersion version = VersionService.load(versionId);
        System.out.println("PROBE verify " + version.id + " chain=" + version.chain
                + " mainClass=" + version.mainClass + " java=" + version.requiredJavaMajor());
        System.out.println("PROBE assets index=" + (version.assetIndex == null ? "none"
                : version.assetIndex.id + " virtual=" + version.assetIndex.virtual));

        List<Downloader.Item> items = LibraryService.downloadItems(version);
        List<String> missingLibraries = new ArrayList<>();
        for (Downloader.Item item : items) {
            if (!Files.exists(item.dest()) || (item.size() > 0 && Files.size(item.dest()) != item.size())) {
                missingLibraries.add(item.label() + " -> " + item.dest());
            }
        }
        List<Path> classpath = LibraryService.classpath(version);
        List<String> missingClasspath = classpath.stream().filter(p -> !Files.exists(p))
                .map(Path::toString).toList();
        Path jar = VersionService.versionJarPath(version.jarVersionId());
        System.out.println("PROBE libraries=" + items.size() + " classpath=" + classpath.size()
                + " missing=" + missingLibraries.size() + " missingClasspath=" + missingClasspath.size()
                + " jar=" + jar + " jarExists=" + Files.exists(jar));
        for (int i = 0; i < Math.min(5, missingLibraries.size()); i++) {
            System.out.println("  missing lib: " + missingLibraries.get(i));
        }
        for (int i = 0; i < Math.min(5, missingClasspath.size()); i++) {
            System.out.println("  missing cp: " + missingClasspath.get(i));
        }
        if (version.assetIndex != null && version.assetIndex.id != null) {
            Path indexFile = AssetService.indexFile(version.assetIndex.id);
            if (Files.exists(indexFile)) {
                AssetIndex index = AssetService.readIndex(version.assetIndex.id);
                int total = index.objects == null ? 0 : index.objects.size();
                int present = 0;
                long bytes = 0;
                if (index.objects != null) {
                    for (Map.Entry<String, AssetIndex.Asset> e : index.objects.entrySet()) {
                        AssetIndex.Asset asset = e.getValue();
                        if (asset == null || asset.hash == null) {
                            continue;
                        }
                        Path p = AssetService.objectFile(asset.hash);
                        if (Files.exists(p) && (asset.size <= 0 || Files.size(p) == asset.size)) {
                            present++;
                            bytes += Files.size(p);
                        }
                    }
                }
                System.out.println("PROBE asset objects " + present + "/" + total + " (" + bytes / 1024 / 1024 + " MB)");
            } else {
                System.out.println("PROBE asset index MISSING " + indexFile);
            }
        }
        for (VersionMeta.Library library : version.libraries) {
            if (!VersionService.rulesAllow(library.rules)) {
                continue;
            }
            String classifier = VersionService.nativeClassifier(library);
            if (classifier == null || library.downloads == null || library.downloads.classifiers == null) {
                continue;
            }
            VersionMeta.Download nativeJar = library.downloads.classifiers.get(classifier);
            if (nativeJar != null && nativeJar.path != null) {
                Path p = LibraryService.pathOf(nativeJar.path);
                if (!Files.exists(p)) {
                    System.out.println("PROBE missing native " + p);
                }
            }
        }
        System.out.println("PROBE logging=" + AssetService.ensureLoggingConfig(version, new Progress(), () -> false));
    }

    private static void discover(String[] args) throws Exception {
        String input = args.length > 1 ? args[1] : "https://littleskin.cn";
        com.sslauncher.auth.YggdrasilAuth.Endpoint endpoint =
                com.sslauncher.auth.YggdrasilAuth.discover(
                        com.sslauncher.auth.YggdrasilAuth.parseDroppedUrl(input));
        System.out.println("PROBE discovered url=" + endpoint.url() + " name=" + endpoint.name());
        System.out.println("PROBE_OK");
    }

    private static void resource(String[] args) throws Exception {
        String queryText = args.length > 1 ? args[1] : "sodium";
        Instance instance = args.length > 2
                ? InstanceStore.get().byId(args[2]) : InstanceStore.get().selected();
        if (instance == null && args.length > 2) {
            for (Instance candidate : InstanceStore.get().all()) {
                if (candidate.name.equalsIgnoreCase(args[2])) {
                    instance = candidate;
                    break;
                }
            }
        }
        System.out.println("PROBE resource instance="
                + (instance == null ? "none" : instance.id + " " + instance.name));
        String mc = instance == null ? "" : ResourceService.instanceGameVersion(instance);
        String loader = instance == null ? "" : ResourceService.instanceLoader(instance);
        System.out.println("PROBE resource filter mc=" + mc + " loader=" + loader);

        ResourceService.SearchResult result = ResourceService.search(new SearchQuery(queryText,
                ResourceKind.MOD, SourceFilter.MODRINTH, mc, loader, "downloads", 0, 8));
        System.out.println("PROBE search results=" + result.projects().size()
                + " hasMore=" + result.hasMore() + " notices=" + result.notices());
        for (int i = 0; i < Math.min(5, result.projects().size()); i++) {
            ResourceProject project = result.projects().get(i);
            System.out.println("  " + project.source() + " " + project.id() + " "
                    + project.title() + " downloads=" + project.downloads());
        }
        if (result.projects().isEmpty()) {
            System.out.println("PROBE no results");
            return;
        }
        ResourceProject project = result.projects().get(0);
        List<ResourceVersion> versions = ResourceService.versions(project, mc, loader);
        System.out.println("PROBE versions=" + versions.size());
        if (versions.isEmpty()) {
            System.out.println("PROBE no versions");
            return;
        }
        ResourceVersion chosen = versions.get(0);
        for (ResourceVersion version : versions) {
            if (instance == null || ResourceService.compatibility(instance, version,
                    ResourceKind.MOD).ok()) {
                chosen = version;
                break;
            }
        }
        System.out.println("PROBE version=" + chosen.versionNumber()
                + " file=" + chosen.fileName() + " size=" + chosen.size()
                + " game=" + chosen.gameVersionSummary(6)
                + " loaders=" + chosen.loaderSummary());
        for (ResourceDependency dependency : chosen.dependencies()) {
            System.out.println("  dep " + dependency.type() + " " + dependency.displayName()
                    + " project=" + dependency.projectId() + " version=" + dependency.versionId());
        }

        Progress progress = progress();
        Path file = ResourceService.download(project, chosen, progress, () -> false);
        String sha1 = Http.sha1(file);
        System.out.println("PROBE downloaded=" + file + " size=" + Files.size(file)
                + " sha1=" + sha1 + " expected=" + chosen.sha1()
                + " match=" + (chosen.sha1() == null || chosen.sha1().isBlank()
                || chosen.sha1().equalsIgnoreCase(sha1)));
        if (instance != null) {
            Path installed = ContentInstallService.installResource(instance, ResourceKind.MOD,
                    file, progress, () -> false);
            System.out.println("PROBE installed=" + installed + " exists="
                    + (installed != null && Files.exists(installed)));
        }
        System.out.println("PROBE_OK");
    }

    private static void skin(String[] args) throws Exception {
        String server = args.length > 1 ? args[1].trim() : Config.get().skinSiteUrl;
        if (server == null || server.isBlank()) {
            System.out.println("PROBE skin server not configured; pass it as: skin <yggdrasil-url>");
            return;
        }
        String metadata = com.sslauncher.auth.YggdrasilAuth.metadata(server);
        System.out.println("PROBE skin metadata bytes=" + metadata.length());
        System.out.println("PROBE skin serverName=" + com.sslauncher.auth.YggdrasilAuth.serverName(server));
        System.out.println("PROBE skin prefetch bytes="
                + com.sslauncher.auth.AuthlibInjector.prefetch(server).length());
        Path jar = com.sslauncher.auth.AuthlibInjector.ensureJar(progress(), () -> false);
        System.out.println("PROBE authlib-injector=" + jar + " size=" + Files.size(jar));
        System.out.println("PROBE_OK");
    }

    private static Progress progress() {
        Progress progress = new Progress();
        final String[] last = {""};
        progress.addListener(p -> {
            String stage = p.getStage();
            if (!stage.equals(last[0])) {
                last[0] = stage;
                System.out.println("PROBE stage: " + stage);
            }
        });
        return progress;
    }
}
