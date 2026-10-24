package com.sslauncher.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * URL rewriting for the Chinese Minecraft community mirrors (QLU BMCLAPI,
 * BMCLAPI and MC 光速镜像). Maven artifacts go through the QLU mirror first
 * because it benchmarks several times faster than the alternatives; the other
 * mirrors and finally the official URLs remain as fallbacks.
 */
public final class Mirror {
    public static final String BMCLAPI = "https://bmclapi2.bangbang93.com";
    public static final String BMCLAPI_MAVEN = BMCLAPI + "/maven/";
    /**
     * 齐鲁工业大学 BMCLAPI 镜像. It serves Maven paths directly below
     * {@code /bmclapi/} (no {@code /maven/} segment) and reads at roughly
     * 5-10 MB/s for loader installers. It does not proxy Mojang endpoints.
     */
    public static final String QLU = "https://mirrors4.qlu.edu.cn/bmclapi";
    public static final String QLU_MAVEN = QLU + "/";
    /**
     * MC 光速镜像. Its mirror host sends the full TLS chain and therefore works
     * with the JDK trust store; the bsmirror host is kept as an alternate in
     * case it appears in mirrored metadata.
     */
    public static final String HKMC = "https://mirror.hkmc.online";
    public static final String HKMC_MAVEN = HKMC + "/maven/";
    /** Official NeoForge CDN; much faster than maven.neoforged.net from China. */
    public static final String NEOFORGE_CDN = "https://neoforged.forgecdn.net";
    /**
     * MCIM community mirror for Modrinth and CurseForge content files. It only
     * caches mods, so it is tried after the official CDN, never instead of it.
     */
    public static final String MCIM = "https://mod.mcimirror.top";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String HKMC_ALT = "https://bsmirror.hkmc.online";

    private static final String[] MAVEN_PREFIXES = {
            "https://libraries.minecraft.net/",
            "https://maven.minecraftforge.net/",
            "https://files.minecraftforge.net/maven/",
            NEOFORGE_MAVEN,
            "https://maven.neoforged.net/repository/",
            "https://maven.fabricmc.net/",
            "https://maven.quiltmc.org/repository/release/",
            "https://maven.quiltmc.org/",
    };

    /**
     * QLU only proxies loader Maven repositories. It answers 404 for Mojang's
     * {@code libraries.minecraft.net}, so those keep using the other mirrors.
     */
    private static final String[] QLU_PREFIXES = {
            "https://maven.minecraftforge.net/",
            "https://files.minecraftforge.net/maven/",
            NEOFORGE_MAVEN,
            "https://maven.neoforged.net/repository/",
            "https://maven.fabricmc.net/",
            "https://maven.quiltmc.org/repository/release/",
            "https://maven.quiltmc.org/",
    };

    private Mirror() {
    }

    public static void refresh() {
        // source is read on demand from Config; this method exists so callers
        // that change the setting can flush anything cached (currently nothing).
    }

    public enum Source {
        /** Domestic mirrors first (MC 光速, then BMCLAPI), official as fallback. */
        AUTO, HKMC, BMCLAPI, OFFICIAL;

        public static Source from(String value) {
            if (value == null) {
                return AUTO;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "hkmc", "light", "speed" -> HKMC;
                case "mirror", "bmclapi" -> BMCLAPI;
                case "official", "mojang" -> OFFICIAL;
                default -> AUTO;
            };
        }
    }

    public static Source source() {
        return Source.from(Config.get().downloadSource);
    }

