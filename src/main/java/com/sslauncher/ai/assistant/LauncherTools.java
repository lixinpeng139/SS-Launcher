package com.sslauncher.ai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sslauncher.ai.AiConfig;
import com.sslauncher.core.AccountStore;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Progress;
import com.sslauncher.model.Account;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.model.VersionManifest;
import com.sslauncher.resource.ContentInstallService;
import com.sslauncher.resource.DependencyType;
import com.sslauncher.resource.ResourceDependency;
import com.sslauncher.resource.ResourceKind;
import com.sslauncher.resource.ResourceProject;
import com.sslauncher.resource.ResourceService;
import com.sslauncher.resource.ResourceSource;
import com.sslauncher.resource.ResourceVersion;
import com.sslauncher.resource.SearchQuery;
import com.sslauncher.resource.SourceFilter;
import com.sslauncher.service.GameFiles;
import com.sslauncher.service.InstallService;
import com.sslauncher.service.LoaderService;
import com.sslauncher.service.VersionService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The launcher capabilities exposed to the embedded assistant. Every method
 * is a single tool; argument validation is strict because these calls arrive
 * from a model and run against launcher services outside any sandbox.
 */
public final class LauncherTools {
    public record Tool(String name, String description, JsonObject inputSchema, Handler handler) {
        public interface Handler {
            String call(JsonObject arguments) throws Exception;
        }
    }

    public static final class ToolFailure extends Exception {
        public ToolFailure(String message) {
            super(message);
        }

        public ToolFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final AssistantUi ui;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public LauncherTools(AssistantUi ui) {
        this.ui = ui == null ? AssistantUi.HEADLESS : ui;
        // The tool registry can be constructed before the main window touches
        // AppPaths (for example by a headless probe).
        AppPaths.init();
        registerStatus();
        registerInstances();
        registerGameVersions();
        registerLoaderVersions();
        registerInstallGame();
        registerSearch();
        registerContentVersions();
        registerInstallContent();
        registerInstanceFiles();
        registerLog();
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public Tool find(String name) {
        return tools.get(name);
    }

    public String call(String name, JsonObject arguments) throws Exception {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolFailure("未知工具：" + name);
        }
        return tool.handler().call(arguments == null ? new JsonObject() : arguments);
    }

    // ------------------------------------------------------------- launcher

    private void registerStatus() {
        tools.put("launcher_status", new Tool("launcher_status",
                "读取启动器当前状态：版本、数据目录、选中实例、账号昵称、内存/Java/下载设置、"
                        + "AI 模型是否已配置、实例数量与日志位置。不会返回任何密钥或令牌。",
                input(objectSchema(), null),
                args -> status()));
    }

    private String status() {
        Config config = Config.get();
        List<Instance> instances = InstanceStore.get().all();
        Instance selected = InstanceStore.get().selected();
        Account account = AccountStore.get().selected();
        AiConfig ai = AiConfig.get();
        boolean modded = selected != null && selected.loaderType() != LoaderType.VANILLA;
        StringBuilder b = new StringBuilder();
        b.append("SS Launcher ").append(launcherVersion()).append('\n');
        b.append("数据目录: ").append(AppPaths.ROOT).append('\n');
        b.append("实例: ").append(instances.size()).append(" 个");
        if (selected != null) {
            b.append("；当前选中: ").append(selected.name)
                    .append("（").append(selected.versionLabel()).append("）");
        } else {
            b.append("；当前没有选中实例");
        }
        b.append('\n');
        b.append("账号: ");
        if (account == null) {
            b.append("未登录");
        } else {
            b.append(account.name).append("（").append(account.typeLabel()).append("）");
        }
        b.append('\n');
        b.append("内存: ");
        if (config.autoMemory) {
            b.append("自动分配，游戏使用 ").append(config.effectiveMemoryMb(modded)).append(" MB")
                    .append("；手动上限 ").append(config.memoryMb).append(" MB");
        } else {
            b.append("手动 ").append(config.effectiveMemoryMb(modded)).append(" MB");
        }
        b.append('\n');
        b.append("Java: ").append(blank(config.javaPath) ? "自动检测" : config.javaPath).append('\n');
        b.append("下载: ").append(downloadSourceLabel(config.downloadSource))
                .append("；线程 ").append(config.downloadThreads).append('\n');
        b.append("AI: ").append(ai.provider().label).append(" / ").append(ai.model)
                .append(ai.isConfigured() ? "，API Key 已配置" : "，API Key 未配置")
                .append("（AI 制作与 AI 助手共用）").append('\n');
        b.append("日志: ").append(AppPaths.LAUNCHER_LOG).append('\n');
        b.append("页面: 主页、实例、文件、资源、联机、设置、账号；AI 制作在资源页右上角；AI 助手在顶部导航。");
        return b.toString();
    }

    private void registerInstances() {
        tools.put("list_instances", new Tool("list_instances",
                "列出启动器中的所有游戏实例：id、名称、Minecraft 版本、加载器、是否选中、"
                        + "版本是否已安装、模组数量、上次启动时间。",
                input(objectSchema(), null),
                args -> instances()));
    }

