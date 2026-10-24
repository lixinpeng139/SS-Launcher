package com.sslauncher.service;

import com.sslauncher.model.LoaderType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderServiceTest {
    @Test
    void parsesOfficialSha1WithOptionalFileName() throws Exception {
        String hash = "b73203963ea70aee0c38dc58de1f6d16a766605b";
        assertEquals(hash, LoaderService.parseOfficialSha1(hash + "\n", "test"));
        assertEquals(hash, LoaderService.parseOfficialSha1(hash + "  forge-installer.jar\n", "test"));
    }

    @Test
    void rejectsMalformedOfficialSha1() {
        assertThrows(IOException.class, () -> LoaderService.parseOfficialSha1("", "test"));
        assertThrows(IOException.class, () -> LoaderService.parseOfficialSha1("not-a-hash", "test"));
        assertThrows(IOException.class, () -> LoaderService.parseOfficialSha1(
                "<html>mirror error page</html>", "test"));
        assertThrows(IOException.class, () -> LoaderService.parseOfficialSha1(
                "b73203963ea70aee0c38dc58de1f6d16a766605", "test"));
    }

    @Test
    void installerChecksumsOnlyComeFromOfficialHosts() {
        for (String url : LoaderService.installerSha1Candidates(
                LoaderType.FORGE, "1.20.1", "47.4.0")) {
            assertTrue(url.startsWith("https://maven.minecraftforge.net/")
                            || url.startsWith("https://files.minecraftforge.net/"),
                    "unexpected Forge checksum host: " + url);
        }
        for (String url : LoaderService.installerSha1Candidates(
                LoaderType.NEOFORGE, "1.21.1", "21.1.80")) {
            assertTrue(url.startsWith("https://neoforged.forgecdn.net/")
                            || url.startsWith("https://maven.neoforged.net/"),
                    "unexpected NeoForge checksum host: " + url);
        }
    }

    @Test
    void fabricProfileIsFetchedFromOfficialMetaOnly() {
        List<String> urls = LoaderService.profileCandidates(LoaderType.FABRIC, "1.21.1", "0.16.10");
        assertEquals(List.of("https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.10/profile/json"),
                urls);
    }
}
