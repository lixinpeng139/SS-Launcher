package com.sslauncher.ui;

import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes the current {@link Palette} as looked-up colours ({@code -md-*})
 * on {@code .root}. The stylesheet and inline styles only ever reference those
 * names, so swapping the background re-tints every window, popup and dialog
 * without rebuilding any node.
 */
public final class Theme {
    private static final String SHEET_PREFIX = "data:text/css;base64,";

    /** Code-drawn pieces that need redrawing when the palette changes. */
    public interface Themed {
        void repaint();
    }

    private static Palette palette = Palette.DEFAULT;
    private static String sheet = buildSheet(palette);
    private static final List<WeakReference<Scene>> scenes = new ArrayList<>();
    private static final List<WeakReference<Themed>> drawn = new ArrayList<>();
    private static final List<Runnable> listeners = new ArrayList<>();

    private Theme() {
    }

    public static Palette palette() {
        return palette;
    }

    /** Weakly remembers a drawing so it is repainted on the next palette change. */
    public static void track(Themed themed) {
        if (drawn.size() > 256) {
            drawn.removeIf(ref -> ref.get() == null);
        }
        drawn.add(new WeakReference<>(themed));
    }

    /** Adds the theme sheet to a scene (after app.css) and keeps it updated. */
    public static void attach(Scene scene) {
        scenes.removeIf(ref -> ref.get() == null || ref.get() == scene);
        scenes.add(new WeakReference<>(scene));
        install(scene);
    }

    public static void apply(Palette next) {
        palette = next == null ? Palette.DEFAULT : next;
        sheet = buildSheet(palette);
        scenes.removeIf(ref -> ref.get() == null);
        for (WeakReference<Scene> ref : scenes) {
            Scene scene = ref.get();
            if (scene != null) {
                install(scene);
            }
        }
        drawn.removeIf(ref -> ref.get() == null);
        for (WeakReference<Themed> ref : List.copyOf(drawn)) {
            Themed themed = ref.get();
            if (themed != null) {
                themed.repaint();
            }
        }
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    /** Called after every palette change, e.g. to redraw swatches. */
    public static void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private static void install(Scene scene) {
        scene.getStylesheets().removeIf(url -> url.startsWith(SHEET_PREFIX));
        scene.getStylesheets().add(sheet);
    }

    /** The palette's roles in display order, for the swatch strip in settings. */
    public static Map<String, Color> swatches() {
        Map<String, Color> map = new LinkedHashMap<>();
        map.put("主色", palette.primary.tone(80));
        map.put("主色容器", palette.primary.tone(30));
        map.put("次要色", palette.secondary.tone(80));
        map.put("点缀色", palette.tertiary.tone(80));
        map.put("点缀容器", palette.tertiary.tone(30));
        map.put("表面", palette.neutral.tone(12));
        map.put("表面容器", palette.neutral.tone(22));
        map.put("文字", palette.neutral.tone(92));
        return map;
    }

    private static String buildSheet(Palette p) {
        StringBuilder css = new StringBuilder(".root {\n");
        role(css, "primary", p.primary);
        role(css, "secondary", p.secondary);
        role(css, "tertiary", p.tertiary);
        role(css, "error", p.error);
        role(css, "success", p.success);
        role(css, "warning", p.warning);

        // Raised (Minecraft-style) button bevels.
        put(css, "btn-primary-face", p.primary.tone(62));
        put(css, "btn-primary-hover", p.primary.tone(68));
        put(css, "btn-primary-pressed", p.primary.tone(54));
        put(css, "btn-primary-light", p.primary.tone(78));
        put(css, "btn-primary-shade", p.primary.tone(42));
        put(css, "btn-primary-edge", p.primary.tone(12));
        put(css, "btn-primary-text", p.primary.tone(8));
        put(css, "btn-face", p.neutralVariant.tone(32));
        put(css, "btn-hover", p.neutralVariant.tone(38));
        put(css, "btn-pressed", p.neutralVariant.tone(27));
        put(css, "btn-light", p.neutralVariant.tone(46));
        put(css, "btn-shade", p.neutralVariant.tone(20));
        put(css, "btn-edge", p.neutral.tone(4));

        // Surfaces (dark scheme).
        put(css, "surface-lowest", p.neutral.tone(4));
        put(css, "surface-dim", p.neutral.tone(6));
        put(css, "surface", p.neutral.tone(8));
        put(css, "surface-low", p.neutral.tone(10));
        put(css, "surface-container", p.neutral.tone(12));
        put(css, "surface-high", p.neutral.tone(17));
        put(css, "surface-highest", p.neutral.tone(22));
        put(css, "surface-variant", p.neutralVariant.tone(30));
        put(css, "on-surface", p.neutral.tone(92));
        put(css, "on-surface-variant", p.neutralVariant.tone(80));
        put(css, "muted", p.neutralVariant.tone(70));
        put(css, "faint", p.neutralVariant.tone(58));
        put(css, "outline", p.neutralVariant.tone(52));
        put(css, "outline-variant", p.neutralVariant.tone(32));
        put(css, "scrim", p.neutral.tone(3));

        // Translucent layers over the blurred background.
        put(css, "glass-bar", alpha(p.neutral.tone(6), 0.80));
        put(css, "glass-card", alpha(p.neutral.tone(10), 0.80));
        put(css, "glass-card-hover", alpha(p.neutral.tone(14), 0.86));
        put(css, "glass-flat", alpha(p.neutral.tone(16), 0.50));
        put(css, "glass-slot", alpha(p.neutral.tone(4), 0.55));
        put(css, "glass-field", alpha(p.neutral.tone(5), 0.72));
        put(css, "state-hover", alpha(p.neutral.tone(92), 0.08));
        put(css, "state-press", alpha(p.neutral.tone(92), 0.12));
        put(css, "line", alpha(p.neutral.tone(92), 0.09));
        put(css, "line-strong", alpha(p.neutral.tone(92), 0.16));
        put(css, "slot-dark", alpha(p.neutral.tone(0), 0.45));
        put(css, "slot-light", alpha(p.neutral.tone(92), 0.10));
        put(css, "shadow", alpha(p.neutral.tone(0), 0.55));
        put(css, "text-shadow", alpha(p.neutral.tone(0), 0.75));
        css.append("}\n");
        return SHEET_PREFIX + Base64.getEncoder().encodeToString(css.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Material's dark-scheme roles plus a few translucent variants. */
    private static void role(StringBuilder css, String name, Palette.Tonal tonal) {
        Color main = tonal.tone(80);
        put(css, name, main);
        put(css, "on-" + name, tonal.tone(20));
        put(css, name + "-container", tonal.tone(30));
        put(css, "on-" + name + "-container", tonal.tone(90));
        put(css, name + "-bright", tonal.tone(88));
        put(css, name + "-a10", alpha(main, 0.10));
        put(css, name + "-a18", alpha(main, 0.18));
        put(css, name + "-a30", alpha(main, 0.30));
        put(css, name + "-a50", alpha(main, 0.50));
    }

    private static Color alpha(Color color, double opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
    }

    private static void put(StringBuilder css, String name, Color color) {
        css.append("    -md-").append(name).append(": ").append(rgba(color)).append(";\n");
    }

    public static String rgba(Color color) {
        if (color.getOpacity() >= 0.999) {
            return hex(color);
        }
        return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.3f)",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255),
                color.getOpacity());
    }

    public static String hex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
