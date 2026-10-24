package com.sslauncher.ui;

import com.sslauncher.model.Account;
import com.sslauncher.skin.SkinService;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small shared helpers for building the JavaFX interface. */
public final class Ui {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");

    private Ui() {
    }

    /** Each argument may hold several space-separated classes, e.g. {@code "chip chip-muted"}. */
    public static Label label(String text, String... classes) {
        Label label = new Label(text);
        for (String group : classes) {
            for (String name : group.trim().split("\\s+")) {
                if (!name.isEmpty()) {
                    label.getStyleClass().add(name);
                }
            }
        }
        return label;
    }

    public static Label title(String text) {
        return label(text, "page-title");
    }

    public static Label subtitle(String text) {
        return label(text, "subtitle");
    }

    public static Button button(String text, String... classes) {
        Button button = new Button(text);
        button.getStyleClass().addAll(classes);
        button.setFocusTraversable(false);
        return button;
    }

    public static Button primaryButton(String text) {
        return button(text, "btn", "btn-primary");
    }

    public static Button ghostButton(String text) {
        return button(text, "btn", "btn-ghost");
    }

    public static Button textButton(String text) {
        return button(text, "btn-text");
    }

    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        VBox.setVgrow(region, Priority.ALWAYS);
        return region;
    }

    public static Region hspacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    public static Region vspacer() {
        Region region = new Region();
        VBox.setVgrow(region, Priority.ALWAYS);
        return region;
    }

    public static SVGPath icon(String path, double size) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        svg.getStyleClass().add("icon");
        return svg;
    }

    /**
     * An icon coloured by a theme role: one of {@code primary}, {@code tertiary},
     * {@code muted}, {@code success}, {@code warning}, {@code error}.
     */
    public static SVGPath icon(String path, double size, String role) {
        SVGPath svg = icon(path, size);
        svg.getStyleClass().add("icon-" + role);
        return svg;
    }

    public static Image image(String name) {
        InputStream in = Ui.class.getResourceAsStream("/com/sslauncher/assets/" + name);
        if (in == null) {
            return null;
        }
        return new Image(in, 0, 0, true, true);
    }

    public static ImageView imageView(String name) {
        Image image = image(name);
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    public static ScrollPane scroll(Node content) {
        ScrollPane pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.getStyleClass().add("page-scroll");
        return pane;
    }

    public static VBox pageBox() {
        VBox box = new VBox(22);
        box.getStyleClass().add("page");
        return box;
    }

    /**
     * A pixel-art player face: square with a thin dark rim, like a skin head
     * in the Minecraft tab list. Colours follow the theme.
     */
    public static StackPane avatar(String name, double size) {
        StackPane pane = new StackPane(PixelArt.face(name, size - 4));
        pane.getStyleClass().add("pixel-avatar");
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        return pane;
    }

    /**
     * A tab-list style avatar taken from the account's current skin. While the
     * skin cache is cold the procedural face is shown and the skin is fetched
     * in the background; the next UI refresh swaps in the real head.
     */
    public static StackPane accountAvatar(Account account, double size) {
        if (account != null) {
            SkinService.ensureLoaded(account);
        }
        Image head = account == null ? null : SkinImages.head(account);
        Node content = head == null
                ? PixelArt.face(account == null ? null : account.name, size - 4)
                : SkinImages.view(head, size - 4, size - 4);
        StackPane pane = new StackPane(content);
        pane.getStyleClass().add("pixel-avatar");
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        return pane;
    }

    /** The theme-tinted isometric block used as the launcher's mark. */
    public static Node grassBlock(double size) {
        return PixelArt.block(size);
    }

    /**
     * A Minecraft-style inventory slot: a sunken square with a block inside.
     * Used for instance and empty-state icons.
     */
    public static StackPane slot(double size, long seed) {
        StackPane slot = new StackPane(PixelArt.block(size * 0.62, seed));
        slot.getStyleClass().add("mc-slot");
        slot.setMinSize(size, size);
        slot.setPrefSize(size, size);
        slot.setMaxSize(size, size);
        return slot;
    }

    /** A slot with a short label in it, e.g. a loader or file type. */
    public static StackPane slot(double size, String text) {
        Label label = label(text, "slot-label");
        StackPane slot = new StackPane(label);
        slot.getStyleClass().add("mc-slot");
        slot.setMinSize(size, size);
        slot.setPrefSize(size, size);
        slot.setMaxSize(size, size);
        return slot;
    }

    /** Sunken square holding an icon, for empty states and notices. */
    public static StackPane iconSlot(String svg, double size) {
        SVGPath icon = icon(svg, size * 0.42);
        icon.getStyleClass().add("slot-icon");
        StackPane slot = new StackPane(icon);
        slot.getStyleClass().add("mc-slot");
        slot.setMinSize(size, size);
        slot.setPrefSize(size, size);
        slot.setMaxSize(size, size);
        return slot;
    }

    /** Small all-caps heading with a block bullet, used above page sections. */
    public static HBox sectionHeading(String text) {
        Region bullet = new Region();
        bullet.getStyleClass().add("heading-bullet");
        HBox row = new HBox(8, bullet, label(text, "section-title"));
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    public static String initial(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    public static HBox row(double spacing, Node... children) {
        HBox box = new HBox(spacing);
        box.getChildren().addAll(children);
        return box;
    }

    public static VBox column(double spacing, Node... children) {
        VBox box = new VBox(spacing);
        box.getChildren().addAll(children);
        return box;
    }

    public static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception ignored) {
        }
    }

    public static void copy(String text) {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text == null ? "" : text);
        clipboard.setContent(content);
    }

    public static String formatDate(long millis) {
        if (millis <= 0) {
            return "从未";
        }
        LocalDate date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
        return date.format(DATE);
    }

    public static String relativeTime(long millis) {
        if (millis <= 0) {
            return "从未启动";
        }
        long delta = System.currentTimeMillis() - millis;
        long minutes = delta / 60_000L;
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + " 天前";
        }
        return formatDate(millis);
    }

    public static String greeting() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour < 6) {
            return "夜深了";
        }
        if (hour < 12) {
            return "早上好";
        }
        if (hour < 18) {
            return "下午好";
        }
        return "晚上好";
    }

    public static void fadeIn(Node node, double millis) {
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(1);
        fade.play();
    }

    public static void slideIn(Node node) {
        node.setOpacity(0);
        node.setTranslateY(10);
        FadeTransition fade = new FadeTransition(Duration.millis(230), node);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(230), node);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        fade.play();
        slide.play();
    }

    public static void loadFontFallback() {
        // JavaFX picks Microsoft YaHei UI through the stylesheet; nothing to do.
        Font.getDefault();
    }
}
