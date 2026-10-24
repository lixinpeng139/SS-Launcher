package com.sslauncher.ui;

import com.sslauncher.auth.YggdrasilAuth;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads values from a JavaFX {@link Dragboard} without assuming a particular
 * browser flavour. A dragged skin-site button can arrive as plain text, a URI
 * list, HTML, a Chromium custom MIME type, or even only a Chromium renderer
 * origin when the page's drag script failed to load.
 */
public final class DragPayloads {
    private static final int MAX_VALUE_CHARS = 32 * 1024;

    private DragPayloads() {
    }

    /**
     * Parsed skin-site drop. {@code authlibInjector} is true when the source
     * supplied the official one-click payload, which is safe to intercept even
     * over a text field. {@code guessed} is true when the address came from
     * Chromium's renderer origin because the drag carried no usable text; such
     * a drop must be checked against the Yggdrasil metadata before opening the
     * login dialog.
     */
    public record SkinDrop(String server, boolean authlibInjector, boolean guessed) {
        public boolean isEmpty() {
            return server == null || server.isEmpty();
        }
    }

    record ChromiumCustomEntry(String name, String value) {
        boolean mentionsAuthlib() {
            return containsAuthlib(name) || containsAuthlib(value);
        }
    }

    public static SkinDrop skin(Dragboard board) {
        if (board == null) {
            return empty();
        }
        try {
            // A file drag may also carry the file name as text; never turn an
            // Explorer drag into a skin-site login.
            if (board.hasFiles()) {
                return empty();
            }

            List<String> candidates = textValues(board);
            List<ChromiumCustomEntry> custom = chromiumCustomEntries(board);
            for (ChromiumCustomEntry entry : custom) {
                if (entry.value() == null || entry.value().isBlank()) {
                    continue;
                }
                String parsed = YggdrasilAuth.parseDroppedUrl(entry.value());
                if (!parsed.isEmpty() && entry.mentionsAuthlib()) {
                    return new SkinDrop(parsed, true, false);
                }
                if (!entry.value().isBlank()) {
                    candidates.add(entry.value());
                }
            }

            for (String value : candidates) {
                if (YggdrasilAuth.isAuthlibInjectorPayload(value)) {
                    String explicit = YggdrasilAuth.parseDroppedUrl(value);
                    if (!explicit.isEmpty()) {
                        return new SkinDrop(explicit, true, false);
                    }
                }
            }
            String server = YggdrasilAuth.parseDroppedUrlCandidates(candidates);
            if (!server.isEmpty()) {
                return new SkinDrop(server, false, false);
            }

            // Some Blessing Skin sites ship a broken/missing dnd.js. The
            // button then carries only Chromium's internal formats, but its
            // renderer origin is still available and normally exposes the
            // Yggdrasil API through /api/yggdrasil.
            String origin = "";
            if (hasDragImage(board)) {
                origin = chromiumRendererOrigin(board);
            }
            if (Boolean.getBoolean("ss.debugDrag")) {
                System.out.println("[drag] transferModes=" + board.getTransferModes()
                        + " types=" + board.getContentTypes()
                        + " candidates=" + candidates.size()
                        + " custom=" + custom.size()
                        + " server=" + server
                        + " origin=" + origin);
            }
            if (!origin.isEmpty()) {
                return new SkinDrop(origin, false, true);
            }
            return empty();
        } catch (RuntimeException e) {
            // Dragboard data is owned by another process; a format may vanish
            // between getContentTypes() and getContent(). Treat it as no drop
            // rather than breaking the whole drag gesture.
            return empty();
        }
    }

    private static SkinDrop empty() {
        return new SkinDrop("", false, false);
    }

