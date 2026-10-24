package com.sslauncher.ui;

import com.sslauncher.model.Account;
import com.sslauncher.skin.SkinService;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders Minecraft skin files for the launcher: an 8×8 tab-list style head
 * (base plus hat overlay) and a small front-facing body preview. Everything is
 * sampled with nearest-neighbour scaling so HD skins stay crisp and pixel art
 * does not blur.
 */
public final class SkinImages {
    private static final int MAX_CACHE = 48;
    private static final Map<String, Image> HEADS = lru();
    private static final Map<String, Image> BODIES = lru();

    private SkinImages() {
    }

    /** The account's head, or {@code null} when it has no skin (caller falls back to PixelArt). */
    public static Image head(Account account) {
        if (account == null) {
            return null;
        }
        SkinService.SkinState state = SkinService.state(account);
        return state.file() == null ? null : head(state.file());
    }

    public static Image head(Path file) {
        String key = key(file, false);
        if (key == null) {
            return null;
        }
        synchronized (HEADS) {
            Image cached = HEADS.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Image skin = load(file);
        if (skin == null) {
            return null;
        }
        Image rendered = renderHead(skin);
        if (rendered != null) {
            synchronized (HEADS) {
                HEADS.put(key, rendered);
            }
        }
        return rendered;
    }

    /** The account's body preview, or {@code null} when it has no skin. */
    public static Image body(Account account) {
        if (account == null) {
            return null;
        }
        SkinService.SkinState state = SkinService.state(account);
        return state.file() == null ? null : body(state.file(), state.isSlim());
    }

    public static Image body(Path file, boolean slim) {
        String key = key(file, slim);
        if (key == null) {
            return null;
        }
        synchronized (BODIES) {
            Image cached = BODIES.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Image skin = load(file);
        if (skin == null) {
            return null;
        }
        Image rendered = renderBody(skin, slim);
        if (rendered != null) {
            synchronized (BODIES) {
                BODIES.put(key, rendered);
            }
        }
        return rendered;
    }

    /**
     * A fixed-size node that magnifies a skin image with nearest-neighbour
     * sampling. {@code ImageView.setSmooth(false)} is not reliable on the
     * hardware pipeline (it still bilinearly filters when magnifying), which
     * made the 8×8 head and the 16×32 body preview look blurry. A Canvas with
     * image smoothing switched off stays sharp at every display scale.
     */
    public static Canvas view(Image image, double width, double height) {
        Canvas canvas = new Canvas(width, height);
        draw(canvas, image);
        canvas.setMouseTransparent(true);
        return canvas;
    }

    /** Redraws a pixel-art {@link Canvas}; a null image clears it. */
    public static void draw(Canvas canvas, Image image) {
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (image == null) {
            return;
        }
        graphics.setImageSmoothing(false);
        graphics.drawImage(image, 0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private static Image load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Image image = new Image(file.toUri().toString());
            if (image.isError() || image.getWidth() < 64 || image.getHeight() < 32) {
                return null;
            }
            if (image.getWidth() > 2048 || image.getHeight() > 2048) {
                return null;
            }
            return image;
        } catch (Exception e) {
            return null;
        }
    }

    private static Image renderHead(Image skin) {
        int scale = scaleOf(skin);
        int[] base = copy(skin, 8, 8, 8, 8, scale, false);
        if (base == null) {
            return null;
        }
        int[] hat = copy(skin, 40, 8, 8, 8, scale, false);
        WritableImage out = new WritableImage(8, 8);
        PixelWriter writer = out.getPixelWriter();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int i = y * 8 + x;
                writer.setArgb(x, y, blend(hat == null ? 0 : hat[i], base[i]));
            }
        }
        return out;
    }

