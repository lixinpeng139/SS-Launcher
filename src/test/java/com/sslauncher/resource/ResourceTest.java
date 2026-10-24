package com.sslauncher.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sslauncher.core.ZipUtil;
import com.sslauncher.model.LoaderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTest {
    @TempDir
    Path temp;

    @Test
    void readsModrinthModpackManifest() throws IOException {
        JsonObject root = new JsonObject();
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", "1.20.1");
        dependencies.addProperty("forge", "47.2.0");
        root.add("dependencies", dependencies);
        root.addProperty("name", "Test Pack");
        root.addProperty("versionId", "1.0");
        JsonArray files = new JsonArray();
        JsonObject file = new JsonObject();
        file.addProperty("path", "mods/example.jar");
        files.add(file);
        root.add("files", files);

        Path archive = zip("pack.mrpack", entry("modrinth.index.json",
                root.toString().getBytes(StandardCharsets.UTF_8)));

        ContentInstallService.ModpackInfo info =
                ContentInstallService.inspectModpack(archive, ResourceSource.MODRINTH);
        assertEquals("1.20.1", info.mcVersion());
        assertEquals(LoaderType.FORGE, info.loader());
        assertEquals("47.2.0", info.loaderVersion());
        assertEquals("Test Pack", info.name());
        assertEquals(1, info.fileCount());
    }

    @Test
    void readsCurseForgeManifest() throws IOException {
        JsonObject minecraft = new JsonObject();
        minecraft.addProperty("version", "1.21.1");
        JsonArray loaders = new JsonArray();
        JsonObject loader = new JsonObject();
        loader.addProperty("id", "neoforge-21.1.264");
        loader.addProperty("primary", true);
        loaders.add(loader);
        minecraft.add("modLoaders", loaders);

        JsonObject root = new JsonObject();
        root.add("minecraft", minecraft);
        root.addProperty("name", "Curse Pack");
        root.addProperty("version", "2.3");
        root.add("files", new JsonArray());

        Path archive = zip("curse.zip", entry("manifest.json",
                root.toString().getBytes(StandardCharsets.UTF_8)));

        ContentInstallService.ModpackInfo info =
                ContentInstallService.inspectModpack(archive, ResourceSource.CURSEFORGE);
        assertEquals("1.21.1", info.mcVersion());
        assertEquals(LoaderType.NEOFORGE, info.loader());
        assertEquals("21.1.264", info.loaderVersion());
        assertEquals("2.3", info.version());
    }

    @Test
    void detectsModrinthModpackFromArchive() throws IOException {
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", "1.20.1");
        dependencies.addProperty("fabric-loader", "0.15.11");
        JsonObject root = new JsonObject();
        root.add("dependencies", dependencies);
        root.add("files", new JsonArray());
        root.addProperty("name", "Dropped Pack");
        Path archive = zip("dropped.mrpack", entry("modrinth.index.json",
                root.toString().getBytes(StandardCharsets.UTF_8)));

        ContentInstallService.DetectedModpack detected =
                ContentInstallService.inspectModpack(archive);
        assertEquals(ResourceSource.MODRINTH, detected.source());
        assertEquals("1.20.1", detected.info().mcVersion());
        assertEquals(LoaderType.FABRIC, detected.info().loader());
        assertEquals("Dropped Pack", detected.info().name());
    }

    @Test
    void detectsCurseForgeModpackFromZip() throws IOException {
        JsonObject minecraft = new JsonObject();
        minecraft.addProperty("version", "1.21.1");
        JsonArray loaders = new JsonArray();
        JsonObject loader = new JsonObject();
        loader.addProperty("id", "neoforge-21.1.264");
        loaders.add(loader);
        minecraft.add("modLoaders", loaders);
        JsonObject root = new JsonObject();
        root.add("minecraft", minecraft);
        root.addProperty("name", "Dropped Curse Pack");
        root.add("files", new JsonArray());

        Path archive = zip("dropped.zip", entry("manifest.json",
                root.toString().getBytes(StandardCharsets.UTF_8)));

        ContentInstallService.DetectedModpack detected =
                ContentInstallService.inspectModpack(archive);
        assertEquals(ResourceSource.CURSEFORGE, detected.source());
        assertEquals("1.21.1", detected.info().mcVersion());
        assertEquals(LoaderType.NEOFORGE, detected.info().loader());
    }

    @Test
    void detectsNestedModrinthModpackFromArchive() throws IOException {
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", "1.20.1");
        dependencies.addProperty("fabric-loader", "0.15.11");
        JsonObject root = new JsonObject();
        root.add("dependencies", dependencies);
        root.add("files", new JsonArray());
        root.addProperty("name", "Nested Pack");
        Path archive = zip("nested.mrpack",
                entry("My Pack/modrinth.index.json",
                        root.toString().getBytes(StandardCharsets.UTF_8)),
                entry("My Pack/overrides/config/example.txt",
                        "ok".getBytes(StandardCharsets.UTF_8)));

        ContentInstallService.DetectedModpack detected =
                ContentInstallService.inspectModpack(archive);
        assertEquals(ResourceSource.MODRINTH, detected.source());
        assertEquals("My Pack/", detected.root());
        assertEquals("1.20.1", detected.info().mcVersion());
        assertEquals(LoaderType.FABRIC, detected.info().loader());
        assertEquals("0.15.11", detected.info().loaderVersion());
        assertEquals("Nested Pack", detected.info().name());
        assertTrue(ZipUtil.containsEntryAnywhere(archive, "modrinth.index.json"));
    }

    @Test
    void detectsNestedCurseForgeModpackFromArchive() throws IOException {
        JsonObject minecraft = new JsonObject();
        minecraft.addProperty("version", "1.21.1");
        JsonArray loaders = new JsonArray();
        JsonObject loader = new JsonObject();
        loader.addProperty("id", "neoforge-21.1.264");
        loaders.add(loader);
        minecraft.add("modLoaders", loaders);
        JsonObject root = new JsonObject();
        root.add("minecraft", minecraft);
        root.addProperty("name", "Nested Curse Pack");
        root.add("files", new JsonArray());
        Path archive = zip("nested-curse.zip", entry("MyPack/manifest.json",
                root.toString().getBytes(StandardCharsets.UTF_8)));

        ContentInstallService.ModpackInfo info =
                ContentInstallService.inspectModpack(archive, ResourceSource.CURSEFORGE);
        assertEquals("1.21.1", info.mcVersion());
        assertEquals(LoaderType.NEOFORGE, info.loader());

        ContentInstallService.DetectedModpack detected =
                ContentInstallService.inspectModpack(archive);
        assertEquals(ResourceSource.CURSEFORGE, detected.source());
        assertEquals("MyPack/", detected.root());
    }

    @Test
    void fallsBackToNestedManifestWhenRootManifestIsNotAPack() throws IOException {
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", "1.20.1");
        dependencies.addProperty("forge", "47.2.0");
        JsonObject root = new JsonObject();
        root.add("dependencies", dependencies);
        root.add("files", new JsonArray());
        root.addProperty("name", "Fallback Pack");
        Path archive = zip("fallback.mrpack",
                entry("manifest.json", "{}".getBytes(StandardCharsets.UTF_8)),
                entry("Fallback/modrinth.index.json",
                        root.toString().getBytes(StandardCharsets.UTF_8)));

        ContentInstallService.DetectedModpack detected =
                ContentInstallService.inspectModpack(archive);
        assertEquals(ResourceSource.MODRINTH, detected.source());
        assertEquals("Fallback/", detected.root());
        assertEquals("Fallback Pack", detected.info().name());
    }

    @Test
    void findsManifestEntriesAtRootAndInFolders() throws IOException {
        Path archive = zip("entries-nested.zip",
                entry("MyPack/modrinth.index.json", "{}".getBytes(StandardCharsets.UTF_8)),
                entry("assets/notmanifest.json", "{}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ZipUtil.containsEntryAnywhere(archive, "modrinth.index.json"));
        assertFalse(ZipUtil.containsEntryAnywhere(archive, "manifest.json"));
        assertFalse(ZipUtil.containsEntry(archive, "modrinth.index.json"));
    }

    @Test
    void extractsNestedOverridesWithPrefix() throws IOException {
        Path archive = zip("nested-overrides.zip",
                entry("My Pack/overrides/config/ok.txt",
                        "ok".getBytes(StandardCharsets.UTF_8)),
                entry("Other/overrides/config/no.txt",
                        "no".getBytes(StandardCharsets.UTF_8)));
        Path game = Files.createDirectories(temp.resolve("nested-game"));

        ZipUtil.extractSubtree(archive, "My Pack/overrides", game);

        assertTrue(Files.exists(game.resolve("config").resolve("ok.txt")));
        assertFalse(Files.exists(game.resolve("Other")));
    }

    @Test
    void rejectsZipWithoutModpackManifest() throws IOException {
        Path archive = zip("not-a-pack.zip",
                entry("pack.mcmeta", "{}".getBytes(StandardCharsets.UTF_8)));
        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ContentInstallService.inspectModpack(archive));
        assertTrue(error.getMessage().contains("不是有效的整合包"));
    }

    @Test
    void findsManifestEntriesInArchive() throws IOException {
        Path archive = zip("entries.zip",
                entry("manifest.json", "{}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ZipUtil.containsEntry(archive, "modrinth.index.json", "manifest.json"));
        assertFalse(ZipUtil.containsEntry(archive, "modrinth.index.json"));
    }

    @Test
    void extractsOverridesWithoutEscapingTarget() throws IOException {
        Path archive = zip("pack.zip",
                entry("overrides/config/ok.txt", "ok".getBytes(StandardCharsets.UTF_8)),
                entry("overrides/../escape.txt", "bad".getBytes(StandardCharsets.UTF_8)),
                entry("overrides/../../escape2.txt", "bad".getBytes(StandardCharsets.UTF_8)));
        Path game = Files.createDirectories(temp.resolve("game"));

        ZipUtil.extractSubtree(archive, "overrides", game);

        assertTrue(Files.exists(game.resolve("config").resolve("ok.txt")));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
        assertFalse(Files.exists(temp.getParent().resolve("escape2.txt")));
    }

    @Test
    void buildsCurseForgeCdnFallbackUrl() {
        assertEquals("https://mediafilez.forgecdn.net/files/9195/226/"
                        + "sodium-fabric-0.9.3-beta.1%2Bmc26.3.jar",
                CurseForgeProvider.cdnUrl(9195226,
                        "sodium-fabric-0.9.3-beta.1+mc26.3.jar"));
        assertEquals("https://mediafilez.forgecdn.net/files/12/003/example%20mod.jar",
                CurseForgeProvider.cdnUrl(12003, "example mod.jar"));
    }

    @Test
    void matchesQuiltToFabricAndNormalizesLoaderNames() {
        assertTrue(ResourceVersion.loaderMatches("quilt", List.of("fabric")));
        assertTrue(ResourceVersion.loaderMatches("fabric-loader", Set.of("fabric")));
        assertFalse(ResourceVersion.loaderMatches("forge", Set.of("fabric")));
        assertEquals("fabric", ResourceVersion.normalizeLoader("fabric-loader"));
        assertEquals("neoforge", ResourceVersion.normalizeLoader("neo-forge"));
    }

    @Test
    void sanitizesDownloadFileNames() {
        assertEquals("mod.jar", ResourceService.safeFileName("../mod.jar", "title", "1.0"));
        assertEquals("a_b.jar", ResourceService.safeFileName("a:b.jar", "title", "1.0"));
        String fallback = ResourceService.safeFileName("", "My Mod", "1.2.3");
        assertEquals("My Mod-1.2.3.jar", fallback);
        assertFalse(ResourceService.safeFileName("..", "title", "1").contains(".."));
    }

    @Test
    void sourceFilterSelectsRequestedSource() {
        assertTrue(SourceFilter.ALL.includes(ResourceSource.MODRINTH));
        assertTrue(SourceFilter.ALL.includes(ResourceSource.CURSEFORGE));
        assertTrue(SourceFilter.MODRINTH.includes(ResourceSource.MODRINTH));
        assertFalse(SourceFilter.MODRINTH.includes(ResourceSource.CURSEFORGE));
        assertFalse(SourceFilter.CURSEFORGE.includes(ResourceSource.MODRINTH));
    }

    private Path zip(String name, ZipEntryData... entries) throws IOException {
        Path path = temp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (ZipEntryData data : entries) {
                out.putNextEntry(new ZipEntry(data.name()));
                out.write(data.bytes());
                out.closeEntry();
            }
        }
        return path;
    }

    private static ZipEntryData entry(String name, byte[] bytes) {
        return new ZipEntryData(name, bytes);
    }

    private record ZipEntryData(String name, byte[] bytes) {
    }
}
