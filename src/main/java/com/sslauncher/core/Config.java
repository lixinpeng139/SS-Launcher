package com.sslauncher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** User settings, persisted to config.json. */
public final class Config {
    private static Config instance = new Config();

    // account / third-party (authlib-injector) login
    public String skinSiteUrl = "";
    public String skinSiteName = "第三方皮肤站";

    // launcher behaviour
    public String selectedInstanceId = "";
    public int memoryMb = 4096;
    public int minMemoryMb = 512;
    public boolean autoMemory = true;
    public String javaPath = "";
    public boolean closeAfterLaunch = false;
    public boolean showGameConsole = true;

    // downloads: auto prefers domestic mirrors (MC 光速, BMCLAPI), then official.
    // Values: auto | hkmc | mirror (BMCLAPI) | official
    public String downloadSource = "auto";
    public int downloadThreads = 16;

    // P2P multiplayer: the bootstrap node only matches rooms and exchanges
    // addresses. Game traffic always goes directly between the two players.
    public String multiplayerName = "";

    // The bootstrap address is deliberately not configurable and never stored
    // here; it is defined in multiplayer/BootstrapServer.
    public int multiplayerPort = 4001;
    public boolean multiplayerAutoMapPort = true;

    // P2P voice chat: mesh streams between room members, no server relay.
    // Volumes are percentages (0–200) so they can also boost quiet headsets.
    public boolean voiceChatEnabled = false;
    public int voiceInputVolume = 100;
    public int voiceOutputVolume = 100;
    public boolean voiceMicMuted = false;

    // advanced
    public String extraJvmArgs = "";
    public String extraGameArgs = "";
    public boolean firstRun = true;

    // appearance: empty background = the bundled grass-block picture. The
    // colour scheme is always derived from whichever picture is shown.
    public String backgroundImage = "";
    public int backgroundBlur = 18;   // 0–100 %
    public int backgroundDim = 35;    // 0–80 %

    // window
    public double windowWidth = 1200;
    public double windowHeight = 780;
    public double windowX = -1;
    public double windowY = -1;

    private Config() {
    }

    public static Config get() {
        return instance;
    }

    public static void load() {
        if (java.nio.file.Files.exists(AppPaths.CONFIG_FILE)) {
            try {
                Config loaded = Json.read(AppPaths.CONFIG_FILE, Config.class);
                instance = loaded;
            } catch (IOException e) {
                Log.warn("配置文件读取失败，使用默认配置: " + e.getMessage());
                instance = new Config();
            }
        } else {
            instance = new Config();
            save();
        }
        sanitize(instance);
        scrubLegacySecrets();
        Mirror.refresh();
    }

    /**
     * Older builds persisted the rendezvous address and the CurseForge key as
     * plain text. Rewriting the file once drops those fields, so neither value
     * keeps sitting on disk.
     */
    private static void scrubLegacySecrets() {
        try {
            if (!Files.exists(AppPaths.CONFIG_FILE)) {
                return;
            }
            String text = Files.readString(AppPaths.CONFIG_FILE, StandardCharsets.UTF_8);
            if (text.contains("multiplayerBootstrap") || text.contains("curseForgeApiKey")) {
                save();
                Log.info("已从配置文件中移除旧的明文密钥");
            }
        } catch (IOException e) {
            Log.warn("检查配置文件失败: " + e.getMessage());
        }
    }

    private static void sanitize(Config c) {
        if (c.skinSiteUrl == null) {
            c.skinSiteUrl = "";
        }
        c.skinSiteUrl = c.skinSiteUrl.trim().replaceAll("/+$", "");
        if (c.skinSiteName == null || c.skinSiteName.isBlank()) {
            c.skinSiteName = "第三方皮肤站";
        }
        if (c.downloadThreads < 1) {
            c.downloadThreads = 1;
        }
        if (c.downloadThreads > 64) {
            c.downloadThreads = 64;
        }
        if (c.multiplayerName == null) {
            c.multiplayerName = "";
        }
        c.multiplayerName = c.multiplayerName.trim();
        if (c.multiplayerName.length() > 24) {
            c.multiplayerName = c.multiplayerName.substring(0, 24);
        }
        if (c.multiplayerPort < 1024 || c.multiplayerPort > 65535) {
            c.multiplayerPort = 4001;
        }
        c.voiceInputVolume = Math.max(0, Math.min(200, c.voiceInputVolume));
        c.voiceOutputVolume = Math.max(0, Math.min(200, c.voiceOutputVolume));
        if (c.backgroundImage == null) {
            c.backgroundImage = "";
        }
        c.backgroundBlur = Math.max(0, Math.min(100, c.backgroundBlur));
        c.backgroundDim = Math.max(0, Math.min(80, c.backgroundDim));
        if (c.memoryMb < 512) {
            c.memoryMb = 512;
        }
        if (c.memoryMb > 65536) {
            c.memoryMb = 65536;
        }
        if (c.windowWidth < 1000) {
            c.windowWidth = 1200;
        }
        if (c.windowHeight < 680) {
            c.windowHeight = 780;
        }
    }

    public static void save() {
        try {
            Json.write(AppPaths.CONFIG_FILE, instance);
        } catch (IOException e) {
            Log.error("保存配置失败", e);
        }
    }

    /** Memory the game should use, honouring the global auto-allocation switch. */
    public int effectiveMemoryMb(boolean modded) {
        if (!autoMemory) {
            return Math.max(512, Math.min(65536, memoryMb));
        }
        return autoMemoryMb(modded);
    }

    /** Suggests a heap size for this machine; modded packs get one extra gigabyte. */
    public static int autoMemoryMb(boolean modded) {
        long total = totalSystemMemoryMb();
        int suggested = 4096;
        if (total > 0) {
            suggested = (int) (Math.round(total / 2.0 / 512.0) * 512);
        }
        suggested = Math.max(2048, Math.min(8192, suggested));
        if (modded) {
            suggested = Math.min(12288, suggested + 1024);
        }
        return Math.max(512, Math.min(65536, suggested));
    }

    private static long totalSystemMemoryMb() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize() / (1024L * 1024L);
            }
        } catch (Throwable ignored) {
            // Fall back to the configured value below.
        }
        return 0;
    }

    public static String skinHome() {
        return skinHomeOf(get().skinSiteUrl);
    }

    /** Turns an Yggdrasil endpoint such as .../api/yggdrasil into the site home. */
    public static String skinHomeOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String value = url.trim().replaceAll("/+$", "");
        String suffix = "/api/yggdrasil";
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        int idx = value.indexOf("/api/");
        return idx > 0 ? value.substring(0, idx) : value;
    }
}