    private String instances() {
        List<Instance> all = InstanceStore.get().all();
        if (all.isEmpty()) {
            return "启动器里还没有游戏实例。可以用 install_game 安装一个游戏版本。";
        }
        Instance selected = InstanceStore.get().selected();
        StringBuilder b = new StringBuilder("共 ").append(all.size()).append(" 个实例：\n");
        for (Instance instance : all) {
            boolean active = selected != null && selected.id.equals(instance.id);
            int mods = GameFiles.count(instance.gameDir().resolve(GameFiles.Category.MODS.folder),
                    GameFiles.Category.MODS);
            b.append("- ").append(instance.name).append(active ? " [选中]" : "").append('\n');
            b.append("  id: ").append(instance.id).append('\n');
            b.append("  版本: Minecraft ").append(instance.versionId)
                    .append(" · ").append(instance.loaderType().label)
                    .append(" · ").append(VersionService.isInstalled(instance.versionId) ? "配置文件已安装" : "配置文件缺失")
                    .append('\n');
            b.append("  模组: ").append(mods).append(" 个；上次启动: ")
                    .append(instance.lastPlayedAt <= 0 ? "从未" : TIME.format(Instant.ofEpochMilli(instance.lastPlayedAt)))
                    .append('\n');
        }
        return b.toString().stripTrailing();
    }

    private void registerGameVersions() {
        JsonObject props = objectSchema();
        props.add("type", enumeration("版本类型：release 正式版、snapshot 快照、all 两者", "release", "snapshot", "all"));
        props.add("limit", integer("最多返回多少个版本", 20, 1, 100));
        tools.put("list_game_versions", new Tool("list_game_versions",
                "获取 Mojang 官方 Minecraft 版本清单，包含最新正式版/快照、发布时间和本机是否已安装。",
                input(props, null),
                args -> gameVersions(args)));
    }

    private String gameVersions(JsonObject args) throws Exception {
        String type = str(args, "type", "release").toLowerCase(Locale.ROOT);
        if (!Set.of("release", "snapshot", "all").contains(type)) {
            throw new ToolFailure("type 只能是 release、snapshot 或 all。");
        }
        int limit = integer(args, "limit", 20, 1, 100);
        VersionManifest manifest = VersionService.manifest();
        StringBuilder b = new StringBuilder();
        if (manifest.latest != null) {
            b.append("最新正式版: ").append(manifest.latest.release)
                    .append("；最新快照: ").append(manifest.latest.snapshot).append('\n');
        }
        b.append("版本列表（").append(type).append("，最多 ").append(limit).append(" 个）：\n");
        int count = 0;
        if (manifest.versions != null) {
            for (VersionManifest.Entry entry : manifest.versions) {
                if (entry == null || entry.id == null) {
                    continue;
                }
                if ("release".equals(type) && !entry.isRelease()) {
                    continue;
                }
                if ("snapshot".equals(type) && !entry.isSnapshot()) {
                    continue;
                }
                if ("all".equals(type) && !entry.isRelease() && !entry.isSnapshot()) {
                    continue;
                }
                b.append("- ").append(entry.id)
                        .append(" · ").append(entry.typeLabel())
                        .append(entry.releaseTime == null || entry.releaseTime.length() < 10
                                ? "" : " · " + entry.releaseTime.substring(0, 10))
                        .append(VersionService.isInstalled(entry.id) ? " · 已安装" : "")
                        .append('\n');
                if (++count >= limit) {
                    break;
                }
            }
        }
        if (count == 0) {
            b.append("（没有匹配的版本）");
        }
        return b.toString().stripTrailing();
    }

    private void registerLoaderVersions() {
        JsonObject props = objectSchema();
        props.add("mcVersion", string("Minecraft 游戏版本，例如 1.21.1 或 26.3"));
        props.add("loader", enumeration("加载器", "fabric", "quilt", "forge", "neoforge"));
        tools.put("list_loader_versions", new Tool("list_loader_versions",
                "查询某个 Minecraft 版本可用的模组加载器版本，并标出推荐版本。",
                input(props, List.of("mcVersion", "loader")),
                args -> loaderVersions(args)));
    }

    private String loaderVersions(JsonObject args) throws Exception {
        String mc = required(args, "mcVersion");
        LoaderType loader = loader(required(args, "loader"));
        List<LoaderService.LoaderVersion> versions = LoaderService.versions(loader, mc);
        if (versions.isEmpty()) {
            return "Minecraft " + mc + " 没有可用的 " + loader.label + " 加载器版本。";
        }
        StringBuilder b = new StringBuilder("Minecraft ").append(mc).append(" · ")
                .append(loader.label).append(" 加载器版本（共 ").append(versions.size()).append(" 个）：\n");
        int shown = 0;
        for (LoaderService.LoaderVersion version : versions) {
            b.append("- ").append(version.id());
            if (version.recommended()) {
                b.append(" [推荐]");
            }
            b.append('\n');
            if (++shown >= 20) {
                break;
            }
        }
        if (versions.size() > shown) {
            b.append("（仅显示前 ").append(shown).append(" 个）\n");
        }
        return b.toString().stripTrailing();
    }

