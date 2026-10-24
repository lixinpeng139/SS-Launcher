package com.sslauncher.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Session index and per-session JSONL event log. The index is the only thing
 * the launcher needs in memory; history is replayed from the event log.
 */
public final class AiSessionStore {
    private static final class Index {
        List<AiSession> sessions = new ArrayList<>();
    }

    /** One JSON object per line; must not use the pretty-printing config. */
    private static final Gson EVENT_JSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final List<AiSession> sessions = new ArrayList<>();

    public AiSessionStore() {
        load();
    }

    private synchronized void load() {
        sessions.clear();
        if (!Files.isRegularFile(AiPaths.SESSION_INDEX)) {
            return;
        }
        try {
            Index index = Json.read(AiPaths.SESSION_INDEX, Index.class);
            if (index != null && index.sessions != null) {
                sessions.addAll(index.sessions);
            }
        } catch (IOException e) {
            Log.warn("AI 会话索引读取失败: " + e.getMessage());
        }
        sessions.removeIf(s -> s == null || s.id == null || s.id.isBlank());
        sessions.sort(Comparator.comparingLong((AiSession s) -> s.updatedAt).reversed());
    }

    public synchronized List<AiSession> all() {
        sessions.sort(Comparator.comparingLong((AiSession s) -> s.updatedAt).reversed());
        return new ArrayList<>(sessions);
    }

    public synchronized List<AiSession> ofKind(String kind) {
        String wanted = kind == null || kind.isBlank() ? AiSession.KIND_MOD : kind;
        List<AiSession> result = new ArrayList<>();
        for (AiSession session : all()) {
            if (wanted.equals(session.kind())) {
                result.add(session);
            }
        }
        return result;
    }

    public synchronized AiSession find(String id) {
        for (AiSession s : sessions) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    public synchronized AiSession create(ModTarget target, Path workspace) {
        AiSession session = new AiSession();
        session.id = UUID.randomUUID().toString();
        session.kind = AiSession.KIND_MOD;
        session.title = target == null ? "" : target.modName;
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        session.workspace = workspace == null ? "" : workspace.toAbsolutePath().toString();
        session.target = target == null ? new ModTarget() : target.copy();
        AiConfig c = AiConfig.get();
        session.provider = c.provider;
        session.model = c.model;
        session.reasoningEffort = c.reasoningEffort;
        sessions.add(session);
        try {
            Files.createDirectories(AiPaths.sessionDir(session.id));
        } catch (IOException e) {
            Log.warn("无法创建 AI 会话目录: " + e.getMessage());
        }
        save();
        return session;
    }

    /** Creates an assistant conversation whose scratch directory is launcher-owned. */
    public synchronized AiSession createAssistant() {
        AiSession session = new AiSession();
        session.id = UUID.randomUUID().toString();
        session.kind = AiSession.KIND_ASSISTANT;
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        Path workspace = AiPaths.assistantWorkspace(session.id);
        try {
            Files.createDirectories(workspace);
            Files.createDirectories(AiPaths.sessionDir(session.id));
        } catch (IOException e) {
            Log.warn("无法创建 AI 助手会话目录: " + e.getMessage());
        }
        session.workspace = workspace.toAbsolutePath().toString();
        AiConfig c = AiConfig.get();
        session.provider = c.provider;
        session.model = c.model;
        session.reasoningEffort = c.reasoningEffort;
        sessions.add(session);
        save();
        return session;
    }

    public synchronized void touch(AiSession session) {
        if (session == null) {
            return;
        }
        session.updatedAt = System.currentTimeMillis();
        save();
    }

    public synchronized void save() {
        try {
            AiPaths.init();
            Index index = new Index();
            index.sessions = new ArrayList<>(sessions);
            index.sessions.sort(Comparator.comparingLong((AiSession s) -> s.updatedAt).reversed());
            AppPaths.atomicWrite(AiPaths.SESSION_INDEX,
                    Json.GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("AI 会话索引保存失败: " + e.getMessage());
        }
    }

    public void appendEvent(AiSession session, JsonObject event) {
        if (session == null || event == null) {
            return;
        }
        synchronized (this) {
            try {
                Files.createDirectories(AiPaths.sessionDir(session.id));
                try (BufferedWriter out = Files.newBufferedWriter(AiPaths.sessionEvents(session.id),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    out.write(EVENT_JSON.toJson(event));
                    out.newLine();
                }
            } catch (IOException e) {
                Log.warn("AI 会话记录写入失败: " + e.getMessage());
            }
        }
    }

    public synchronized List<JsonObject> loadEvents(AiSession session) {
        List<JsonObject> events = new ArrayList<>();
        if (session == null || session.id == null || session.id.isBlank()) {
            return events;
        }
        Path file = AiPaths.sessionEvents(session.id);
        if (!Files.isRegularFile(file)) {
            return events;
        }
        // Read a stream of objects rather than lines: older builds wrote the
        // event log with pretty printing, so one event could span many lines.
        try (JsonReader reader = new JsonReader(
                Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            reader.setStrictness(Strictness.LENIENT);
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element != null && element.isJsonObject()) {
                    events.add(element.getAsJsonObject());
                }
            }
        } catch (IOException | JsonParseException e) {
            Log.warn("AI 会话记录读取失败: " + e.getMessage());
        }
        return events;
    }

    public synchronized void delete(AiSession session) {
        if (session == null) {
            return;
        }
        sessions.removeIf(s -> s.id.equals(session.id));
        save();
        deleteTree(AiPaths.sessionDir(session.id));
        // Assistant workspaces are launcher-owned scratch directories. Only
        // remove the canonical path derived from this session id, never a path
        // copied from a hand-edited session index.
        if (AiSession.KIND_ASSISTANT.equals(session.kind())) {
            deleteTree(AiPaths.assistantWorkspace(session.id));
        }
    }

    private static void deleteTree(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException e) {
            Log.warn("AI 会话目录删除失败: " + e.getMessage());
        }
    }

    /** Updates the display title from dsh's session/title event. */
    public synchronized void applyTitle(AiSession session, String title) {
        if (session == null || title == null || title.isBlank()) {
            return;
        }
        String clean = title.replace('\n', ' ').trim();
        if (clean.length() > 60) {
            clean = clean.substring(0, 60);
        }
        session.title = clean;
        session.updatedAt = System.currentTimeMillis();
        save();
    }
}
