package com.sslauncher.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;

/**
 * Monet-style dynamic colour. The dominant hue of the background picture
 * becomes the seed, a second clearly different hue becomes the tertiary
 * accent, and both are expanded into tonal palettes.
 *
 * <p>A tone is CIE L* (0 = black, 100 = white) at a fixed hue and chroma in
 * CIE LCh, with chroma reduced only as far as needed to stay inside sRGB.
 * That keeps every role of one palette visibly the same colour family, which
 * is the property the Material You "tonal spot" scheme relies on.
 */
public final class Palette {
    /** One hue/chroma pair; {@link #tone(double)} renders it at a lightness. */
    public record Tonal(double hue, double chroma) {
        public Color tone(double tone) {
            return lch(tone, chroma, hue);
        }
    }

    /** Grass green: used before any picture is read, and for grey pictures. */
    private static final double FALLBACK_HUE = 132;

    public static final Palette DEFAULT = fromHues(FALLBACK_HUE, 48, 62, 34);

    public final Tonal primary;
    public final Tonal secondary;
    public final Tonal tertiary;
    public final Tonal neutral;
    public final Tonal neutralVariant;
    public final Tonal error;
    public final Tonal success;
    public final Tonal warning;

    private Palette(double hue, double chroma, double tertiaryHue, double tertiaryChroma) {
        primary = new Tonal(hue, clamp(chroma, 30, 56));
        secondary = new Tonal(hue, 16);
        tertiary = new Tonal(tertiaryHue, clamp(tertiaryChroma, 24, 42));
        neutral = new Tonal(hue, 5);
        neutralVariant = new Tonal(hue, 10);
        // Semantic colours keep their meaning but lean towards the seed hue so
        // they sit in the same family instead of fighting it.
        error = new Tonal(harmonize(32, hue), 58);
        success = new Tonal(harmonize(140, hue), 46);
        warning = new Tonal(harmonize(78, hue), 54);
    }

    public static Palette fromHues(double hue, double chroma, double tertiaryHue, double tertiaryChroma) {
        return new Palette(normalize(hue), chroma, normalize(tertiaryHue), tertiaryChroma);
    }

    public static Palette fromSeed(Color seed) {
        double[] lab = toLab(seed);
        double chroma = Math.hypot(lab[1], lab[2]);
        double hue = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return fromHues(hue, chroma, hue + 60, chroma * 0.7);
    }

    /** The seed colour the palette was built around, at a mid tone. */
    public Color seed() {
        return primary.tone(55);
    }

