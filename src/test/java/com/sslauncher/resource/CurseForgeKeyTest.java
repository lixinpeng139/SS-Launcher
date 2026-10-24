package com.sslauncher.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CurseForgeKeyTest {
    @Test
    void keyIsStableAcrossCalls() {
        String first = CurseForgeKey.apiKey();
        assertNotNull(first);
        assertSame(first, CurseForgeKey.apiKey());
    }
}
