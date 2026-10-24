package com.sslauncher.skin;

import com.google.gson.JsonObject;
import com.sslauncher.core.AppPaths;
import com.sslauncher.model.Account;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in network probe: runs the fetch, upload and reset paths against a
 * local Yggdrasil mock. Enable with {@code -Dss.skinProbe=true}.
 */
@EnabledIfSystemProperty(named = "ss.skinProbe", matches = "true")
class SkinServiceNetworkProbe {
    @TempDir
    Path temp;

    private HttpServer server;
    private Account account;
    private Path skin;
    private final AtomicReference<String> putBody = new AtomicReference<>();
    private final AtomicReference<String> putType = new AtomicReference<>();
    private final AtomicReference<Boolean> deleted = new AtomicReference<>(false);

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("ss.home", temp.resolve("home").toString());
        AppPaths.init();
        skin = temp.resolve("skin.png");
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", skin.toFile());
        byte[] png = Files.readAllBytes(skin);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;

        server.createContext("/api/yggdrasil/authserver/validate", exchange -> respond(exchange, 204, new byte[0], "text/plain"));
        server.createContext("/skin.png", exchange -> respond(exchange, 200, png, "image/png"));
        server.createContext("/api/yggdrasil/sessionserver/session/minecraft/profile/", exchange -> {
            JsonObject texture = new JsonObject();
            JsonObject skinNode = new JsonObject();
            skinNode.addProperty("url", base + "/skin.png");
            JsonObject metadata = new JsonObject();
            metadata.addProperty("model", "slim");
            skinNode.add("metadata", metadata);
            JsonObject textures = new JsonObject();
            textures.add("SKIN", skinNode);
            texture.add("textures", textures);
            String encoded = Base64.getEncoder().encodeToString(
                    texture.toString().getBytes(StandardCharsets.UTF_8));
            JsonObject property = new JsonObject();
            property.addProperty("name", "textures");
            property.addProperty("value", encoded);
            JsonObject uploadable = new JsonObject();
            uploadable.addProperty("name", "uploadableTextures");
            uploadable.addProperty("value", "skin,cape");
            JsonObject response = new JsonObject();
            response.addProperty("id", "8667ba71b85a4004af54457a9734eed7");
            response.addProperty("name", "Probe");
            var properties = new com.google.gson.JsonArray();
            properties.add(property);
            properties.add(uploadable);
            response.add("properties", properties);
            respond(exchange, 200, response.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        });
        server.createContext("/api/user/profile/", exchange -> {
            String method = exchange.getRequestMethod();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("PUT".equals(method)) {
                putBody.set(new String(body, StandardCharsets.ISO_8859_1));
                putType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                respond(exchange, 204, new byte[0], "text/plain");
            } else if ("DELETE".equals(method)) {
                deleted.set(true);
                respond(exchange, 204, new byte[0], "text/plain");
            } else {
                respond(exchange, 405, new byte[0], "text/plain");
            }
        });
        server.start();

        account = new Account();
        account.id = "yggdrasil:" + base + "/api/yggdrasil:8667ba71b85a4004af54457a9734eed7";
        account.type = Account.TYPE_YGGDRASIL;
        account.uuid = "8667ba71b85a4004af54457a9734eed7";
        account.name = "Probe";
        account.serverUrl = base + "/api/yggdrasil";
        account.accessToken = "token";
        account.clientToken = "client";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchUploadAndResetAgainstMockServer() throws Exception {
        SkinService.SkinState fetched = SkinService.reload(account);
        assertEquals(SkinService.Source.SERVER, fetched.source());
        assertEquals("slim", fetched.model());
        assertEquals(Boolean.TRUE, fetched.uploadable());
        assertNotNull(fetched.file());
        assertTrue(Files.isRegularFile(fetched.file()));

        SkinService.upload(account, skin, "classic");
        String body = putBody.get();
        assertNotNull(body, "PUT body was not captured");
        assertTrue(putType.get().startsWith("multipart/form-data; boundary="));
        assertTrue(body.contains("name=\"model\""));
        // authlib-injector expects an empty model field for the classic model.
        assertTrue(body.contains("name=\"model\"\r\n\r\n\r\n--"));
        assertTrue(body.contains("name=\"file\"; filename=\"skin.png\""));
        assertTrue(body.contains("Content-Type: image/png"));
        SkinService.SkinState uploaded = SkinService.state(account);
        assertEquals(SkinService.Source.SERVER, uploaded.source());
        assertEquals("classic", uploaded.model());
        assertFalse(Files.exists(SkinService.accountDir(account).resolve(SkinService.LOCAL_PNG)));

        SkinService.reset(account);
        assertEquals(Boolean.TRUE, deleted.get());
        SkinService.SkinState cleared = SkinService.state(account);
        assertEquals(SkinService.Source.NONE, cleared.source());
        assertNull(cleared.file());
        assertFalse(Files.exists(SkinService.accountDir(account).resolve(SkinService.SERVER_PNG)));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                byte[] body, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
