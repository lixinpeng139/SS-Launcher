package com.sslauncher.ui.dialogs;

import com.sslauncher.core.Config;
import com.sslauncher.model.Account;
import com.sslauncher.skin.SkinService;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PixelArt;
import com.sslauncher.ui.SkinImages;
import com.sslauncher.ui.Ui;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Per-account skin editor.
 *
 * <p>Microsoft accounts upload to the Minecraft profile; Yggdrasil accounts
 * try authlib-injector's texture endpoint and fall back to the skin site when
 * the server does not implement it; offline accounts can only set a local
 * launcher avatar. The preview uses the real skin head and body, and the same
 * PNG can also be dragged into the dialog.</p>
 */
public class SkinDialog {
    private static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z";
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8"
            + "a2 2 0 00-2-2h-8l-2-2z";
    private static final String UPLOAD = "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z";
    private static final String CHECK = "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    private static final String SYNC = "M12 4V1L8 5l4 4V6a6 6 0 016 6 6 6 0 01-.34 2h2.08A8 8 0 0012 4z"
            + "M6 12a6 6 0 01.34-2H4.26A8 8 0 0012 20v3l4-4-4-4v3a6 6 0 01-6-6z";

    private enum Mode {
        UPLOAD,
        LOCAL
    }

    private final MainWindow main;
    private final Account account;
    private final VBox card = new VBox(16);
    private final StackPane preview = new StackPane();
    private final Canvas bodyView = new Canvas(112, 224);
    private final VBox fallback = new VBox(7);
    private final Label previewCaption = Ui.label("当前皮肤", "section-title");
    private final Label sourceLabel = Ui.label("", "tiny");
    private final VBox noticeBox = new VBox();
    private final Label noticeLabel = Ui.label("", "notice-info-text");
    private final VBox statusBox = new VBox(8);
    private final Label statusLabel = Ui.label("", "notice-text");
    private final Button clearCacheButton = Ui.textButton("仅清除启动器中的皮肤");
    private final Label fileLabel = Ui.label("未选择文件", "tiny");
    private final ToggleButton classic = new ToggleButton("经典");
    private final ToggleButton slim = new ToggleButton("纤细");
    private final Button choose = Ui.ghostButton("选择 PNG 文件…");
    private final Button primary = Ui.primaryButton("上传到皮肤站");
    private final Button localOnly = Ui.ghostButton("仅设为启动器头像");
    private final Button reset = Ui.ghostButton("恢复默认");
    private final Button sync = Ui.textButton("同步账号皮肤");
    private final Button site = Ui.textButton("打开皮肤站");
    private final HBox resetConfirm = new HBox(10);
    private final Button confirmReset = Ui.button("确认恢复", "btn", "btn-danger");
    private final Button cancelReset = Ui.ghostButton("取消");
    private final ToggleGroup modelGroup = new ToggleGroup();

    private SkinService.SkinState current;
    private Path selected;
    private SkinService.SkinFile selectedFile;
    private Mode primaryMode = Mode.UPLOAD;
    private boolean busy;
    private boolean closed;

    public SkinDialog(MainWindow main, Account account) {
        this.main = main;
        this.account = account;
    }

