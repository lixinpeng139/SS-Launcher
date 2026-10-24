package com.sslauncher.bootstrap;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Minimal stdout logger; systemd/journald adds its own timestamps. */
final class Log {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {
    }

    static void info(String message) {
        System.out.println("[" + LocalDateTime.now().format(FORMAT) + "] [INFO] " + message);
    }

    static void warn(String message) {
        System.out.println("[" + LocalDateTime.now().format(FORMAT) + "] [WARN] " + message);
    }
}
