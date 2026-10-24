package com.sslauncher.ui.dialogs;

import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.model.VersionManifest;
import com.sslauncher.service.InstallService;
import com.sslauncher.service.LoaderService;
import com.sslauncher.service.VersionService;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.Ui;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class InstallDialog {
    private static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z";
    private static final String PLAY = "M8 5v14l11-7z";
    private static final String CHECK = "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";

    private final MainWindow main;
    private VBox card;
    private final ObservableList<VersionManifest.Entry> visible = FXCollections.observableArrayList();
    private List<VersionManifest.Entry> all = List.of();
    private final ListView<VersionManifest.Entry> list = new ListView<>(visible);
    private final TextField search = new TextField();
    private final ToggleGroup filterGroup = new ToggleGroup();
    private final ToggleGroup loaderGroup = new ToggleGroup();
    private final ComboBox<LoaderService.LoaderVersion> loaderVersion = new ComboBox<>();
    private final TextField instanceName = new TextField();
    private final Label loaderHint = Ui.label("", "tiny");
    private final Label summary = Ui.label("", "muted");
    private final Label error = Ui.label("", "danger-text");
    private final Button start = Ui.primaryButton("开始安装");
    private final AtomicInteger loaderGeneration = new AtomicInteger();
    private boolean installing;
    private boolean closed;
    private String preselect;
    private String latestRelease = "";
    private VersionManifest.Entry selectedVersion;

    public InstallDialog(MainWindow main) {
        this.main = main;
    }

    public void preselect(String mcVersion) {
        this.preselect = mcVersion;
    }

    public void show() {
        card = new VBox(16);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(930);
        card.setMaxWidth(960);
        card.setPrefHeight(628);
        card.setMaxHeight(660);

        Label title = Ui.label("安装游戏", "modal-title");
        Label sub = Ui.label("选择 Minecraft 版本与加载器，所有文件都会自动校验。", "tiny");
        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(Ui.icon(CLOSE, 14));
        close.setOnAction(e -> {
            if (installing) {
                main.toast("安装进行中，请先取消安装");
            } else {
                main.hideModal();
            }
        });
        HBox header = new HBox(12, new VBox(3, title, sub), Ui.hspacer(), close);
        header.setAlignment(Pos.TOP_LEFT);

        HBox content = new HBox(18, versionPane(), optionPane());
        HBox.setHgrow(content, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);
        card.getChildren().addAll(header, content);
        main.showModal(card, true, () -> closed = true);
        loadManifest();
    }

    // ---------------------------------------------------------------- versions

    private Node versionPane() {
        VBox pane = new VBox(10);
        pane.setPrefWidth(398);
        pane.setMinWidth(360);
        pane.setMaxWidth(420);

        search.setPromptText("搜索版本，例如 1.21.4");
        search.textProperty().addListener((obs, old, value) -> applyFilter());

        HBox filters = new HBox(7,
                filterButton("正式版", "release", true),
                filterButton("快照", "snapshot", false),
                filterButton("全部", "all", false));

        list.getStyleClass().add("version-list");
        list.setPlaceholder(Ui.label("正在获取版本列表…", "muted"));
        list.setPrefHeight(440);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(VersionManifest.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setGraphic(null);
                    return;
                }
                Label id = Ui.label(entry.id, "version-cell-name");
                Label meta = Ui.label(entry.typeLabel() + "  ·  " + date(entry.releaseTime), "version-cell-meta");
                VBox text = new VBox(2, id, meta);
                if (entry.id.equals(latestRelease)) {
                    text.getChildren().add(Ui.label("最新版本", "chip"));
                }
                HBox row = new HBox(8, text);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("version-cell");
                row.setPrefWidth(356);
                setGraphic(row);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, entry) -> {
            if (entry != null) {
                selectVersion(entry);
            }
        });

        pane.getChildren().addAll(search, filters, list);
        return pane;
    }

    private ToggleButton filterButton(String text, String value, boolean selected) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(filterGroup);
        button.setUserData(value);
        button.setSelected(selected);
        button.setOnAction(e -> applyFilter());
        return button;
    }

    private String date(String iso) {
        if (iso == null || iso.length() < 10) {
            return "";
        }
        return iso.substring(0, 10);
    }

    private void loadManifest() {
        Async.supply(VersionService::manifest, manifest -> {
            if (closed) {
                return;
            }
            all = manifest.versions == null ? List.of() : manifest.versions;
            latestRelease = manifest.latest == null || manifest.latest.release == null
                    ? "" : manifest.latest.release;
            applyFilter();
            VersionManifest.Entry target = null;
            if (preselect != null) {
                target = all.stream().filter(e -> preselect.equals(e.id)).findFirst().orElse(null);
            }
            if (target == null && manifest.latest != null) {
                target = all.stream().filter(e -> manifest.latest.release != null
                        && manifest.latest.release.equals(e.id)).findFirst().orElse(null);
            }
            if (target == null && !all.isEmpty()) {
                target = all.get(0);
            }
            if (target != null) {
                if (!visible.contains(target)) {
                    search.clear();
                    selectFilter("all");
                    applyFilter();
                }
                list.getSelectionModel().select(target);
                list.scrollTo(target);
                selectVersion(target);
            }
        }, e -> {
            list.setPlaceholder(Ui.label("版本列表获取失败：" + e.getMessage(), "danger-text"));
            error.setText("无法获取版本列表，请检查网络或下载源设置。");
        });
    }

    private void selectFilter(String value) {
        for (var toggle : filterGroup.getToggles()) {
            toggle.setSelected(value.equals(toggle.getUserData()));
        }
    }

    private void applyFilter() {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String filter = "release";
        for (var toggle : filterGroup.getToggles()) {
            if (toggle.isSelected()) {
                filter = String.valueOf(toggle.getUserData());
            }
        }
        List<VersionManifest.Entry> filtered = new ArrayList<>();
        for (VersionManifest.Entry entry : all) {
            if (entry.id == null) {
                continue;
            }
            boolean typeOk = "all".equals(filter)
                    || ("release".equals(filter) && entry.isRelease())
                    || ("snapshot".equals(filter) && entry.isSnapshot());
            if (!typeOk) {
                continue;
            }
            if (!query.isEmpty() && !entry.id.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            filtered.add(entry);
        }
        VersionManifest.Entry keep = list.getSelectionModel().getSelectedItem();
        visible.setAll(filtered);
        if (keep != null && filtered.contains(keep)) {
            list.getSelectionModel().select(keep);
        } else if (selectedVersion != null && filtered.contains(selectedVersion)) {
            list.getSelectionModel().select(selectedVersion);
        }
    }

    // ---------------------------------------------------------------- options

    private Node optionPane() {
        VBox pane = new VBox(13);
        pane.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pane, Priority.ALWAYS);

        Label loaderLabel = Ui.label("加载器", "form-label");
        HBox loaders = new HBox(7);
        for (LoaderType type : LoaderType.values()) {
            ToggleButton button = new ToggleButton(type.label);
            button.setToggleGroup(loaderGroup);
            button.setUserData(type);
            button.setTooltip(new Tooltip(type.description));
            button.setOnAction(e -> {
                loaderHint.setText(type.description);
                if (type == LoaderType.VANILLA) {
                    loaderVersion.setDisable(true);
                    loaderVersion.getItems().clear();
                    loaderVersion.setPromptText("原版无需加载器");
                } else if (selectedVersion != null) {
                    loadLoaderVersions(type, selectedVersion.id);
                }
                updateSummary();
            });
            loaders.getChildren().add(button);
            if (type == LoaderType.VANILLA) {
                button.setSelected(true);
            }
        }
        loaderHint.setText(LoaderType.VANILLA.description);

        Label versionLabel = Ui.label("加载器版本", "form-label");
        loaderVersion.setMaxWidth(Double.MAX_VALUE);
        loaderVersion.setPromptText("选择加载器版本");
        loaderVersion.setDisable(true);
        loaderVersion.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(LoaderService.LoaderVersion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.label() + (item.recommended() ? "  ·  推荐" : ""));
                }
            }
        });
        loaderVersion.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(LoaderService.LoaderVersion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.label() + (item.recommended() ? "  ·  推荐" : ""));
                }
            }
        });
        loaderVersion.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> updateSummary());

        Label nameLabel = Ui.label("实例名称", "form-label");
        instanceName.setPromptText("留空则使用默认名称");
        instanceName.textProperty().addListener((obs, old, value) -> updateSummary());

        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Region spacer = Ui.vspacer();
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        start.setOnAction(e -> startInstall());
        HBox buttons = new HBox(9, Ui.hspacer(), cancel, start);

        VBox summaryCard = new VBox(4, Ui.label("即将安装", "tiny"), summary);
        summaryCard.getStyleClass().add("card-flat");

        pane.getChildren().addAll(loaderLabel, loaders, loaderHint,
                versionLabel, loaderVersion,
                nameLabel, instanceName,
                summaryCard, error, spacer, buttons);
        return pane;
    }

    private LoaderType selectedLoader() {
        for (var toggle : loaderGroup.getToggles()) {
            if (toggle.isSelected()) {
                return (LoaderType) toggle.getUserData();
            }
        }
        return LoaderType.VANILLA;
    }

    private void selectVersion(VersionManifest.Entry entry) {
        selectedVersion = entry;
        LoaderType type = selectedLoader();
        if (type != LoaderType.VANILLA) {
            loadLoaderVersions(type, entry.id);
        }
        loaderVersion.setDisable(type == LoaderType.VANILLA);
        updateSummary();
    }

    private void loadLoaderVersions(LoaderType type, String mcVersion) {
        int generation = loaderGeneration.incrementAndGet();
        loaderVersion.getItems().clear();
        loaderVersion.setPromptText("正在获取版本…");
        loaderVersion.setDisable(true);
        error.setVisible(false);
        error.setManaged(false);
        Async.supply(() -> LoaderService.versions(type, mcVersion), versions -> {
            if (closed || generation != loaderGeneration.get()) {
                return;
            }
            loaderVersion.getItems().setAll(versions);
            if (versions.isEmpty()) {
                loaderVersion.setPromptText("没有可用版本");
                error.setText("该 Minecraft 版本没有可用的 " + type.label + " 版本，请换一个版本或加载器。");
                error.setVisible(true);
                error.setManaged(true);
            } else {
                loaderVersion.getSelectionModel().select(0);
                loaderVersion.setDisable(false);
            }
            updateSummary();
        }, e -> {
            if (closed || generation != loaderGeneration.get()) {
                return;
            }
            loaderVersion.setPromptText("获取失败");
            error.setText("无法获取 " + type.label + " 版本列表：" + e.getMessage());
            error.setVisible(true);
            error.setManaged(true);
            updateSummary();
        });
    }

    private void updateSummary() {
        if (selectedVersion == null) {
            summary.setText("请先选择一个 Minecraft 版本");
        } else {
            LoaderType type = selectedLoader();
            String loader = type == LoaderType.VANILLA ? "原版"
                    : type.label + " " + (loaderVersion.getValue() == null ? "" : loaderVersion.getValue().label());
            summary.setText(selectedVersion.id + "  ·  " + loader.trim());
        }
        start.setDisable(installing || selectedVersion == null
                || (selectedLoader() != LoaderType.VANILLA && loaderVersion.getValue() == null));
    }

    // ----------------------------------------------------------------- install

    private void startInstall() {
        LoaderType type = selectedLoader();
        if (selectedVersion == null) {
            showError("请先选择 Minecraft 版本");
            return;
        }
        if (type != LoaderType.VANILLA && loaderVersion.getValue() == null) {
            showError("请选择加载器版本");
            return;
        }
        error.setVisible(false);
        error.setManaged(false);
        installing = true;
        main.setModalDismissible(false);
        String mc = selectedVersion.id;
        String loader = type == LoaderType.VANILLA ? "" : loaderVersion.getValue().id();
        String name = instanceName.getText() == null ? "" : instanceName.getText().trim();
        Progress progress = new Progress();
        showProgress(mc, type, loader);
        ProgressBar bar = progressBar;
        Label stage = progressStage;
        Label detail = progressDetail;
        Button cancel = progressCancel;
        cancel.setOnAction(e -> {
            progress.cancel();
            cancel.setDisable(true);
            cancel.setText("正在取消…");
        });

        progress.addListener(p -> Platform.runLater(() -> {
            stage.setText(p.getStage());
            if (p.getDetail() != null && !p.getDetail().isBlank()) {
                detail.setText(p.getDetail());
            }
            bar.setProgress(p.isIndeterminate() ? -1 : p.fraction());
        }));

        Thread thread = new Thread(() -> {
            try {
                Instance instance = InstallService.install(mc, type, loader, name, progress,
                        () -> progress.isCancelled() || closed);
                Platform.runLater(() -> {
                    installing = false;
                    if (closed) {
                        return;
                    }
                    showSuccess(instance);
                });
            } catch (InterruptedException e) {
                Platform.runLater(() -> {
                    installing = false;
                    if (!closed) {
                        showFailed("已取消安装", true);
                    }
                });
            } catch (Throwable e) {
                Log.error("安装失败", e);
                Platform.runLater(() -> {
                    installing = false;
                    if (!closed) {
                        showFailed(e.getMessage() == null ? e.toString() : e.getMessage(), false);
                    }
                });
            }
        }, "ss-install");
        thread.setDaemon(true);
        thread.start();
    }

    private ProgressBar progressBar;
    private Label progressStage;
    private Label progressDetail;
    private Button progressCancel;

    private void showProgress(String mc, LoaderType type, String loader) {
        VBox pane = new VBox(15);
        pane.setAlignment(Pos.CENTER_LEFT);
        pane.setPadding(new Insets(30, 10, 20, 10));

        Label title = Ui.label("正在安装 " + mc + (type == LoaderType.VANILLA ? "" : " · " + type.label),
                "modal-title");
        Label hint = Ui.label("首次安装需要下载游戏本体、依赖库与资源文件，请保持网络畅通。", "tiny");
        hint.setWrapText(true);
        progressStage = Ui.label("准备中…", "section-title");
        progressDetail = Ui.label("", "tiny");
        progressDetail.setWrapText(true);
        progressBar = new ProgressBar(-1);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(7);
        progressCancel = Ui.ghostButton("取消安装");
        HBox bottom = new HBox(progressCancel);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        pane.getChildren().addAll(title, hint, progressStage, progressBar, progressDetail, Ui.vspacer(), bottom);

        card.getChildren().setAll(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
    }

    private void showSuccess(Instance instance) {
        installing = false;
        main.setModalDismissible(true);
        VBox pane = new VBox(14);
        pane.setAlignment(Pos.CENTER);
        pane.setPadding(new Insets(50, 20, 40, 20));
        StackPane icon = Ui.slot(64, instance.id.hashCode());
        Label title = Ui.label("安装完成", "modal-title");
        Label name = Ui.label(instance.name, "section-title");
        Label meta = Ui.label(instance.versionLabel() + "  ·  已创建独立实例目录", "tiny");
        Button play = Ui.primaryButton("启动游戏");
        play.setGraphic(Ui.icon(PLAY, 14));
        play.setOnAction(e -> {
            main.hideModal();
            main.launch(instance);
        });
        Button done = Ui.ghostButton("完成");
        done.setOnAction(e -> {
            main.hideModal();
            main.showPage(MainWindow.Page.VERSIONS);
        });
        HBox buttons = new HBox(9, play, done);
        buttons.setAlignment(Pos.CENTER);
        pane.getChildren().addAll(icon, title, name, meta, buttons);
        card.getChildren().setAll(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
        main.refreshAll();
    }

    private void showFailed(String message, boolean cancelled) {
        installing = false;
        main.setModalDismissible(true);
        VBox pane = new VBox(14);
        pane.setPadding(new Insets(24, 8, 8, 8));
        Label title = Ui.label(cancelled ? "已取消安装" : "安装失败", "modal-title");
        if (!cancelled) {
            title.getStyleClass().add("danger-text");
        }
        Label detail = Ui.label(message, "muted");
        detail.setWrapText(true);
        ScrollPane scroll = Ui.scroll(detail);
        scroll.setPrefHeight(180);
        scroll.getStyleClass().add("resource-version-list");
        Button retry = Ui.primaryButton("重新选择");
        retry.setOnAction(e -> {
            main.hideModal();
            new InstallDialog(main).show();
        });
        Button close = Ui.ghostButton("关闭");
        close.setOnAction(e -> {
            main.hideModal();
            main.showPage(MainWindow.Page.VERSIONS);
        });
        HBox buttons = new HBox(9, Ui.hspacer(), close, retry);
        pane.getChildren().addAll(title, detail, scroll, buttons);
        card.getChildren().setAll(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
    }
}
