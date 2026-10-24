package com.sslauncher.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanWorldModServiceTest {
    @TempDir
    Path temp;

    @Test
    void detectsFabricMetadataRegardlessOfFileName() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        writeJar(mods.resolve("renamed.jar"), Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"mcwifipnp\",\"version\":\"1.9.0\"}"));
        assertTrue(LanWorldModService.isInstalled(mods));
    }

    @Test
    void detectsForgeMetadata() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        writeJar(mods.resolve("something.jar"), Map.of("META-INF/mods.toml",
                "modLoader=\"javafml\"\n[[mods]]\nmodId=\"mcwifipnp\"\n"));
        assertTrue(LanWorldModService.isInstalled(mods));
    }

    @Test
    void fallsBackToFileName() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        writeJar(mods.resolve("mcwifipnp-1.7.6-1.20.1-fabric.jar"), Map.of());
        assertTrue(LanWorldModService.isInstalled(mods));
    }

    @Test
    void ignoresDisabledAndUnrelatedJars() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        writeJar(mods.resolve("mcwifipnp-1.7.6.jar.disabled"), Map.of());
        writeJar(mods.resolve("sodium-0.5.11.jar"), Map.of("fabric.mod.json",
                "{\"id\":\"sodium\"}"));
        assertFalse(LanWorldModService.isInstalled(mods));
    }

    @Test
    void missingModsFolderIsNotInstalled() {
        assertFalse(LanWorldModService.isInstalled(temp.resolve("does-not-exist")));
    }

    private static void writeJar(Path target, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
