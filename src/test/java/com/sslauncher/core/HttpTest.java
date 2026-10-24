package com.sslauncher.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTest {
    @TempDir
    Path temp;

    @Test
    void prefersOfficialCdnThenMcim() {
        assertEquals(List.of("https://cdn.modrinth.com/data/a/versions/b/mod.jar",
                        "https://mod.mcimirror.top/data/a/versions/b/mod.jar"),
                Mirror.resourceCandidates("https://cdn.modrinth.com/data/a/versions/b/mod.jar"));
        assertEquals(List.of("https://mediafilez.forgecdn.net/files/1/2/mod.jar",
                        "https://mod.mcimirror.top/files/1/2/mod.jar"),
                Mirror.resourceCandidates("https://mediafilez.forgecdn.net/files/1/2/mod.jar"));
        assertEquals(List.of("https://example.com/mod.jar"),
                Mirror.resourceCandidates("https://example.com/mod.jar"));
    }

    @Test
    void segmentedDownloadAssemblesRanges() throws Exception {
        byte[] data = randomBytes(3 * 1024 * 1024);
        HttpServer server = server(exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range == null) {
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(data);
                }
                return;
            }
            int dash = range.indexOf('-');
            long start = Long.parseLong(range.substring("bytes=".length(), dash));
            long end = Long.parseLong(range.substring(dash + 1));
            int length = (int) (end - start + 1);
            exchange.getResponseHeaders().add("Content-Range",
                    "bytes " + start + "-" + end + "/" + data.length);
            exchange.sendResponseHeaders(206, length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data, (int) start, length);
            }
        });
        try {
            Path dest = temp.resolve("out").resolve("pack.mrpack");
            Http.downloadSegmented(dest, url(server, "/file.bin"), sha1(data), data.length,
                    4, new Progress());
            assertArrayEquals(data, Files.readAllBytes(dest));
            assertFalse(Files.exists(dest.resolveSibling("pack.mrpack.part")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void segmentedDownloadRejectsWrongChecksum() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024);
        HttpServer server = server(exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int dash = range.indexOf('-');
            long start = Long.parseLong(range.substring("bytes=".length(), dash));
            long end = Long.parseLong(range.substring(dash + 1));
            int length = (int) (end - start + 1);
            exchange.getResponseHeaders().add("Content-Range",
                    "bytes " + start + "-" + end + "/" + data.length);
            exchange.sendResponseHeaders(206, length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data, (int) start, length);
            }
        });
        try {
            Path dest = temp.resolve("bad").resolve("pack.mrpack");
            assertThrows(IOException.class, () -> Http.downloadSegmented(dest,
                    url(server, "/file.bin"), "0000000000000000000000000000000000000000",
                    data.length, 4, new Progress()));
            assertFalse(Files.exists(dest));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadVerifiesSha256WhenProvided() throws Exception {
        byte[] data = randomBytes(512 * 1024);
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });
        try {
            String hash = sha256(data);
            Path dest = temp.resolve("verified").resolve("node.zip");
            Http.downloadTo(dest, url(server, "/file.bin"), null, hash, new Progress());
            assertArrayEquals(data, Files.readAllBytes(dest));

            Path rejected = temp.resolve("rejected").resolve("node.zip");
            assertThrows(IOException.class, () -> Http.downloadTo(rejected,
                    url(server, "/file.bin"), null, "0".repeat(64), new Progress()));
            assertFalse(Files.exists(rejected));
            assertFalse(Files.exists(rejected.resolveSibling("node.zip.part")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void officialFirstKeepsOfficialOriginAheadOfMirrors() {
        String url = "https://piston-meta.mojang.com/v1/packages/abc/1.21.4.json";
        List<String> candidates = Mirror.officialFirst(url);
        assertEquals(url, candidates.get(0));
        assertTrue(candidates.subList(1, candidates.size()).stream()
                .allMatch(c -> !c.startsWith("https://piston-meta.mojang.com/")));
    }

    @Test
    void abandonsStalledConnections() throws Exception {
        long previous = Http.stallTimeoutMs;
        Http.stallTimeoutMs = 1_000L;
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 10 * 1024 * 1024);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[4096]);
                out.flush();
                release.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            Path dest = temp.resolve("stall").resolve("pack.jar");
            long started = System.nanoTime();
            assertThrows(IOException.class, () -> Http.downloadTo(dest, url(server, "/file.bin"),
                    "", new Progress(), Http.CONTENT_CLIENT));
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsed < 10_000, "stalled download should abort, took " + elapsed + " ms");
            assertFalse(Files.exists(dest));
            assertFalse(Files.exists(dest.resolveSibling("pack.jar.part")));
        } finally {
            Http.stallTimeoutMs = previous;
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void segmentedDownloadReportsServersWithoutRanges() throws Exception {
        byte[] data = randomBytes(1024 * 1024);
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });
        try {
            Path dest = temp.resolve("plain").resolve("pack.mrpack");
            assertThrows(Http.RangeUnsupportedException.class,
                    () -> Http.downloadSegmented(dest, url(server, "/file.bin"), "",
                            data.length, 4, new Progress()));
            assertFalse(Files.exists(dest));
            try (var files = Files.list(dest.getParent())) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("pack.mrpack")));
            }
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/file.bin", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        return data;
    }

    private static String sha1(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