    public void show() {
        if (account == null) {
            return;
        }
        noticeLabel.setWrapText(true);
        noticeBox.getChildren().add(noticeLabel);
        noticeBox.getStyleClass().add("notice-blue");

        statusLabel.setWrapText(true);
        statusBox.getChildren().addAll(statusLabel, clearCacheButton);
        statusBox.getStyleClass().add("notice");
        statusBox.setVisible(false);
        statusBox.setManaged(false);
        clearCacheButton.setVisible(false);
        clearCacheButton.setManaged(false);
        clearCacheButton.setOnAction(e -> {
            SkinService.clearAll(account);
            selected = null;
            selectedFile = null;
            fileLabel.setText("未选择文件");
            fileLabel.getStyleClass().setAll("tiny");
            refreshCurrent();
            showStatus("已清除启动器中的皮肤缓存。", false);
            main.refreshAll();
        });

        fallback.setAlignment(Pos.CENTER);
        fallback.getChildren().setAll(PixelArt.face(account.name, 84),
                Ui.label("暂无自定义皮肤", "tiny"));

        // 16×32 body at a whole-number 7× scale, drawn through a Canvas with
        // image smoothing off so skin pixels stay sharp.
        bodyView.setMouseTransparent(true);
        preview.getChildren().addAll(bodyView, fallback);
        preview.getStyleClass().add("skin-preview");
        preview.setMinSize(152, 256);
        preview.setPrefSize(152, 256);
        preview.setMaxSize(152, 256);

        previewCaption.setTextAlignment(TextAlignment.CENTER);
        sourceLabel.setWrapText(true);
        sourceLabel.setTextAlignment(TextAlignment.CENTER);
        sourceLabel.setMaxWidth(164);
        sourceLabel.setAlignment(Pos.CENTER);

        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(Ui.icon(CLOSE, 14));
        close.setOnAction(e -> main.hideModal());

        Label title = Ui.label("更换皮肤", "modal-title");
        Label sub = Ui.label("为 " + account.typeLabel() + " 账号「" + account.name + "」设置皮肤", "tiny");
        HBox header = new HBox(12, new VBox(3, title, sub), Ui.hspacer(), close);
        header.setAlignment(Pos.TOP_LEFT);

        HBox body = new HBox(22, leftColumn(), rightColumn());
        body.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(body.getChildren().get(1), Priority.ALWAYS);

        card.getStyleClass().add("modal-card");
        card.setPrefWidth(760);
        card.setMaxWidth(820);
        card.getChildren().addAll(header, body, resetConfirm, actions());

        installDropTarget();
        // A background skin fetch may complete while this dialog is open; keep
        // the preview and the upload/sync hints in step with the cache.
        Runnable skinListener = () -> Platform.runLater(this::onSkinEvent);
        SkinService.addListener(skinListener);
        main.showModal(card, true, () -> {
            closed = true;
            SkinService.removeListener(skinListener);
        });
        refreshCurrent();
    }

    private void onSkinEvent() {
        if (closed || busy) {
            return;
        }
        refreshCurrent();
    }

    private Node leftColumn() {
        Label dragHint = Ui.label("拖入 PNG 可预览", "tiny");
        VBox left = new VBox(8, preview, previewCaption, sourceLabel, dragHint);
        left.setAlignment(Pos.TOP_CENTER);
        left.setMinWidth(164);
        left.setPrefWidth(164);

        sync.setGraphic(Ui.icon(SYNC, 13));
        sync.setText(account.isMicrosoft() ? "同步 Minecraft 皮肤" : "从皮肤站同步");
        sync.setVisible(!account.isOffline());
        sync.setManaged(!account.isOffline());
        sync.setOnAction(e -> syncSkin());
        HBox syncRow = new HBox(sync);
        syncRow.setAlignment(Pos.CENTER);
        left.getChildren().add(syncRow);
        return left;
    }

    private Node rightColumn() {
        Label fileTitle = Ui.label("皮肤文件", "section-title");
        HBox fileHeader = new HBox(10, fileTitle, Ui.hspacer(), fileLabel);
        fileHeader.setAlignment(Pos.CENTER_LEFT);
        fileLabel.setWrapText(true);
        fileLabel.setMaxWidth(320);
        choose.setGraphic(Ui.icon(FOLDER, 15));
        choose.setMaxWidth(Double.MAX_VALUE);
        choose.setOnAction(e -> chooseFile());
        Label fileHint = Ui.label("支持 64×32、64×64 及 2×/4× 高清皮肤；"
                + "拖到左侧预览框也可以。", "field-hint");
        fileHint.setWrapText(true);
        VBox fileSection = new VBox(8, fileHeader, choose, fileHint);

        Label modelTitle = Ui.label("皮肤模型", "section-title");
        classic.setToggleGroup(modelGroup);
        slim.setToggleGroup(modelGroup);
        classic.setTooltip(new Tooltip("经典模型（4 像素手臂）"));
        slim.setTooltip(new Tooltip("纤细模型（3 像素手臂）"));
        modelGroup.selectedToggleProperty().addListener((obs, old, value) -> {
            if (value == null) {
                if (old != null) {
                    old.setSelected(true);
                }
                return;
            }
            if (!busy && selected != null) {
                renderPreview(selected, value == slim);
                sourceLabel.setText("尚未保存 · " + selectedFile.dimensions());
            }
        });
        HBox modelButtons = new HBox(8, classic, slim);
        modelButtons.setAlignment(Pos.CENTER_LEFT);
        Label modelHint = Ui.label("经典 = 4 像素手臂，纤细 = 3 像素手臂；选择文件后可以切换。",
                "field-hint");
        modelHint.setWrapText(true);
        VBox modelSection = new VBox(8, modelTitle, modelButtons, modelHint);

        VBox right = new VBox(14, fileSection, modelSection, noticeBox, statusBox);
        right.setMaxWidth(Double.MAX_VALUE);
        return right;
    }

