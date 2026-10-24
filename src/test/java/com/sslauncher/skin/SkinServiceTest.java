package com.sslauncher.skin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinServiceTest {
    @TempDir
    Path temp;

    @Test
    void validatesStandardAndHighResolutionSkins() throws Exception {
        Path standard = png("standard.png", 64, 64);
        SkinService.SkinFile file = SkinService.validate(standard);
        assertEquals(64, file.width());
        assertEquals(64, file.height());
        assertEquals(1, file.scale());
        assertEquals("64×64", file.dimensions());

        Path legacy = png("legacy.png", 64, 32);
        assertEquals(32, SkinService.validate(legacy).height());

        Path hd = png("hd.png", 256, 256);
        assertEquals(4, SkinService.validate(hd).scale());

        Path wideHd = png("wide-hd.png", 128, 64);
        assertEquals(2, SkinService.validate(wideHd).scale());
    }

    @Test
    void rejectsFilesThatAreNotAcceptableSkins() throws Exception {
        Path missing = temp.resolve("missing.png");
        assertTrue(message(() -> SkinService.validate(missing)).contains("不存在"));

        Path notPng = temp.resolve("skin.png");
        Files.writeString(notPng, "not a png at all", StandardCharsets.UTF_8);
        assertTrue(message(() -> SkinService.validate(notPng)).contains("PNG"));

        Path tooSmall = png("small.png", 32, 32);
        assertTrue(message(() -> SkinService.validate(tooSmall)).contains("太小"));

        Path badShape = png("bad-shape.png", 128, 96);
        assertTrue(message(() -> SkinService.validate(badShape)).contains("64×32"));
    }

    @Test
    void parsesYggdrasilTexturesAndModel() throws Exception {
        String json = """
                {
                  "timestamp": 1700000000000,
                  "profileId": "abc",
                  "profileName": "Steve",
                  "uploadableTextures": ["skin", "cape"],
                  "textures": {
                    "SKIN": {
                      "url": "https://example.com/skin.png",
                      "metadata": {"model": "slim"}
                    }
                  }
                }
                """;
        SkinService.TextureInfo info = SkinService.parseTextures(base64(json));
        assertEquals("https://example.com/skin.png", info.skinUrl());
        assertEquals("slim", info.model());
        assertEquals(Boolean.TRUE, info.uploadable());

        String noSkin = """
                {"textures": {}, "uploadableTextures": {"skin": false}}
                """;
        SkinService.TextureInfo none = SkinService.parseTextures(base64(noSkin));
        assertTrue(none.skinUrl().isEmpty());
        assertEquals("classic", none.model());
        assertEquals(Boolean.FALSE, none.uploadable());
    }

    @Test
    void acceptsUrlSafeBase64AndUnknownUploadSupport() throws Exception {
        String json = """
                {"textures": {"SKIN": {"url": "https://example.com/a.png"}}}
                """;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SkinService.TextureInfo info = SkinService.parseTextures(urlSafe);
        assertEquals("https://example.com/a.png", info.skinUrl());
        assertEquals("classic", info.model());
        assertNull(info.uploadable());

        SkinService.TextureInfo blank = SkinService.parseTextures("");
        assertTrue(blank.skinUrl().isEmpty());
        assertNull(blank.uploadable());

        assertThrows(SkinService.SkinException.class,
                () -> SkinService.parseTextures("not-base64!!"));
        assertThrows(SkinService.SkinException.class,
                () -> SkinService.parseTextures(base64("not json")));
    }

    @Test
    void parsesUploadableShapes() {
        assertEquals(Boolean.TRUE, SkinService.parseUploadable(
                JsonParser.parseString("[\"skin\"]")));
        assertEquals(Boolean.FALSE, SkinService.parseUploadable(
                JsonParser.parseString("[\"cape\"]")));
        assertEquals(Boolean.TRUE, SkinService.parseUploadable(
                JsonParser.parseString("{\"skin\": true}")));
        assertEquals(Boolean.FALSE, SkinService.parseUploadable(
                JsonParser.parseString("{\"skin\": false}")));
        assertNull(SkinService.parseUploadable(
                JsonParser.parseString("{\"cape\": true}")));
        assertNull(SkinService.parseUploadable(
                JsonParser.parseString("null")));
        assertEquals(Boolean.TRUE, SkinService.parseUploadable(
                JsonParser.parseString("true")));
    }

    @Test
    void parsesAuthlibUploadableTexturesProperty() {
        assertEquals(Boolean.TRUE, SkinService.parseUploadableProperty("skin"));
        assertEquals(Boolean.TRUE, SkinService.parseUploadableProperty("skin,cape"));
        assertEquals(Boolean.TRUE, SkinService.parseUploadableProperty("cape, Skin"));
        assertEquals(Boolean.FALSE, SkinService.parseUploadableProperty("cape"));
        assertNull(SkinService.parseUploadableProperty(""));
        assertNull(SkinService.parseUploadableProperty(null));
        assertEquals(Boolean.TRUE, SkinService.parseUploadableProperty("[\"skin\"]"));
        assertEquals(Boolean.FALSE, SkinService.parseUploadableProperty("{\"skin\":false}"));
    }

    @Test
    void normalizesModelNames() {
        assertEquals("slim", SkinService.normalizeModel(" SLIM "));
        assertEquals("classic", SkinService.normalizeModel("classic"));
        assertEquals("classic", SkinService.normalizeModel(null));
        assertEquals("纤细", SkinService.modelLabel("slim"));
        assertEquals("经典", SkinService.modelLabel(""));
        assertFalse(SkinService.normalizeModel("slim").isEmpty());
    }

    private Path png(String name, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Path path = temp.resolve(name);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String message(ThrowingCall call) {
        return assertThrows(SkinService.SkinException.class, call::run).getMessage();
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }
}
