package com.sslauncher.service;

import com.sslauncher.core.AccountStore;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Log;
import com.sslauncher.multiplayer.MultiplayerService;
import com.sslauncher.multiplayer.MultiplayerService.RoomMember;
import com.sslauncher.multiplayer.MultiplayerService.Status;
import com.sslauncher.ui.MainWindow;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Development-only screenshot harness. Run through the Gradle {@code uiShot}
 * task, e.g. {@code ./gradlew uiShot -Ppage=HOME -Pshot=build/home.png}.
 */
public final class UiProbe {
    private UiProbe() {
    }

    public static void main(String[] args) {
        Application.launch(ShotApp.class, args);
    }

    public static class ShotApp extends Application {
        @Override
        public void start(Stage stage) {
            System.setProperty("ss.debugUi", "true");
            AppPaths.init();
            Log.init();
            Config.load();
            com.sslauncher.ai.AiConfig.load();
            AccountStore.get().load();
            InstanceStore.get().load();
            seedMultiplayerDemo();
            MainWindow window = new MainWindow(stage);
            window.show();

            int delay = Integer.getInteger("ss.shotDelay", 1400);
            PauseTransition pause = new PauseTransition(Duration.millis(delay));
            pause.setOnFinished(e -> {
                try {
                    Path out = Path.of(System.getProperty("ss.shot", "build/ui-shot.png"));
                    if (out.getParent() != null) {
                        java.nio.file.Files.createDirectories(out.getParent());
                    }
                    ImageIO.write(snapshot(stage.getScene()), "png", out.toFile());
                    System.out.println("PROBE screenshot=" + out.toAbsolutePath());
                    if (Boolean.getBoolean("ss.dump")) {
                        dump(stage.getScene().getRoot());
                        dumpControls(stage.getScene(), ".icon-btn");
                        dumpControls(stage.getScene(), ".more-button");
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    Platform.exit();
                }
            });
            pause.play();
        }

        /**
         * Seeded room state for roster screenshots: {@code -PmultiplayerDemo=1}.
         * Purely a probe convenience; no network node is started.
         */
        private static void seedMultiplayerDemo() {
            if (!Boolean.getBoolean("ss.multiplayerDemo")) {
                return;
            }
            try {
                MultiplayerService service = MultiplayerService.get();
                Map<String, RoomMember> members = new LinkedHashMap<>();
                members.put("12D3KooWProbeHost0000000000000000000000000000000001",
                        new RoomMember("12D3KooWProbeHost0000000000000000000000000000000001",
                                "星河", true, false));
                members.put("12D3KooWProbeSelf0000000000000000000000000000000002",
                        new RoomMember("12D3KooWProbeSelf0000000000000000000000000000000002",
                                "阿澈", false, true));
                members.put("12D3KooWProbeGuest000000000000000000000000000000003",
                        new RoomMember("12D3KooWProbeGuest000000000000000000000000000000003",
                                "小北", false, false));
                members.put("12D3KooWProbeGuest000000000000000000000000000000004",
                        new RoomMember("12D3KooWProbeGuest000000000000000000000000000000004",
                                "Nova", false, false));

                setField(service, "hosting", false);
                setField(service, "roomCode", "AB12CD");
                setField(service, "hostPeerId", "12D3KooWProbeHost0000000000000000000000000000000001");
                setField(service, "peerId", "12D3KooWProbeSelf0000000000000000000000000000000002");
                setField(service, "gamePort", 25565);
                setField(service, "roomMembers", members);
                Method publish = MultiplayerService.class
                        .getDeclaredMethod("publish", Status.class, String.class);
                publish.setAccessible(true);
                publish.invoke(service, Status.CONNECTED,
                        "已连接房主，可以在 Minecraft 中连接本地地址");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法构造联机演示状态", e);
            }
        }

        private static void setField(Object target, String name, Object value)
                throws ReflectiveOperationException {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        private static void dumpControls(Scene scene, String selector) {
            for (javafx.scene.Node node : scene.getRoot().lookupAll(selector)) {
                javafx.geometry.Bounds bounds = node.localToScreen(node.getBoundsInLocal());
                String tip = node instanceof javafx.scene.control.Control control
                        && control.getTooltip() != null ? control.getTooltip().getText() : "";
                System.out.printf("PROBE control %s bounds=%s tooltip=%s%n",
                        selector, bounds, tip);
            }
        }

        private static void dump(javafx.scene.Node node) {
            if (node.getOpacity() < 0.999) {
                String text = node instanceof javafx.scene.control.Label label ? " text=" + label.getText() : "";
                System.out.printf("PROBE dim %-28s opacity=%.2f visible=%s%s%n",
                        node.getClass().getSimpleName() + " " + node.getStyleClass(),
                        node.getOpacity(), node.isVisible(), text);
            }
            if (node instanceof javafx.scene.Parent parent) {
                for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                    dump(child);
                }
            }
        }

        private static BufferedImage snapshot(Scene scene) {
            WritableImage image = scene.snapshot(null);
            int width = (int) Math.ceil(image.getWidth());
            int height = (int) Math.ceil(image.getHeight());
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            PixelReader reader = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            return out;
        }
    }
}