    private void registerInstallGame() {
        JsonObject props = objectSchema();
        props.add("mcVersion", string("要安装的 Minecraft 版本 id，必须来自 list_game_versions"));
        props.add("loader", enumeration("加载器；vanilla 表示不装加载器", "vanilla", "fabric", "quilt", "forge", "neoforge"));
        props.add("loaderVersion", string("加载器版本；留空使用列表中的推荐版本"));
        props.add("name", string("新实例名称；留空自动生成"));
        tools.put("install_game", new Tool("install_game",
                "下载并安装 Minecraft 游戏本体与可选加载器，并创建一个新实例。"
                        + "执行前启动器会弹出确认框；用户取消时不要重试。",
                input(props, List.of("mcVersion")),
                args -> installGame(args)));
    }

    private String installGame(JsonObject args) throws Exception {
        String mc = required(args, "mcVersion");
        LoaderType loader = loader(str(args, "loader", "vanilla"));
        String loaderVersion = str(args, "loaderVersion", "");
        String name = str(args, "name", "");
        VersionManifest manifest = VersionService.manifest();
        VersionManifest.Entry entry = VersionManifest.Entry.byId(manifest).get(mc);
        if (entry == null) {
            throw new ToolFailure("版本列表里没有 Minecraft " + mc + "，请先用 list_game_versions 查询。");
        }
        if (loader != LoaderType.VANILLA && loaderVersion.isBlank()) {
            List<LoaderService.LoaderVersion> versions = LoaderService.versions(loader, mc);
            if (versions.isEmpty()) {
                throw new ToolFailure("Minecraft " + mc + " 没有可用的 " + loader.label + " 加载器版本。");
            }
            loaderVersion = versions.stream().filter(LoaderService.LoaderVersion::recommended)
                    .findFirst().orElse(versions.get(0)).id();
        }
        String targetName = name.isBlank()
                ? (loader == LoaderType.VANILLA ? mc : mc + " · " + loader.label)
                : name;
        StringBuilder detail = new StringBuilder();
        detail.append("版本：Minecraft ").append(mc).append("（").append(entry.typeLabel()).append("）\n");
        if (loader != LoaderType.VANILLA) {
            detail.append("加载器：").append(loader.label).append(" ").append(loaderVersion).append('\n');
        }
        detail.append("新实例：").append(targetName).append('\n');
        detail.append("需要下载游戏本体、依赖库与资源文件，耗时取决于网络。");
        if (!ui.confirm("安装游戏", detail.toString(), "下载并安装")) {
            throw new ToolFailure("用户取消了安装。不要重试，先询问用户接下来想做什么。");
        }
        AssistantUi.Operation op = ui.beginOperation("安装 Minecraft " + mc);
        Progress progress = new Progress();
        progress.addListener(p -> op.progress(p.getStage(), p.getDetail()));
        BooleanSupplier cancelled = () -> op.isCancelled() || progress.isCancelled();
        try {
            Instance instance = InstallService.install(mc, loader, loaderVersion, targetName,
                    progress, cancelled);
            ui.refreshLauncher();
            StringBuilder b = new StringBuilder();
            b.append("游戏安装完成。\n");
            b.append("实例：").append(instance.name).append('\n');
            b.append("实例 id：").append(instance.id).append('\n');
            b.append("版本：Minecraft ").append(mc);
            if (loader != LoaderType.VANILLA) {
                b.append(" · ").append(loader.label).append(" ").append(loaderVersion);
            }
            b.append('\n');
            b.append("新实例已自动设为当前选中实例。");
            return b.toString();
        } catch (InterruptedException e) {
            throw new ToolFailure("安装已取消。不要重试，先询问用户接下来想做什么。", e);
        } finally {
            op.finish();
        }
    }

    // -------------------------------------------------------------- content

    private void registerSearch() {
        JsonObject props = objectSchema();
        props.add("query", string("搜索关键词；留空可浏览热门项目"));
        props.add("kind", enumeration("资源类型", "mod", "resourcepack", "shader", "modpack"));
        props.add("gameVersion", string("Minecraft 版本过滤，可留空"));
        props.add("loader", string("加载器过滤（fabric/quilt/forge/neoforge），可留空"));
        props.add("source", enumeration("来源", "all", "modrinth", "curseforge"));
        props.add("sort", enumeration("排序", "relevance", "downloads", "updated"));
        props.add("instanceId", string("参考实例 id，可留空；留空时使用当前选中实例判断兼容性"));
        props.add("limit", integer("返回数量", 10, 1, 20));
        tools.put("search_content", new Tool("search_content",
                "在 Modrinth / CurseForge 搜索模组、材质包、光影或整合包，并返回可继续查询的项目 key。"
                        + "会结合参考实例标注游戏版本与加载器兼容性。",
                input(props, null),
                args -> searchContent(args)));
    }

