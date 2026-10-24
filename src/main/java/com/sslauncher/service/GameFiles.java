package com.sslauncher.service;

import com.sslauncher.core.AppPaths;
import com.sslauncher.model.Instance;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Content management for one instance's game directory: mods, resource packs,
 * shader packs and worlds. Files are enabled/disabled the same way most
 * launchers do it, by adding or removing a {@code .disabled} suffix.
 */
public final class GameFiles {
    private GameFiles() {
    }

    public enum Category {
        MODS("mods", "模组", "把 .jar 模组文件添加到这里，安装后可直接启用或禁用。",
                "添加模组", ".jar", false),
        RESOURCE_PACKS("resourcepacks", "材质包", "支持 .zip 或文件夹形式的材质包。",
                "添加材质包", ".zip", true),
        SHADER_PACKS("shaderpacks", "光影包", "支持 .zip 或文件夹形式的光影包，通常需要搭配光影加载器。",
                "添加光影包", ".zip", true),
        SAVES("saves", "存档", "每个世界是存档目录中的一个独立文件夹。",
                "导入存档", null, true);

        public final String folder;
        public final String label;
        public final String hint;
        public final String addLabel;
        public final String extension;
        public final boolean allowsFolder;

        Category(String folder, String label, String hint, String addLabel,
                 String extension, boolean allowsFolder) {
            this.folder = folder;
            this.label = label;
            this.hint = hint;
            this.addLabel = addLabel;
            this.extension = extension;
            this.allowsFolder = allowsFolder;
        }
    }

    public record Entry(Path path, String name, boolean directory, boolean enabled,
                        long size, long modified) {
    }

    public static Path directory(Instance instance, Category category) {
        return instance.gameDir().resolve(category.folder);
    }

    public static List<Entry> scan(Path directory, Category category) {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return entries;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                if (!matches(category, path)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                boolean isDirectory = Files.isDirectory(path);
                boolean disabled = fileName.toLowerCase(Locale.ROOT).endsWith(".disabled");
                String name = disabled
                        ? fileName.substring(0, fileName.length() - ".disabled".length())
                        : fileName;
                long size = isDirectory ? directorySize(path) : fileSize(path);
                long modified = lastModified(path);
                entries.add(new Entry(path, name, isDirectory, !disabled, size, modified));
            }
        } catch (IOException ignored) {
            // An unreadable folder simply shows as empty; opening it still works.
        }
        entries.sort(Comparator.comparing((Entry e) -> !e.enabled())
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return entries;
    }

    /** Cheap item count for category tabs; does not walk directory sizes. */
    public static int count(Path directory, Category category) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                if (matches(category, path)) {
                    count++;
                }
            }
        } catch (IOException ignored) {
            // Treat an unreadable folder as empty.
        }
        return count;
    }

    private static boolean matches(Category category, Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.startsWith(".") || fileName.equalsIgnoreCase("desktop.ini")) {
            return false;
        }
        boolean isDirectory = Files.isDirectory(path);
        boolean disabled = fileName.toLowerCase(Locale.ROOT).endsWith(".disabled");
        String name = disabled
                ? fileName.substring(0, fileName.length() - ".disabled".length())
                : fileName;
        if (category == Category.SAVES) {
            return isDirectory && !disabled && Files.exists(path.resolve("level.dat"));
        }
        if (category == Category.MODS) {
            return !isDirectory && name.toLowerCase(Locale.ROOT).endsWith(".jar");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return isDirectory || lower.endsWith(".zip");
    }

    /** Renames a file/folder to {@code *.disabled} or back. */
    public static void setEnabled(Entry entry, boolean enabled) throws IOException {
        if (entry.enabled() == enabled) {
            return;
        }
        Path source = entry.path();
        String fileName = source.getFileName().toString();
        Path target;
        if (enabled) {
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
                return;
            }
            target = source.resolveSibling(
                    fileName.substring(0, fileName.length() - ".disabled".length()));
        } else {
            target = source.resolveSibling(fileName + ".disabled");
        }
        if (Files.exists(target)) {
            throw new IOException("已存在同名文件：" + target.getFileName());
        }
        Files.move(source, target);
    }

    /**
     * Deletes an entry, preferring the system recycle bin. Returns {@code true}
     * when it was moved to the recycle bin, {@code false} when it was removed
     * permanently because no trash is available.
     */
    public static boolean delete(Entry entry) throws IOException {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                    && Desktop.getDesktop().moveToTrash(entry.path().toFile())) {
                return true;
            }
        } catch (Exception ignored) {
            // Fall through to a permanent delete.
        }
        deleteRecursively(entry.path());
        return false;
    }

    /**
     * Copies user-selected files or folders into the category directory.
     * Existing names are kept and a numbered suffix is added instead.
     */
    public static List<Path> add(Path directory, Category category, List<Path> sources)
            throws IOException {
        Files.createDirectories(directory);
        for (Path source : sources) {
            validateSource(category, source);
        }
        List<Path> added = new ArrayList<>();
        for (Path source : sources) {
            Path target = uniqueTarget(directory, source.getFileName().toString(),
                    Files.isDirectory(source));
            if (Files.isDirectory(source)) {
                copyDirectory(source, target);
            } else {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
            added.add(target);
        }
        return added;
    }

    public static void openDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(path.toFile());
            return;
        }
        String os = AppPaths.osName();
        if ("windows".equals(os)) {
            new ProcessBuilder("explorer.exe", path.toString()).start();
        } else if ("osx".equals(os)) {
            new ProcessBuilder("open", path.toString()).start();
        } else {
            new ProcessBuilder("xdg-open", path.toString()).start();
        }
    }

    /** Opens the containing folder and highlights the entry when possible. */
    public static void reveal(Path path) throws IOException {
        String os = AppPaths.osName();
        if ("windows".equals(os)) {
            new ProcessBuilder("explorer.exe", "/select," + path.toAbsolutePath()).start();
        } else if ("osx".equals(os)) {
            new ProcessBuilder("open", "-R", path.toString()).start();
        } else {
            openDirectory(path.toAbsolutePath().getParent());
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return Math.max(0, bytes) + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private static void validateSource(Category category, Path source) throws IOException {
        if (source == null || !Files.exists(source)) {
            throw new IOException("文件不存在：" + source);
        }
        boolean directory = Files.isDirectory(source);
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        switch (category) {
            case MODS -> {
                if (directory || !name.endsWith(".jar")) {
                    throw new IOException("模组必须是 .jar 文件：" + source.getFileName());
                }
            }
            case SAVES -> {
                if (!directory || !Files.exists(source.resolve("level.dat"))) {
                    throw new IOException("不是有效的存档文件夹（缺少 level.dat）："
                            + source.getFileName());
                }
            }
            default -> {
                if (!directory && !name.endsWith(".zip")) {
                    throw new IOException("请选择 .zip 文件或文件夹：" + source.getFileName());
                }
            }
        }
    }

    private static Path uniqueTarget(Path directory, String fileName, boolean isDirectory) {
        Path target = directory.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        String stem = fileName;
        String extension = "";
        if (!isDirectory) {
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                stem = fileName.substring(0, dot);
                extension = fileName.substring(dot);
            }
        }
        int n = 2;
        do {
            target = directory.resolve(stem + " (" + n++ + ")" + extension);
        } while (Files.exists(target));
        return target;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long directorySize(Path path) {
        try (Stream<Path> stream = Files.walk(path)) {
            long total = 0;
            for (Path file : stream.toList()) {
                if (Files.isRegularFile(file)) {
                    total += fileSize(file);
                }
            }
            return total;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
