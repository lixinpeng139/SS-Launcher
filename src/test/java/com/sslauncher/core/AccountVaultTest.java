package com.sslauncher.core;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountVaultTest {
    @Test
    void encryptsAndRoundTripsTokensWithoutStoringPlaintext() {
        for (String token : List.of(
                "plain-access-token-4f6c2a",
                "plain-refresh-token-91be7d",
                "plain-client-token-30c5aa")) {
            String sealed = AccountVault.seal(token);
            assertNotEquals(token, sealed);
            assertFalse(sealed.contains(token));
            assertTrue(AccountVault.isSealed(sealed));
            assertEquals(token, AccountVault.open(sealed));
        }
    }

    @Test
    void emptyTokensStayEmpty() {
        assertEquals("", AccountVault.seal(""));
        assertEquals("", AccountVault.seal(null));
        assertEquals("", AccountVault.open(""));
        assertEquals("", AccountVault.open(null));
    }

    @Test
    void legacyPlaintextIsAcceptedAndNotMarkedSealed() {
        assertEquals("legacy-token", AccountVault.open("legacy-token"));
        assertFalse(AccountVault.isSealed("legacy-token"));
    }

    @Test
    void tamperedEnvelopeIsRejected() {
        String sealed = AccountVault.seal("token-that-must-not-leak");
        String prefix = sealed.startsWith(AccountVault.DPAPI_PREFIX)
                ? AccountVault.DPAPI_PREFIX : AccountVault.AES_PREFIX;
        byte[] raw = Base64.getDecoder().decode(sealed.substring(prefix.length()));
        raw[raw.length - 1] ^= 0x40;
        String tampered = prefix + Base64.getEncoder().encodeToString(raw);
        assertNull(AccountVault.open(tampered));
    }

    @Test
    void aesFallbackRoundTripsWhenDpapiIsNotUsed() {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            String sealed = AccountVault.seal("aes-fallback-token-7d21");
            assertTrue(sealed.startsWith(AccountVault.AES_PREFIX));
            assertEquals("aes-fallback-token-7d21", AccountVault.open(sealed));
        } finally {
            if (previous == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previous);
            }
        }
    }

    @Test
    void invalidAesPayloadIsRejected() {
        assertNull(AccountVault.open(AccountVault.AES_PREFIX + "AAAA"));
    }
}
