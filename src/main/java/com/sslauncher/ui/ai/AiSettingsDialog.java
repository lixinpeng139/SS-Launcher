package com.sslauncher.ui.ai;

import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * First-run provider dialog. It can always be dismissed, which leaves the AI
 * page in a read-only preview state so new users can look around before
 * committing to an API key.
 */
public final class AiSettingsDialog {
    private static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 "
            + "12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z";

    private final MainWindow main;
    private final Runnable onSaved;

    public AiSettingsDialog(MainWindow main, Runnable onSaved) {
        this.main = main;
        this.onSaved = onSaved;
    }

    public void show() {
        AiSettingsPane pane = new AiSettingsPane(main, true);
        pane.setPadding(new Insets(2, 0, 2, 0));

        Label title = Ui.label("配置 AI 模型", "modal-title");
        Label subtitle = Ui.label("填写 API Key 后即可开始制作模组；也可以先关闭窗口预览界面。",
                "tiny");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(480);
        VBox heading = new VBox(5, title, subtitle);
        HBox.setHgrow(heading, Priority.ALWAYS);

        Button close = new Button();
        close.setGraphic(Ui.icon(CLOSE, 13));
        close.getStyleClass().add("modal-close");
        close.setFocusTraversable(false);
        close.setTooltip(new Tooltip("关闭"));
        close.setOnAction(e -> main.hideModal());

        HBox head = new HBox(14, heading, close);
        head.setAlignment(Pos.TOP_LEFT);

        Label note = Ui.label("首次开始制作时会自动下载内置 AI 运行时（Node.js + DeepSeek Harness），"
                + "之后无需重复下载。", "tiny");
        note.setWrapText(true);

        Button preview = Ui.ghostButton("先预览界面");
        preview.setOnAction(e -> main.hideModal());
        Button save = Ui.primaryButton("保存并继续");
        save.setOnAction(e -> {
            if (pane.save(true) != null) {
                return;
            }
            main.hideModal();
            main.toastSuccess("AI 设置已保存");
            if (onSaved != null) {
                onSaved.run();
            }
        });
        HBox buttons = new HBox(9, Ui.hspacer(), preview, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(16, head, pane, note, buttons);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(660);
        card.setMaxWidth(660);
        main.showModal(card, true);
        pane.focusApiKey();
    }
}
