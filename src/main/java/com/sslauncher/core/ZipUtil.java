package com.sslauncher.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class ZipUtil {
    private ZipUtil() {
    }

    /** Extracts native libraries, rejecting entries that escape the target directory. */
    public static void extractNatives(Path jar, Path target, List<String> excludes) throws IOException {
        Files.createDirectories(target);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                if (excluded(name, excludes)) {
                    continue;
                }
                Path dest = target.resolve(name).normalize();
                if (!dest.startsWith(target)) {
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean excluded(String name, List<String> excludes) {
        if (excludes == null) {
            return false;
        }
        for (String prefix : excludes) {
            if (prefix != null && !prefix.isBlank() && name.startsWith(prefix.replace('\\', '/'))) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when the archive contains any of the named root entries. */
    public static boolean containsEntry(Path archive, String... entryNames) throws IOException {
        if (archive == null || entryNames == null || entryNames.length == 0) {
            return false;
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (String entryName : entryNames) {
                if (entryName != null && !entryName.isBlank()
                        && zip.getEntry(entryName) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true when any of the named entries exists at the archive root or
     * inside a folder. Modpacks exported by some launchers keep their manifest
     * one level down (for example {@code MyPack/modrinth.index.json}).
     */
    public static boolean containsEntryAnywhere(Path archive, String... entryNames) throws IOException {
        if (archive == null || entryNames == null || entryNames.length == 0) {
            return false;
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                for (String entryName : entryNames) {
                    if (entryName == null || entryName.isBlank()) {
                        continue;
                    }
                    if (name.equals(entryName) || name.endsWith("/" + entryName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Reads a single entry from a jar, or returns null when absent. */
    public static byte[] readEntry(Path jar, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    /** Extracts one entry to a file (used by the Forge installer preflight). */
    public static boolean extractEntry(Path jar, String entryName, Path dest) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return false;
            }
            Files.createDirectories(dest.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    /**
     * Extracts one subtree of an archive (for example {@code overrides/}) into
     * a target directory, stripping the prefix. Every entry is checked so a
     * crafted {@code ..} path cannot escape the target.
     */
    public static void extractSubtree(Path zipFile, String prefix, Path target) throws IOException {
        if (target == null) {
            throw new IOException("没有指定解压目录");
        }
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        String normalizedPrefix = prefix == null ? "" : prefix.replace('\\', '/');
        while (normalizedPrefix.startsWith("/")) {
            normalizedPrefix = normalizedPrefix.substring(1);
        }
        if (!normalizedPrefix.isEmpty() && !normalizedPrefix.endsWith("/")) {
            normalizedPrefix += "/";
        }
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (!normalizedPrefix.isEmpty()) {
                    if (!name.startsWith(normalizedPrefix)) {
                        continue;
                    }
                    name = name.substring(normalizedPrefix.length());
                }
                if (name.isBlank()) {
                    continue;
                }
                Path dest = root.resolve(name).normalize();
                if (!dest.startsWith(root)) {
                    // Zip-slip attempt: ignore the entry instead of writing
                    // outside the instance directory.
                    continue;
                }
                Path parent = dest.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Ensures every directory in the zip exists (not used for natives). */
    public static void extract(Path zipFile, Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path dest = target.resolve(entry.getName()).normalize();
                if (!dest.startsWith(target)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
