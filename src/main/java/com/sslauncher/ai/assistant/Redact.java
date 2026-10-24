package com.sslauncher.ai.assistant;

import com.sslauncher.core.Secrets;

/** Last-line scrubbing for text that leaves the launcher through a tool. */
final class Redact {
    private Redact() {
    }

    static String text(String value) {
        return Secrets.redact(value);
    }
}