    private static List<String> textValues(Dragboard board) {
        Set<String> values = new LinkedHashSet<>();
        // Prefer conventional formats. The URL is usually more precise than
        // the label browsers put in text/plain.
        addContent(board, DataFormat.PLAIN_TEXT, values);
        addContent(board, DataFormat.URL, values);
        addContent(board, DataFormat.HTML, values);
        addContent(board, DataFormat.RTF, values);

        Set<DataFormat> types;
        try {
            types = board.getContentTypes();
        } catch (RuntimeException e) {
            types = Set.of();
        }
        if (types != null) {
            for (DataFormat format : types) {
                if (format == null || format == DataFormat.FILES || format == DataFormat.IMAGE
                        || !isTextFormat(format)) {
                    continue;
                }
                addContent(board, format, values);
            }
        }

        // Belt and braces for JavaFX versions that expose the convenience
        // accessors without listing the matching DataFormat.
        try {
            if (board.hasString()) {
                addText(board.getString(), values);
            }
            if (board.hasUrl()) {
                addText(board.getUrl(), values);
            }
        } catch (RuntimeException ignored) {
            // The drag source went away; keep whatever was already read.
        }
        return new ArrayList<>(values);
    }

    /**
     * Chromium advertises image and internal formats (for example
     * {@code DragImageBits}) alongside text. Decoding those as strings wastes
     * time and can produce false URL matches from binary data, so only inspect
     * formats whose names identify string data.
     */
    private static boolean isTextFormat(DataFormat format) {
        for (String identifier : format.getIdentifiers()) {
            if (identifier == null) {
                continue;
            }
            String lower = identifier.toLowerCase(Locale.ROOT);
            if (lower.startsWith("message/external-body") || lower.startsWith("chromium/")) {
                continue;
            }
            if (lower.contains("text") || lower.contains("html") || lower.contains("url")
                    || lower.contains("string") || lower.contains("authlib")) {
                return true;
            }
        }
        return false;
    }

    private static void addContent(Dragboard board, DataFormat format, Set<String> values) {
        try {
            if (board.hasContent(format)) {
                collect(board.getContent(format), values);
            }
        } catch (RuntimeException ignored) {
            // Some formats are advertised but cannot be materialised.
        }
    }

