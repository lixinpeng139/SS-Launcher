package com.sslauncher.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorTest {
    private static final String VERSION_JSON =
            "https://piston-meta.mojang.com/v1/packages/abc/1.21.8.json";

    private String previousSource;

    @BeforeEach
    void rememberSource() {
        previousSource = Config.get().downloadSource;
    }

    @AfterEach
    void restoreSource() {
        Config.get().downloadSource = previousSource;
    }

    @Test
    void officialSourceOnlyUsesMojang() {
        Config.get().downloadSource = "official";

        assertEquals(List.of(VERSION_JSON), Mirror.candidates(VERSION_JSON));
        assertEquals(List.of("https://libraries.minecraft.net/a/b.jar"),
                Mirror.mavenCandidates("a/b.jar", "https://libraries.minecraft.net/a/b.jar"));
    }

    @Test
    void autoTriesDomesticMirrorsBeforeOfficial() {
        Config.get().downloadSource = "auto";

        List<String> candidates = Mirror.candidates(VERSION_JSON);

        assertEquals(List.of(
                "https://mirror.hkmc.online/v1/packages/abc/1.21.8.json",
                "https://bmclapi2.bangbang93.com/v1/packages/abc/1.21.8.json",
                VERSION_JSON), candidates);
    }

    @Test
    void hkmcSourceUsesLightSpeedMirrorWithOfficialFallback() {
        Config.get().downloadSource = "hkmc";

        assertEquals(List.of(
                "https://mirror.hkmc.online/v1/packages/abc/1.21.8.json",
                VERSION_JSON), Mirror.candidates(VERSION_JSON));
    }

    @Test
    void bmclapiAliasStillWorks() {
        Config.get().downloadSource = "bmclapi";

        assertEquals(List.of(
                "https://bmclapi2.bangbang93.com/v1/packages/abc/1.21.8.json",
                VERSION_JSON), Mirror.candidates(VERSION_JSON));
    }

    @Test
    void rewritesAssetsAndMavenRepositories() {
        Config.get().downloadSource = "hkmc";

        assertEquals(List.of(
                "https://mirror.hkmc.online/assets/ab/abcdef",
                "https://resources.download.minecraft.net/ab/abcdef"),
                Mirror.candidates("https://resources.download.minecraft.net/ab/abcdef"));
        assertEquals(
                "https://mirror.hkmc.online/maven/net/fabricmc/fabric-loader/0.16.5/fabric-loader-0.16.5.jar",
                Mirror.maven("net/fabricmc/fabric-loader/0.16.5/fabric-loader-0.16.5.jar"));
    }

    @Test
    void manifestLinksFromAlternateHkmcHostAreRewritten() {
        Config.get().downloadSource = "hkmc";
        String alternate = "https://bsmirror.hkmc.online/v1/packages/abc/1.21.8.json";

        List<String> candidates = Mirror.candidates(alternate);

        assertEquals("https://mirror.hkmc.online/v1/packages/abc/1.21.8.json", candidates.get(0));
        assertTrue(candidates.contains(alternate));
    }

    @Test
    void mavenCandidatesFollowSelectedSource() {
        Config.get().downloadSource = "auto";
        assertEquals(List.of(
                "https://mirrors4.qlu.edu.cn/bmclapi/a/b.jar",
                "https://mirror.hkmc.online/maven/a/b.jar",
                "https://bmclapi2.bangbang93.com/maven/a/b.jar",
                "https://example.com/a/b.jar"),
                Mirror.mavenCandidates("a/b.jar", "https://example.com/a/b.jar"));

        Config.get().downloadSource = "mirror";
        assertEquals(List.of(
                "https://mirrors4.qlu.edu.cn/bmclapi/a/b.jar",
                "https://bmclapi2.bangbang93.com/maven/a/b.jar",
                "https://example.com/a/b.jar"),
                Mirror.mavenCandidates("a/b.jar", "https://example.com/a/b.jar"));

        Config.get().downloadSource = "official";
        assertEquals(List.of("https://example.com/a/b.jar"),
                Mirror.mavenCandidates("a/b.jar", "https://example.com/a/b.jar"));
    }

    @Test
    void autoPutsQluFirstForMavenLibraries() {
        Config.get().downloadSource = "auto";
        String forge = "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.0/"
                + "forge-1.20.1-47.4.0-installer.jar";

        assertEquals(List.of(
                "https://mirrors4.qlu.edu.cn/bmclapi/net/minecraftforge/forge/1.20.1-47.4.0/"
                        + "forge-1.20.1-47.4.0-installer.jar",
                "https://mirror.hkmc.online/maven/net/minecraftforge/forge/1.20.1-47.4.0/"
                        + "forge-1.20.1-47.4.0-installer.jar",
                "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/1.20.1-47.4.0/"
                        + "forge-1.20.1-47.4.0-installer.jar",
                forge), Mirror.candidates(forge));
    }

    @Test
    void vanillaLibrariesSkipQluBecauseItReturns404() {
        Config.get().downloadSource = "auto";
        String logging = "https://libraries.minecraft.net/com/mojang/logging/1.2.7/logging-1.2.7.jar";

        assertEquals(List.of(
                "https://mirror.hkmc.online/maven/com/mojang/logging/1.2.7/logging-1.2.7.jar",
                "https://bmclapi2.bangbang93.com/maven/com/mojang/logging/1.2.7/logging-1.2.7.jar",
                logging), Mirror.candidates(logging));
    }

    @Test
    void neoForgeUsesOfficialCdnFallback() {
        Config.get().downloadSource = "auto";
        String path = "net/neoforged/neoforge/21.1.264/neoforge-21.1.264-installer.jar";
        String official = "https://maven.neoforged.net/releases/" + path;

        assertEquals(List.of(
                "https://mirrors4.qlu.edu.cn/bmclapi/" + path,
                "https://neoforged.forgecdn.net/releases/" + path,
                "https://mirror.hkmc.online/maven/" + path,
                "https://bmclapi2.bangbang93.com/maven/" + path,
                official), Mirror.mavenCandidates(path, official));
    }

    @Test
    void loaderMetadataSourcesAreOrderedFastestFirst() {
        Config.get().downloadSource = "auto";
        assertTrue(Mirror.forgeMetadataCandidates("net/minecraftforge/forge/maven-metadata.xml")
                .get(0).startsWith("https://mirrors4.qlu.edu.cn/"));
        assertTrue(Mirror.neoForgeMetadataCandidates("net/neoforged/neoforge/maven-metadata.xml")
                .get(0).startsWith("https://mirrors4.qlu.edu.cn/"));
    }

    @Test
    void sourceNamesAreAliased() {
        assertEquals(Mirror.Source.AUTO, Mirror.Source.from(null));
        assertEquals(Mirror.Source.HKMC, Mirror.Source.from("HKMC"));
        assertEquals(Mirror.Source.BMCLAPI, Mirror.Source.from("mirror"));
        assertEquals(Mirror.Source.OFFICIAL, Mirror.Source.from("mojang"));
    }
}
