package com.sslauncher.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsTest {
    private static final String JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNTQyIn0.c2lnbmF0dXJl";
    private static final String YGGDRASIL_TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void masksCredentialsInLaunchCommand() {
        List<String> command = List.of(
                "java", "-Xmx4G",
                "-Dauthlibinjector.yggdrasil.prefetched=eyJtZXRhIjoiZGF0YSJ9",
                "-cp", "a.jar;b.jar", "net.minecraft.client.main.Main",
                "--username", "Steve",
                "--uuid", "0123456789abcdef0123456789abcdef",
                "--accessToken", JWT,
                "--clientToken", "client-secret-value",
                "--session", "token:" + JWT + ":" + YGGDRASIL_TOKEN,
                "--server", "example.com");

        String masked = Secrets.maskCommand(command);

        assertFalse(masked.contains(JWT));
        assertFalse(masked.contains("client-secret-value"));
        assertFalse(masked.contains("eyJtZXRhIjoiZGF0YSJ9"));
        assertTrue(masked.contains("--accessToken " + Secrets.HIDDEN));
        assertTrue(masked.contains("--clientToken " + Secrets.HIDDEN));
        assertTrue(masked.contains("--session " + Secrets.HIDDEN));
        // Public identifiers stay visible so the log remains useful.
        assertTrue(masked.contains("--username Steve"));
        assertTrue(masked.contains("--uuid 0123456789abcdef0123456789abcdef"));
        assertTrue(masked.contains("--server example.com"));
    }

    @Test
    void masksEqualsFormsAndSensitiveJvmProperties() {
        List<String> command = List.of(
                "java", "-Dhttp.proxyPassword=hunter2", "-Dfile.encoding=UTF-8",
                "--accessToken=" + YGGDRASIL_TOKEN, "--clientId=public-client-id");

        String masked = Secrets.maskCommand(command);

        assertFalse(masked.contains("hunter2"));
        assertFalse(masked.contains(YGGDRASIL_TOKEN));
        assertTrue(masked.contains("-Dhttp.proxyPassword=" + Secrets.HIDDEN));
        assertTrue(masked.contains("-Dfile.encoding=UTF-8"));
        assertTrue(masked.contains("--clientId=public-client-id"));
    }

    @Test
    void redactsFlagValuesFromLogExcerpts() {
        String excerpt = "命令行: java -cp a.jar Main --accessToken " + JWT
                + " --accessToken " + YGGDRASIL_TOKEN + " --username Steve";

        String clean = Secrets.redact(excerpt);

        assertFalse(clean.contains(JWT));
        assertFalse(clean.contains(YGGDRASIL_TOKEN));
        assertTrue(clean.contains("--accessToken " + Secrets.HIDDEN));
        assertTrue(clean.contains("--username Steve"));
    }

    @Test
    void redactsCommonSecretShapes() {
        String clean = Secrets.redact("Authorization: Bearer abcdef123456\n"
                + "--accessToken=" + JWT + "\n"
                + "{\"api_key\": \"abcdef1234567890\"}\n"
                + "https://example.com/download?token=supersecret\n"
                + "sk-abcdefgh12345678");

        assertFalse(clean.contains("abcdef123456"));
        assertFalse(clean.contains(JWT));
        assertFalse(clean.contains("abcdef1234567890"));
        assertFalse(clean.contains("supersecret"));
        assertFalse(clean.contains("sk-abcdefgh12345678"));
    }

    @Test
    void leavesOrdinaryArgumentsAlone() {
        String text = "--username Steve --server example.com --assetIndex 17 --uuid 0123456789abcdef";

        assertEquals(text, Secrets.redact(text));
        assertEquals(text, Secrets.redact(Secrets.redact(text)));
    }
}