    /**
     * Picks the seed from a picture. Pixels are bucketed into 10° hue bins
     * weighted by chroma, with very dark and very bright pixels ignored, so a
     * large colourful area wins over a few saturated specks or grey sky.
     */
    public static Palette fromImage(Image image) {
        if (image == null || image.isError() || image.getPixelReader() == null
                || image.getWidth() < 1 || image.getHeight() < 1) {
            return DEFAULT;
        }
        PixelReader reader = image.getPixelReader();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int step = Math.max(1, (int) Math.sqrt((double) width * height / 16000.0));

        final int bins = 36;
        double[] weight = new double[bins];
        double[] chromaSum = new double[bins];
        double[] hueX = new double[bins];
        double[] hueY = new double[bins];
        int[] count = new int[bins];
        int samples = 0;
        int chromatic = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int argb = reader.getArgb(x, y);
                if ((argb >>> 24) < 128) {
                    continue;
                }
                samples++;
                double[] lab = toLab(((argb >> 16) & 0xFF) / 255.0, ((argb >> 8) & 0xFF) / 255.0,
                        (argb & 0xFF) / 255.0);
                double l = lab[0];
                double chroma = Math.hypot(lab[1], lab[2]);
                if (l < 10 || l > 96 || chroma < 7) {
                    continue;
                }
                chromatic++;
                double angle = Math.atan2(lab[2], lab[1]);
                double hue = normalize(Math.toDegrees(angle));
                int bin = (int) (hue / (360.0 / bins)) % bins;
                double lightness = Math.max(0.25, 1 - Math.abs(l - 55) / 55);
                double w = chroma * lightness;
                weight[bin] += w;
                count[bin]++;
                chromaSum[bin] += chroma * w;
                hueX[bin] += Math.cos(angle) * w;
                hueY[bin] += Math.sin(angle) * w;
            }
        }
        if (samples == 0 || chromatic < samples * 0.04) {
            // Essentially a grey picture: stay quiet rather than invent a hue.
            return fromHues(FALLBACK_HUE, 18, FALLBACK_HUE + 60, 20);
        }

        double[] smooth = new double[bins];
        for (int i = 0; i < bins; i++) {
            smooth[i] = weight[(i + bins - 1) % bins] * 0.5 + weight[i] + weight[(i + 1) % bins] * 0.5;
        }
        int best = 0;
        for (int i = 1; i < bins; i++) {
            if (smooth[i] > smooth[best]) {
                best = i;
            }
        }
        double[] main = windowAverage(best, weight, chromaSum, hueX, hueY);

        // Tertiary: the strongest hue at least 50° away from the seed, as long
        // as it covers a meaningful share of the picture (the dirt under the
        // grass). Area, not weight, decides: such accents are often muted.
        int second = -1;
        for (int i = 0; i < bins; i++) {
            int distance = Math.min(Math.abs(i - best), bins - Math.abs(i - best));
            int area = count[(i + bins - 1) % bins] + count[i] + count[(i + 1) % bins];
            if (distance >= 5 && area >= chromatic * 0.05
                    && (second < 0 || smooth[i] > smooth[second])) {
                second = i;
            }
        }
        if (second < 0) {
            return fromHues(main[0], main[1], main[0] + 60, main[1] * 0.7);
        }
        double[] accent = windowAverage(second, weight, chromaSum, hueX, hueY);
        return fromHues(main[0], main[1], accent[0], accent[1]);
    }

    /** Circular mean hue and weighted chroma of a bin and its two neighbours. */
    private static double[] windowAverage(int center, double[] weight, double[] chromaSum,
                                          double[] hueX, double[] hueY) {
        int bins = weight.length;
        double w = 0;
        double c = 0;
        double x = 0;
        double y = 0;
        for (int d = -1; d <= 1; d++) {
            int i = (center + d + bins) % bins;
            w += weight[i];
            c += chromaSum[i];
            x += hueX[i];
            y += hueY[i];
        }
        double hue = normalize(Math.toDegrees(Math.atan2(y, x)));
        return new double[]{hue, w > 0 ? c / w : 30};
    }

    /** Rotates {@code hue} towards {@code toward} by at most 15°, like Material's blend. */
    private static double harmonize(double hue, double toward) {
        double diff = normalize(toward - hue);
        if (diff > 180) {
            diff -= 360;
        }
        double rotation = Math.min(Math.abs(diff) * 0.5, 15);
        return normalize(hue + Math.signum(diff) * rotation);
    }

    // ------------------------------------------------------------ colour math

    private static double normalize(double degrees) {
        double d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** CIE LCh(ab) to sRGB, lowering chroma by bisection until it is in gamut. */
    static Color lch(double l, double c, double hueDegrees) {
        if (l <= 0) {
            return Color.BLACK;
        }
        if (l >= 100) {
            return Color.WHITE;
        }
        double h = Math.toRadians(hueDegrees);
        double cos = Math.cos(h);
        double sin = Math.sin(h);
        double[] rgb = labToRgb(l, c * cos, c * sin);
        if (inGamut(rgb)) {
            return toColor(rgb);
        }
        double lo = 0;
        double hi = c;
        for (int i = 0; i < 20; i++) {
            double mid = (lo + hi) / 2;
            if (inGamut(labToRgb(l, mid * cos, mid * sin))) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return toColor(labToRgb(l, lo * cos, lo * sin));
    }

    private static boolean inGamut(double[] rgb) {
        for (double v : rgb) {
            if (v < -1e-4 || v > 1 + 1e-4) {
                return false;
            }
        }
        return true;
    }

    private static Color toColor(double[] rgb) {
        return Color.color(clamp(rgb[0], 0, 1), clamp(rgb[1], 0, 1), clamp(rgb[2], 0, 1));
    }

    static double[] toLab(Color color) {
        return toLab(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static double[] toLab(double r, double g, double b) {
        double lr = linear(r);
        double lg = linear(g);
        double lb = linear(b);
        double x = (0.4124564 * lr + 0.3575761 * lg + 0.1804375 * lb) / 0.95047;
        double y = 0.2126729 * lr + 0.7151522 * lg + 0.0721750 * lb;
        double z = (0.0193339 * lr + 0.1191920 * lg + 0.9503041 * lb) / 1.08883;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double[] labToRgb(double l, double a, double b) {
        double fy = (l + 16) / 116;
        double fx = fy + a / 500;
        double fz = fy - b / 200;
        double x = 0.95047 * labFInverse(fx);
        double y = labFInverse(fy);
        double z = 1.08883 * labFInverse(fz);
        double r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        double bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        return new double[]{gamma(r), gamma(g), gamma(bl)};
    }

    private static double linear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double gamma(double v) {
        return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    }

    private static double labF(double t) {
        return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16) / 116;
    }

    private static double labFInverse(double t) {
        double cube = t * t * t;
        return cube > 216.0 / 24389.0 ? cube : (116 * t - 16) / (24389.0 / 27.0);
    }
}
