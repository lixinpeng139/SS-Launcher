package com.sslauncher.ui.dialogs;

import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.service.LanWorldModService;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.Ui;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Shown when a host creates a room from an instance that does not have the
 * LAN World Plug-n-Play mod. Offers to install it (and a loader when the
 * instance is vanilla) and launch the game, or to launch without it.
 */
public class LanModDialog {
    private final MainWindow main;
    private final Instance instance;
    private final int gamePort;

    private final VBox card = new VBox(15);
    private final ToggleGroup loaderGroup = new ToggleGroup();
    private final HBox loaderBox = new HBox(8);
    private final Label loaderHint = Ui.label("", "tiny");
    private final Button recheck = Ui.textButton("重新检测");
    private final Button installButton = Ui.primaryButton("安装并启动");
    private final Button skipButton = Ui.ghostButton("不安装，直接启动");

    private LanWorldModService.Plan plan;
    private boolean vanilla;
    private boolean installing;
    private boolean decided;
    private boolean closed;

    private ProgressBar bar;
    private Label stageLabel;
    private Label detailLabel;
    private Button cancelButton;

    public LanModDialog(MainWindow main, Instance instance, int gamePort) {
        this.main = main;
        this.instance = instance;
        this.gamePort = gamePort;
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(640);
        card.setMaxWidth(680);
    }

    public void show() {
        buildShell();
        loadPlan();
    }

    // ------------------------------------------------------------------ shell