    private String searchContent(JsonObject args) throws Exception {
        String query = str(args, "query", "");
        ResourceKind kind = kind(str(args, "kind", "mod"));
        SourceFilter source = sourceFilter(str(args, "source", "all"));
        String sort = sort(str(args, "sort", "relevance"));
        int limit = integer(args, "limit", 10, 1, 20);
        Instance reference = instanceArg(args);
        String gameVersion = str(args, "gameVersion", "");
        String loader = str(args, "loader", "");
        if (reference != null && kind != ResourceKind.MODPACK) {
            if (gameVersion.isBlank()) {
                gameVersion = ResourceService.instanceGameVersion(reference);
            }
            if (kind.needsLoader() && loader.isBlank()) {
                loader = ResourceService.instanceLoader(reference);
            }
        }
        if (!kind.needsLoader()) {
            loader = "";
        }
        if (kind == ResourceKind.MODPACK) {
            // A pack creates its own instance; the selected loader must not hide
            // packs the user could install.
            if (loader.isBlank()) {
                loader = "";
            }
        }
        SearchQuery request = new SearchQuery(query, kind, source, gameVersion, loader, sort, 0, limit);
        ResourceService.SearchResult result = ResourceService.search(request);
        StringBuilder b = new StringBuilder();
        b.append("搜索「").append(query.isBlank() ? "热门" : query).append("」 · ")
                .append(kind.label).append(" · ").append(source.label);
        if (reference != null && kind != ResourceKind.MODPACK) {
            b.append(" · 参考实例：").append(reference.name);
        }
        if (!gameVersion.isBlank()) {
            b.append(" · MC ").append(gameVersion);
        }
        if (!loader.isBlank()) {
            b.append(" · ").append(loader);
        }
        b.append('\n');
        for (String notice : result.notices()) {
            b.append("注意：").append(notice).append('\n');
        }
        if (result.projects().isEmpty()) {
            b.append("没有找到匹配的项目。可以尝试更换关键词、去掉版本/加载器过滤，或换一个来源。");
            return b.toString().stripTrailing();
        }
        int index = 0;
        for (ResourceProject project : result.projects()) {
            index++;
            b.append(index).append(". ").append(project.title());
            if (!blank(project.author())) {
                b.append(" — ").append(project.author());
            }
            b.append("\n   key: ").append(project.key())
                    .append(" · 下载 ").append(count(project.downloads()))
                    .append(" · 来源 ").append(project.source().label).append('\n');
            String description = compact(project.description(), 150);
            if (!description.isBlank()) {
                b.append("   ").append(description).append('\n');
            }
            b.append("   支持版本: ").append(setSummary(project.gameVersions(), 8));
            if (project.kind().needsLoader()) {
                b.append(" · 加载器: ").append(setSummary(project.loaders(), 6));
            }
            b.append('\n');
            if (reference != null && kind != ResourceKind.MODPACK) {
                ResourceService.Compatibility compatibility =
                        ResourceService.projectCompatibility(reference, project);
                b.append("   与实例「").append(reference.name).append("」")
                        .append(compatibility.ok() ? "兼容" : "不兼容：" + compatibility.label())
                        .append('\n');
            }
            if (!blank(project.pageUrl())) {
                b.append("   页面: ").append(project.pageUrl()).append('\n');
            }
        }
        if (result.hasMore()) {
            b.append("还有更多结果；可以增加 limit 或缩小关键词。\n");
        }
        return b.toString().stripTrailing();
    }

    private void registerContentVersions() {
        JsonObject props = objectSchema();
        props.add("source", enumeration("来源", "modrinth", "curseforge"));
        props.add("projectId", string("项目 id，来自 search_content 返回的 key（冒号后的部分）"));
        props.add("kind", enumeration("资源类型", "mod", "resourcepack", "shader", "modpack"));
        props.add("gameVersion", string("Minecraft 版本过滤，可留空"));
        props.add("loader", string("加载器过滤，可留空"));
        props.add("limit", integer("返回数量", 10, 1, 30));
        tools.put("get_content_versions", new Tool("get_content_versions",
                "列出某个项目的具体文件版本（versionId、文件名、大小、发布频道、支持版本、加载器与依赖）。"
                        + "安装前用它选出与实例兼容的文件。",
                input(props, List.of("source", "projectId", "kind")),
                args -> contentVersions(args)));
    }