    private static void collect(Object value, Set<String> values) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            addText(text, values);
        } else if (value instanceof CharSequence text) {
            addText(text.toString(), values);
        } else if (value instanceof URI uri) {
            addText(uri.toString(), values);
        } else if (value instanceof URL url) {
            addText(url.toExternalForm(), values);
        } else if (value instanceof byte[] bytes) {
            addText(decode(bytes), values);
        } else if (value instanceof ByteBuffer buffer) {
            addText(decode(toBytes(buffer)), values);
        } else if (value instanceof Iterable<?> list) {
            for (Object item : list) {
                collect(item, values);
            }
        } else if (value instanceof Object[] array) {
            for (Object item : array) {
                collect(item, values);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collect(entry.getKey(), values);
                collect(entry.getValue(), values);
            }
        } else if (value instanceof File file) {
            addText(file.toURI().toString(), values);
        }
    }

    private static void addText(String text, Set<String> values) {
        if (text == null || text.isBlank()) {
            return;
        }
        values.add(text.length() > MAX_VALUE_CHARS
                ? text.substring(0, MAX_VALUE_CHARS) : text);
    }

    private static boolean hasDragImage(Dragboard board) {
        return findFormat(board, "dragimagebits") != null;
    }

    private static String chromiumRendererOrigin(Dragboard board) {
        DataFormat format = findFormat(board, "chromium/x-renderer-taint");
        if (format == null) {
            return "";
        }
        byte[] bytes = formatBytes(board, format);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_VALUE_CHARS) {
            return "";
        }
        return originOf(YggdrasilAuth.parseDroppedUrl(decode(bytes)));
    }

    /**
     * Chromium's renderer taint is an origin, but trim any path defensively:
     * discovery appends {@code /api/yggdrasil} to this base.
     */
    static String originOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() != null && uri.getAuthority() != null) {
                return uri.getScheme() + "://" + uri.getAuthority();
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to the already-normalized address.
        }
        return url;
    }

    private static List<ChromiumCustomEntry> chromiumCustomEntries(Dragboard board) {
        DataFormat format = findChromiumCustomDataFormat(board);
        if (format == null) {
            return List.of();
        }
        byte[] bytes = formatBytes(board, format);
        if (bytes == null) {
            return List.of();
        }
        return parseChromiumCustomData(bytes);
    }

    private static DataFormat findChromiumCustomDataFormat(Dragboard board) {
        Set<DataFormat> types;
        try {
            types = board.getContentTypes();
        } catch (RuntimeException e) {
            return null;
        }
        if (types == null) {
            return null;
        }
        for (DataFormat format : types) {
            if (format == null) {
                continue;
            }
            for (String identifier : format.getIdentifiers()) {
                if (identifier == null) {
                    continue;
                }
                String lower = identifier.toLowerCase(Locale.ROOT);
                if (lower.contains("chromium web custom mime data format")
                        || lower.contains("org.chromium.web-custom-data")) {
                    return format;
                }
            }
        }
        return null;
    }

    private static DataFormat findFormat(Dragboard board, String identifierPart) {
        Set<DataFormat> types;
        try {
            types = board.getContentTypes();
        } catch (RuntimeException e) {
            return null;
        }
        if (types == null) {
            return null;
        }
        for (DataFormat format : types) {
            if (format == null) {
                continue;
            }
            for (String identifier : format.getIdentifiers()) {
                if (identifier != null
                        && identifier.toLowerCase(Locale.ROOT).contains(identifierPart)) {
                    return format;
                }
            }
        }
        return null;
    }

    private static byte[] formatBytes(Dragboard board, DataFormat format) {
        try {
            if (!board.hasContent(format)) {
                return null;
            }
            Object content = board.getContent(format);
            if (content instanceof byte[] bytes) {
                return bytes;
            }
            if (content instanceof ByteBuffer buffer) {
                return toBytes(buffer);
            }
            if (content instanceof String text) {
                return text.getBytes(StandardCharsets.UTF_8);
            }
        } catch (RuntimeException ignored) {
            // The format disappeared between enumeration and reading.
        }
        return null;
    }

    /**
     * Decodes Chromium's {@code Chromium Web Custom MIME Data Format}. The
     * value is a Pickle: payload size (u32LE), entry count (u32LE), then a
     * UTF-16LE name and value for each entry, both length-prefixed in UTF-16
     * code units and padded to a four-byte boundary.
     */
    static List<ChromiumCustomEntry> parseChromiumCustomData(byte[] data) {
        if (data == null || data.length < 12) {
            return List.of();
        }
        List<ChromiumCustomEntry> entries = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int payloadSize = buffer.getInt();
        int count = buffer.getInt();
        if (count < 0 || count > 256) {
            return List.of();
        }
        int limit = Math.min(data.length, 4 + Math.max(0, payloadSize));
        while (count-- > 0 && buffer.remaining() >= 4) {
            String name = readPickleString(data, buffer, limit);
            if (name == null) {
                return entries;
            }
            String value = readPickleString(data, buffer, limit);
            if (value == null) {
                return entries;
            }
            entries.add(new ChromiumCustomEntry(name, value));
        }
        return entries;
    }

    private static String readPickleString(byte[] data, ByteBuffer buffer, int limit) {
        if (buffer.remaining() < 4) {
            return null;
        }
        int units = buffer.getInt();
        if (units < 0 || units > MAX_VALUE_CHARS || units > (limit - buffer.position()) / 2) {
            return null;
        }
        int bytes = units * 2;
        String value = new String(data, buffer.position(), bytes, StandardCharsets.UTF_16LE);
        buffer.position(buffer.position() + bytes);
        int aligned = (buffer.position() + 3) & ~3;
        if (aligned > limit || aligned > data.length) {
            return null;
        }
        buffer.position(aligned);
        return value;
    }

    private static boolean containsAuthlib(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("authlib");
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static String decode(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        // Windows clipboard formats such as text/x-moz-url may be UTF-16LE
        // without a BOM. Detect the characteristic zero high bytes.
        if (bytes.length >= 4) {
            int evenZeros = 0;
            int oddZeros = 0;
            int pairs = bytes.length / 2;
            for (int i = 0; i + 1 < bytes.length; i += 2) {
                if (bytes[i] == 0) {
                    evenZeros++;
                }
                if (bytes[i + 1] == 0) {
                    oddZeros++;
                }
            }
            if (oddZeros > pairs / 2 && evenZeros == 0) {
                return new String(bytes, StandardCharsets.UTF_16LE);
            }
            if (evenZeros > pairs / 2 && oddZeros == 0) {
                return new String(bytes, StandardCharsets.UTF_16BE);
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
