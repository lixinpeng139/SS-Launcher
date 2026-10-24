package com.sslauncher.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Credential scrubbing for text that leaves the launcher's memory.
 *
 * <p>The launcher starts Minecraft with the account's access token on the
 * command line, so writing the raw command to {@code launcher.log} would keep
 * a live token on disk and expose it to anything that reads the log (including
 * the AI assistant's log tool). {@link #maskCommand(List)} hides the values of
 * sensitive flags, and {@link #redact(String)} is the last-line filter for
 * free-form text such as a log excerpt or an HTTP error message.</p>
 */
public final class Secrets {
    public static final String HIDDEN = "[已隐藏]";

    /** Flag names whose following token is a credential, normalized to lowercase. */
    private static final Set<String> SECRET_WORDS = Set.of(
            "token", "password", "passwd", "secret", "session", "prefetched",
            "apikey", "api_key", "api-key");

    private static final Pattern TOKEN_FLAG = Pattern.compile(
            "(?i)(--(?:access|client|refresh|auth)?[-_]?token(?:=|\\s+))[^\\s\"']+");
    private static final Pattern SESSION_FLAG = Pattern.compile(
            "(?i)(--session(?:=|\\s+))[^\\s\"']+");
    private static final Pattern PREFETCHED = Pattern.compile(
            "(?i)(-Dauthlibinjector\\.yggdrasil\\.prefetched=)[^\\s\"']+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s\"']+");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?token"
                    + "|password|passwd|secret)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"']{4,}");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])ey[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]{8,}){1,2}");
    private static final Pattern DEEPSEEK = Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");
    private static final Pattern URL_KEY = Pattern.compile(
            "(?i)([?&](?:api[_-]?key|token|access_token|refresh_token|client_token)=)[^&\\s]+");

    private Secrets() {
    }

    /**
     * Renders a command line for logging with credential values replaced.
     * Handles both {@code --accessToken <value>} and {@code --accessToken=<value>}
     * forms, plus sensitive JVM properties such as the authlib-injector
     * prefetched metadata.
     */
    public static String maskCommand(List<String> command) {
        List<String> masked = new ArrayList<>(command.size());
        boolean hideNext = false;
        for (String arg : command) {
            if (hideNext) {
                masked.add(HIDDEN);
                hideNext = false;
                continue;
            }
            if (arg == null || arg.isEmpty()) {
                masked.add(arg == null ? "" : arg);
                continue;
            }
            String assignment = maskAssignment(arg);
            if (assignment != null) {
                masked.add(assignment);
                continue;
            }
            masked.add(arg);
            if (isSecretFlag(arg)) {
                hideNext = true;
            }
        }
        StringBuilder out = new StringBuilder();
        for (String arg : masked) {
            if (out.length() > 0) {
                out.append(' ');
            }
            if (arg.contains(" ") && !arg.startsWith("\"")) {
                out.append('"').append(arg).append('"');
            } else {
                out.append(arg);
            }
        }
        return out.toString();
    }

    /** Masks credentials in arbitrary text before it is logged or sent to a model. */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String clean = TOKEN_FLAG.matcher(value).replaceAll("$1" + HIDDEN);
        clean = SESSION_FLAG.matcher(clean).replaceAll("$1" + HIDDEN);
        clean = PREFETCHED.matcher(clean).replaceAll("$1" + HIDDEN);
        clean = BEARER.matcher(clean).replaceAll("$1" + HIDDEN);
        clean = ASSIGNMENT.matcher(clean).replaceAll("$1" + HIDDEN);
        clean = JWT.matcher(clean).replaceAll(HIDDEN);
        clean = DEEPSEEK.matcher(clean).replaceAll("sk-" + HIDDEN);
        clean = URL_KEY.matcher(clean).replaceAll("$1" + HIDDEN);
        return clean;
    }

    /** Masks {@code --flag=value} and {@code -Dproperty=value} credentials. */
    private static String maskAssignment(String arg) {
        int eq = arg.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String prefix = arg.substring(0, eq);
        String name;
        if (prefix.startsWith("-D") || prefix.startsWith("--")) {
            name = prefix.substring(2);
        } else if (prefix.startsWith("-")) {
            name = prefix.substring(1);
        } else {
            return null;
        }
        return sensitive(name) ? prefix + "=" + HIDDEN : null;
    }

    private static boolean isSecretFlag(String arg) {
        if (arg.indexOf('=') >= 0) {
            return false;
        }
        String name;
        if (arg.startsWith("--")) {
            name = arg.substring(2);
        } else if (arg.startsWith("-") && arg.length() > 1 && !arg.startsWith("-D")) {
            name = arg.substring(1);
        } else {
            return false;
        }
        return sensitive(name);
    }

    private static boolean sensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('_', '-');
        for (String word : SECRET_WORDS) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
