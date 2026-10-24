package com.sslauncher.ui;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Log;
import javafx.scene.CacheHint;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The window background: a user-chosen picture scaled to cover the window,
 * blurred and dimmed by the amounts set in the appearance settings. Changing
 * the picture also re-derives the Monet palette from it.
 */
public final class Backdrop extends Region {
    public static final String DEFAULT_RESOURCE = "/com/sslauncher/assets/bg-default.jpg";
    private static final double MAX_BLUR_RADIUS = 63;

    private final ImageView view = new ImageView();
    private final Region dim = new Region();
    private final GaussianBlur blur = new GaussianBlur(0);
    private final Rectangle clip = new Rectangle();
    private double blurPercent;

    public Backdrop() {
        getStyleClass().add("backdrop");
        view.setPreserveRatio(false);
        view.setSmooth(true);
        view.setCache(true);
        view.setCacheHint(CacheHint.QUALITY);
        dim.getStyleClass().add("backdrop-dim");
        getChildren().addAll(view, dim);
        setMouseTransparent(true);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
    }

    /** Applies the configured picture, blur and dim, updating the palette synchronously. */
    public void loadFromConfig() {
        Config config = Config.get();
        setBlur(config.backgroundBlur);
        setDim(config.backgroundDim);
        Path path = resolve(config.backgroundImage);
        if (!config.backgroundImage.isBlank() && path == null) {
            Log.warn("背景图片不存在，已恢复默认背景: " + config.backgroundImage);
            config.backgroundImage = "";
        }
        Theme.apply(Palette.fromImage(loadPreview(path)));
        setImage(loadFull(path));
    }

    /**
     * Switches to another picture: {@code null} restores the bundled one. The
     * palette is extracted off the UI thread, then picture and colours change
     * together so the window never shows a mismatched combination.
     */
    public void change(Path path, Runnable onDone) {
        Async.supply(() -> new Object[]{Palette.fromImage(loadPreview(path)), loadFull(path)}, result -> {
            Config.get().backgroundImage = path == null ? "" : path.toString();
            Config.save();
            Theme.apply((Palette) result[0]);
            setImage((Image) result[1]);
            if (onDone != null) {
                onDone.run();
            }
        }, error -> Log.warn("背景切换失败: " + error.getMessage()));
    }

    public void setImage(Image image) {
        view.setImage(image);
        requestLayout();
    }

    /** @param percent 0–100 */
    public void setBlur(double percent) {
        blurPercent = Math.max(0, Math.min(100, percent));
        double radius = blurPercent / 100.0 * MAX_BLUR_RADIUS;
        blur.setRadius(radius);
        view.setEffect(radius < 0.5 ? null : blur);
        requestLayout();
    }

    /** @param percent 0–80; how strongly the surface colour veils the picture. */
    public void setDim(double percent) {
        dim.setOpacity(Math.max(0, Math.min(80, percent)) / 100.0);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        dim.resizeRelocate(0, 0, w, h);
        Image image = view.getImage();
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return;
        }
        // Grow past the edges by the blur radius so the soft, semi-transparent
        // rim the blur produces stays outside the visible area.
        double bleed = blur.getRadius() * (view.getEffect() == null ? 0 : 1);
        double targetW = w + bleed * 2;
        double targetH = h + bleed * 2;
        double scale = Math.max(targetW / image.getWidth(), targetH / image.getHeight());
        double drawW = image.getWidth() * scale;
        double drawH = image.getHeight() * scale;
        view.setFitWidth(drawW);
        view.setFitHeight(drawH);
        view.relocate((w - drawW) / 2, (h - drawH) / 2);
    }

    // ------------------------------------------------------------ storage

    /** Folder that keeps copies of imported pictures, so moving the originals is harmless. */
    public static Path folder() {
        return AppPaths.ROOT.resolve("backgrounds");
    }

    /** Imported pictures, newest first. */
    public static List<Path> saved() {
        List<Path> list = new ArrayList<>();
        Path dir = folder();
        if (!Files.isDirectory(dir)) {
            return list;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Backdrop::isImage).forEach(list::add);
        } catch (IOException e) {
            Log.warn("读取背景目录失败: " + e.getMessage());
        }
        list.sort((a, b) -> Long.compare(modified(b), modified(a)));
        return list;
    }

    public static Path importImage(Path source) throws IOException {
        Path dir = folder();
        Files.createDirectories(dir);
        if (source.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize())) {
            return source;
        }
        String name = source.getFileName().toString();
        Path target = dir.resolve(name);
        int n = 1;
        while (Files.exists(target)) {
            if (Files.size(target) == Files.size(source) && Files.mismatch(target, source) == -1) {
                return target;
            }
            int dot = name.lastIndexOf('.');
            target = dir.resolve(dot > 0 ? name.substring(0, dot) + "-" + n + name.substring(dot) : name + "-" + n);
            n++;
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    public static boolean isImage(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".bmp") || name.endsWith(".gif"));
    }

    /** Same picture? {@code null} stands for the bundled default. */
    public static boolean isCurrent(Path path) {
        String current = Config.get().backgroundImage;
        if (path == null) {
            return current.isBlank();
        }
        return !current.isBlank() && Path.of(current).toAbsolutePath().normalize()
                .equals(path.toAbsolutePath().normalize());
    }

    /** A small version of a picture, for thumbnails and palette extraction. */
    public static Image thumbnail(Path path, double width) {
        try (InputStream in = open(path)) {
            return in == null ? null : new Image(in, width, 0, true, true);
        } catch (IOException e) {
            return null;
        }
    }

    private static Image loadPreview(Path path) {
        return thumbnail(path, 192);
    }

    private static Image loadFull(Path path) {
        try (InputStream in = open(path)) {
            // Pictures far larger than a screen only cost memory and blur time.
            return in == null ? null : new Image(in, 2560, 2560, true, true);
        } catch (IOException e) {
            Log.warn("背景图片读取失败: " + e.getMessage());
            return null;
        }
    }

    private static InputStream open(Path path) throws IOException {
        if (path == null) {
            return Backdrop.class.getResourceAsStream(DEFAULT_RESOURCE);
        }
        return Files.newInputStream(path);
    }

    private static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(configured);
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
