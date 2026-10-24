package com.sslauncher.core;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encrypts the credentials in {@code accounts.json} before they reach disk.
 *
 * <p>On Windows the preferred envelope is DPAPI ({@code CryptProtectData}),
 * which ties the ciphertext to the current Windows user and machine. If JNA or
 * DPAPI is unavailable, the launcher falls back to an AES-256-GCM envelope so
 * the file is never written with plaintext tokens. The AES key is compiled into
 * the launcher and is therefore obfuscation rather than a real vault; the file
 * ACL set by {@link FileSecurity} is the second layer there.</p>
 *
 * <p>Both formats carry a version prefix. Values without a prefix are legacy
 * plaintext from older builds and are accepted on read so they can be migrated
 * transparently.</p>
 */
public final class AccountVault {
    static final String DPAPI_PREFIX = "dpapi:v1:";
    static final String AES_PREFIX = "aes:v1:";

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AES_AAD =
            "SSLauncher/account-token/v1".getBytes(StandardCharsets.UTF_8);
    // Random key material, stored split/obfuscated like SecretBox's key.
    // key[i] = DATA[i] ^ MASK[i].
    private static final int[] DATA = {
            81, -86, 11, -16, -116, 37, 97, -42, -12, 91, 61, -72, -101, -86, -9, 15,
            96, 89, -53, -7, -23, -90, -112, 81, -48, -8, -6, -66, -127, 18, -77, 105};
    private static final int[] MASK = {
            17, -84, 52, 110, -7, 61, 99, -12, 43, 121, -66, 8, 77, -35, 90, 14,
            -58, 36, 111, -19, 5, -92, 64, 27, -101, 18, 73, -45, 126, -3, 88, 40};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicBoolean DPAPI_WARNING_LOGGED = new AtomicBoolean();

    private AccountVault() {
    }

    /** Encrypts a token, or returns {@code ""} for an empty token. */
    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        if ("windows".equals(AppPaths.osName())) {
            try {
                byte[] blob = WindowsDpapi.protect(plain.getBytes(StandardCharsets.UTF_8));
                return DPAPI_PREFIX + Base64.getEncoder().encodeToString(blob);
            } catch (RuntimeException | LinkageError e) {
                if (DPAPI_WARNING_LOGGED.compareAndSet(false, true)) {
                    Log.warn("Windows DPAPI 不可用，账号令牌将使用 AES-GCM 加密: " + e.getMessage());
                }
            }
        }
        return AES_PREFIX + aesSeal(plain);
    }

    /**
     * Decrypts a stored token.
     *
     * @return the plaintext token, {@code ""} for an empty value, the value
     *         unchanged when it is legacy plaintext, or {@code null} when a
     *         versioned payload is corrupt or cannot be decrypted by this user.
     */
    public static String open(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (stored.startsWith(DPAPI_PREFIX)) {
            try {
                byte[] blob = Base64.getDecoder().decode(stored.substring(DPAPI_PREFIX.length()));
                return new String(WindowsDpapi.unprotect(blob), StandardCharsets.UTF_8);
            } catch (RuntimeException | LinkageError e) {
                Log.warn("账号令牌无法用 Windows DPAPI 解密，需要重新登录");
                return null;
            }
        }
        if (stored.startsWith(AES_PREFIX)) {
            try {
                return aesOpen(stored.substring(AES_PREFIX.length()));
            } catch (RuntimeException e) {
                Log.warn("账号令牌无法解密，需要重新登录");
                return null;
            }
        }
        return stored;
    }

    public static boolean isSealed(String stored) {
        return stored != null
                && (stored.startsWith(DPAPI_PREFIX) || stored.startsWith(AES_PREFIX));
    }

    private static String aesSeal(String plain) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AES_AAD);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法加密账号令牌", e);
        }
    }

    private static String aesOpen(String payload) {
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length <= NONCE_LENGTH) {
                throw new IllegalArgumentException("加密的账号令牌长度无效");
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(raw, 0, nonce, 0, NONCE_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AES_AAD);
            byte[] plain = cipher.doFinal(raw, NONCE_LENGTH, raw.length - NONCE_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法解密账号令牌", e);
        }
    }

    private static SecretKeySpec aesKey() {
        byte[] key = new byte[DATA.length];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (DATA[i] ^ MASK[i]);
        }
        return new SecretKeySpec(key, "AES");
    }
}