    /**
     * A 16×32 front view: head, body, both arms and both legs, in skin texture
     * coordinates. Legacy 64×32 skins mirror the right limbs for the left ones
     * and have no overlays below the head, exactly like the game.
     */
    private static Image renderBody(Image skin, boolean slim) {
        int scale = scaleOf(skin);
        boolean legacy = skin.getHeight() < 64L * scale;
        int arm = slim ? 3 : 4;
        int total = 8 + arm * 2;
        int x0 = (16 - total) / 2;
        int bodyX = x0 + arm;

        WritableImage out = new WritableImage(16, 32);
        PixelWriter writer = out.getPixelWriter();

        drawPart(writer, skin, 8, 8, 8, 8, 40, 8, bodyX, 0, scale, false);
        drawPart(writer, skin, 20, 20, 8, 12, 20, 36, bodyX, 8, scale, false);
        drawPart(writer, skin, 44, 20, arm, 12, 44, 36, x0, 8, scale, false);
        if (legacy) {
            drawPart(writer, skin, 44, 20, arm, 12, 44, 36,
                    x0 + arm + 8, 8, scale, true);
        } else {
            drawPart(writer, skin, 36, 52, arm, 12, 52, 52,
                    x0 + arm + 8, 8, scale, false);
        }
        drawPart(writer, skin, 4, 20, 4, 12, 4, 36, bodyX, 20, scale, false);
        if (legacy) {
            drawPart(writer, skin, 4, 20, 4, 12, 4, 36,
                    bodyX + 4, 20, scale, true);
        } else {
            drawPart(writer, skin, 20, 52, 4, 12, 4, 52,
                    bodyX + 4, 20, scale, false);
        }
        return out;
    }

    private static void drawPart(PixelWriter out, Image skin,
                                 int baseX, int baseY, int width, int height,
                                 int overlayX, int overlayY,
                                 int destX, int destY, int scale, boolean flip) {
        int[] base = copy(skin, baseX, baseY, width, height, scale, flip);
        int[] overlay = copy(skin, overlayX, overlayY, width, height, scale, flip);
        if (base == null && overlay == null) {
            return;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int bottom = base == null ? 0 : base[i];
                int top = overlay == null ? 0 : overlay[i];
                out.setArgb(destX + x, destY + y, blend(top, bottom));
            }
        }
    }

    /**
     * Samples one texture face. The full skin may be 64×64 or an integer
     * multiple; {@code scale=2} means a 128×128 skin. Returns null when the
     * region does not exist in this image (legacy skins have no overlays).
     */
    private static int[] copy(Image skin, int x, int y, int width, int height,
                              int scale, boolean flip) {
        int imageWidth = (int) skin.getWidth();
        int imageHeight = (int) skin.getHeight();
        if ((x + width) * scale > imageWidth || (y + height) * scale > imageHeight) {
            return null;
        }
        PixelReader reader = skin.getPixelReader();
        if (reader == null) {
            return null;
        }
        int[] pixels = new int[width * height];
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int sx = (x + (flip ? width - 1 - px : px)) * scale;
                int sy = (y + py) * scale;
                pixels[py * width + px] = reader.getArgb(sx, sy);
            }
        }
        return pixels;
    }

    /** Standard non-premultiplied source-over compositing. */
    private static int blend(int top, int bottom) {
        int topAlpha = top >>> 24;
        if (topAlpha == 0) {
            return bottom;
        }
        if (topAlpha == 255) {
            return top;
        }
        int bottomAlpha = bottom >>> 24;
        int outAlpha = topAlpha + bottomAlpha * (255 - topAlpha) / 255;
        if (outAlpha == 0) {
            return 0;
        }
        int weightTop = topAlpha * 255;
        int weightBottom = bottomAlpha * (255 - topAlpha);
        int red = (((top >> 16) & 0xFF) * weightTop
                + ((bottom >> 16) & 0xFF) * weightBottom) / (outAlpha * 255);
        int green = (((top >> 8) & 0xFF) * weightTop
                + ((bottom >> 8) & 0xFF) * weightBottom) / (outAlpha * 255);
        int blue = ((top & 0xFF) * weightTop
                + (bottom & 0xFF) * weightBottom) / (outAlpha * 255);
        return (outAlpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int scaleOf(Image skin) {
        return Math.max(1, (int) Math.round(skin.getWidth() / 64.0));
    }

    private static String key(Path file, boolean slim) {
        try {
            return file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis()
                    + "|" + Files.size(file) + (slim ? "|slim" : "|classic");
        } catch (IOException e) {
            return null;
        }
    }

    private static <K, V> Map<K, V> lru() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_CACHE;
            }
        };
    }
}