    private String contentVersions(JsonObject args) throws Exception {
        ResourceSource source = source(required(args, "source"));
        String projectId = required(args, "projectId");
        ResourceKind kind = kind(required(args, "kind"));
        String gameVersion = str(args, "gameVersion", "");
        String loader = kind.needsLoader() ? str(args, "loader", "") : "";
        int limit = integer(args, "limit", 10, 1, 30);
        ResourceProject project = project(source, projectId, kind);
        List<ResourceVersion> versions = ResourceService.versions(project, gameVersion, loader);
        List<ResourceVersion> matching = new ArrayList<>();
        for (ResourceVersion version : versions) {
            if (!gameVersion.isBlank() && !version.supportsGameVersion(gameVersion)) {
                continue;
            }
            if (!loader.isBlank() && !version.supportsLoader(loader)) {
                continue;
            }
            matching.add(version);
        }
        if (matching.isEmpty()) {
            StringBuilder b = new StringBuilder("没有找到匹配的文件版本");
            if (!gameVersion.isBlank() || !loader.isBlank()) {
                b.append("（过滤条件：");
                if (!gameVersion.isBlank()) {
                    b.append("MC ").append(gameVersion).append(' ');
                }
                if (!loader.isBlank()) {
                    b.append(loader);
                }
                b.append("）");
            }
            b.append("。可以去掉过滤条件后重试。");
            return b.toString();
        }
        StringBuilder b = new StringBuilder("项目 ").append(project.key()).append(" 的文件版本（共 ")
                .append(matching.size()).append(" 个，显示前 ").append(Math.min(limit, matching.size()))
                .append(" 个）：\n");
        int shown = 0;
        for (ResourceVersion version : matching) {
            b.append("- versionId: ").append(version.versionId()).append('\n');
            b.append("  名称: ").append(versionName(version)).append('\n');
            b.append("  频道: ").append(blank(version.channel()) ? "未标注" : version.channel())
                    .append(" · 文件: ").append(version.fileName())
                    .append(" · ").append(GameFiles.formatSize(version.size())).append('\n');
            b.append("  支持: ").append(String.join(", ", limitList(version.gameVersions(), 8)));
            if (kind.needsLoader()) {
                b.append(" · 加载器: ").append(String.join(", ", limitList(version.loaders(), 6)));
            }
            b.append('\n');
            if (version.publishedAt() > 0) {
                b.append("  发布: ").append(DAY.format(Instant.ofEpochMilli(version.publishedAt()))).append('\n');
            }
            String dependencies = dependencies(version.dependencies());
            if (!dependencies.isBlank()) {
                b.append("  依赖: ").append(dependencies).append('\n');
            }
            if (++shown >= limit) {
                break;
            }
        }
        if (matching.size() > shown) {
            b.append("（还有 ").append(matching.size() - shown).append(" 个版本未显示）\n");
        }
        return b.toString().stripTrailing();
    }

    private void registerInstallContent() {
        JsonObject props = objectSchema();
        props.add("source", enumeration("来源", "modrinth", "curseforge"));
        props.add("projectId", string("项目 id，来自 search_content"));
        props.add("kind", enumeration("资源类型", "mod", "resourcepack", "shader", "modpack"));
        props.add("versionId", string("要安装的 versionId；留空选择最新兼容文件"));
        props.add("instanceId", string("目标实例 id；非整合包留空时使用当前选中实例"));
        props.add("projectTitle", string("项目显示名，建议从搜索结果原样传入，可留空"));
        props.add("name", string("整合包创建的新实例名称，可留空"));
        tools.put("install_content", new Tool("install_content",
                "下载并安装模组、材质包、光影或整合包。模组/材质包/光影安装到现有实例，"
                        + "整合包创建新实例。执行前启动器会弹出确认框；用户取消时不要重试。",
                input(props, List.of("source", "projectId", "kind")),
                args -> installContent(args)));
    }

