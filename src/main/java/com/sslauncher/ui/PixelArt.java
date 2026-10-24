package com.sslauncher.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.Random;

/**
 * Small pixel-art pieces drawn in code, so they follow the Monet palette: an
 * isometric block (primary on top, tertiary for the soil, which is exactly a
 * grass block for the default background) and 8×8 player faces.
 */
public final class PixelArt {
    /** Fixed warm hue for skin, so faces read as faces whatever the theme. */
    private static final Palette.Tonal SKIN = new Palette.Tonal(62, 24);

    private PixelArt() {
    }

    /** Canvas that repaints itself when the palette changes. */
    public abstract static class Art extends Canvas implements Theme.Themed {
        Art(double width, double height) {
            super(width, height);
            setMouseTransparent(true);
            Theme.track(this);
        }

        @Override
        public final void repaint() {
            GraphicsContext g = getGraphicsContext2D();
            g.clearRect(0, 0, getWidth(), getHeight());
            draw(g, Theme.palette());
        }

        abstract void draw(GraphicsContext g, Palette palette);
    }

    public static Art block(double size) {
        return block(size, 7);
    }

    /** An isometric grass-style block; {@code seed} varies the pixel texture. */
    public static Art block(double size, long seed) {
        Art art = new Art(size, size) {
            @Override
            void draw(GraphicsContext g, Palette p) {
                drawBlock(g, size, seed, p);
            }
        };
        art.repaint();
        return art;
    }

    /** A Steve-like 8×8 face, varied by the player name. */
    public static Art face(String name, double size) {
        long seed = name == null ? 0 : name.hashCode();
        Art art = new Art(size, size) {
            @Override
            void draw(GraphicsContext g, Palette p) {
                drawFace(g, size, seed, name == null, p);
            }
        };
        art.repaint();
        return art;
    }

    private static void drawBlock(GraphicsContext g, double size, long seed, Palette p) {
        int n = size < 40 ? 4 : 8;
        Random random = new Random(seed * 31 + n);
        double w = size;
        double h = size;
        double cx = w / 2;
        double depth = h / 2;
        // Unit steps along the three visible edges, in cells.
        double ux = w / 2 / n;
        double uy = h / 4 / n;
        double dy = depth / n;

        double[] grassTones = {58, 62, 66, 54, 60};
        double[] soilTones = {40, 44, 36, 47, 42};
        // Top face: origin at the top vertex, i towards the right, j towards the left.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double ox = cx + i * ux - j * ux;
                double oy = i * uy + j * uy;
                Color c = p.primary.tone(grassTones[random.nextInt(grassTones.length)]);
                cell(g, c, ox, oy, ux, uy, -ux, uy);
            }
        }
        int[] overhangLeft = new int[n];
        int[] overhangRight = new int[n];
        for (int i = 0; i < n; i++) {
            overhangLeft[i] = Math.max(1, n / 4 + random.nextInt(Math.max(1, n / 4 + 1)));
            overhangRight[i] = Math.max(1, n / 4 + random.nextInt(Math.max(1, n / 4 + 1)));
        }
        // Left face: from the left vertex towards the front-bottom vertex.
        double lx = 0;
        double ly = h / 4;
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                double ox = lx + i * ux;
                double oy = ly + i * uy + k * dy;
                boolean grass = k < overhangLeft[i];
                double tone = grass ? grassTones[random.nextInt(grassTones.length)] - 10
                        : soilTones[random.nextInt(soilTones.length)];
                Color c = grass ? p.primary.tone(tone) : p.tertiary.tone(tone);
                cell(g, c, ox, oy, ux, uy, 0, dy);
            }
        }
        // Right face: from the front-bottom vertex towards the right vertex, in shade.
        double rx = cx;
        double ry = h / 2;
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                double ox = rx + i * ux;
                double oy = ry - i * uy + k * dy;
                boolean grass = k < overhangRight[i];
                double tone = grass ? grassTones[random.nextInt(grassTones.length)] - 20
                        : soilTones[random.nextInt(soilTones.length)] - 12;
                Color c = grass ? p.primary.tone(tone) : p.tertiary.tone(tone);
                cell(g, c, ox, oy, ux, -uy, 0, dy);
            }
        }
    }

    /** Fills one parallelogram cell; the matching stroke hides anti-aliasing seams. */
    private static void cell(GraphicsContext g, Color c, double ox, double oy,
                             double ax, double ay, double bx, double by) {
        double[] xs = {ox, ox + ax, ox + ax + bx, ox + bx};
        double[] ys = {oy, oy + ay, oy + ay + by, oy + by};
        g.setFill(c);
        g.setStroke(c);
        g.setLineWidth(0.6);
        g.fillPolygon(xs, ys, 4);
        g.strokePolygon(xs, ys, 4);
    }

    private static void drawFace(GraphicsContext g, double size, long seed, boolean unknown, Palette p) {
        Random random = new Random(seed);
        Palette.Tonal[] hairRoles = {p.primary, p.tertiary, p.secondary, p.neutralVariant};
        Palette.Tonal hairRole = hairRoles[random.nextInt(hairRoles.length)];
        Color hair = hairRole.tone(22 + random.nextInt(4) * 6);
        Color hairLight = hairRole.tone(34 + random.nextInt(3) * 4);
        double skinTone = new double[]{74, 66, 56, 46}[random.nextInt(4)];
        Color skin = SKIN.tone(skinTone);
        Color skinShade = SKIN.tone(skinTone - 9);
        Color eyeWhite = p.neutral.tone(96);
        Color pupil = (random.nextBoolean() ? p.primary : p.tertiary).tone(38);
        Color mouth = SKIN.tone(skinTone - 26);
        int fringe = 1 + random.nextInt(2);
        boolean beard = random.nextInt(4) == 0;

        if (unknown) {
            // Not signed in: a blank, faceless head in neutral tones.
            hair = p.neutralVariant.tone(30);
            hairLight = p.neutralVariant.tone(36);
            skin = p.neutralVariant.tone(52);
            skinShade = p.neutralVariant.tone(46);
        }
        double cell = size / 8.0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                Color c = skin;
                if (y < fringe || (y == fringe && (x == 0 || x == 7))
                        || (y == fringe + 1 && (x == 0 || x == 7) && fringe == 1)) {
                    c = (x + y + seed) % 3 == 0 ? hairLight : hair;
                } else if (!unknown && y == 4 && (x == 1 || x == 6)) {
                    c = eyeWhite;
                } else if (!unknown && y == 4 && (x == 2 || x == 5)) {
                    c = pupil;
                } else if (!unknown && y == 5 && (x == 3 || x == 4)) {
                    c = skinShade;
                } else if (!unknown && y == 6 && x >= 2 && x <= 5) {
                    c = mouth;
                } else if (!unknown && beard && y >= 6 && (x <= 1 || x >= 6 || y == 7)) {
                    c = hair;
                } else if ((x + y * 3 + seed) % 7 == 0) {
                    c = skinShade;
                }
                g.setFill(c);
                // Snap to whole pixels and overlap by one so no hairline gaps appear.
                double px = Math.floor(x * cell);
                double py = Math.floor(y * cell);
                g.fillRect(px, py, Math.ceil(cell) + 0.5, Math.ceil(cell) + 0.5);
            }
        }
    }
}