    private Node actions() {
        reset.setOnAction(e -> onReset());
        String home = account.isYggdrasil() ? Config.skinHomeOf(account.serverUrl) : "";
        site.setVisible(!home.isBlank());
        site.setManaged(!home.isBlank());
        site.setOnAction(e -> Ui.browse(home));

        Label confirmLabel = Ui.label("确定要恢复账号的默认皮肤吗？此操作不可撤销。", "notice-text");
        confirmLabel.setWrapText(true);
        confirmReset.setOnAction(e -> {
            hideResetConfirm();
            performReset();
        });
        cancelReset.setOnAction(e -> hideResetConfirm());
        resetConfirm.getChildren().addAll(confirmLabel, Ui.hspacer(), cancelReset, confirmReset);
        resetConfirm.getStyleClass().add("notice");
        resetConfirm.setAlignment(Pos.CENTER_LEFT);
        resetConfirm.setVisible(false);
        resetConfirm.setManaged(false);

        primary.setOnAction(e -> submit(false));
        localOnly.setOnAction(e -> submit(true));
        Button cancel = Ui.ghostButton("关闭");
        cancel.setOnAction(e -> main.hideModal());

        HBox bar = new HBox(8, reset, site, Ui.hspacer(), cancel, localOnly, primary);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ------------------------------------------------------------- state

    private void refreshCurrent() {
        current = SkinService.state(account);
        if (selected == null) {
            renderPreview(current.file(), current.isSlim());
            previewCaption.setText("当前皮肤");
            sourceLabel.setText(sourceText(current));
            setModelToggle(current.model());
        }
        primaryMode = account.isOffline() || Boolean.FALSE.equals(current.uploadable())
                ? Mode.LOCAL : Mode.UPLOAD;
        updateNotice();
        updateResetLabel();
        updateButtons();
    }

    private String sourceText(SkinService.SkinState state) {
        String model = SkinService.modelLabel(state.model());
        return switch (state.source()) {
            case LOCAL -> "本机头像 · " + model + "\n仅显示在启动器中";
            case SERVER -> (account.isYggdrasil() ? "皮肤站皮肤 · " : "Minecraft 皮肤 · ")
                    + model + "\n已同步到启动器";
            case NONE -> account.isOffline()
                    ? "默认头像\n还没有设置本机皮肤"
                    : "默认头像\n可从账号同步或上传";
        };
    }

    private void updateNotice() {
        boolean unsupported = Boolean.FALSE.equals(current == null ? null : current.uploadable());
        if (account.isOffline()) {
            setNotice("离线账号不能上传皮肤。这里设置的 PNG 只作为启动器头像，"
                    + "游戏内仍使用默认皮肤。", true);
        } else if (account.isMicrosoft()) {
            setNotice("上传后会写入 Minecraft 个人档案，在正版服务器中立即生效。"
                    + "也可以把图片仅设为启动器头像。", false);
        } else if (unsupported) {
            setNotice("该皮肤站没有开放启动器上传接口。请在皮肤站网站更换皮肤，"
                    + "然后点左侧的「从皮肤站同步」；或把图片仅设为启动器头像。", true);
        } else {
            setNotice("皮肤会从皮肤站同步。支持上传接口的皮肤站可以直接在这里更换；"
                    + "不支持的站点请在网页更换后点「同步」。", false);
        }
    }

    private void setNotice(String text, boolean warning) {
        noticeBox.getStyleClass().setAll(warning ? "notice" : "notice-blue");
        noticeLabel.getStyleClass().setAll(warning ? "notice-text" : "notice-info-text");
        noticeLabel.setText(text);
    }

    private void updateButtons() {
        boolean hasFile = selected != null && !busy;
        choose.setDisable(busy);
        classic.setDisable(!hasFile);
        slim.setDisable(!hasFile);

        boolean local = primaryMode == Mode.LOCAL;
        primary.setText(local ? "设为启动器头像"
                : account.isMicrosoft() ? "上传到 Minecraft" : "上传到皮肤站");
        primary.setGraphic(Ui.icon(local ? CHECK : UPLOAD, 14));
        primary.setDisable(!hasFile);

        boolean showLocalOnly = !account.isOffline() && !local;
        localOnly.setVisible(showLocalOnly);
        localOnly.setManaged(showLocalOnly);
        localOnly.setDisable(!hasFile);

        reset.setDisable(busy || current == null
                || current.source() == SkinService.Source.NONE);
        sync.setDisable(busy);
        confirmReset.setDisable(busy);
        cancelReset.setDisable(busy);
    }

    private void updateResetLabel() {
        if (current == null || current.source() == SkinService.Source.NONE) {
            reset.setText("恢复默认");
        } else if (current.source() == SkinService.Source.LOCAL) {
            reset.setText("清除本机头像");
        } else {
            reset.setText("恢复默认皮肤");
        }
    }

    private void setModelToggle(String model) {
        if (SkinService.MODEL_SLIM.equals(SkinService.normalizeModel(model))) {
            slim.setSelected(true);
        } else {
            classic.setSelected(true);
        }
    }

    private String selectedModel() {
        return slim.isSelected() ? SkinService.MODEL_SLIM : SkinService.MODEL_CLASSIC;
    }

    // ------------------------------------------------------------- preview

    private void renderPreview(Path file, boolean slimModel) {
        Image image = file == null ? null : SkinImages.body(file, slimModel);
        boolean showBody = image != null;
        SkinImages.draw(bodyView, image);
        bodyView.setVisible(showBody);
        fallback.setVisible(!showBody);
    }

    private void chooseFile() {
        if (busy) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Minecraft 皮肤");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PNG 皮肤", "*.png", "*.PNG"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File home = new File(System.getProperty("user.home"));
        File downloads = new File(home, "Downloads");
        File initial = downloads.isDirectory() ? downloads : home;
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File file = chooser.showOpenDialog(main.stage());
        if (file != null) {
            selectFile(file.toPath());
        }
    }

    private void selectFile(Path file) {
        if (busy || file == null) {
            return;
        }
        busy = true;
        showStatus("正在读取皮肤文件…", false);
        updateButtons();
        Async.supply(() -> SkinService.validate(file), info -> {
            if (closed) {
                return;
            }
            busy = false;
            selected = info.path();
            selectedFile = info;
            fileLabel.setText(info.path().getFileName() + " · " + info.dimensions());
            fileLabel.getStyleClass().setAll("skin-file-name");
            previewCaption.setText("新皮肤预览");
            sourceLabel.setText("尚未保存 · " + info.dimensions());
            renderPreview(selected, slim.isSelected());
            clearStatus();
            updateButtons();
        }, error -> {
            if (closed) {
                return;
            }
            busy = false;
            updateButtons();
            showStatus(message(error), true);
        });
    }

    private void installDropTarget() {
        card.setOnDragOver(event -> {
            if (busy) {
                return;
            }
            Dragboard board = event.getDragboard();
            if (board != null && board.hasFiles()
                    && board.getFiles().stream().anyMatch(SkinDialog::isPng)) {
                event.acceptTransferModes(TransferMode.COPY);
                if (!preview.getStyleClass().contains("skin-drop-active")) {
                    preview.getStyleClass().add("skin-drop-active");
                }
                event.consume();
            }
        });
        card.setOnDragExited(event -> preview.getStyleClass().remove("skin-drop-active"));
        card.setOnDragDropped(event -> {
            preview.getStyleClass().remove("skin-drop-active");
            Path dropped = firstPng(event.getDragboard());
            if (dropped != null) {
                event.setDropCompleted(true);
                event.consume();
                selectFile(dropped);
            }
        });
    }

    private static boolean isPng(File file) {
        return file != null && file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static Path firstPng(Dragboard board) {
        if (board == null || !board.hasFiles()) {
            return null;
        }
        for (File file : board.getFiles()) {
            if (isPng(file)) {
                return file.toPath();
            }
        }
        return null;
    }

    // ------------------------------------------------------------- actions

    private void submit(boolean localOnlyMode) {
        if (busy || selected == null) {
            return;
        }
        hideResetConfirm();
        Path file = selected;
        String model = selectedModel();
        boolean local = localOnlyMode || primaryMode == Mode.LOCAL || account.isOffline();
        String busyText = local ? "正在保存头像…" : "正在上传皮肤…";
        if (local) {
            runAction(busyText,
                    () -> SkinService.saveLocal(account, file, model),
                    () -> {
                        showStatus("已设为启动器头像，账号头像已更新。", false);
                        main.toastSuccess("已更新启动器头像");
                    });
        } else {
            runAction(busyText,
                    () -> SkinService.upload(account, file, model),
                    () -> {
                        showStatus("皮肤已上传，账号头像已更新。", false);
                        main.toastSuccess(account.isMicrosoft()
                                ? "皮肤已同步到 Minecraft" : "皮肤已上传到皮肤站");
                    });
        }
    }

    private void onReset() {
        if (busy || current == null || current.source() == SkinService.Source.NONE) {
            return;
        }
        if (current.source() == SkinService.Source.LOCAL) {
            SkinService.clearLocal(account);
            refreshCurrent();
            showStatus("已清除本机头像。", false);
            main.refreshAll();
            return;
        }
        resetConfirm.setVisible(true);
        resetConfirm.setManaged(true);
    }

    private void hideResetConfirm() {
        resetConfirm.setVisible(false);
        resetConfirm.setManaged(false);
    }

    private void performReset() {
        if (busy) {
            return;
        }
        busy = true;
        showStatus("正在恢复默认皮肤…", false);
        updateButtons();
        Async.run(() -> SkinService.reset(account), () -> {
            if (closed) {
                return;
            }
            busy = false;
            selected = null;
            selectedFile = null;
            fileLabel.setText("未选择文件");
            fileLabel.getStyleClass().setAll("tiny");
            refreshCurrent();
            showStatus("已恢复默认皮肤。", false);
            main.refreshAll();
            main.toastSuccess("已恢复默认皮肤");
        }, error -> {
            if (closed) {
                return;
            }
            busy = false;
            updateButtons();
            SkinService.SkinException skinError = skinError(error);
            showStatus(skinError != null ? skinError.getMessage() : message(error), true);
            if (skinError != null && skinError.uploadUnsupported()) {
                clearCacheButton.setVisible(true);
                clearCacheButton.setManaged(true);
            }
        });
    }

    private void syncSkin() {
        if (busy) {
            return;
        }
        hideResetConfirm();
        busy = true;
        showStatus("正在同步账号皮肤…", false);
        updateButtons();
        Async.supply(() -> SkinService.reload(account), synced -> {
            if (closed) {
                return;
            }
            busy = false;
            refreshCurrent();
            showStatus(synced != null && synced.source() == SkinService.Source.LOCAL
                    ? "已同步账号皮肤；当前仍显示本机头像，点「清除本机头像」可切换。"
                    : "已同步账号皮肤。", false);
            main.refreshAll();
            main.toastSuccess("已同步账号皮肤");
        }, error -> {
            if (closed) {
                return;
            }
            busy = false;
            updateButtons();
            showStatus(message(error), true);
        });
    }

    private void runAction(String busyText, Async.Work work, Runnable onSuccess) {
        busy = true;
        showStatus(busyText, false);
        updateButtons();
        Async.run(work, () -> {
            if (closed) {
                return;
            }
            busy = false;
            selected = null;
            selectedFile = null;
            fileLabel.setText("未选择文件");
            fileLabel.getStyleClass().setAll("tiny");
            refreshCurrent();
            onSuccess.run();
            updateButtons();
            main.refreshAll();
        }, error -> {
            if (closed) {
                return;
            }
            busy = false;
            updateButtons();
            SkinService.SkinException skinError = skinError(error);
            showStatus(skinError != null ? skinError.getMessage() : message(error), true);
        });
    }

    // --------------------------------------------------------------- status

    private void showStatus(String text, boolean error) {
        statusBox.getStyleClass().setAll(error ? "notice" : "notice-blue");
        statusLabel.getStyleClass().setAll(error ? "notice-text" : "notice-info-text");
        statusLabel.setText(text == null ? "" : text);
        clearCacheButton.setVisible(false);
        clearCacheButton.setManaged(false);
        statusBox.setVisible(true);
        statusBox.setManaged(true);
    }

    private void clearStatus() {
        statusBox.setVisible(false);
        statusBox.setManaged(false);
    }

    private static SkinService.SkinException skinError(Throwable error) {
        Throwable value = error;
        while (value != null && !(value instanceof SkinService.SkinException)
                && value.getCause() != null) {
            value = value.getCause();
        }
        return value instanceof SkinService.SkinException skin ? skin : null;
    }

    private static String message(Throwable error) {
        Throwable value = error;
        while (value != null && (value.getMessage() == null || value.getMessage().isBlank())
                && value.getCause() != null) {
            value = value.getCause();
        }
        String message = value == null ? null : value.getMessage();
        return message == null || message.isBlank() ? "操作失败，请稍后再试。" : message;
    }
}