    private String installContent(JsonObject args) throws Exception {
        ResourceSource source = source(required(args, "source"));
        String projectId = required(args, "projectId");
        ResourceKind kind = kind(required(args, "kind"));
        String versionId = str(args, "versionId", "");
        String name = str(args, "name", "");
        String projectTitle = str(args, "projectTitle", "");
        if (projectTitle.length() > 120) {
            projectTitle = projectTitle.substring(0, 120);
        }
        Instance target = null;
        if (kind != ResourceKind.MODPACK) {
            target = instanceArg(args);
            if (target == null) {
                throw new ToolFailure("还没有可安装的实例。请先用 install_game 安装游戏，"
                        + "或告诉用户先在「实例」页面创建一个实例。");
            }
        }
        ResourceProject project = project(source, projectId, kind);
        String gameVersion = target == null ? "" : ResourceService.instanceGameVersion(target);
        String loader = target == null || !kind.needsLoader() ? ""
                : ResourceService.instanceLoader(target);
        List<ResourceVersion> versions = ResourceService.versions(project, gameVersion, loader);
        ResourceVersion chosen = findVersion(versions, versionId);
        if (chosen == null && !versionId.isBlank()) {
            // Explicit ids are allowed even when the target filter hid them;
            // the confirmation will carry the compatibility warning.
            versions = ResourceService.versions(project, "", "");
            chosen = findVersion(versions, versionId);
            if (chosen == null) {
                throw new ToolFailure("找不到 versionId=" + versionId
                        + "，请先用 get_content_versions 确认可用版本。");
            }
        }
        if (chosen == null) {
            List<ResourceVersion> compatible = new ArrayList<>();
            for (ResourceVersion version : versions) {
                if (gameVersion.isBlank() || version.supportsGameVersion(gameVersion)) {
                    if (loader.isBlank() || !kind.needsLoader() || version.supportsLoader(loader)) {
                        compatible.add(version);
                    }
                }
            }
            if (compatible.isEmpty()) {
                throw new ToolFailure("没有找到与当前实例兼容的文件版本。"
                        + "请先用 get_content_versions 查看支持的游戏版本和加载器。");
            }
            chosen = compatible.get(0);
        }
        ResourceService.Compatibility compatibility = target == null
                ? new ResourceService.Compatibility(true, true)
                : ResourceService.compatibility(target, chosen, kind);
        List<ResourceDependency> requiredDependencies = requiredDependencies(chosen);
        String chosenName = versionName(chosen);
        String displayTitle = projectTitle.isBlank() ? chosenName : projectTitle;
        String instanceName = name;
        if (kind == ResourceKind.MODPACK && instanceName.isBlank()) {
            instanceName = displayTitle;
        }
        ResourceProject display = new ResourceProject(source, projectId, "", displayTitle,
                "", "", "", "", 0, 0, 0, Set.of(), Set.of(), kind);
        StringBuilder detail = new StringBuilder();
        detail.append("项目：").append(displayTitle).append("（").append(source.label)
                .append(" · ").append(projectId).append("）\n");
        detail.append("文件：").append(chosenName).append('\n');
        detail.append("类型：").append(kind.label)
                .append(" · 大小：").append(GameFiles.formatSize(chosen.size())).append('\n');
        if (kind == ResourceKind.MODPACK) {
            detail.append("将创建新实例");
            if (!instanceName.isBlank()) {
                detail.append("「").append(instanceName).append("」");
            }
            detail.append("；整合包会下载额外文件，耗时可能较长。\n");
        } else {
            detail.append("目标实例：").append(target.name).append("（").append(target.versionLabel()).append("）\n");
            detail.append("文件会复制到该实例的 ").append(kind.folder).append(" 文件夹。\n");
            if (!compatibility.ok()) {
                detail.append("警告：该文件与实例").append(compatibility.label())
                        .append("，安装后可能无法正常使用。\n");
            }
        }
        if (!requiredDependencies.isEmpty()) {
            detail.append("该版本声明了必需前置，不会自动安装：");
            int count = 0;
            for (ResourceDependency dependency : requiredDependencies) {
                if (count++ > 0) {
                    detail.append("、");
                }
                detail.append(dependency.displayName());
            }
            detail.append("。\n");
        }
        if (!ui.confirm(kind == ResourceKind.MODPACK ? "安装整合包" : "安装" + kind.label,
                detail.toString(), "下载并安装")) {
            throw new ToolFailure("用户取消了安装。不要重试，先询问用户接下来想做什么。");
        }
        AssistantUi.Operation op = ui.beginOperation("安装 " + versionName(chosen));
        Progress progress = new Progress();
        progress.addListener(p -> op.progress(p.getStage(), p.getDetail()));
        BooleanSupplier cancelled = () -> op.isCancelled() || progress.isCancelled();
        try {
            Path archive = ResourceService.download(display, chosen, progress, cancelled);
            StringBuilder result = new StringBuilder();
            if (kind == ResourceKind.MODPACK) {
                ContentInstallService.InstalledModpack installed =
                        ContentInstallService.installModpack(display, chosen, archive, instanceName,
                                progress, cancelled);
                result.append("整合包安装完成。\n");
                result.append("新实例：").append(installed.instance().name).append('\n');
                result.append("实例 id：").append(installed.instance().id).append('\n');
                result.append("整合包：").append(installed.info().mcVersion()).append(" · ")
                        .append(installed.info().loader().label).append('\n');
            } else {
                Path installed = ContentInstallService.installResource(target, kind, archive,
                        progress, cancelled);
                result.append(kind.label).append("安装完成。\n");
                result.append("目标实例：").append(target.name).append('\n');
                if (installed != null) {
                    result.append("文件：").append(installed.toAbsolutePath()).append('\n');
                }
            }
            if (!requiredDependencies.isEmpty()) {
                result.append("仍需安装的必需前置：\n");
                for (ResourceDependency dependency : requiredDependencies) {
                    result.append("- ").append(dependency.displayName())
                            .append("（source=").append(dependency.source().id())
                            .append(", projectId=").append(dependency.projectId()).append("）\n");
                }
                result.append("先向用户说明，再按需调用 install_content 安装这些前置。");
            }
            ui.refreshLauncher();
            return result.toString().stripTrailing();
        } catch (InterruptedException e) {
            throw new ToolFailure("安装已取消。不要重试，先询问用户接下来想做什么。", e);
        } finally {
            op.finish();
        }
    }

    private void registerInstanceFiles() {
        JsonObject props = objectSchema();
        props.add("instanceId", string("实例 id；留空时使用当前选中实例"));
        props.add("category", enumeration("文件类别", "mods", "resourcepacks", "shaderpacks"));
        tools.put("list_instance_files", new Tool("list_instance_files",
                "列出某个实例已安装的模组、材质包或光影文件，以及它们是启用还是禁用状态。",
                input(props, null),
                args -> instanceFiles(args)));
    }

