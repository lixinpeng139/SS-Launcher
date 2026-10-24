package com.sslauncher.ui.pages;

import com.sslauncher.core.InstanceStore;
import com.sslauncher.model.Instance;
import com.sslauncher.resource.ContentInstallService;
import com.sslauncher.resource.CurseForgeProvider;
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
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.ProjectIcons;
import com.sslauncher.ui.Ui;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Browses Modrinth and CurseForge, labels prerequisites and installs the
 * downloaded file into an instance chosen by the user.
 */
public class ResourcesPage implements PageView {
    private static final int PAGE_SIZE = 20;
    private static final String SEARCH = "M15.5 14h-.79l-.28-.27A6.47 6.47 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 "
            + "4.23-1.57l.27.28v.79l5 4.99L20.49 19zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z";
    private static final String DOWNLOAD = "M19 9h-4V3H9v6H5l7 7zM5 18v2h14v-2z";
    private static final String OPEN = "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3zM19 19H5V5h7V3H5a2 2 0 00-2 "
            + "2v14a2 2 0 002 2h14a2 2 0 002-2v-7h-2z";
    private static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 "
            + "5 17.6 10.6 12 5 6.4z";
    private static final String WARNING = "M1 21h22L12 2zm12-3h-2v-2h2zm0-4h-2v-4h2z";
    private static final String KEY = "M12.65 10A5.99 5.99 0 007 6a6 6 0 000 12 5.99 5.99 0 005.65-4H17v4h4v-4h2v-4zM7 14a2 2 0 110-4 2 2 0 010 4z";
    private static final String CUBE = "M12 2 3 7v10l9 5 9-5V7zm0 2.3L18.5 8 12 11.3 5.5 8zM5 9.6l6 3v5.8l-6-3.3zm14 0v5.4l-6 3.3v-5.8z";
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8z";
    private static final String AI = "M12 2l2.4 6.6L21 11l-6.6 2.4L12 20l-2.4-6.6L3 11l6.6-2.4z";

    private static final Instance ANY_INSTANCE = new Instance("__any__", "不限实例", "", com.sslauncher.model.LoaderType.VANILLA);
    /** Theme roles used for the letter tiles, picked by a hash of the title. */
    private static final String[] TILE_ROLES = {"primary", "tertiary", "secondary"};

    private final MainWindow main;
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;
    private final TextField search = new TextField();
    private final HBox kindBar = new HBox(7);
    private final HBox sourceBar = new HBox(7);
    private final ComboBox<Instance> instancePicker = new ComboBox<>();
    private final ComboBox<String> sort = new ComboBox<>();
    private final VBox noticeBox = new VBox(8);
    private final VBox resultsHost = new VBox(10);
    private final GridPane grid = new GridPane();
    private final HBox moreBar = new HBox();
    private final Button loadMore = Ui.ghostButton("加载更多");
    private final Label status = Ui.label("", "tiny");

    private ResourceKind kind = ResourceKind.MOD;
    private SourceFilter sourceFilter = SourceFilter.ALL;
    private Instance filterInstance;
    private final List<ResourceProject> projects = new ArrayList<>();
    private final List<String> lastNotices = new ArrayList<>();
    private boolean loading;
    private boolean searched;
    private boolean hasMore;
    private String searchError = "";
    private int nextOffset;
    private int searchSerial;
    private boolean updatingPicker;
    private boolean updatingSort;
    private boolean updatingSearch;
    private boolean lastConfigured = CurseForgeProvider.isConfigured();
    private boolean debugDialogOpened;
    private PauseTransition debounce;

    private record Downloaded(Path file, ContentInstallService.ModpackInfo info) {
    }

    public ResourcesPage(MainWindow main) {
        this.main = main;
        this.scroll = Ui.scroll(content);
        build();
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        boolean force = applyDebugProperties();
        boolean configured = CurseForgeProvider.isConfigured();
        if (configured != lastConfigured) {
            lastConfigured = configured;
            if (searched) {
                force = true;
            }
        }
        refreshInstancePicker();
        renderFilters();
        renderNotices();
        renderResults();
        if (!searched || force) {
            doSearch(true);
        }
        Ui.fadeIn(content, 160);
    }

    // ------------------------------------------------------------------- shell

    private void build() {
        search.setPromptText("搜索模组、材质包、光影包或整合包…");
        search.setPrefHeight(38);
        Button searchButton = Ui.primaryButton("搜索");
        searchButton.setGraphic(Ui.icon(SEARCH, 13));
        searchButton.setMinWidth(92);
        searchButton.setOnAction(e -> submitSearch());
        search.setOnAction(e -> submitSearch());

        Button importButton = Ui.ghostButton("导入整合包");
        importButton.setGraphic(Ui.icon(CUBE, 13));
        importButton.setTooltip(new Tooltip(
                "选择 .mrpack 或 CurseForge 整合包 .zip；也可以直接拖入启动器窗口"));
        importButton.setOnAction(e -> chooseModpack());

        HBox searchRow = new HBox(10, search, searchButton, importButton);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);

        debounce = new PauseTransition(Duration.millis(600));
        debounce.setOnFinished(e -> {
            if (!updatingSearch) {
                doSearch(true);
            }
        });
        search.textProperty().addListener((obs, old, value) -> {
            if (!updatingSearch && debounce != null) {
                debounce.playFromStart();
            }
        });

        instancePicker.setPrefWidth(196);
        instancePicker.setPrefHeight(32);
        instancePicker.setFocusTraversable(false);
        instancePicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(Instance value) {
                if (value == null || value == ANY_INSTANCE) {
                    return "不限实例（全部）";
                }
                return value.name;
            }

