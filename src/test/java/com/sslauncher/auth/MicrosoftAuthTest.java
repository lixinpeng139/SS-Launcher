package com.sslauncher.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftAuthTest {
    private static final String EMBEDDED = "5c5c1e30-d2af-4ab0-8779-636b53082d82";
    private String previous;

    @AfterEach
    void restoreProperty() {
        if (previous == null) {
            System.clearProperty("ss.msClientId");
        } else {
            System.setProperty("ss.msClientId", previous);
        }
    }

    @Test
    void clientIdIsBakedInAndCannotBeOverridden() {
        previous = System.getProperty("ss.msClientId");
        System.setProperty("ss.msClientId", "00000000-0000-0000-0000-000000000000");

        assertEquals(EMBEDDED, MicrosoftAuth.CLIENT_ID);
        assertEquals(EMBEDDED, MicrosoftAuth.clientId());
        assertTrue(MicrosoftAuth.isConfigured());
    }
}
