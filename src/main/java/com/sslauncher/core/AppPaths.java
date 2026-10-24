package com.sslauncher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * All launcher data lives under one root, by default
 * {@code %APPDATA%\SSLauncher} on Windows.
 *
 * <pre>
 *   config.json
 *   accounts.json
 *   instances.json
 *   libraries/          shared Minecraft libraries
 *   assets/             shared asset objects + indexes
 *   versions/           shared version json/jars (vanilla, Fabric, Forge ...)
 *   instances/&lt;id&gt;/     one game directory per instance
 *   runtimes/           Java runtimes downloaded from Mojang
 *   cache/              authlib-injector.jar, metadata caches, project icons
 *   skins/              per-account skin cache and launcher-only avatars
 *   logs/
 * </pre>
 */
public final class AppPaths {
    public static Path ROOT;
    public static Path LIBRARIES;
    public static Path ASSETS;
    public static Path ASSET_OBJECTS;
    public static Path ASSET_INDEXES;
    public static Path ASSET_LOGS;
    public static Path ASSET_VIRTUAL;
    public static Path VERSIONS;
    public static Path INSTANCES;
    public static Path RUNTIMES;
    public static Path CACHE;
    public static Path SKINS;
    public static Path LOGS;
    public static Path CONFIG_FILE;
    public static Path ACCOUNTS_FILE;
    public static Path INSTANCES_FILE;
    public static Path LAUNCHER_LOG;

    private AppPaths() {
    }

    public static synchronized void init() {
        if (ROOT != null) {
            return;
        }
        String override = System.getProperty("ss.home");
        if (override != null && !override.isBlank()) {
            ROOT = Path.of(override);
        } else {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                ROOT = Path.of(appData, "SSLauncher");
            } else {
                ROOT = Path.of(System.getProperty("user.home"), ".ss-launcher");
            }
        }
        LIBRARIES = ROOT.resolve("libraries");
        ASSETS = ROOT.resolve("assets");
        ASSET_OBJECTS = ASSETS.resolve("objects");
        ASSET_INDEXES = ASSETS.resolve("indexes");
        ASSET_LOGS = ASSETS.resolve("log_configs");
        ASSET_VIRTUAL = ASSETS.resolve("virtual");
        VERSIONS = ROOT.resolve("versions");
        INSTANCES = ROOT.resolve("instances");
        RUNTIMES = ROOT.resolve("runtimes");
        CACHE = ROOT.resolve("cache");
        SKINS = ROOT.resolve("skins");
        LOGS = ROOT.resolve("logs");
        CONFIG_FILE = ROOT.resolve("config.json");
        ACCOUNTS_FILE = ROOT.resolve("accounts.json");
        INSTANCES_FILE = ROOT.resolve("instances.json");
        LAUNCHER_LOG = LOGS.resolve("launcher.log");

        for (Path p : new Path[]{ROOT, LIBRARIES, ASSETS, ASSET_OBJECTS, ASSET_INDEXES, ASSET_LOGS,
                ASSET_VIRTUAL, VERSIONS, INSTANCES, RUNTIMES, CACHE, SKINS, LOGS}) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建目录: " + p, e);
            }
        }
    }

    public static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "osx";
        }
        return "linux";
    }

    public static String osArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "x86_64".equals(arch) || "amd64".equals(arch) ? "x64" : arch;
    }

    public static String classpathSeparator() {
        return System.getProperty("path.separator");
    }

    public static void atomicWrite(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Atomically writes a file whose contents are private to the current user.
     * The temporary file is restricted before the secret is written, and the
     * final file is restricted again after the rename so the permissions also
     * hold on filesystems that reset them during a move.
     */
    public static void atomicWritePrivate(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".ss-private-", ".tmp");
        try {
            FileSecurity.restrict(tmp);
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            FileSecurity.restrict(target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
