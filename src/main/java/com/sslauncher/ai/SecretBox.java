package com.sslauncher.ai;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Small AES-GCM envelope used to keep a user's model API key out of a plain
 * text {@code config.json}. The key material is compiled into the launcher,
 * so this is obfuscation at rest rather than a hardware-backed vault; it does
 * keep the value from being copied by a casual glance or a config screenshot.
 */
public final class SecretBox {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    // Random key material, stored split/obfuscated like the built-in
    // CurseForge key. key[i] = DATA[i] ^ MASK[i].
    private static final int[] DATA = {
            149, 23, 153, 70, 65, 153, 2, 171, 117, 34, 193, 209, 236, 64, 14, 47,
            60, -72, -121, 36, 80, 100, 40, -12, -70, -97, -56, -11, 73, 1, 110, -110};
    private static final int[] MASK = {
            1, 116, -113, -90, 81, 57, -69, -33, 54, -112, 70, 50, 7, -89, -117, -4,
            109, -3, -62, -81, -35, 12, -97, -107, 108, 17, -90, -117, 115, 101, 70, -44};
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretBox() {
    }

    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法加密 AI API Key", e);
        }
    }

    /** Returns an empty string when the payload is missing or cannot be read. */
    public static String unseal(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length <= NONCE_LENGTH) {
                return "";
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(raw, 0, nonce, 0, NONCE_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(raw, NONCE_LENGTH, raw.length - NONCE_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return "";
        }
    }

    private static SecretKeySpec key() {
        byte[] key = new byte[DATA.length];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (DATA[i] ^ MASK[i]);
        }
        return new SecretKeySpec(key, "AES");
    }
}
