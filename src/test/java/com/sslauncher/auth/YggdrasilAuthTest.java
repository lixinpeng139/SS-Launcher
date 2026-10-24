package com.sslauncher.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YggdrasilAuthTest {
    @Test
    void parsesPlainAddress() {
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl("  https://littleskin.cn/api/yggdrasil/  "));
    }

    @Test
    void parsesOneClickLink() {
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl("authlib-injector:https://littleskin.cn/api/yggdrasil"));
    }

    @Test
    void parsesPercentEncodedOneClickLink() {
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "authlib-injector:https%3A%2F%2Flittleskin.cn%2Fapi%2Fyggdrasil"));
    }

    @Test
    void parsesBlessingSkinDragButton() {
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "authlib-injector:yggdrasil-server:"
                                + "https%3A%2F%2Flittleskin.cn%2Fapi%2Fyggdrasil"));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "authlib-injector:yggdrasil-server:https://littleskin.cn/api/yggdrasil"));
        assertEquals("https://skin.example.com",
                YggdrasilAuth.parseDroppedUrl(
                        "authlib-injector:yggdrasil-server:https%3A%2F%2Fskin.example.com"));
    }

    @Test
    void extractsUrlFromLinkText() {
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl("LittleSkin 皮肤站 https://littleskin.cn/api/yggdrasil"));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl("https://littleskin.cn/api/yggdrasil\nLittleSkin"));
    }

    @Test
    void parsesBrowserDragFormats() {
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "<a href=\"https://littleskin.cn/api/yggdrasil\">LittleSkin</a>"));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "# dragged from a browser\r\n"
                                + "https://littleskin.cn/api/yggdrasil\r\n"));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "authlib-injector:yggdrasil-server:"
                                + "https%3A%2F%2Flittleskin.cn%2Fapi%2Fyggdrasil\r\n"));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "https://littleskin.cn/api/yggdrasil\u0000"));
    }

    @Test
    void prefersAuthlibPayloadAcrossDragFormats() {
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrlCandidates(List.of(
                        "LittleSkin",
                        "https://littleskin.cn/api/yggdrasil",
                        "<a href=\"https://littleskin.cn/api/yggdrasil\">LittleSkin</a>")));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrlCandidates(List.of(
                        "https://example.com/",
                        "authlib-injector:yggdrasil-server:"
                                + "https%3A%2F%2Flittleskin.cn%2Fapi%2Fyggdrasil")));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrlCandidates(List.of(
                        "<a href=\"https://example.com/\" data-payload=\""
                                + "authlib-injector:yggdrasil-server:"
                                + "https%3A%2F%2Flittleskin.cn%2Fapi%2Fyggdrasil\">"
                                + "LittleSkin</a>")));
        assertTrue(YggdrasilAuth.isAuthlibInjectorPayload(
                "authlib-injector:yggdrasil-server:https%3A%2F%2Fexample.com"));
        assertFalse(YggdrasilAuth.isAuthlibInjectorPayload("https://example.com"));
    }

    @Test
    void trimsSubResourceAndQuery() {
        assertEquals("https://example.com/api/yggdrasil",
                YggdrasilAuth.parseDroppedUrl(
                        "https://example.com/api/yggdrasil/authserver/authenticate?x=1#top"));
    }

    @Test
    void rejectsNonAddress() {
        assertTrue(YggdrasilAuth.parseDroppedUrl("hello world").isEmpty());
        assertTrue(YggdrasilAuth.parseDroppedUrl("ftp://example.com/api/yggdrasil").isEmpty());
        assertFalse(YggdrasilAuth.looksLikeServerUrl("not a link"));
    }

    @Test
    void buildsDiscoveryCandidates() {
        List<String> home = YggdrasilAuth.candidateServers("https://littleskin.cn");
        assertEquals(List.of("https://littleskin.cn", "https://littleskin.cn/api/yggdrasil"), home);

        List<String> root = YggdrasilAuth.candidateServers("https://littleskin.cn/api/yggdrasil");
        assertEquals(List.of("https://littleskin.cn/api/yggdrasil"), root);

        List<String> api = YggdrasilAuth.candidateServers("https://littleskin.cn/api");
        assertEquals(List.of("https://littleskin.cn/api",
                "https://littleskin.cn/api/yggdrasil"), api);
    }
}
