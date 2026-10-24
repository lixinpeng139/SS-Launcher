package com.sslauncher;

import com.sslauncher.ai.AiConfig;
import com.sslauncher.core.AccountStore;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Log;
import com.sslauncher.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;

public class LauncherApp extends Application {
    public static final String VERSION = "1.0.0";

    @Override
    public void start(Stage stage) {
        try {
            Config.load();
            AiConfig.load();
            AccountStore.get().load();
            InstanceStore.get().load();
            Image icon = loadIcon();
            if (icon != null) {
                stage.getIcons().add(icon);
            }
            MainWindow window = new MainWindow(stage);
            window.show();
        } catch (Throwable e) {
            Log.error("启动器启动失败", e);
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("SS Launcher");
            alert.setHeaderText("启动器无法启动");
            alert.setContentText(String.valueOf(e.getMessage()));
            alert.showAndWait();
            javafx.application.Platform.exit();
        }
    }

    private Image loadIcon() {
        try (InputStream in = getClass().getResourceAsStream("/com/sslauncher/assets/icon.png")) {
            return in == null ? null : new Image(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
