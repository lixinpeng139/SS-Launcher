package com.sslauncher.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Tiny rotating file logger. */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LINES = 500;
    private static final Deque<String> RECENT = new ArrayDeque<>();
    private static PrintWriter writer;

    private Log() {
    }

    public static synchronized void init() {
        boolean scrubbed = false;
        try {
            Path log = AppPaths.LAUNCHER_LOG;
            Files.createDirectories(log.getParent());
            if (Files.exists(log) && Files.size(log) > 4L * 1024 * 1024) {
                Files.move(log, log.resolveSibling("launcher.old.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            // Older builds logged the full launch command, including the
            // account access token. Remove those credentials from logs that
            // were written before the command line was masked.
            scrubbed = scrub(log);
            scrubbed |= scrub(log.resolveSibling("launcher.old.log"));
            writer = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
        } catch (IOException e) {
            System.err.println("Cannot open launcher log: " + e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (Log.class) {
                if (writer != null) {
                    writer.flush();
                    writer.close();
                }
            }
        }));
        info("SS Launcher starting, home=" + AppPaths.ROOT);
        if (scrubbed) {
            info("已从历史启动器日志中清除账号令牌等敏感信息");
        }
    }

    private static boolean scrub(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String clean = Secrets.redact(text);
            if (clean.equals(text)) {
                return false;
            }
            AppPaths.atomicWrite(file, clean.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            System.err.println("Cannot scrub launcher log " + file + ": " + e);
            return false;
        }
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void error(String message) {
        write("ERROR", message, null);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    private static synchronized void write(String level, String message, Throwable t) {
        String line = "[" + TS.format(LocalDateTime.now()) + "] [" + level + "] " + message;
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line += System.lineSeparator() + sw;
        }
        // Last line of defence: even a caller that forgets to mask a command
        // line must not persist a live account token to launcher.log.
        line = Secrets.redact(line);
        System.out.println(line);
        RECENT.addLast(line);
        while (RECENT.size() > MAX_LINES) {
            RECENT.removeFirst();
        }
        if (writer != null) {
            writer.println(line);
        }
    }

    public static synchronized List<String> recent() {
        return List.copyOf(RECENT);
    }
}