            @Override
            public Instance fromString(String string) {
                return null;
            }
        });
        instancePicker.setTooltip(new Tooltip("选择实例后，结果会按该实例的 Minecraft 版本和加载器筛选"));
        instancePicker.setOnAction(e -> {
            if (updatingPicker) {
                return;
            }
            Instance chosen = instancePicker.getValue();
            if (chosen == null) {
                return;
            }
            filterInstance = chosen;
            doSearch(true);
        });

        sort.getItems().setAll("相关度", "下载量", "最近更新");
        sort.setValue("相关度");
        sort.setPrefWidth(116);
        sort.setFocusTraversable(false);
        sort.setOnAction(e -> {
            if (!updatingSort) {
                doSearch(true);
            }
        });

        kindBar.setAlignment(Pos.CENTER_LEFT);
        sourceBar.setAlignment(Pos.CENTER_LEFT);
        VBox filters = new VBox(9, kindBar, sourceBar);
        filters.getStyleClass().add("card-flat");
        filters.setPadding(new Insets(12, 14, 12, 14));

        noticeBox.setVisible(false);
        noticeBox.setManaged(false);

        grid.setHgap(14);
        grid.setVgap(14);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(50);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(50);
        grid.getColumnConstraints().setAll(left, right);

        moreBar.setAlignment(Pos.CENTER);
        moreBar.setPadding(new Insets(4, 0, 6, 0));
        loadMore.setOnAction(e -> doSearch(false));
        moreBar.getChildren().setAll(loadMore);
        moreBar.setVisible(false);
        moreBar.setManaged(false);

        status.setVisible(false);
        status.setManaged(false);

        resultsHost.getChildren().addAll(status, grid, moreBar);

        VBox headerText = new VBox(4, Ui.title("资源下载"),
                Ui.subtitle("从 Modrinth 与 CurseForge 获取模组、材质包、光影包和整合包；"
                        + "下载后选择安装实例，前置模组会清楚标注。也可以把整合包直接拖入窗口导入。"));
        HBox.setHgrow(headerText, Priority.ALWAYS);
        Button aiButton = Ui.primaryButton("AI 制作");
        aiButton.setGraphic(Ui.icon(AI, 14));
        aiButton.setTooltip(new Tooltip("用 AI 从零制作一个 Minecraft 模组"));
        aiButton.setOnAction(e -> main.showPage(MainWindow.Page.AI));
        HBox header = new HBox(18, headerText, aiButton);
        header.setAlignment(Pos.TOP_LEFT);
        content.getChildren().addAll(header, searchRow, filters, noticeBox, resultsHost);
    }

    private void submitSearch() {
        if (debounce != null) {
            debounce.stop();
        }
        doSearch(true);
    }

    private boolean applyDebugProperties() {
        if (!Boolean.getBoolean("ss.debugUi")) {
            return false;
        }
        boolean force = false;
        String query = System.getProperty("ss.resourceQuery");
        if (query != null) {
            updatingSearch = true;
            search.setText(query);
            updatingSearch = false;
            force = true;
        }
        String debugKind = System.getProperty("ss.resourceKind");
        if (debugKind != null && !debugKind.isBlank()) {
            try {
                kind = ResourceKind.valueOf(debugKind.trim().toUpperCase(Locale.ROOT));
                force = true;
            } catch (IllegalArgumentException ignored) {
                // Keep the current tab.
            }
        }
        String debugSource = System.getProperty("ss.resourceSource");
        if (debugSource != null && !debugSource.isBlank()) {
            try {
                sourceFilter = SourceFilter.valueOf(debugSource.trim().toUpperCase(Locale.ROOT));
                force = true;
            } catch (IllegalArgumentException ignored) {
                // Keep the current source filter.
            }
        }
        String debugInstance = System.getProperty("ss.resourceInstance");
        if (debugInstance != null && !debugInstance.isBlank()) {
            filterInstance = ANY_INSTANCE;
            for (Instance instance : InstanceStore.get().all()) {
                if (instance.id.equalsIgnoreCase(debugInstance)
                        || instance.name.equalsIgnoreCase(debugInstance)) {
                    filterInstance = instance;
                    break;
                }
            }
            force = true;
        }
        return force;
    }

    // ----------------------------------------------------------------- filters

    private void refreshInstancePicker() {
        List<Instance> options = new ArrayList<>();
        options.add(ANY_INSTANCE);
        List<Instance> instances = InstanceStore.get().all();
        options.addAll(instances);

        Instance desired = filterInstance;
        if (desired == null) {
            Instance selected = InstanceStore.get().selected();
            desired = selected == null ? ANY_INSTANCE : selected;
        }
        if (desired != ANY_INSTANCE) {
            Instance match = null;
            for (Instance option : instances) {
                if (option.id.equals(desired.id)) {
                    match = option;
                    break;
                }
            }
            desired = match == null ? ANY_INSTANCE : match;
        }

        updatingPicker = true;
        instancePicker.getItems().setAll(options);
        instancePicker.setValue(desired);
        updatingPicker = false;
        filterInstance = desired;
    }

    private void renderFilters() {
        kindBar.getChildren().clear();
        for (ResourceKind option : ResourceKind.values()) {
            Button tab = Ui.button(option.label, "tab-button");
            if (option == kind) {
                tab.getStyleClass().add("active");
            }
            tab.setOnAction(e -> {
                if (this.kind != option) {
                    this.kind = option;
                    renderFilters();
                    doSearch(true);
                }
            });
            kindBar.getChildren().add(tab);
        }
        kindBar.getChildren().addAll(Ui.hspacer(), Ui.label("兼容实例", "form-label"), instancePicker);

        sourceBar.getChildren().clear();
        sourceBar.getChildren().add(Ui.label("来源", "form-label"));
        ToggleGroup group = new ToggleGroup();
        for (SourceFilter option : SourceFilter.values()) {
            ToggleButton toggle = new ToggleButton(option.label);
            toggle.getStyleClass().add("tab-button");
            toggle.setToggleGroup(group);
            toggle.setSelected(option == sourceFilter);
            toggle.setOnAction(e -> {
                if (!toggle.isSelected()) {
                    toggle.setSelected(true);
                    return;
                }
                if (sourceFilter != option) {
                    sourceFilter = option;
                    renderFilters();
                    doSearch(true);
                }
            });
            sourceBar.getChildren().add(toggle);
        }
        sourceBar.getChildren().addAll(Ui.hspacer(), Ui.label("排序", "form-label"));
        updatingSort = true;
        sort.setValue(sortLabel());
        updatingSort = false;
        sourceBar.getChildren().add(sort);
    }

    private String sortValue() {
        int index = sort.getSelectionModel().getSelectedIndex();
        return switch (index) {
            case 1 -> "downloads";
            case 2 -> "updated";
            default -> "relevance";
        };
    }

    private String sortLabel() {
        int index = sort.getSelectionModel().getSelectedIndex();
        return switch (index) {
            case 1 -> "下载量";
            case 2 -> "最近更新";
            default -> "相关度";
        };
    }

    private Instance targetInstance() {
        return filterInstance == null || filterInstance == ANY_INSTANCE ? null : filterInstance;
    }

    // ---------------------------------------------------------------- searching

    private void doSearch(boolean reset) {
        if (reset) {
            projects.clear();
            lastNotices.clear();
            nextOffset = 0;
            hasMore = false;
            searchError = "";
            searched = true;
            renderNotices();
        }
        final Instance target = targetInstance();
        final String gameVersion = target == null ? "" : ResourceService.instanceGameVersion(target);
        final String loader = target == null || !kind.needsLoader()
                ? "" : ResourceService.instanceLoader(target);
        final int offset = reset ? 0 : nextOffset;
        final int serial = ++searchSerial;

        if (sourceFilter == SourceFilter.CURSEFORGE && !CurseForgeProvider.isConfigured()) {
            loading = false;
            projects.clear();
            hasMore = false;
            renderResults();
            return;
        }

        SearchQuery query = new SearchQuery(search.getText(), kind, sourceFilter,
                gameVersion, loader, sortValue(), offset, PAGE_SIZE);
        loading = true;
        searchError = "";
        renderResults();

        Async.supply(() -> ResourceService.search(query), result -> {
            if (serial != searchSerial) {
                return;
            }
            loading = false;
            projects.addAll(result.projects());
            lastNotices.clear();
            lastNotices.addAll(result.notices());
            hasMore = result.hasMore();
            nextOffset = offset + PAGE_SIZE;
            searchError = "";
            renderNotices();
            renderResults();
        }, error -> {
            if (serial != searchSerial) {
                return;
            }
            loading = false;
            searchError = message(error);
            renderResults();
            if (!projects.isEmpty()) {
                main.toast("加载更多失败：" + searchError);
            }
        });
    }

    private void setSearchAndSearch(String text) {
        if (debounce != null) {
            debounce.stop();
        }
        updatingSearch = true;
        search.setText(text == null ? "" : text);
        updatingSearch = false;
        if (kind != ResourceKind.MOD) {
            kind = ResourceKind.MOD;
            renderFilters();
        }
        doSearch(true);
    }

    // --------------------------------------------------------------- rendering

    private void renderNotices() {
        List<Node> cards = new ArrayList<>();
        for (String notice : lastNotices) {
            if (notice != null && !notice.isBlank()) {
                cards.add(noticeCard(notice, false));
            }
        }
        noticeBox.getChildren().setAll(cards);
        boolean visible = !cards.isEmpty();
        noticeBox.setVisible(visible);
        noticeBox.setManaged(visible);
    }

    private Node noticeCard(String text, boolean info, Node... actions) {
        Label label = Ui.label(text, info ? "notice-info-text" : "notice-text");
        label.setWrapText(true);
        HBox box = new HBox(10, label);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        if (actions.length > 0) {
            HBox buttons = new HBox(4, actions);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            box.getChildren().add(buttons);
        }
        box.getStyleClass().add(info ? "notice-blue" : "notice");
        return box;
    }

    private void renderResults() {
        grid.getChildren().clear();
        moreBar.setVisible(false);
        moreBar.setManaged(false);

        if (projects.isEmpty()) {
            Node state;
            if (loading) {
                ProgressBar bar = new ProgressBar(-1);
                bar.setPrefWidth(180);
                bar.setPrefHeight(4);
                bar.getStyleClass().add("resource-progress");
                state = stateCard(SEARCH, "primary", "正在搜索资源",
                        "正在连接 Modrinth 与 CurseForge，请稍候…", bar);
            } else if (!searchError.isBlank()) {
                state = stateCard(WARNING, "error", "搜索失败", searchError,
                        stateButton("重试", () -> doSearch(true), true));
            } else if (sourceFilter == SourceFilter.CURSEFORGE
                    && !CurseForgeProvider.isConfigured()) {
                state = stateCard(KEY, "warning", "CurseForge 暂不可用",
                        "内置 CurseForge 配置异常，请重新安装启动器；"
                                + "也可以先把来源切换回 Modrinth。");
            } else if (!searched) {
                state = stateCard(CUBE, "primary", "搜索社区资源",
                        "输入关键词，从 Modrinth 与 CurseForge 查找模组、材质包、光影包和整合包。");
            } else {
                state = stateCard(CUBE, "primary", "没有找到匹配的资源",
                        "换个关键词，或者调整来源、资源类型和兼容实例后再试。");
            }
            grid.add(state, 0, 0, 2, 1);
            setStatus("");
            return;
        }

        for (int i = 0; i < projects.size(); i++) {
            Node card = projectCard(projects.get(i));
            GridPane.setFillWidth(card, true);
            GridPane.setFillHeight(card, true);
            grid.add(card, i % 2, i / 2);
        }

        String text = "已显示 " + projects.size() + " 个资源";
        if (loading) {
            text += " · 正在加载更多…";
        } else if (!searchError.isBlank()) {
            text += " · " + searchError;
        } else if (hasMore) {
            text += " · 还有更多";
        }
        setStatus(text);

        boolean showMore = loading || hasMore;
        moreBar.setVisible(showMore);
        moreBar.setManaged(showMore);
        loadMore.setDisable(loading);
        loadMore.setText(loading ? "正在加载…" : "加载更多");

        if (Boolean.getBoolean("ss.debugUi") && !debugDialogOpened && !projects.isEmpty()) {
            String open = System.getProperty("ss.resourceOpen");
            if ("versions".equals(open)) {
                debugDialogOpened = true;
                ResourceProject first = projects.get(0);
                Platform.runLater(() -> openVersions(first));
            } else if ("targets".equals(open)) {
                debugDialogOpened = true;
                ResourceProject first = projects.get(0);
                Instance target = targetInstance();
                String gameVersion = target == null ? "" : ResourceService.instanceGameVersion(target);
                ResourceVersion sample = new ResourceVersion(first.source(), first.id(),
                        "debug-sample", "调试版本", "1.0", "release",
                        gameVersion.isBlank() ? List.of() : List.of(gameVersion),
                        first.loaders() == null ? List.of() : new ArrayList<>(first.loaders()),
                        "example.jar", "", "", 0, System.currentTimeMillis(), List.of());
                Platform.runLater(() -> showTargetDialog(first, sample, Path.of("example.jar")));
            }
        }
    }

    private void setStatus(String text) {
        boolean visible = text != null && !text.isBlank();
        status.setText(visible ? text : "");
        status.setVisible(visible);
        status.setManaged(visible);
    }

    private Node projectCard(ResourceProject project) {
        VBox card = new VBox(9);
        card.getStyleClass().addAll("card", "card-hover", "resource-card");
        card.setPadding(new Insets(14));
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);

        StackPane tile = resourceTile(project, 44);
        Label title = Ui.label(project.title(), "resource-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);
        String author = project.author() == null || project.author().isBlank()
                ? "未知作者" : project.author();
        Label meta = Ui.label(author + " · " + formatCount(project.downloads()) + " 次下载", "file-meta");
        meta.setMaxWidth(Double.MAX_VALUE);
        Label sourceChip = chip(project.source().label,
                project.source() == ResourceSource.MODRINTH ? "chip-green" : "chip-gold");
        HBox metaRow = new HBox(6, sourceChip, meta);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        VBox headings = new VBox(4, title, metaRow);
        HBox.setHgrow(headings, Priority.ALWAYS);
        headings.setMaxWidth(Double.MAX_VALUE);
        HBox top = new HBox(11, tile, headings);
        top.setAlignment(Pos.TOP_LEFT);

        String description = project.description() == null || project.description().isBlank()
                ? "没有简介" : shorten(project.description(), 140);
        Label desc = Ui.label(description, "resource-desc");
        desc.setWrapText(true);
        desc.setMaxHeight(36);

        HBox tags = new HBox(6);
        tags.setAlignment(Pos.CENTER_LEFT);
        Instance target = targetInstance();
        if (target != null) {
            ResourceService.Compatibility compatibility =
                    ResourceService.projectCompatibility(target, project);
            tags.getChildren().add(chip(compatibility.ok() ? "兼容当前实例" : compatibility.label(),
                    compatibility.ok() ? "chip-green" : "chip-danger"));
        }
        boolean hasTags = !tags.getChildren().isEmpty();
        tags.setVisible(hasTags);
        tags.setManaged(hasTags);

        Label support = Ui.label(supportSummary(project, target), "tiny");
        support.setWrapText(true);

        Button download = Ui.primaryButton(project.kind() == ResourceKind.MODPACK ? "安装整合包" : "下载");
        download.getStyleClass().add("btn-mini");
        download.setGraphic(Ui.icon(DOWNLOAD, 12));
        download.setTooltip(new Tooltip(project.kind() == ResourceKind.MODPACK
                ? "下载后创建一个新的独立实例" : "下载后选择要安装到的实例"));
        download.setOnAction(e -> openVersions(project));

        Button page = Ui.textButton("项目页面");
        page.getStyleClass().add("btn-mini");
        page.setGraphic(Ui.icon(OPEN, 10));
        page.setDisable(project.pageUrl() == null || project.pageUrl().isBlank());
        page.setOnAction(e -> Ui.browse(project.pageUrl()));

        HBox actions = new HBox(7, download, page);
        actions.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(top, desc);
        if (hasTags) {
            card.getChildren().add(tags);
        }
        card.getChildren().addAll(support, Ui.vspacer(), actions);
        return card;
    }

    private StackPane resourceTile(ResourceProject project, double size) {
        StackPane tile = new StackPane();
        tile.setMinSize(size, size);
        tile.setPrefSize(size, size);
        tile.setMaxSize(size, size);
        int hash = Math.abs((project.title() == null ? "" : project.title()).hashCode());
        tile.getStyleClass().addAll("resource-tile", "tile-" + TILE_ROLES[hash % TILE_ROLES.length]);
        Label letter = Ui.label(Ui.initial(project.title()), "resource-tile-label");
        tile.getChildren().add(letter);

        String iconUrl = project.iconUrl();
        if (iconUrl != null && !iconUrl.isBlank()) {
            double side = size - 4;
            ImageView view = new ImageView();
            view.setFitWidth(side);
            view.setFitHeight(side);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setVisible(false);
            Rectangle clip = new Rectangle(side, side);
            clip.setArcWidth(8);
            clip.setArcHeight(8);
            view.setClip(clip);
            tile.getChildren().add(view);
            ProjectIcons.load(iconUrl, image -> {
                view.setImage(image);
                view.setVisible(true);
                letter.setVisible(false);
                Ui.fadeIn(view, 140);
            });
        }
        return tile;
    }

    private VBox stateCard(String svg, String role, String heading, String body, Node... actions) {
        VBox card = new VBox(11);
        card.getStyleClass().addAll("card", "resource-state");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(38, 24, 38, 24));
        card.setMaxWidth(Double.MAX_VALUE);

        StackPane tile = new StackPane();
        tile.setMinSize(56, 56);
        tile.setPrefSize(56, 56);
        tile.setMaxSize(56, 56);
        tile.getStyleClass().add("resource-state-icon");
        tile.getChildren().add(Ui.icon(svg, 24, role));

        Label title = Ui.label(heading, "section-title");
        Label text = Ui.label(body, "tiny");
        text.setWrapText(true);
        text.setMaxWidth(470);
        text.setTextAlignment(TextAlignment.CENTER);
        card.getChildren().addAll(tile, title, text);
        if (actions.length > 0) {
            HBox row = new HBox(8, actions);
            row.setAlignment(Pos.CENTER);
            card.getChildren().add(row);
        }
        return card;
    }

    private Button stateButton(String text, Runnable action, boolean primary) {
        Button button = primary ? Ui.primaryButton(text) : Ui.ghostButton(text);
        button.setOnAction(e -> action.run());
        return button;
    }

    private static Label chip(String text, String style) {
        return Ui.label(text == null ? "" : text, "chip", style);
    }

    // ---------------------------------------------------------------- versions

    private void openVersions(ResourceProject project) {
        openVersions(project, targetInstance());
    }

    private void openVersions(ResourceProject project, Instance target) {
        if (project.source() == ResourceSource.CURSEFORGE && !CurseForgeProvider.isConfigured()) {
            main.toast("内置 CurseForge 配置不可用，请重新安装启动器");
            return;
        }
        if (target != null && InstanceStore.get().byId(target.id) == null) {
            target = null;
        }
        final Instance filter = target;
        final String gameVersion = filter == null ? "" : ResourceService.instanceGameVersion(filter);
        final String loader = filter == null || !project.kind().needsLoader()
                ? "" : ResourceService.instanceLoader(filter);
        AtomicBoolean closed = new AtomicBoolean();

        VBox card = modalCard(620);
        Label loadingLabel = Ui.label("正在获取可用版本…", "section-title");
        Label hint = Ui.label(versionFilterHint(filter, gameVersion, loader), "tiny");
        hint.setWrapText(true);
        ProgressBar bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(5);
        bar.getStyleClass().add("resource-progress");
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        HBox bottom = new HBox(10, Ui.hspacer(), cancel);
        card.getChildren().addAll(
                modalHeader(project.title(), project.source().label + " · " + project.kind().label, true),
                loadingLabel, hint, bar, bottom);

        main.showModal(card, true, () -> closed.set(true));
        Async.supply(() -> ResourceService.versions(project, gameVersion, loader), versions -> {
            if (closed.get()) {
                return;
            }
            if (versions == null || versions.isEmpty()) {
                showNoVersions(project, filter, gameVersion, loader);
            } else {
                showVersionChooser(project, filter, versions);
            }
        }, error -> {
            if (closed.get()) {
                return;
            }
            showVersionError(project, filter, error);
        });
    }

    private void showVersionChooser(ResourceProject project, Instance target,
                                    List<ResourceVersion> versions) {
        VBox card = modalCard(860);
        card.setMaxWidth(900);

        HBox header = modalHeader(project.title(),
                project.kind().label + " · 选择一个文件版本", true);

        Label hint = Ui.label(versionFilterHint(target, target == null ? "" : ResourceService.instanceGameVersion(target),
                target == null || !project.kind().needsLoader() ? "" : ResourceService.instanceLoader(target)), "tiny");
        hint.setWrapText(true);

        ListView<ResourceVersion> list = new ListView<>();
        list.getItems().setAll(versions);
        list.setPrefHeight(196);
        list.setMinHeight(170);
        list.setFocusTraversable(false);
        list.getStyleClass().add("resource-version-list");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ResourceVersion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(null);
                setGraphic(versionCell(item, target, project.kind()));
            }
        });

        Label selectedName = Ui.label("", "resource-version-title");
        selectedName.setWrapText(true);
        Label selectedMeta = Ui.label("", "file-meta");
        selectedMeta.setWrapText(true);
        Label warning = Ui.label("", "danger-text");
        warning.setWrapText(true);
        Label dependenciesTitle = Ui.label("前置模组与依赖", "form-label");
        Label dependenciesSummary = Ui.label("", "field-hint");
        dependenciesSummary.setWrapText(true);
        VBox dependenciesBox = new VBox(4);
        Label fileLabel = Ui.label("", "tiny");
        fileLabel.setWrapText(true);
        VBox dependencyPanel = new VBox(5, dependenciesTitle, dependenciesSummary, dependenciesBox);
        dependencyPanel.getStyleClass().add("resource-deps");
        VBox details = new VBox(5, selectedName, selectedMeta, warning, dependencyPanel, fileLabel);
        details.getStyleClass().add("resource-version-details");

        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        Button page = Ui.textButton("项目页面");
        page.setGraphic(Ui.icon(OPEN, 10));
        page.setDisable(project.pageUrl() == null || project.pageUrl().isBlank());
        page.setOnAction(e -> Ui.browse(project.pageUrl()));
        Button download = Ui.primaryButton(project.kind() == ResourceKind.MODPACK
                ? "下载并安装整合包" : "下载");
        download.setGraphic(Ui.icon(DOWNLOAD, 13));
        HBox bottom = new HBox(8, page, Ui.hspacer(), cancel, download);

        Runnable refresh = () -> {
            ResourceVersion selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                selectedName.setText("请选择一个版本");
                selectedMeta.setText("");
                warning.setVisible(false);
                warning.setManaged(false);
                dependenciesBox.getChildren().clear();
                dependenciesSummary.setText("");
                fileLabel.setText("");
                download.setDisable(true);
                return;
            }
            selectedName.setText(displayVersionName(selected));
            String published = selected.publishedAt() > 0
                    ? " · 发布于 " + Ui.formatDate(selected.publishedAt()) : "";
            selectedMeta.setText(selected.gameVersionSummary(6) + " · " + selected.loaderSummary()
                    + " · " + GameFiles.formatSize(selected.size()) + published);
            fileLabel.setText("文件：" + selected.fileName()
                    + (selected.channel() != null && !selected.channel().isBlank()
                    ? "  ·  " + channelLabel(selected.channel()) : ""));

            boolean showWarning = false;
            if (target != null && project.kind() != ResourceKind.MODPACK) {
                ResourceService.Compatibility compatibility =
                        ResourceService.compatibility(target, selected, project.kind());
                if (!compatibility.ok()) {
                    warning.setText("注意：该文件与实例 " + target.name + " " + compatibility.label()
                            + "，安装后可能无法正常使用。");
                    showWarning = true;
                }
            }
            warning.setVisible(showWarning);
            warning.setManaged(showWarning);

            List<ResourceDependency> dependencies = sortedDependencies(selected);
            List<ResourceDependency> required = new ArrayList<>();
            for (ResourceDependency dependency : dependencies) {
                if (dependency.type() == DependencyType.REQUIRED) {
                    required.add(dependency);
                }
            }
            if (required.isEmpty()) {
                dependenciesSummary.setText("该版本没有声明必需前置模组。");
            } else {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < required.size() && i < 3; i++) {
                    names.add(required.get(i).displayName());
                }
                String more = required.size() > 3 ? " 等 " + required.size() + " 个" : "";
                dependenciesSummary.setText("需要前置：" + String.join("、", names) + more);
            }

            dependenciesBox.getChildren().clear();
            if (dependencies.isEmpty()) {
                dependenciesBox.getChildren().add(Ui.label("没有声明任何依赖。", "tiny"));
            } else {
                int shown = Math.min(8, dependencies.size());
                for (int i = 0; i < shown; i++) {
                    dependenciesBox.getChildren().add(dependencyRow(dependencies.get(i)));
                }
                if (dependencies.size() > shown) {
                    dependenciesBox.getChildren().add(Ui.label(
                            "还有 " + (dependencies.size() - shown) + " 个依赖未显示", "tiny"));
                }
            }
            download.setDisable(false);
        };

        list.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> refresh.run());

        ResourceVersion initial = preferredVersion(versions, target, project.kind());
        list.getSelectionModel().select(initial);
        refresh.run();

        download.setOnAction(e -> {
            ResourceVersion selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                main.toast("请选择一个版本");
                return;
            }
            main.hideModal();
            downloadResource(project, selected);
        });

        card.getChildren().addAll(header, hint, list, details, bottom);
        main.showModal(card, true);
    }

    private Node versionCell(ResourceVersion version, Instance target, ResourceKind projectKind) {
        Label name = Ui.label(displayVersionName(version), "version-cell-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox nameRow = new HBox(7, name);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        if (version.channel() != null && !version.channel().isBlank()
                && !"release".equalsIgnoreCase(version.channel())) {
            nameRow.getChildren().add(chip(channelLabel(version.channel()),
                    "beta".equalsIgnoreCase(version.channel()) ? "chip-gold" : "chip-danger"));
        }

        String published = version.publishedAt() > 0
                ? " · " + Ui.formatDate(version.publishedAt()) : "";
        Label meta = Ui.label(version.gameVersionSummary(5) + " · " + version.loaderSummary()
                + " · " + GameFiles.formatSize(version.size()) + published, "version-cell-meta");
        meta.setMaxWidth(Double.MAX_VALUE);
        VBox text = new VBox(3, nameRow, meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);

        HBox tags = new HBox(5);
        tags.setAlignment(Pos.CENTER_RIGHT);
        int requiredCount = 0;
        for (ResourceDependency dependency : version.dependencies()) {
            if (dependency.type() == DependencyType.REQUIRED) {
                requiredCount++;
            }
        }
        if (requiredCount > 0) {
            tags.getChildren().add(chip("前置 " + requiredCount, "chip-gold"));
        }
        if (target != null && projectKind != ResourceKind.MODPACK) {
            ResourceService.Compatibility compatibility =
                    ResourceService.compatibility(target, version, projectKind);
            tags.getChildren().add(chip(compatibility.ok() ? "兼容" : compatibility.label(),
                    compatibility.ok() ? "chip-green" : "chip-danger"));
        }

        HBox row = new HBox(9, text, tags);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("version-cell");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setPrefWidth(780);
        return row;
    }

    private Node dependencyRow(ResourceDependency dependency) {
        String style = switch (dependency.type()) {
            case REQUIRED -> "chip-gold";
            case OPTIONAL -> "chip-muted";
            case EMBEDDED -> "chip-green";
            case INCOMPATIBLE -> "chip-danger";
            case TOOL -> "chip";
        };
        Label name = Ui.label(dependency.displayName(), "file-name");
        name.setWrapText(true);
        HBox row = new HBox(8, chip(dependency.type().label, style), name);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        if (dependency.projectId() != null && !dependency.projectId().isBlank()) {
            Button find = Ui.textButton("搜索");
            find.getStyleClass().add("btn-mini");
            find.setOnAction(e -> {
                main.hideModal();
                setSearchAndSearch(dependency.displayName());
            });
            row.getChildren().add(find);
        }
        return row;
    }

    private void showNoVersions(ResourceProject project, Instance target,
                                String gameVersion, String loader) {
        VBox card = modalCard(560);
        Label body = Ui.label(target == null
                ? "该项目目前没有可下载的文件版本。"
                : "按实例 " + target.name + versionFilterSuffix(gameVersion, loader)
                + " 筛选后没有可用版本。", "muted");
        body.setWrapText(true);
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (target != null) {
            Button all = Ui.primaryButton("查看全部版本");
            all.setOnAction(e -> {
                main.hideModal();
                openVersions(project, null);
            });
            actions.getChildren().add(all);
        }
        Button page = Ui.ghostButton("项目页面");
        page.setDisable(project.pageUrl() == null || project.pageUrl().isBlank());
        page.setOnAction(e -> Ui.browse(project.pageUrl()));
        Button close = Ui.ghostButton("关闭");
        close.setOnAction(e -> main.hideModal());
        actions.getChildren().addAll(page, close);
        card.getChildren().addAll(modalHeader("没有可用版本", project.title(), true), body, actions);
        main.showModal(card, true);
    }

    private void showVersionError(ResourceProject project, Instance target, Throwable error) {
        VBox card = modalCard(580);
        Label body = Ui.label(message(error), "danger-text");
        body.setWrapText(true);
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Button retry = Ui.primaryButton("重试");
        retry.setOnAction(e -> {
            main.hideModal();
            openVersions(project, target);
        });
        actions.getChildren().add(retry);
        Button page = Ui.ghostButton("项目页面");
        page.setDisable(project.pageUrl() == null || project.pageUrl().isBlank());
        page.setOnAction(e -> Ui.browse(project.pageUrl()));
        Button close = Ui.ghostButton("关闭");
        close.setOnAction(e -> main.hideModal());
        actions.getChildren().addAll(page, close);
        card.getChildren().addAll(modalHeader("获取版本失败", project.title(), true), body, actions);
        main.showModal(card, true);
    }

    // ------------------------------------------------------------ installation

    private void downloadResource(ResourceProject project, ResourceVersion version) {
        ProgressBox progress = showProgress("正在下载", project.title() + " · " + version.versionNumber());
        Async.supply(() -> {
            Path file = ResourceService.download(project, version, progress.progress(),
                    () -> progress.progress().isCancelled());
            ContentInstallService.ModpackInfo info = project.kind() == ResourceKind.MODPACK
                    ? ContentInstallService.inspectModpack(file, project.source()) : null;
            return new Downloaded(file, info);
        }, result -> {
            main.hideModal();
            main.setModalDismissible(true);
            if (project.kind() == ResourceKind.MODPACK) {
                if (result.info() == null) {
                    main.showError("整合包读取失败", "下载完成，但无法读取整合包清单。");
                } else {
                    showModpackDialog(project, version, result.file(), result.info());
                }
            } else {
                showTargetDialog(project, version, result.file());
            }
        }, error -> {
            main.hideModal();
            main.setModalDismissible(true);
            if (progress.progress().isCancelled()) {
                main.toast("已取消下载");
            } else {
                main.showError(project.kind() == ResourceKind.MODPACK ? "整合包读取失败" : "下载失败",
                        message(error));
            }
        });
    }

    private void showTargetDialog(ResourceProject project, ResourceVersion version, Path file) {
        List<Instance> instances = InstanceStore.get().all();
        if (instances.isEmpty()) {
            showNoInstanceDialog(project, file);
            return;
        }

        VBox card = modalCard(680);
        card.setMaxWidth(760);
        Label fileLabel = Ui.label(file.getFileName() + " · " + fileSize(file), "file-meta");
        Label hint = Ui.label("选择一个实例，文件会被复制到该实例的 "
                + folderLabel(project.kind()) + " 文件夹；不同实例互不影响。", "tiny");
        hint.setWrapText(true);

        ToggleGroup group = new ToggleGroup();
        VBox rows = new VBox(7);
        RadioButton preferredRadio = null;
        Instance preferred = targetInstance();
        if (preferred == null || !containsInstance(instances, preferred)) {
            preferred = null;
            for (Instance instance : instances) {
                if (ResourceService.compatibility(instance, version, project.kind()).ok()) {
                    preferred = instance;
                    break;
                }
            }
            if (preferred == null) {
                preferred = instances.get(0);
            }
        }

        for (Instance instance : instances) {
            RadioButton radio = new RadioButton();
            radio.setToggleGroup(group);
            radio.setUserData(instance);

            Label name = Ui.label(instance.name, "file-name");
            name.setWrapText(true);
            Label meta = Ui.label(instanceMeta(instance), "file-meta");
            VBox text = new VBox(2, name, meta);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);

            ResourceService.Compatibility compatibility =
                    ResourceService.compatibility(instance, version, project.kind());
            Label badge = chip(compatibility.ok() ? "兼容" : compatibility.label(),
                    compatibility.ok() ? "chip-green" : "chip-danger");

            HBox row = new HBox(10, radio, text, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("file-row");
            row.setPadding(new Insets(10, 12, 10, 12));
            row.setCursor(Cursor.HAND);
            row.setOnMouseClicked(e -> radio.setSelected(true));
            rows.getChildren().add(row);
            if (instance == preferred) {
                preferredRadio = radio;
            }
        }
        if (preferredRadio != null) {
            preferredRadio.setSelected(true);
        }

        ScrollPane scrollPane = new ScrollPane(rows);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPrefHeight(Math.min(296, 64 * instances.size() + 8));
        scrollPane.setMaxHeight(310);
        scrollPane.getStyleClass().add("modal-scroll");

        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        Button install = Ui.primaryButton("安装到这里");
        install.setGraphic(Ui.icon(DOWNLOAD, 13));
        install.setOnAction(e -> {
            if (group.getSelectedToggle() == null
                    || !(group.getSelectedToggle().getUserData() instanceof Instance target)) {
                main.toast("请选择要安装到的实例");
                return;
            }
            main.hideModal();
            installResource(project, version, file, target);
        });
        HBox bottom = new HBox(8, Ui.hspacer(), cancel, install);

        card.getChildren().addAll(
                modalHeader("安装到哪个实例？", project.title() + " · " + version.versionNumber(), true),
                fileLabel, hint, scrollPane, bottom);
        main.showModal(card, true);
    }

    private void showNoInstanceDialog(ResourceProject project, Path file) {
        VBox card = modalCard(540);
        Label body = Ui.label("文件已经下载好了，但启动器里还没有游戏实例。先安装一个游戏版本，"
                + "之后可以再下载并选择实例安装。", "muted");
        body.setWrapText(true);
        Button installGame = Ui.primaryButton("安装游戏版本");
        installGame.setOnAction(e -> {
            main.hideModal();
            main.openInstallDialog();
        });
        Button folder = Ui.ghostButton("打开下载文件夹");
        folder.setOnAction(e -> openFolder(file.getParent()));
        Button close = Ui.ghostButton("关闭");
        close.setOnAction(e -> main.hideModal());
        HBox actions = new HBox(8, Ui.hspacer(), folder, close, installGame);
        card.getChildren().addAll(modalHeader("还没有游戏实例", project.title(), true), body, actions);
        main.showModal(card, true);
    }

    private void installResource(ResourceProject project, ResourceVersion version, Path file,
                                 Instance target) {
        ProgressBox progress = showProgress("正在安装",
                project.title() + " → " + target.name);
        main.setModalDismissible(false);
        Async.supply(() -> ContentInstallService.installResource(target, project.kind(), file,
                progress.progress(), () -> progress.progress().isCancelled()), installed -> {
            main.hideModal();
            main.setModalDismissible(true);
            main.refreshAll();
            if (installed != null) {
                main.toastSuccess("已安装到 " + target.name);
            }
            showInstalledDialog(project, target, installed, file);
        }, error -> {
            main.hideModal();
            main.setModalDismissible(true);
            if (progress.progress().isCancelled()) {
                main.toast("已取消安装");
            } else {
                main.showError("安装失败", message(error));
            }
        });
    }

    private void showInstalledDialog(ResourceProject project, Instance target, Path installed, Path file) {
        VBox card = modalCard(560);
        Path shown = installed != null ? installed : file;
        Label body = Ui.label("已将 " + (file == null ? project.title() : file.getFileName())
                + " 安装到实例 " + target.name + "。", "muted");
        body.setWrapText(true);
        Label path = Ui.label(shown == null ? "" : shown.toString(), "mono");
        path.setWrapText(true);
        path.getStyleClass().add("path-text");

        Button folder = Ui.ghostButton("打开文件夹");
        folder.setGraphic(Ui.icon(FOLDER, 12));
        folder.setDisable(shown == null);
        folder.setOnAction(e -> reveal(shown));
        Button launch = Ui.primaryButton("启动游戏");
        launch.setOnAction(e -> {
            main.hideModal();
            main.launch(target);
        });
        Button close = Ui.ghostButton("完成");
        close.setOnAction(e -> main.hideModal());
        HBox actions = new HBox(8, Ui.hspacer(), folder, close, launch);
        card.getChildren().addAll(modalHeader("安装完成", project.title(), true), body, path, actions);
        main.showModal(card, true);
    }

    // --------------------------------------------------------------- modpacks

    /** File-picker entry point for users who drag-and-drop is not convenient. */
    private void chooseModpack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择整合包");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Minecraft 整合包 (*.mrpack, *.zip)",
                        "*.mrpack", "*.zip"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showOpenDialog(main.stage());
        if (file != null) {
            installDroppedModpack(file.toPath());
        }
    }

    /**
     * Installs a .mrpack or CurseForge .zip dropped onto the launcher window.
     * The archive format is detected from its manifest, which may be at the
     * archive root or inside a top-level folder.
     */
    public void installDroppedModpack(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) {
            main.showError("无法安装整合包", "文件不存在或不可读。");
            return;
        }
        main.toast("正在读取整合包…");
        Async.supply(() -> ContentInstallService.inspectModpack(archive), detected -> {
            main.hideToast();
            if (detected.source() == ResourceSource.CURSEFORGE
                    && !CurseForgeProvider.isConfigured()) {
                showCurseForgeKeyRequired();
                return;
            }
            ContentInstallService.ModpackInfo info = detected.info();
            String title = fileStem(archive);
            ResourceProject project = new ResourceProject(detected.source(), "local", "",
                    title, "", "从本地拖入的整合包", "", "",
                    0, 0, System.currentTimeMillis(),
                    Set.of(info.mcVersion()), loaderSet(info), ResourceKind.MODPACK);
            ResourceVersion version = new ResourceVersion(detected.source(), "local", "local",
                    info.version().isBlank() ? title : info.version(),
                    info.version().isBlank() ? "本地文件" : info.version(), "release",
                    List.of(info.mcVersion()), List.copyOf(loaderSet(info)),
                    archive.getFileName().toString(), "", "", fileLength(archive),
                    System.currentTimeMillis(), List.of());
            showModpackDialog(project, version, archive, info);
        }, error -> {
            main.hideToast();
            main.showError("无法识别整合包", message(error));
        });
    }

    private void showCurseForgeKeyRequired() {
        main.showError("无法安装整合包", "内置 CurseForge 配置异常，请重新安装启动器。");
    }

    private static Set<String> loaderSet(ContentInstallService.ModpackInfo info) {
        return info.loader() == com.sslauncher.model.LoaderType.VANILLA
                ? Set.of() : Set.of(info.loader().id);
    }

    private static long fileLength(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fileStem(Path file) {
        String name = file == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void showModpackDialog(ResourceProject project, ResourceVersion version, Path file,
                                   ContentInstallService.ModpackInfo info) {
        VBox card = modalCard(620);
        String defaultName = info.name() == null || info.name().isBlank()
                ? project.title() : info.name().trim();
        if (defaultName.length() > 64) {
            defaultName = defaultName.substring(0, 64);
        }

        TextField nameField = new TextField(defaultName);
        nameField.setPromptText("实例名称");

        VBox infoRows = new VBox(7, infoRow("Minecraft", info.mcVersion()),
                infoRow("加载器", info.loader() == com.sslauncher.model.LoaderType.VANILLA
                        ? "原版" : info.loader().label + " " + info.loaderVersion()),
                infoRow("整合包版本", version.versionNumber()),
                infoRow("文件数量", info.fileCount() + " 个"));
        infoRows.getStyleClass().add("card-flat");
        infoRows.setPadding(new Insets(13, 15, 13, 15));

        Label notice = Ui.label("整合包会安装到一个全新的独立实例，不会修改现有实例。"
                + "安装过程中会下载清单里的模组；失败或取消时会自动清理新实例。", "tiny");
        notice.setWrapText(true);

        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        Button install = Ui.primaryButton("创建并安装");
        install.setGraphic(Ui.icon(DOWNLOAD, 13));
        install.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isBlank()) {
                main.toast("请输入实例名称");
                return;
            }
            if (name.length() > 64) {
                name = name.substring(0, 64);
            }
            main.hideModal();
            installModpack(project, version, file, name, info);
        });
        HBox bottom = new HBox(8, Ui.hspacer(), cancel, install);

        card.getChildren().addAll(
                modalHeader("安装整合包", project.title() + " · " + version.versionNumber(), true),
                infoRows,
                Ui.label("实例名称", "form-label"), nameField, notice, bottom);
        main.showModal(card, true);
    }

    private void installModpack(ResourceProject project, ResourceVersion version, Path file,
                                String name, ContentInstallService.ModpackInfo info) {
        ProgressBox progress = showProgress("正在安装整合包",
                info.mcVersion() + " · " + info.loader().label + " · " + name);
        main.setModalDismissible(false);
        Async.supply(() -> ContentInstallService.installModpack(project, version, file, name,
                progress.progress(), () -> progress.progress().isCancelled()), installed -> {
            main.hideModal();
            main.setModalDismissible(true);
            main.refreshAll();
            showModpackSuccess(installed);
        }, error -> {
            main.hideModal();
            main.setModalDismissible(true);
            if (progress.progress().isCancelled()) {
                main.toast("已取消安装");
            } else {
                main.showError("整合包安装失败", message(error));
            }
        });
    }

    private void showModpackSuccess(ContentInstallService.InstalledModpack installed) {
        if (installed == null) {
            return;
        }
        Instance instance = installed.instance();
        ContentInstallService.ModpackInfo info = installed.info();

        VBox card = modalCard(560);
        Label body = Ui.label("已创建实例 " + instance.name + "，整合包文件已经安装完成。", "muted");
        body.setWrapText(true);
        VBox infoRows = new VBox(7, infoRow("Minecraft", info.mcVersion()),
                infoRow("加载器", info.loader() == com.sslauncher.model.LoaderType.VANILLA
                        ? "原版" : info.loader().label + " " + info.loaderVersion()),
                infoRow("文件数量", info.fileCount() + " 个"));
        infoRows.getStyleClass().add("card-flat");
        infoRows.setPadding(new Insets(13, 15, 13, 15));

        Button launch = Ui.primaryButton("启动游戏");
        launch.setOnAction(e -> {
            main.hideModal();
            main.launch(instance);
        });
        Button versions = Ui.ghostButton("查看实例");
        versions.setOnAction(e -> {
            main.hideModal();
            main.showPage(MainWindow.Page.VERSIONS);
        });
        Button close = Ui.ghostButton("完成");
        close.setOnAction(e -> main.hideModal());
        HBox actions = new HBox(8, Ui.hspacer(), versions, close, launch);

        card.getChildren().addAll(
                modalHeader("整合包安装完成", instance.name, true), body, infoRows, actions);
        main.showModal(card, true);
    }

    private Node infoRow(String label, String value) {
        Label name = Ui.label(label, "form-label");
        name.setMinWidth(92);
        Label text = Ui.label(value == null ? "" : value, "file-name");
        text.setWrapText(true);
        HBox row = new HBox(12, name, text);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        return row;
    }

    // ------------------------------------------------------------------ shared

    private ProgressBox showProgress(String heading, String subtitle) {
        com.sslauncher.core.Progress progress = new com.sslauncher.core.Progress();
        VBox card = modalCard(520);
        Label stage = Ui.label(progress.getStage().isBlank() ? "准备中…" : progress.getStage(),
                "section-title");
        Label detail = Ui.label(progress.getDetail(), "tiny");
        detail.setWrapText(true);
        ProgressBar bar = new ProgressBar(progress.isIndeterminate() ? -1 : progress.fraction());
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(6);
        bar.getStyleClass().add("resource-progress");
        Label percent = Ui.label("", "tiny");
        percent.setMinWidth(130);
        percent.setAlignment(Pos.CENTER_RIGHT);
        HBox barRow = new HBox(9, bar, percent);
        barRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(bar, Priority.ALWAYS);
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> {
            progress.cancel();
            cancel.setDisable(true);
            cancel.setText("正在取消…");
        });
        HBox bottom = new HBox(8, Ui.hspacer(), cancel);
        Label note = Ui.label("下载文件会校验 SHA-1；取消不会留下不完整的实例。", "tiny");
        note.setWrapText(true);
        card.getChildren().addAll(modalHeader(heading, subtitle, false),
                stage, barRow, detail, note, bottom);
        main.showModal(card, false);

        long[] sampleDone = {progress.getDone()};
        long[] sampleAt = {System.nanoTime()};
        String[] speed = {""};
        progress.addListener(p -> Platform.runLater(() -> {
            if (p.getStage() != null && !p.getStage().isBlank()) {
                stage.setText(p.getStage());
            }
            if (p.getDetail() != null && !p.getDetail().isBlank()) {
                detail.setText(p.getDetail());
            }
            bar.setProgress(p.isIndeterminate() ? -1 : p.fraction());
            long now = System.nanoTime();
            long done = p.getDone();
            if (now - sampleAt[0] >= 500_000_000L) {
                double rate = (done - sampleDone[0]) / ((now - sampleAt[0]) / 1_000_000_000.0);
                speed[0] = formatSpeed(rate);
                sampleDone[0] = done;
                sampleAt[0] = now;
            }
            String percentText = p.isIndeterminate() || p.fraction() < 0
                    ? "" : Math.round(p.fraction() * 100) + "%";
            percent.setText(speed[0].isBlank() ? percentText
                    : percentText + "  " + speed[0]);
        }));
        return new ProgressBox(progress);
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (!(bytesPerSecond > 1)) {
            return "";
        }
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
        }
        if (bytesPerSecond >= 1024) {
            return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024);
        }
        return String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond);
    }

    private record ProgressBox(com.sslauncher.core.Progress progress) {
    }

    private VBox modalCard(double width) {
        VBox card = new VBox(14);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(width);
        card.setMaxWidth(width);
        return card;
    }

    private HBox modalHeader(String title, String subtitle, boolean close) {
        Label heading = Ui.label(title, "modal-title");
        heading.setWrapText(true);
        heading.setMaxWidth(Double.MAX_VALUE);
        VBox text = new VBox(3, heading);
        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = Ui.label(subtitle, "muted");
            sub.setWrapText(true);
            text.getChildren().add(sub);
        }
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, text);
        row.setAlignment(Pos.TOP_LEFT);
        if (close) {
            row.getChildren().add(closeButton());
        }
        return row;
    }

    private Button closeButton() {
        Button button = new Button();
        button.setGraphic(Ui.icon(CLOSE, 12));
        button.getStyleClass().add("modal-close");
        button.setFocusTraversable(false);
        button.setOnAction(e -> main.hideModal());
        return button;
    }

    private void openFolder(Path path) {
        if (path == null) {
            return;
        }
        try {
            GameFiles.openDirectory(path);
        } catch (Exception e) {
            main.toast("无法打开文件夹：" + path);
        }
    }

    private void reveal(Path path) {
        if (path == null) {
            return;
        }
        try {
            GameFiles.reveal(path);
        } catch (Exception e) {
            openFolder(path.getParent());
        }
    }

    // ------------------------------------------------------------------ format

    private String versionFilterHint(Instance target, String gameVersion, String loader) {
        if (target == null) {
            return "未按实例筛选，显示所有可用版本。下载完成后可以选择要安装到的实例。";
        }
        return "按实例 " + target.name + versionFilterSuffix(gameVersion, loader)
                + " 筛选；仍可在下载后选择其他实例。";
    }

    private String versionFilterSuffix(String gameVersion, String loader) {
        StringBuilder suffix = new StringBuilder();
        if (gameVersion != null && !gameVersion.isBlank()) {
            suffix.append(" · Minecraft ").append(gameVersion);
        }
        if (loader != null && !loader.isBlank()) {
            suffix.append(" · ").append(loaderLabel(loader));
        }
        return suffix.toString();
    }

    private String instanceMeta(Instance instance) {
        String label = instance.versionLabel();
        String gameVersion = ResourceService.instanceGameVersion(instance);
        if (gameVersion != null && !gameVersion.isBlank() && !label.contains(gameVersion)) {
            return gameVersion + " · " + label;
        }
        return label;
    }

    private static boolean containsInstance(List<Instance> instances, Instance wanted) {
        for (Instance instance : instances) {
            if (instance.id.equals(wanted.id)) {
                return true;
            }
        }
        return false;
    }

    private static String folderLabel(ResourceKind kind) {
        return switch (kind) {
            case MOD -> "mods";
            case RESOURCE_PACK -> "resourcepacks";
            case SHADER -> "shaderpacks";
            case MODPACK -> "实例";
        };
    }

    private static String displayVersionName(ResourceVersion version) {
        if (version.name() != null && !version.name().isBlank()) {
            return version.name();
        }
        if (version.versionNumber() != null && !version.versionNumber().isBlank()) {
            return version.versionNumber();
        }
        return version.fileName();
    }

    private static String channelLabel(String channel) {
        return switch (channel == null ? "" : channel.toLowerCase(Locale.ROOT)) {
            case "beta" -> "Beta";
            case "alpha" -> "Alpha";
            default -> "Release";
        };
    }

    /** Prefers the newest compatible release file over beta/alpha builds. */
    private static ResourceVersion preferredVersion(List<ResourceVersion> versions, Instance target,
                                                     ResourceKind kind) {
        ResourceVersion fallback = null;
        for (ResourceVersion version : versions) {
            boolean compatible = target == null || kind == ResourceKind.MODPACK
                    || ResourceService.compatibility(target, version, kind).ok();
            if (!compatible) {
                continue;
            }
            if (fallback == null) {
                fallback = version;
            }
            if (version.channel() == null || version.channel().isBlank()
                    || "release".equalsIgnoreCase(version.channel())) {
                return version;
            }
        }
        return fallback == null ? versions.get(0) : fallback;
    }

    private static List<ResourceDependency> sortedDependencies(ResourceVersion version) {
        List<ResourceDependency> dependencies = new ArrayList<>(version.dependencies());
        dependencies.sort(Comparator.comparingInt(dependency ->
                dependency.type() == null ? 99 : dependency.type().ordinal()));
        return dependencies;
    }

    private static String supportSummary(ResourceProject project, Instance target) {
        boolean versionsUnknown = project.gameVersions() == null || project.gameVersions().isEmpty();
        String gameVersions = gameVersionSummary(project.gameVersions(), 3);
        String loaders = loadersSummary(project.loaders());
        if (versionsUnknown && loaders.isBlank()) {
            return "可用版本与加载器由具体文件决定";
        }
        StringBuilder summary = new StringBuilder(versionsUnknown
                ? "版本由具体文件决定" : "支持 " + gameVersions);
        String matched = target == null ? "" : ResourceService.instanceGameVersion(target);
        if (!matched.isBlank() && project.supportsGameVersion(matched)
                && !gameVersions.contains(matched)) {
            summary.append(" · 已匹配 ").append(matched);
        }
        if (!loaders.isBlank()) {
            summary.append(" · ").append(loaders);
        }
        return summary.toString();
    }

    private static String gameVersionSummary(Set<String> versions, int max) {
        if (versions == null || versions.isEmpty()) {
            return "通用";
        }
        List<String> list = new ArrayList<>(versions);
        int from = Math.max(0, list.size() - Math.max(1, max));
        List<String> tail = new ArrayList<>(list.subList(from, list.size()));
        Collections.reverse(tail);
        String joined = String.join(", ", tail);
        return list.size() > max ? joined + " 等" : joined;
    }

    private static String loadersSummary(Set<String> loaders) {
        if (loaders == null || loaders.isEmpty()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (String loader : loaders) {
            labels.add(loaderLabel(loader));
        }
        return String.join(" / ", labels);
    }

    private static String loaderLabel(String loader) {
        return switch (ResourceVersion.normalizeLoader(loader)) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "" -> "通用";
            default -> loader;
        };
    }

    private static String formatCount(long count) {
        if (count >= 100_000_000L) {
            return trimmed(count / 100_000_000.0) + "亿";
        }
        if (count >= 10_000L) {
            return trimmed(count / 10_000.0) + "万";
        }
        return String.valueOf(Math.max(0, count));
    }

    private static String trimmed(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static String shorten(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String fileSize(Path file) {
        try {
            return GameFiles.formatSize(Files.size(file));
        } catch (Exception e) {
            return "大小未知";
        }
    }


    private static String message(Throwable error) {
        Throwable current = error;
        while (current != null && (current.getMessage() == null || current.getMessage().isBlank())
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null) {
            return "未知错误";
        }
        String text = current.getMessage();
        return text == null || text.isBlank() ? current.getClass().getSimpleName() : text;
    }
}
