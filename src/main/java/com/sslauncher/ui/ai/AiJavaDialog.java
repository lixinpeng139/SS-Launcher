package com.sslauncher.ui.ai;

import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;
import com.sslauncher.service.JavaService;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.Ui;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Offers to install the JDK a chosen Minecraft version needs. Closing the
 * prompt without installing is treated as "not available": the caller keeps
 * the start button disabled so the flow cannot continue.
 */
final class AiJavaDialog {
    private final MainWindow main;
    private final int javaMajor;
    private final String mcVersion;
    private final Consumer<Boolean> onFinished;

    private VBox card;
    private boolean settled;
    private boolean installing;
    private Progress progress;
    private ProgressBar progressBar;
    private Label progressStage;
    private Label progressDetail;
    private Button progressCancel;

    AiJavaDialog(MainWindow main, int javaMajor, String mcVersion, Consumer<Boolean> onFinished) {
        this.main = main;
        this.javaMajor = javaMajor;
        this.mcVersion = mcVersion == null ? "" : mcVersion;
        this.onFinished = onFinished;
    }

    void show() {
        card = new VBox(16);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(540);
        card.setMaxWidth(560);
        showPrompt();
        main.showModal(card, true, () -> {
            if (!settled) {
                settled = true;
                finish(false);
            }
        });
    }

    private void showPrompt() {
        Label title = Ui.label("需要 Java " + javaMajor, "modal-title");
        Label subtitle = Ui.label("Minecraft " + mcVersion + " 的模组构建需要 JDK " + javaMajor
                + "，当前没有检测到可用的 JDK " + javaMajor + "。", "muted");
        subtitle.setWrapText(true);
        Label note = Ui.label("安装会下载 Mojang 官方 Java " + javaMajor
                + " 运行时到启动器数据目录，不会修改系统环境变量。约需数分钟，取决于网络。", "tiny");
        note.setWrapText(true);
        Label blocked = Ui.label("取消后需要先安装 JDK " + javaMajor + " 才能开始制作。", "tiny", "danger-text");
        blocked.setWrapText(true);

        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> close(false));
        Button install = Ui.primaryButton("下载并安装");
        install.setOnAction(e -> startInstall());
        HBox buttons = new HBox(9, Ui.hspacer(), cancel, install);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().setAll(title, subtitle, note, blocked, buttons);
    }

    private void startInstall() {
        if (installing) {
            return;
        }
        installing = true;
        main.setModalDismissible(false);
        progress = new Progress();

        Label title = Ui.label("正在安装 Java " + javaMajor, "modal-title");
        Label hint = Ui.label("下载完成后会自动校验，无需手动配置。", "tiny");
        progressStage = Ui.label("准备中…", "section-title");
        progressDetail = Ui.label("", "tiny");
        progressDetail.setWrapText(true);
        progressBar = new ProgressBar(-1);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(7);
        progressCancel = Ui.ghostButton("取消下载");
        progressCancel.setOnAction(e -> {
            if (progress != null) {
                progress.cancel();
            }
            progressCancel.setDisable(true);
            progressCancel.setText("正在取消…");
        });
        HBox bottom = new HBox(progressCancel);
        bottom.setAlignment(Pos.CENTER_RIGHT);

        VBox pane = new VBox(14, title, hint, progressStage, progressBar, progressDetail,
                Ui.vspacer(), bottom);
        pane.setPadding(new Insets(6, 0, 2, 0));
        card.getChildren().setAll(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);

        progress.addListener(p -> Platform.runLater(() -> {
            if (progressStage == null || progressBar == null) {
                return;
            }
            progressStage.setText(p.getStage());
            if (p.getDetail() != null && !p.getDetail().isBlank()) {
                progressDetail.setText(p.getDetail());
            }
            progressBar.setProgress(p.isIndeterminate() ? -1 : p.fraction());
        }));

        Progress active = progress;
        Thread thread = new Thread(() -> {
            try {
                JavaService.ensureJdk(javaMajor, active, active::isCancelled);
                Platform.runLater(() -> {
                    installing = false;
                    main.setModalDismissible(true);
                    settled = true;
                    main.hideModal();
                    main.toastSuccess("Java " + javaMajor + " 已安装");
                    if (onFinished != null) {
                        onFinished.accept(true);
                    }
                });
            } catch (InterruptedException e) {
                Platform.runLater(() -> showFailed("已取消下载", true));
            } catch (Throwable e) {
                Log.error("Java 运行时安装失败", e);
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                Platform.runLater(() -> showFailed(message, false));
            }
        }, "ss-ai-java");
        thread.setDaemon(true);
        thread.start();
    }

    private void showFailed(String message, boolean cancelled) {
        installing = false;
        main.setModalDismissible(true);
        Label title = Ui.label(cancelled ? "已取消安装" : "Java 安装失败", "modal-title");
        if (!cancelled) {
            title.getStyleClass().add("danger-text");
        }
        Label detail = Ui.label(message, "muted");
        detail.setWrapText(true);
        Label blocked = Ui.label("没有 JDK " + javaMajor + " 就无法开始制作。", "tiny", "danger-text");
        blocked.setWrapText(true);

        Button close = Ui.ghostButton("关闭");
        close.setOnAction(e -> close(false));
        Button retry = Ui.primaryButton("重试");
        retry.setOnAction(e -> startInstall());
        HBox buttons = new HBox(9, Ui.hspacer(), close, retry);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().setAll(title, detail, blocked, buttons);
    }

    private void close(boolean available) {
        if (settled) {
            return;
        }
        settled = true;
        main.setModalDismissible(true);
        main.hideModal();
        finish(available);
    }

    private void finish(boolean available) {
        if (onFinished != null) {
            onFinished.accept(available);
        }
    }
}
