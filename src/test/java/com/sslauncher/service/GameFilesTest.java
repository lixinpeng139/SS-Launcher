package com.sslauncher.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameFilesTest {
    @TempDir
    Path temp;

    @Test
    void scansModsAndIgnoresOtherFiles() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Files.writeString(mods.resolve("sodium.jar"), "mod");
        Files.writeString(mods.resolve("old.jar.disabled"), "mod");
        Files.writeString(mods.resolve("readme.txt"), "not a mod");
        Files.createDirectories(mods.resolve("folder.jar"));

        List<GameFiles.Entry> entries = GameFiles.scan(mods, GameFiles.Category.MODS);
        assertEquals(2, entries.size());
        assertEquals("sodium.jar", entries.get(0).name());
        assertTrue(entries.get(0).enabled());
        assertEquals("old.jar", entries.get(1).name());
        assertFalse(entries.get(1).enabled());
    }

    @Test
    void togglesModsBothWays() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Path jar = Files.writeString(mods.resolve("create.jar"), "mod");
        GameFiles.Entry entry = GameFiles.scan(mods, GameFiles.Category.MODS).get(0);

        GameFiles.setEnabled(entry, false);
        assertFalse(Files.exists(jar));
        assertTrue(Files.exists(mods.resolve("create.jar.disabled")));

        GameFiles.Entry disabled = GameFiles.scan(mods, GameFiles.Category.MODS).get(0);
        assertFalse(disabled.enabled());
        GameFiles.setEnabled(disabled, true);
        assertTrue(Files.exists(jar));
        assertFalse(Files.exists(mods.resolve("create.jar.disabled")));
    }

    @Test
    void scansPacksIncludingFolders() throws IOException {
        Path packs = Files.createDirectories(temp.resolve("resourcepacks"));
        Files.writeString(packs.resolve("fresh.zip"), "pack");
        Files.writeString(packs.resolve("old.zip.disabled"), "pack");
        Files.createDirectories(packs.resolve("folder-pack"));
        Files.writeString(packs.resolve("notes.txt"), "ignored");

        List<GameFiles.Entry> entries = GameFiles.scan(packs, GameFiles.Category.RESOURCE_PACKS);
        assertEquals(3, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("folder-pack") && e.directory()));
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("old.zip") && !e.enabled()));
    }

    @Test
    void onlyListsRealWorlds() throws IOException {
        Path saves = Files.createDirectories(temp.resolve("saves"));
        Path world = Files.createDirectories(saves.resolve("survival"));
        Files.writeString(world.resolve("level.dat"), "nbt");
        Files.createDirectories(saves.resolve("not-a-world"));
        Path disabled = Files.createDirectories(saves.resolve("old-world.disabled"));
        Files.writeString(disabled.resolve("level.dat"), "nbt");

        List<GameFiles.Entry> entries = GameFiles.scan(saves, GameFiles.Category.SAVES);
        assertEquals(1, entries.size());
        assertEquals("survival", entries.get(0).name());
    }

    @Test
    void addsFilesAndBuildsUniqueNames() throws IOException {
        Path packs = Files.createDirectories(temp.resolve("shaderpacks"));
        Files.writeString(packs.resolve("shiny.zip"), "existing");
        Path source = Files.writeString(temp.resolve("shiny.zip"), "new");

        List<Path> added = GameFiles.add(packs, GameFiles.Category.SHADER_PACKS, List.of(source));
        assertEquals(1, added.size());
        assertTrue(Files.exists(packs.resolve("shiny (2).zip")));
        assertEquals("new", Files.readString(added.get(0)));
    }

    @Test
    void importsAndDeletesWorldFolder() throws IOException {
        Path saves = Files.createDirectories(temp.resolve("saves"));
        Path source = Files.createDirectories(temp.resolve("my-world"));
        Files.writeString(source.resolve("level.dat"), "nbt");
        Files.createDirectories(source.resolve("region"));
        Files.writeString(source.resolve("region").resolve("r.0.0.mca"), "region");

        List<Path> added = GameFiles.add(saves, GameFiles.Category.SAVES, List.of(source));
        Path imported = added.get(0);
        assertTrue(Files.exists(imported.resolve("level.dat")));
        assertTrue(Files.exists(imported.resolve("region").resolve("r.0.0.mca")));

        GameFiles.Entry entry = GameFiles.scan(saves, GameFiles.Category.SAVES).get(0);
        GameFiles.delete(entry);
        assertFalse(Files.exists(imported));
        assertTrue(Files.exists(source));
    }

    @Test
    void rejectsWrongContentTypes() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Path zip = Files.writeString(temp.resolve("pack.zip"), "zip");
        boolean failed = false;
        try {
            GameFiles.add(mods, GameFiles.Category.MODS, List.of(zip));
        } catch (IOException expected) {
            failed = true;
        }
        assertTrue(failed);

        Path notWorld = Files.createDirectories(temp.resolve("empty-folder"));
        failed = false;
        try {
            GameFiles.add(mods, GameFiles.Category.SAVES, List.of(notWorld));
        } catch (IOException expected) {
            failed = true;
        }
        assertTrue(failed);
    }

    @Test
    void formatsSizes() {
        assertEquals("512 B", GameFiles.formatSize(512));
        assertEquals("1.0 KB", GameFiles.formatSize(1024));
        assertEquals("1.5 MB", GameFiles.formatSize(1536L * 1024));
        assertEquals("2.0 GB", GameFiles.formatSize(2L * 1024 * 1024 * 1024));
    }
}
