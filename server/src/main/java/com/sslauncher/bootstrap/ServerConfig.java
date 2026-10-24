package com.sslauncher.bootstrap;

import java.nio.file.Path;

/**
 * Server configuration. Values come from command-line flags first, then
 * environment variables, then defaults:
 *
 * <pre>
 *   --port=4001            SS_PORT
 *   --udp-port=4001        SS_UDP_PORT
 *   --home=/var/lib/...    SS_HOME
 *   --public-host=1.2.3.4  SS_PUBLIC_HOST
 *   --max-rooms=500        SS_MAX_ROOMS
 *   --max-guests=8         SS_MAX_GUESTS
 *   --ping-seconds=45      SS_PING_SECONDS
 *   --idle-seconds=150     SS_IDLE_SECONDS
 * </pre>
 */
final class ServerConfig {
    final int port;
    final int udpPort;
    final Path home;
    final String publicHost;
    final int maxRooms;
    final int maxGuests;
    final int pingSeconds;
    final int idleSeconds;

    private ServerConfig(int port, int udpPort, Path home, String publicHost, int maxRooms,
                         int maxGuests, int pingSeconds, int idleSeconds) {
        this.port = port;
        this.udpPort = udpPort;
        this.home = home;
        this.publicHost = publicHost;
        this.maxRooms = maxRooms;
        this.maxGuests = maxGuests;
        this.pingSeconds = pingSeconds;
        this.idleSeconds = idleSeconds;
    }

    static ServerConfig load(String[] args) {
        java.util.Map<String, String> flags = new java.util.HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq > 2) {
                flags.put(arg.substring(2, eq), arg.substring(eq + 1));
            } else {
                flags.put(arg.substring(2), "true");
            }
        }

        int port = clamp(intValue(flags, "port", env("SS_PORT"), 4001), 1, 65535);
        // UDP endpoint discovery uses the same port number by default, so
        // clients only need to remember one port for the built-in server.
        int udpPort = clamp(intValue(flags, "udp-port", env("SS_UDP_PORT"), port), 1, 65535);
        Path home = Path.of(stringValue(flags, "home", env("SS_HOME"), "data")).toAbsolutePath().normalize();
        String publicHost = stringValue(flags, "public-host", env("SS_PUBLIC_HOST"), "").trim();
        int maxRooms = clamp(intValue(flags, "max-rooms", env("SS_MAX_ROOMS"), 500), 1, 100_000);
        int maxGuests = clamp(intValue(flags, "max-guests", env("SS_MAX_GUESTS"), 8), 1, 64);
        int pingSeconds = clamp(intValue(flags, "ping-seconds", env("SS_PING_SECONDS"), 45), 10, 3600);
        int idleSeconds = clamp(intValue(flags, "idle-seconds", env("SS_IDLE_SECONDS"), 150),
                pingSeconds + 30, 24 * 3600);
        return new ServerConfig(port, udpPort, home, publicHost, maxRooms, maxGuests,
                pingSeconds, idleSeconds);
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static String stringValue(java.util.Map<String, String> flags, String flag,
                                      String envValue, String fallback) {
        String value = flags.get(flag);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return envValue == null || envValue.isBlank() ? fallback : envValue;
    }

    private static int intValue(java.util.Map<String, String> flags, String flag,
                                String envValue, int fallback) {
        String value = flags.get(flag);
        if (value == null || value.isBlank()) {
            value = envValue;
        }
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
