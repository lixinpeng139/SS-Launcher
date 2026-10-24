package com.sslauncher.multiplayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapServerTest {
    @Test
    void addressIsStableAndIncludesTcp() {
        String address = BootstrapServer.address();
        assertNotNull(address);
        assertFalse(address.isBlank());
        assertTrue(address.contains("/tcp/"), address);
        assertSame(address, BootstrapServer.address());
    }
}
