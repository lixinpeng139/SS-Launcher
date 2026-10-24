package com.sslauncher.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragPayloadsTest {
    @Test
    void parsesChromiumDragIdPayload() {
        byte[] payload = pickle("chromium/x-drag-id",
                "7481FDB1469454F03769E8C397112CA46");

        List<DragPayloads.ChromiumCustomEntry> entries =
                DragPayloads.parseChromiumCustomData(payload);

        assertEquals(1, entries.size());
        assertEquals("chromium/x-drag-id", entries.get(0).name());
        assertEquals("7481FDB1469454F03769E8C397112CA46", entries.get(0).value());
    }

    @Test
    void parsesAuthlibCustomMimeAndPadding() {
        // An odd-length name exercises the four-byte padding that Pickle adds
        // between the name and the value.
        byte[] payload = pickle(
                "authlib-injector/one", "https://example.com/api/yggdrasil",
                "text/plain", "hello");

        List<DragPayloads.ChromiumCustomEntry> entries =
                DragPayloads.parseChromiumCustomData(payload);

        assertEquals(2, entries.size());
        assertEquals("authlib-injector/one", entries.get(0).name());
        assertEquals("https://example.com/api/yggdrasil", entries.get(0).value());
        assertTrue(entries.get(0).mentionsAuthlib());
        assertEquals("text/plain", entries.get(1).name());
        assertEquals("hello", entries.get(1).value());
    }

    @Test
    void stripsPagePathFromRendererOrigin() {
        assertEquals("https://skin.example.com",
                DragPayloads.originOf("https://skin.example.com/user?tab=profile"));
        assertEquals("http://127.0.0.1:8080",
                DragPayloads.originOf("http://127.0.0.1:8080/api/yggdrasil"));
        assertEquals("", DragPayloads.originOf(""));
    }

    @Test
    void rejectsMalformedChromiumPayload() {
        assertTrue(DragPayloads.parseChromiumCustomData(new byte[0]).isEmpty());
        assertTrue(DragPayloads.parseChromiumCustomData(new byte[11]).isEmpty());

        ByteBuffer hugeCount = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        hugeCount.putInt(8).putInt(100_000).putInt(0);
        assertTrue(DragPayloads.parseChromiumCustomData(hugeCount.array()).isEmpty());

        ByteBuffer truncated = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        truncated.putInt(12).putInt(1).putInt(4).putInt(0);
        assertTrue(DragPayloads.parseChromiumCustomData(truncated.array()).isEmpty());
    }

    private static byte[] pickle(String... nameValuePairs) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, nameValuePairs.length / 2);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            writeString(payload, nameValuePairs[i]);
            writeString(payload, nameValuePairs[i + 1]);
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        writeInt(result, payload.size());
        result.writeBytes(payload.toByteArray());
        return result.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        writeInt(out, value.length());
        out.writeBytes(value.getBytes(StandardCharsets.UTF_16LE));
        while (out.size() % 4 != 0) {
            out.write(0);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array());
    }
}