    private String instanceFiles(JsonObject args) throws Exception {
        Instance instance = instanceArg(args);
        if (instance == null) {
            throw new ToolFailure("当前没有选中实例，请传入 instanceId 或先在启动器中选中一个实例。");
        }
        GameFiles.Category category = category(str(args, "category", "mods"));
        List<GameFiles.Entry> entries = GameFiles.scan(GameFiles.directory(instance, category), category);
        StringBuilder b = new StringBuilder("实例「").append(instance.name).append("」的")
                .append(category.label).append("：共 ").append(entries.size()).append(" 个\n");
        if (entries.isEmpty()) {
            b.append("（目录为空：").append(GameFiles.directory(instance, category)).append("）");
            return b.toString();
        }
        int shown = 0;
        for (GameFiles.Entry entry : entries) {
            b.append("- ").append(entry.name())
                    .append(entry.enabled() ? " · 已启用" : " · 已禁用")
                    .append(entry.directory() ? " · 文件夹" : " · " + GameFiles.formatSize(entry.size()))
                    .append('\n');
            if (++shown >= 100) {
                b.append("（仅显示前 100 个）\n");
                break;
            }
        }
        return b.toString().stripTrailing();
    }

    private void registerLog() {
        JsonObject props = objectSchema();
        props.add("instanceId", string("实例 id；留空读取启动器日志，填写则读取该实例的 logs/latest.log"));
        props.add("lines", integer("读取最近多少行（1-500）", 100, 1, 500));
        tools.put("read_launcher_log", new Tool("read_launcher_log",
                "读取启动器日志或某个实例最近一次游戏的日志，用于排查启动失败、崩溃、模组冲突等问题。",
                input(props, null),
                args -> log(args)));
    }

    private String log(JsonObject args) throws Exception {
        int lines = integer(args, "lines", 100, 1, 500);
        String instanceId = str(args, "instanceId", "");
        Path file;
        String label;
        if (instanceId.isBlank()) {
            file = AppPaths.LAUNCHER_LOG;
            label = "启动器日志";
        } else {
            Instance instance = InstanceStore.get().byId(instanceId);
            if (instance == null) {
                throw new ToolFailure("找不到实例：" + instanceId + "。请先用 list_instances 查询。");
            }
            file = instance.gameDir().resolve("logs").resolve("latest.log");
            label = "实例「" + instance.name + "」最近一次游戏日志";
        }
        if (!Files.isRegularFile(file)) {
            return label + "不存在：" + file
                    + "\n如果游戏尚未启动过，请先正常启动一次后再排查。";
        }
        String tail = tail(file, lines);
        return label + "（" + file + "，最近 " + lines + " 行）：\n```\n"
                + Redact.text(tail) + "\n```";
    }

    // --------------------------------------------------------------- helpers

    private static ResourceVersion findVersion(List<ResourceVersion> versions, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return null;
        }
        for (ResourceVersion version : versions) {
            if (versionId.equals(version.versionId())) {
                return version;
            }
        }
        return null;
    }

    private static List<ResourceDependency> requiredDependencies(ResourceVersion version) {
        List<ResourceDependency> required = new ArrayList<>();
        if (version.dependencies() == null) {
            return required;
        }
        for (ResourceDependency dependency : version.dependencies()) {
            if (dependency != null && dependency.type() == DependencyType.REQUIRED
                    && !blank(dependency.projectId())) {
                required.add(dependency);
            }
        }
        return required;
    }

    private static String dependencies(List<ResourceDependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (ResourceDependency dependency : dependencies) {
            if (dependency == null) {
                continue;
            }
            if (dependency.type() == DependencyType.INCOMPATIBLE) {
                if (b.length() > 0) {
                    b.append("；");
                }
                b.append("不兼容: ").append(dependency.displayName());
                continue;
            }
            if (dependency.type() != DependencyType.REQUIRED) {
                continue;
            }
            if (b.length() > 0) {
                b.append("；");
            }
            b.append("必需: ").append(dependency.displayName())
                    .append("（projectId=").append(dependency.projectId()).append("）");
        }
        return b.toString();
    }

    private static String versionName(ResourceVersion version) {
        if (!blank(version.name())) {
            return version.name();
        }
        if (!blank(version.versionNumber())) {
            return projectFallback(version) + " " + version.versionNumber();
        }
        return styleFile(version.fileName());
    }

    private static String projectFallback(ResourceVersion version) {
        return version.projectId() == null ? "项目" : version.projectId();
    }

    private static String styleFile(String name) {
        return blank(name) ? "文件" : name;
    }

    private Instance instanceArg(JsonObject args) throws ToolFailure {
        String id = str(args, "instanceId", "");
        if (id.isBlank()) {
            return InstanceStore.get().selected();
        }
        Instance instance = InstanceStore.get().byId(id);
        if (instance == null) {
            throw new ToolFailure("找不到实例 " + id + "。\n" + instanceHint());
        }
        return instance;
    }

    private static String instanceHint() {
        List<Instance> all = InstanceStore.get().all();
        if (all.isEmpty()) {
            return "当前没有实例。";
        }
        StringBuilder b = new StringBuilder("可用实例：\n");
        int shown = 0;
        for (Instance instance : all) {
            b.append("- ").append(instance.name).append("（id=").append(instance.id).append("）\n");
            if (++shown >= 10) {
                break;
            }
        }
        return b.toString().stripTrailing();
    }

