package com.sslauncher.core;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around Windows DPAPI. The protection is scoped to the current
 * Windows user by default, so another user account or a copy of the file on a
 * different machine cannot recover the plaintext.
 *
 * <p>This class deliberately contains every JNA import. {@link AccountVault}
 * only touches it on Windows and catches linkage failures, so a build that is
 * started without JNA on the classpath can still use the AES-GCM fallback.</p>
 */
final class WindowsDpapi {
    /** Binds the blob to this purpose instead of accepting any protected data. */
    private static final byte[] ENTROPY =
            "SSLauncher/account-token/v1".getBytes(StandardCharsets.UTF_8);
    private static final String DESCRIPTION = "SS Launcher account token";
    private static final int FLAGS = WinCrypt.CRYPTPROTECT_UI_FORBIDDEN;

    private WindowsDpapi() {
    }

    static byte[] protect(byte[] plain) {
        return Crypt32Util.cryptProtectData(plain, ENTROPY, FLAGS, DESCRIPTION, null);
    }

    static byte[] unprotect(byte[] blob) {
        return Crypt32Util.cryptUnprotectData(blob, ENTROPY, FLAGS, null);
    }
}
