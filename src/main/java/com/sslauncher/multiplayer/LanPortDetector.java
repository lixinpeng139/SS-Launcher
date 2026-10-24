package com.sslauncher.multiplayer;

import com.sslauncher.core.Log;
import com.sslauncher.model.Instance;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the TCP port Minecraft is listening on for an instance.
 *
 * <p>Single-player worlds opened to LAN log "Started serving on &lt;port&gt;";
 * a dedicated server stores the port in server.properties. The tail of the
 * latest log is enough because a LAN world is normally opened just before the
 * player joins.</p>
 */
public final class LanPortDetector {
    private static final Pattern[] LOG_PATTERNS = {
            Pattern.compile("Started serving on (\\d{1,5})"),
            Pattern.compile("Local game hosted on port (\\d{1,5})"),
            Pattern.compile("Opening LAN on port (\\d{1,5})"),
            Pattern.compile("Started server on [^\\n]*:(\\d{1,5})")
    };
    private static final long TAIL_BYTES = 400_000L;

    private LanPortDetector() {
    }

    public static int detect(Instance instance) {
        if (instance == null) {
            return 0;
        }
        Path dir = instance.gameDir();
        int port = fromLogs(dir.resolve("logs").resolve("latest.log"));
        if (port > 0) {
            return port;
        }
        return fromProperties(dir.resolve("server.properties"));
    }

    /** Returns the port announced in the log tail, or 0. */
    static int fromLogs(Path log) {
        if (log == null || !Files.isRegularFile(log)) {
            return 0;
        }
        String text;
        try (SeekableByteChannel channel = Files.newByteChannel(log)) {
            long size = channel.size();
            long start = Math.max(0, size - TAIL_BYTES);
            channel.position(start);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                out.write(chunk);
                buffer.clear();
            }
            text = out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("联机：读取游戏日志失败: " + e.getMessage());
            return 0;
        }
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            for (Pattern pattern : LOG_PATTERNS) {
                Matcher matcher = pattern.matcher(lines[i]);
                if (matcher.find()) {
                    int port = parsePort(matcher.group(1));
                    if (port > 0) {
                        return port;
                    }
                }
            }
        }
        return 0;
    }

    /** Reads {@code server-port} from a dedicated server's properties file. */
    static int fromProperties(Path properties) {
        if (properties == null || !Files.isRegularFile(properties)) {
            return 0;
        }
        try {
            int port = 0;
            for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("server-port=")) {
                    port = parsePort(trimmed.substring("server-port=".length()).trim());
                }
            }
            return port;
        } catch (IOException e) {
            return 0;
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
