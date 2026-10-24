package com.sslauncher.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Append-only diagnostics for the AI runtime. Never receives API keys or prompts. */
public final class AiLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private AiLog() {
    }

    public static synchronized void write(String name, String message) {
        if (message == null) {
            return;
        }
        try {
            AiPaths.init();
            Path file = AiPaths.logFile(name);
            if (Files.isRegularFile(file) && Files.size(file) > MAX_BYTES) {
                Path old = file.resolveSibling(file.getFileName() + ".1");
                Files.deleteIfExists(old);
                Files.move(file, old);
            }
            String line = LocalDateTime.now().format(TIME) + "  " + message.replace('\u0000', ' ') + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