    /** Candidate URLs in the order they should be tried. */
    public static List<String> candidates(String original) {
        if (original == null || original.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        if (source() != Source.OFFICIAL) {
            // QLU only carries loader Maven paths; Mojang endpoints and
            // libraries.minecraft.net return 404 there.
            String path = qluPath(original);
            if (path != null) {
                out.add(QLU_MAVEN + path);
                if (original.startsWith(NEOFORGE_MAVEN)
                        || original.startsWith("https://maven.neoforged.net/repository/")) {
                    out.add(NEOFORGE_CDN + "/releases/" + path);
                }
            }
        }
        for (String base : mirrorBases()) {
            String mirrored = rewriteFor(original, base);
            if (mirrored != null) {
                // Keep the original in its natural position when it already
                // points at a mirror host (mirrored manifests do this).
                out.add(mirrored);
            }
        }
        out.add(original);
        return new ArrayList<>(out);
    }

    /**
     * Candidate URLs with the official origin first, mirrors only as fallback.
     * Use this for metadata that decides what will be executed (version JSON,
     * loader profiles): the trusted description must be fetched from the
     * official HTTPS origin, while mirrors may still serve the bytes if the
     * official host is unreachable.
     */
    public static List<String> officialFirst(String original) {
        if (original == null || original.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(original);
        out.addAll(candidates(original));
        return new ArrayList<>(out);
    }

    /**
     * Path of a Maven artifact URL (no leading slash), or {@code null} if the
     * URL does not belong to a supported Maven repository.
     */
    static String mavenPath(String url) {
        return pathBelow(url, MAVEN_PREFIXES);
    }

    /** Maven path for repositories mirrored by QLU, or {@code null}. */
    static String qluPath(String url) {
        return pathBelow(url, QLU_PREFIXES);
    }

    private static String pathBelow(String url, String[] prefixes) {
        if (url == null) {
            return null;
        }
        for (String prefix : prefixes) {
            if (url.startsWith(prefix)) {
                return url.substring(prefix.length());
            }
        }
        return null;
    }

    /** Mirror bases to try, in order; empty when the official source is selected. */
    private static List<String> mirrorBases() {
        return switch (source()) {
            case OFFICIAL -> List.of();
            case HKMC -> List.of(HKMC);
            case BMCLAPI -> List.of(BMCLAPI);
            default -> List.of(HKMC, BMCLAPI);
        };
    }

    /** Rewrites a Mojang/Maven URL for the selected mirror; used where no fallback list fits. */
    public static String rewrite(String url) {
        List<String> bases = mirrorBases();
        return bases.isEmpty() ? url : rewriteFor(url, bases.get(0));
    }

    private static String rewriteFor(String url, String base) {
        if (url == null) {
            return null;
        }
        if (url.startsWith(HKMC_ALT + "/")) {
            return base + url.substring(HKMC_ALT.length());
        }
        if (url.startsWith(HKMC + "/")) {
            return base + url.substring(HKMC.length());
        }
        if (url.startsWith("https://launchermeta.mojang.com/")) {
            return base + url.substring("https://launchermeta.mojang.com".length());
        }
        if (url.startsWith("https://piston-meta.mojang.com/")) {
            return base + url.substring("https://piston-meta.mojang.com".length());
        }
        if (url.startsWith("https://piston-data.mojang.com/")) {
            return base + url.substring("https://piston-data.mojang.com".length());
        }
        if (url.startsWith("https://resources.download.minecraft.net/")) {
            return base + "/assets/" + url.substring("https://resources.download.minecraft.net/".length());
        }
        if (url.startsWith("https://libraries.minecraft.net/")) {
            return base + "/maven/" + url.substring("https://libraries.minecraft.net/".length());
        }
        if (url.startsWith("https://maven.minecraftforge.net/")) {
            return base + "/maven/" + url.substring("https://maven.minecraftforge.net/".length());
        }
        if (url.startsWith("https://files.minecraftforge.net/maven/")) {
            return base + "/maven/" + url.substring("https://files.minecraftforge.net/maven/".length());
        }
        if (url.startsWith("https://maven.neoforged.net/releases/")) {
            return base + "/maven/" + url.substring("https://maven.neoforged.net/releases/".length());
        }
        if (url.startsWith("https://maven.neoforged.net/repository/")) {
            return base + "/maven/" + url.substring("https://maven.neoforged.net/repository/".length());
        }
        if (url.startsWith("https://maven.fabricmc.net/")) {
            return base + "/maven/" + url.substring("https://maven.fabricmc.net/".length());
        }
        if (url.startsWith("https://maven.quiltmc.org/repository/release/")) {
            return base + "/maven/" + url.substring("https://maven.quiltmc.org/repository/release/".length());
        }
        if (url.startsWith("https://maven.quiltmc.org/")) {
            return base + "/maven/" + url.substring("https://maven.quiltmc.org/".length());
        }
        return url;
    }

    /** URL of a Maven artifact path (e.g. {@code com/mojang/logging/1.1.1/...}). */
    public static String maven(String path) {
        return switch (source()) {
            case OFFICIAL -> "https://libraries.minecraft.net/" + path;
            case BMCLAPI -> BMCLAPI_MAVEN + path;
            case HKMC -> HKMC_MAVEN + path;
            default -> QLU_MAVEN + path;
        };
    }

    /**
     * Candidate URLs of an installer or library artifact, fastest first: QLU,
     * the official NeoForge CDN (NeoForge artifacts only), MC 光速, BMCLAPI and
     * finally the official repository.
     */
    public static List<String> mavenCandidates(String path, String officialUrl) {
        Set<String> out = new LinkedHashSet<>();
        Source source = source();
        if (source != Source.OFFICIAL) {
            out.add(QLU_MAVEN + path);
            if (officialUrl.startsWith(NEOFORGE_MAVEN)) {
                out.add(NEOFORGE_CDN + "/releases/" + path);
            }
            if (source == Source.AUTO || source == Source.HKMC) {
                out.add(HKMC_MAVEN + path);
            }
            if (source == Source.AUTO || source == Source.BMCLAPI) {
                out.add(BMCLAPI_MAVEN + path);
            }
        }
        out.add(officialUrl);
        return new ArrayList<>(out);
    }

    /**
     * Forge Maven metadata candidates. QLU is by far the fastest but its copy
     * is truncated (it misses e.g. 1.20.1), so MC 光速 backs it up with the
     * complete list and the official repository is the last resort. The caller
     * skips to the next source when the requested game version is absent.
     */
    public static List<String> forgeMetadataCandidates(String path) {
        if (source() == Source.OFFICIAL) {
            return List.of("https://maven.minecraftforge.net/" + path);
        }
        return List.of(QLU_MAVEN + path, HKMC_MAVEN + path,
                "https://maven.minecraftforge.net/" + path);
    }

    /**
     * NeoForge Maven metadata candidates. QLU is much faster than both the
     * official CDN and maven.neoforged.net; ForgeCDN backs it up because the
     * official NeoForge host frequently times out from China.
     */
    public static List<String> neoForgeMetadataCandidates(String path) {
        Set<String> out = new LinkedHashSet<>();
        if (source() != Source.OFFICIAL) {
            out.add(QLU_MAVEN + path);
        }
        out.add(NEOFORGE_CDN + "/releases/" + path);
        out.add(NEOFORGE_MAVEN + path);
        return new ArrayList<>(out);
    }

    /**
     * Candidate URLs of the Fabric meta API; {@code path} starts with a slash.
     * MC 光速's proxy for this endpoint frequently answers HTTP 502, so loader
     * lists keep using BMCLAPI (or the official API) instead.
     */
    public static List<String> fabricMetaCandidates(String path, String officialUrl) {
        Set<String> out = new LinkedHashSet<>();
        if (source() != Source.OFFICIAL) {
            out.add(BMCLAPI + "/fabric-meta" + path);
        }
        out.add(officialUrl);
        return new ArrayList<>(out);
    }

    /**
     * Candidate URLs for a Modrinth or CurseForge content file: the official
     * CDN first, then MCIM as a fallback. The caller still verifies SHA-1, so
     * a mirror cannot substitute different content.
     */
    public static List<String> resourceCandidates(String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(url);
        if (source() != Source.OFFICIAL) {
            String mirrored = resourceMirror(url);
            if (mirrored != null) {
                out.add(mirrored);
            }
        }
        return new ArrayList<>(out);
    }

    private static String resourceMirror(String url) {
        if (url.startsWith("https://cdn.modrinth.com/")) {
            return MCIM + url.substring("https://cdn.modrinth.com".length());
        }
        if (url.startsWith("https://mediafilez.forgecdn.net/")) {
            return MCIM + url.substring("https://mediafilez.forgecdn.net".length());
        }
        if (url.startsWith("https://edge.forgecdn.net/")) {
            return MCIM + url.substring("https://edge.forgecdn.net".length());
        }
        return null;
    }

    public static boolean isMirror(String url) {
        return url != null
                && (url.startsWith(BMCLAPI) || url.startsWith(HKMC)
                || url.startsWith(HKMC_ALT) || url.startsWith(QLU));
    }
}
