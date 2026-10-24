package com.sslauncher.ui;

import com.sslauncher.model.Instance;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;

/** Detached console that mirrors the game's stdout/stderr. */
public class ConsoleWindow {
    private final Stage stage = new Stage();
    private final TextArea area = new TextArea();
    private final Label status = new Label("等待游戏输出…");
    private final StringBuilder buffer = new StringBuilder();
    private Instance instance;

    public ConsoleWindow(Window owner) {
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("SS Launcher · 游戏控制台");
        stage.setWidth(920);
        stage.setHeight(560);

        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("mono", "console-area");
        VBox.setVgrow(area, Priority.ALWAYS);

        Label title = Ui.label("游戏控制台", "section-title");
        status.getStyleClass().add("tiny");
        HBox titleRow = Ui.row(10, title, status, Ui.hspacer());
        titleRow.setStyle("-fx-padding: 0 0 10 0;");

        HBox toolbar = new HBox(8);
        toolbar.setStyle("-fx-padding: 10 0 0 0;");
        var openDir = Ui.ghostButton("打开实例目录");
        openDir.setOnAction(e -> openFolder());
        var openLog = Ui.ghostButton("打开日志文件");
        openLog.setOnAction(e -> {
            if (instance != null) {
                openFolder(instance.gameDir().resolve("logs"));
            }
        });
        var clear = Ui.ghostButton("清空");
        clear.setOnAction(e -> area.clear());
        var close = Ui.ghostButton("关闭控制台");
        close.setOnAction(e -> stage.hide());
        toolbar.getChildren().addAll(openDir, openLog, clear, Ui.hspacer(), close);

        BorderPane root = new BorderPane();
        root.setTop(titleRow);
        root.setCenter(area);
        root.setBottom(toolbar);
        root.getStyleClass().add("console-root");

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/sslauncher/css/app.css").toExternalForm());
        Theme.attach(scene);
        stage.setScene(scene);
    }

    public void setInstance(Instance instance) {
        this.instance = instance;
        stage.setTitle("SS Launcher · " + (instance == null ? "游戏控制台" : instance.name));
        status.setText(instance == null ? "等待游戏输出…" : instance.name + " · 等待游戏输出…");
        buffer.setLength(0);
        area.clear();
    }

    public void append(String line) {
        if (line == null) {
            return;
        }
        Platform.runLater(() -> {
            buffer.append(line).append('\n');
            if (buffer.length() > 600_000) {
                buffer.delete(0, buffer.length() - 400_000);
                area.setText(buffer.toString());
            } else {
                area.appendText(line + "\n");
            }
            area.setScrollTop(Double.MAX_VALUE);
        });
    }

    public void gameExited(int code) {
        Platform.runLater(() -> status.setText("游戏已退出 · 退出码 " + code));
    }

    public void show() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
    }

    public void close() {
        stage.hide();
    }

    private void openFolder() {
        if (instance != null) {
            openFolder(instance.gameDir());
        }
    }

    private void openFolder(Path path) {
        try {
            java.awt.Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.setTitle("SS Launcher");
            alert.setHeaderText("无法打开文件夹");
            alert.setContentText(path.toString());
            alert.showAndWait();
        }
    }
}
