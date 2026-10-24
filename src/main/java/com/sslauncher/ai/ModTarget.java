package com.sslauncher.ai;

import com.sslauncher.model.LoaderType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Minecraft/mod metadata chosen on the first screen of AI 制作. */
public class ModTarget {
    private static final Pattern MC = Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?");
    /** Mojang's year-based release ids, e.g. 26.1 or 26.4-snapshot-5. */
    private static final Pattern YEAR = Pattern.compile("^(\\d{2})\\.");

    public String mcVersion = "";
    public String loader = LoaderType.FABRIC.id;
    public String loaderVersion = "";
    public String modName = "";
    public String modId = "";
    public String packageName = "";
    public String description = "";
    public String author = "";

    public LoaderType loaderType() {
        return LoaderType.fromId(loader);
    }

    public String loaderLabel() {
        return loaderType().label;
    }

    public String summary() {
        String v = mcVersion == null ? "" : mcVersion;
        String l = loaderLabel();
        if (loaderType() == LoaderType.VANILLA) {
            return "Minecraft " + v + " · 原版";
        }
        return "Minecraft " + v + " · " + l
                + (loaderVersion == null || loaderVersion.isBlank() ? "" : " " + loaderVersion);
    }

    /**
     * Java release used by the Gradle/Maven launcher process. Modern loaders
     * require Java 21; 1.18–1.20.4 use 17, and older Forge toolchains need 8.
     */
    public int javaMajor() {
        String v = mcVersion == null ? "" : mcVersion.trim();
        Matcher m = MC.matcher(v);
        if (m.find()) {
            int minor = Integer.parseInt(m.group(1));
            int patch = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            // Java 21 is mandatory from 1.20.5 onward, including every 1.21.x
            // release; the patch component only matters for the 1.20 line.
            if (minor > 20 || (minor == 20 && patch >= 5)) {
                return 21;
            }
            if (minor >= 17) {
                return 17;
            }
            return 8;
        }
        Matcher year = YEAR.matcher(v);
        if (year.find()) {
            // Mojang's 26.x release line runs on java-runtime-epsilon (Java 25).
            return Integer.parseInt(year.group(1)) >= 26 ? 25 : 21;
        }
        // Modern snapshots: the current toolchain is the safest bet.
        return 21;
    }

    public boolean valid() {
        return mcVersion != null && !mcVersion.isBlank()
                && modName != null && !modName.isBlank()
                && modId != null && !modId.isBlank()
                && packageName != null && !packageName.isBlank();
    }

    public ModTarget copy() {
        ModTarget t = new ModTarget();
        t.mcVersion = mcVersion;
        t.loader = loader;
        t.loaderVersion = loaderVersion;
        t.modName = modName;
        t.modId = modId;
        t.packageName = packageName;
        t.description = description;
        t.author = author;
        return t;
    }

    /** A safe default id derived from the display name. */
    public static String slug(String name) {
        if (name == null) {
            return "mymod";
        }
        String value = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (value.isBlank()) {
            return "mymod";
        }
        if (!Character.isLetter(value.charAt(0))) {
            value = "mod_" + value;
        }
        return value.length() > 48 ? value.substring(0, 48) : value;
    }

    public static String packageFor(String modId) {
        String id = slug(modId).replace('_', '.');
        return "com.example." + id;
    }

    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"minecraftVersion\": \"").append(escape(mcVersion)).append("\",\n");
        b.append("  \"loader\": \"").append(escape(loader)).append("\",\n");
        b.append("  \"loaderVersion\": \"").append(escape(loaderVersion)).append("\",\n");
        b.append("  \"modName\": \"").append(escape(modName)).append("\",\n");
        b.append("  \"modId\": \"").append(escape(modId)).append("\",\n");
        b.append("  \"packageName\": \"").append(escape(packageName)).append("\",\n");
        b.append("  \"description\": \"").append(escape(description)).append("\",\n");
        b.append("  \"author\": \"").append(escape(author)).append("\",\n");
        b.append("  \"javaMajor\": ").append(javaMajor()).append("\n");
        b.append("}\n");
        return b.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