    private static ResourceProject project(ResourceSource source, String id, ResourceKind kind) {
        return new ResourceProject(source, id, "", "", "", "", "", "", 0, 0, 0,
                Set.of(), Set.of(), kind);
    }

    private static ResourceSource source(String value) throws ToolFailure {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "modrinth" -> ResourceSource.MODRINTH;
            case "curseforge" -> ResourceSource.CURSEFORGE;
            default -> throw new ToolFailure("source 只能是 modrinth 或 curseforge。");
        };
    }

    private static SourceFilter sourceFilter(String value) throws ToolFailure {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "all", "" -> SourceFilter.ALL;
            case "modrinth" -> SourceFilter.MODRINTH;
            case "curseforge" -> SourceFilter.CURSEFORGE;
            default -> throw new ToolFailure("source 只能是 all、modrinth 或 curseforge。");
        };
    }

    private static LoaderType loader(String value) throws ToolFailure {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (LoaderType type : LoaderType.values()) {
            if (type.id.equals(v)) {
                return type;
            }
        }
        throw new ToolFailure("loader 只能是 vanilla、fabric、quilt、forge 或 neoforge。");
    }

    private static ResourceKind kind(String value) throws ToolFailure {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "mod", "mods", "模组" -> ResourceKind.MOD;
            case "resourcepack", "resource_pack", "resource-pack", "材质包", "资源包" -> ResourceKind.RESOURCE_PACK;
            case "shader", "shaders", "光影", "光影包" -> ResourceKind.SHADER;
            case "modpack", "modpacks", "整合包" -> ResourceKind.MODPACK;
            default -> throw new ToolFailure("kind 只能是 mod、resourcepack、shader 或 modpack。");
        };
    }

    private static GameFiles.Category category(String value) throws ToolFailure {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "mods", "mod" -> GameFiles.Category.MODS;
            case "resourcepacks", "resourcepack" -> GameFiles.Category.RESOURCE_PACKS;
            case "shaderpacks", "shader" -> GameFiles.Category.SHADER_PACKS;
            default -> throw new ToolFailure("category 只能是 mods、resourcepacks 或 shaderpacks。");
        };
    }

    private static String sort(String value) throws ToolFailure {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "relevance" -> "relevance";
            case "downloads" -> "downloads";
            case "updated" -> "updated";
            default -> throw new ToolFailure("sort 只能是 relevance、downloads 或 updated。");
        };
    }

    private static String required(JsonObject args, String key) throws ToolFailure {
        String value = str(args, key, "");
        if (value.isBlank()) {
            throw new ToolFailure("缺少参数 " + key + "。");
        }
        return value;
    }

    private static String str(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return args.get(key).getAsString().trim();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int integer(JsonObject args, String key, int fallback, int min, int max) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, args.get(key).getAsInt()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static JsonObject input(JsonObject properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        if (required != null && !required.isEmpty()) {
            JsonArray array = new JsonArray();
            required.forEach(array::add);
            schema.add("required", array);
        }
        return schema;
    }

    private static JsonObject objectSchema() {
        return new JsonObject();
    }

    private static JsonObject string(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject enumeration(String description, String... values) {
        JsonObject property = string(description);
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        property.add("enum", array);
        return property;
    }

    private static JsonObject integer(String description, int fallback, int min, int max) {
        // Keep schemas to the widely supported subset; bounds are still
        // enforced when the arguments are parsed.
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        property.addProperty("default", fallback);
        return property;
    }

    private static String launcherVersion() {
        String version = LauncherTools.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "开发版" : version;
    }

    private static String downloadSourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case "official" -> "官方源";
            case "mirror", "bmclapi" -> "BMCLAPI 镜像";
            case "hkmc" -> "MC 光速镜像";
            default -> "国内镜像（自动）";
        };
    }

    private static String count(long value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return Long.toString(value);
    }

    private static String setSummary(Set<String> values, int max) {
        return String.join(", ", limitList(values, max));
    }

    private static List<String> limitList(Iterable<String> values, int max) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return List.of();
        }
        int count = 0;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            result.add(value);
            if (++count >= max) {
                break;
            }
        }
        return result.isEmpty() ? List.of("通用") : result;
    }

    private static String compact(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String tail(Path file, int lines) throws IOException {
        final int maxBytes = 1024 * 1024;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            raf.seek(start);
            byte[] data = new byte[(int) (length - start)];
            raf.readFully(data);
            String text = new String(data, StandardCharsets.UTF_8);
            String[] split = text.split("\\R", -1);
            int from = Math.max(0, split.length - Math.max(1, lines));
            StringBuilder b = new StringBuilder();
            if (start > 0) {
                b.append("…（日志较长，已截取末尾部分）\n");
            }
            for (int i = from; i < split.length; i++) {
                if (i > from) {
                    b.append('\n');
                }
                b.append(split[i]);
            }
            return b.toString();
        }
    }
}