    private void buildShell() {
        closed = false;
        decided = false;
        installing = false;
        plan = null;
        vanilla = instance.loaderType() == LoaderType.VANILLA;
        loaderGroup.getToggles().clear();
        loaderBox.getChildren().clear();
        loaderBox.setAlignment(Pos.CENTER_LEFT);

        Label title = Ui.label("缺少联机模组", "modal-title");
        Label message = Ui.label("该游戏未安装高级联机mod，非正版登录用户不能加入您的游戏。",
                "section-title");
        message.setWrapText(true);
        Label body = Ui.label("LAN World Plug-n-Play（mcwifipnp）可以在「对局域网开放」时"
                + "关闭正版验证，让没有正版账号的好友也能加入。", "tiny");
        body.setWrapText(true);

        VBox pane = new VBox(12, title, message, body);

        loaderHint.setWrapText(true);
        recheck.setManaged(false);
        recheck.setVisible(false);
        recheck.setOnAction(e -> loadPlan());
        HBox hintRow = new HBox(8, loaderHint, recheck);
        hintRow.setAlignment(Pos.CENTER_LEFT);
        if (vanilla) {
            Label choose = Ui.label("该实例没有模组加载器，请选择要安装的：", "form-label");
            pane.getChildren().addAll(choose, loaderBox, hintRow);
        } else {
            pane.getChildren().add(hintRow);
        }

        Label portHint = Ui.label("安装完成后会自动启动游戏。在游戏里「对局域网开放」时，"
                + "请把端口设为 " + gamePort + "，并在模组界面关闭正版验证。", "tiny");
        portHint.setWrapText(true);
        pane.getChildren().add(portHint);

        installButton.setText("安装并启动");
        installButton.setDisable(true);
        installButton.setOnAction(e -> startInstall());
        skipButton.setDisable(false);
        skipButton.setOnAction(e -> launchWithoutMod());
        HBox buttons = new HBox(9, skipButton, Ui.hspacer(), installButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.getChildren().add(buttons);

        card.getChildren().setAll(pane);
        main.showModal(card, true, this::onDismissed);
        main.setModalDismissible(true);
    }

    private void loadPlan() {
        installButton.setDisable(true);
        loaderBox.getChildren().clear();
        recheck.setManaged(false);
        recheck.setVisible(false);
        loaderHint.setText(vanilla ? "正在检测可用的模组加载器…" : "正在检查联机模组版本…");

        Async.supply(() -> LanWorldModService.plan(instance), result -> {
            if (closed) {
                return;
            }
            plan = result;
            if (vanilla) {
                buildLoaderChoices(result);
            } else if (result.loaders().isEmpty()) {
                loaderHint.setText("当前 " + instance.loaderType().label + " 实例没有兼容 Minecraft "
                        + result.gameVersion() + " 的联机模组版本。");
                recheck.setManaged(true);
                recheck.setVisible(true);
            } else {
                loaderHint.setText("将安装到当前的 " + instance.loaderType().label
                        + " 实例（Minecraft " + result.gameVersion() + "）。");
                installButton.setDisable(false);
            }
        }, error -> {
            if (closed) {
                return;
            }
            loaderHint.setText("无法获取模组信息：" + message(error));
            recheck.setManaged(true);
            recheck.setVisible(true);
        });
    }

    private void buildLoaderChoices(LanWorldModService.Plan result) {
        if (result.loaders().isEmpty()) {
            loaderHint.setText("Minecraft " + result.gameVersion()
                    + " 暂无可用的模组加载器版本，无法自动安装联机模组。");
            recheck.setManaged(true);
            recheck.setVisible(true);
            return;
        }
        boolean first = true;
        for (LoaderType type : result.loaders()) {
            ToggleButton button = new ToggleButton(type.label);
            button.setToggleGroup(loaderGroup);
            button.setUserData(type);
            button.setTooltip(new Tooltip(type.description));
            button.setOnAction(e -> updateLoaderHint());
            loaderBox.getChildren().add(button);
            if (first) {
                button.setSelected(true);
                first = false;
            }
        }
        updateLoaderHint();
        installButton.setDisable(false);
    }

    private void updateLoaderHint() {
        LoaderType selected = selectedLoader();
        if (selected == null || plan == null) {
            return;
        }
        loaderHint.setText("将安装 " + selected.label + " 加载器与联机模组（Minecraft "
                + plan.gameVersion() + "）。");
    }

    private LoaderType selectedLoader() {
        for (var toggle : loaderGroup.getToggles()) {
            if (toggle.isSelected()) {
                return (LoaderType) toggle.getUserData();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- install

    private void startInstall() {
        if (installing) {
            return;
        }
        LoaderType loader = vanilla ? selectedLoader() : instance.loaderType();
        if (vanilla && loader == null) {
            main.toast("请选择模组加载器");
            return;
        }
        installing = true;
        main.setModalDismissible(false);
        Progress progress = new Progress();
        showProgressPane(loader, progress);

        progress.addListener(p -> Platform.runLater(() -> {
            if (stageLabel == null) {
                return;
            }
            stageLabel.setText(p.getStage());
            if (p.getDetail() != null && !p.getDetail().isBlank()) {
                detailLabel.setText(p.getDetail());
            }
            bar.setProgress(p.isIndeterminate() ? -1 : p.fraction());
        }));

        Thread thread = new Thread(() -> {
            try {
                LanWorldModService.InstallResult result =
                        LanWorldModService.install(instance, loader, progress,
                                () -> progress.isCancelled() || closed);
                Platform.runLater(() -> {
                    installing = false;
                    if (closed) {
                        return;
                    }
                    decided = true;
                    main.refreshAll();
                    if (main.isGameRunning()) {
                        // Installing a mod does not affect the JVM that is
                        // already running; force an explicit restart message.
                        main.toastSuccess("联机模组已安装（" + result.loader().label + "）");
                        showRestartRequired(result.loader());
                        return;
                    }
                    main.hideModal();
                    main.toastSuccess("联机模组已安装（" + result.loader().label + "），正在启动游戏");
                    main.launch(instance);
                });
            } catch (InterruptedException e) {
                Platform.runLater(() -> {
                    installing = false;
                    if (!closed) {
                        showFailure("已取消安装", true);
                    }
                });
            } catch (Throwable e) {
                Log.error("安装联机模组失败", e);
                Platform.runLater(() -> {
                    installing = false;
                    if (!closed) {
                        showFailure(message(e), false);
                    }
                });
            }
        }, "ss-lanmod");
        thread.setDaemon(true);
        thread.start();
    }

    private void showProgressPane(LoaderType loader, Progress progress) {
        Label title = Ui.label("正在安装联机模组", "modal-title");
        Label hint = Ui.label(loader == null ? instance.versionLabel()
                        : instance.versionLabel() + "  ·  " + loader.label, "tiny");
        stageLabel = Ui.label("准备中…", "section-title");
        bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(7);
        detailLabel = Ui.label("", "tiny");
        detailLabel.setWrapText(true);
        cancelButton = Ui.ghostButton("取消安装");
        cancelButton.setOnAction(e -> {
            progress.cancel();
            cancelButton.setDisable(true);
            cancelButton.setText("正在取消…");
        });
        HBox bottom = new HBox(9, Ui.hspacer(), cancelButton);
        bottom.setAlignment(Pos.CENTER_RIGHT);

        VBox pane = new VBox(14, title, hint, stageLabel, bar, detailLabel, bottom);
        pane.setPadding(new Insets(6, 0, 2, 0));
        card.getChildren().setAll(pane);
    }

    private void showRestartRequired(LoaderType loader) {
        main.setModalDismissible(true);
        Label title = Ui.label("联机模组已安装", "modal-title");
        Label message = Ui.label("正在运行的 Minecraft 还是安装前的状态，新模组需要重启游戏才会生效。"
                + "请退出游戏，然后从启动器重新启动。", "muted");
        message.setWrapText(true);
        Label meta = Ui.label(instance.name + "  ·  " + loader.label
                + "  ·  已安装 " + LanWorldModService.TITLE, "tiny");
        meta.setWrapText(true);
        Button close = Ui.primaryButton("知道了");
        close.setOnAction(e -> {
            decided = true;
            main.hideModal();
        });
        HBox buttons = new HBox(9, Ui.hspacer(), close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().setAll(new VBox(14, title, message, meta, buttons));
    }

    private void showFailure(String message, boolean cancelled) {
        main.setModalDismissible(true);
        Label title = Ui.label(cancelled ? "已取消安装" : "安装失败", "modal-title");
        if (!cancelled) {
            title.getStyleClass().add("danger-text");
        }
        Label detail = Ui.label(message, "muted");
        detail.setWrapText(true);
        ScrollPane scroll = Ui.scroll(detail);
        scroll.setPrefHeight(150);
        scroll.getStyleClass().add("resource-version-list");

        Button direct = Ui.primaryButton("不安装，直接启动");
        direct.setOnAction(e -> launchWithoutMod());
        Button retry = Ui.ghostButton("重试");
        retry.setOnAction(e -> {
            buildShell();
            loadPlan();
        });
        Button close = Ui.textButton("关闭");
        close.setOnAction(e -> {
            decided = true;
            main.hideModal();
        });
        HBox buttons = new HBox(9, close, Ui.hspacer(), retry, direct);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox pane = new VBox(14, title, scroll, buttons);
        card.getChildren().setAll(pane);
    }

    // ---------------------------------------------------------------- dismiss

    private void launchWithoutMod() {
        decided = true;
        main.hideModal();
        main.launch(instance);
    }

    private void onDismissed() {
        closed = true;
        if (!decided) {
            // Closing the dialog counts as "install later": start the game.
            decided = true;
            main.launch(instance);
        }
    }

    private static String message(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
