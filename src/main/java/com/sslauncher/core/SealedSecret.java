package com.sslauncher.core;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Decrypts small secrets that are embedded as AES-256-GCM payloads.
 *
 * <p>Used for values that must not appear as plain strings in config files or
 * in a {@code strings} dump of the jar. The key still ships inside the binary,
 * so this is obfuscation rather than a secure enclave; it keeps the value out
 * of casual view.</p>
 */
public final class SealedSecret {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private SealedSecret() {
    }

    /**
     * @param payload base64(nonce[12] + ciphertext + tag[16])
     * @param keyData key bytes XOR {@code keyMask}
     * @param keyMask mask applied to {@code keyData}
     * @param error   message to surface when the payload cannot be decrypted
     */
    public static String decrypt(String payload, byte[] keyData, byte[] keyMask,
                                 String error) {
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(raw, 0, nonce, 0, NONCE_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(keyData, keyMask), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(raw, NONCE_LENGTH, raw.length - NONCE_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(error, e);
        }
    }

    private static byte[] key(byte[] keyData, byte[] keyMask) {
        byte[] key = new byte[keyData.length];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (keyData[i] ^ keyMask[i]);
        }
        return key;
    }
}
