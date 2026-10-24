package com.sslauncher.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one live dsh conversation: runtime provisioning, handshake, prompt
 * dispatch, event persistence and the automatic build after each turn.
 *
 * <p>dsh fixes provider/model per process, so changing the model or thinking
 * level stops the runtime, starts it again and resumes the same persisted
 * session. Stopping works the same way.</p>
 */
public final class AiConversation {
    public interface Listener {
        default void onSessionOpened(AiSession session) {
        }

        default void onEvent(JsonObject event) {
        }

        default void onStatus(String status) {
        }

        default void onProgress(String stage, String detail) {
        }

        default void onError(String message, boolean fatal) {
        }

        default void onBuild(AiSession session, JsonObject event, ModBuildService.Result result) {
        }

        default void onTitle(AiSession session) {
        }

        default void onSubagent(boolean started, String childId) {
        }
    }

    private static final Set<String> PERSISTED = Set.of(
            "user/message", "assistant/message", "tool/call", "tool/result",
            "turn/start", "turn/end", "session/title");
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");

    private final AiSessionStore store;
    private final Listener listener;
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ss-ai-control");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ss-ai-build");
        t.setDaemon(true);
        return t;
    });
    private final ModBuildService buildService = new ModBuildService();
    private final AtomicLong generation = new AtomicLong();
    private final Object buildLock = new Object();

    private volatile AiSession current;
    private volatile DshClient client;
    private volatile boolean building;
    private volatile boolean buildPending;
    private volatile boolean buildPendingForce;
    private volatile boolean closing;

    public AiConversation(AiSessionStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    public AiSession currentSession() {
        return current;
    }

    public boolean isRunning() {
        return client != null && client.alive();
    }

    public List<JsonObject> history() {
        AiSession session = current;
        return session == null ? List.of() : store.loadEvents(session);
    }

    // --------------------------------------------------------------- lifecycle

    /** Downloads/validates the dsh runtime. Safe to call while the user is on the setup screen. */
    public void prepare(Progress progress) {
        control.execute(() -> {
            try {
                progress.setStage("检查 AI 运行时");
                DshRuntime.ensure(progress);
                progress.setDetail("AI 运行时已就绪");
            } catch (Exception e) {
                Log.warn("AI 运行时准备失败: " + e.getMessage());
                Platform.runLater(() -> listener.onError("AI 运行时准备失败：\n" + e.getMessage(), true));
            }
        });
    }

    public void create(ModTarget target, Path workspace, Runnable created) {
        control.execute(() -> {
            AiSession session = store.create(target, workspace);
            current = session;
            Platform.runLater(() -> {
                listener.onSessionOpened(session);
                listener.onStatus("starting");
                if (created != null) {
                    created.run();
                }
            });
            try {
                openInternal(session);
            } catch (Exception e) {
                failStart(e);
            }
        });
    }

    public void open(AiSession session) {
        if (session == null) {
            return;
        }
        control.execute(() -> {
            current = session;
            Platform.runLater(() -> {
                listener.onSessionOpened(session);
                listener.onStatus("starting");
            });
            try {
                openInternal(session);
            } catch (Exception e) {
                failStart(e);
            }
        });
    }

    private void openInternal(AiSession session) throws Exception {
        closeClientInternal();
        if (session.workspace == null || session.workspace.isBlank()) {
            throw new IOException("会话没有工作区，请重新创建会话");
        }
        Path workspace = Path.of(session.workspace);
        if (!Files.isDirectory(workspace)) {
            throw new IOException("工作区不存在：" + workspace);
        }
        AiConfig config = AiConfig.get();
        if (!config.isConfigured()) {
            throw new IOException("还没有配置 API Key 与模型");
        }

        Progress progress = new Progress();
        progress.addListener(p -> {
            String stage = p.getStage();
            String detail = p.getDetail();
            Platform.runLater(() -> listener.onProgress(stage, detail));
        });
        DshRuntime.Installation installation = DshRuntime.ensure(progress);
        Path patch = DshPatch.write(config, session.target, workspace, session.id);

        session.provider = config.provider;
        session.model = config.model;
        session.reasoningEffort = config.reasoningEffort;
        store.touch(session);

        long gen = generation.incrementAndGet();
        DshClient created = DshClient.launch(installation, workspace, patch, config,
                new ClientListener(gen, session));
        client = created;
        try {
            created.initialize(workspace, config.routeId(), config.model, config.reasoningEffort, 0);
            if (session.dshCreated) {
                try {
                    created.resume(session.id);
                    created.setWorkingDirectory(session.id, workspace);
                } catch (IOException resumeError) {
                    if (missingSession(resumeError)) {
                        session.dshCreated = false;
                        store.touch(session);
                        AiLog.write("runtime.log", "resume missing, starting fresh: " + resumeError.getMessage());
                    } else {
                        throw resumeError;
                    }
                }
            }
        } catch (Exception e) {
            if (client == created) {
                client = null;
            }
            created.close();
            throw e;
        }
        Platform.runLater(() -> listener.onStatus("idle"));
    }

    private static boolean missingSession(IOException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("not found") || message.contains("missing")
                || message.contains("enoent") || message.contains("不存在")
                || message.contains("no such") || message.contains("unknown session");
    }

    private void failStart(Exception e) {
        Log.warn("AI 会话启动失败: " + e.getMessage());
        client = null;
        Platform.runLater(() -> listener.onError("AI 会话启动失败：\n" + e.getMessage(), false));
    }

    public void stop() {
        control.execute(() -> {
            closeClientInternal();
            Platform.runLater(() -> listener.onStatus("idle"));
        });
    }

    /** Called after settings change; resumes the same session on the new route. */
    public void restart() {
        control.execute(() -> {
            AiSession session = current;
            if (session == null) {
                return;
            }
            Platform.runLater(() -> listener.onStatus("starting"));
            try {
                openInternal(session);
            } catch (Exception e) {
                failStart(e);
            }
        });
    }

    public void close() {
        closing = true;
        buildService.cancel();
        control.execute(this::closeClientInternal);
        control.shutdown();
        builder.shutdownNow();
    }

    private void closeClientInternal() {
        generation.incrementAndGet();
        DshClient c = client;
        client = null;
        if (c != null) {
            c.close();
        }
    }

    // ------------------------------------------------------------------ prompt

    public void send(String text, List<Path> attachments) {
        control.execute(() -> {
            try {
                AiSession session = current;
                if (session == null) {
                    throw new IOException("请先选择游戏版本并创建工作区");
                }
                if (client == null || !client.alive()) {
                    Platform.runLater(() -> listener.onStatus("starting"));
                    openInternal(session);
                }
                JsonArray blocks = contentBlocks(session, text, attachments);
                if (blocks.size() == 0) {
                    return;
                }
                Platform.runLater(() -> listener.onStatus("running"));
                DshClient c = client;
                if (c == null) {
                    throw new IOException("AI 运行时未启动");
                }
                c.prompt(session.id, blocks);
                if (!session.dshCreated) {
                    session.dshCreated = true;
                }
                store.touch(session);
            } catch (Exception e) {
                Log.warn("AI 提示词发送失败: " + e.getMessage());
                Platform.runLater(() -> {
                    listener.onStatus("idle");
                    listener.onError("发送失败：\n" + e.getMessage(), false);
                });
            }
        });
    }

    private JsonArray contentBlocks(AiSession session, String text, List<Path> attachments) throws IOException {
        JsonArray blocks = new JsonArray();
        StringBuilder body = new StringBuilder(text == null ? "" : text.trim());
        List<Path> images = new ArrayList<>();
        if (attachments != null && !attachments.isEmpty()) {
            Path uploads = Path.of(session.workspace).resolve(".ss-ai").resolve("uploads");
            Files.createDirectories(uploads);
            for (Path file : attachments) {
                if (file == null || !Files.isRegularFile(file)) {
                    continue;
                }
                String mime = mimeOf(file);
                long size = Files.size(file);
                if (IMAGE_TYPES.contains(mime) && size <= 8L * 1024 * 1024) {
                    images.add(file);
                    continue;
                }
                String name = safeName(file.getFileName().toString());
                Path target = uploads.resolve(System.currentTimeMillis() + "-" + name);
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                body.append("用户上传了参考文件：").append(target.toAbsolutePath())
                        .append("（只读，不要修改）");
            }
        }
        if (body.length() == 0 && images.isEmpty()) {
            return blocks;
        }
        if (body.length() == 0) {
            body.append("请查看我上传的图片。");
        }
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", body.toString());
        blocks.add(textBlock);
        for (Path image : images) {
            byte[] data = Files.readAllBytes(image);
            JsonObject block = new JsonObject();
            block.addProperty("type", "image");
            block.addProperty("data", Base64.getEncoder().encodeToString(data));
            block.addProperty("mimeType", mimeOf(image));
            blocks.add(block);
        }
        return blocks;
    }

    private static String mimeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        try {
            String probe = Files.probeContentType(file);
            return probe == null ? "application/octet-stream" : probe;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static String safeName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (clean.isBlank()) {
            clean = "file";
        }
        return clean.length() > 80 ? clean.substring(clean.length() - 80) : clean;
    }

    // ------------------------------------------------------------------ events

    private final class ClientListener implements DshClient.Listener {
        private final long gen;
        private final AiSession session;

        private ClientListener(long gen, AiSession session) {
            this.gen = gen;
            this.session = session;
        }

        private boolean stale() {
            return gen != generation.get();
        }

        @Override
        public void onNotification(String method, JsonObject params) {
            if (stale() || closing) {
                return;
            }
            String sessionId = str(params, "sessionId");
            if (!session.id.equals(sessionId)) {
                return;
            }
            switch (method) {
                case "session.status" -> {
                    String status = str(params, "status");
                    Platform.runLater(() -> listener.onStatus(status));
                }
                case "session.event" -> handleEvent(params.getAsJsonObject("event"), session);
                case "subagent.started" -> {
                    String child = str(params, "childSessionId");
                    Platform.runLater(() -> listener.onSubagent(true, child));
                }
                case "subagent.finished" -> {
                    String child = str(params, "childSessionId");
                    Platform.runLater(() -> listener.onSubagent(false, child));
                }
                default -> {
                }
            }
        }

        private void handleEvent(JsonObject event, AiSession session) {
            if (event == null) {
                return;
            }
            String type = str(event, "type");
            if (type.isBlank()) {
                return;
            }
            if ("session/title".equals(type)) {
                JsonObject data = object(event, "data");
                String title = str(data, "title");
                if (!title.isBlank()) {
                    store.applyTitle(session, title);
                    Platform.runLater(() -> listener.onTitle(session));
                }
                return;
            }
            if (!PERSISTED.contains(type)) {
                return;
            }
            store.appendEvent(session, event);
            Platform.runLater(() -> listener.onEvent(event));
            if ("turn/end".equals(type)) {
                JsonObject data = object(event, "data");
                JsonObject reason = object(data, "reason");
                String kind = str(reason, "kind");
                if ("error".equals(kind)) {
                    JsonObject error = object(reason, "error");
                    String message = str(error, "message");
                    if (message.isBlank()) {
                        message = "模型请求失败";
                    }
                    String text = message;
                    Platform.runLater(() -> listener.onError(text, false));
                }
                store.touch(session);
                maybeBuild(session);
            }
        }

        @Override
        public void onExit(int code, String stderrTail) {
            if (stale() || closing) {
                return;
            }
            client = null;
            String tail = stderrTail == null ? "" : stderrTail;
            if (tail.length() > 1200) {
                tail = tail.substring(tail.length() - 1200);
            }
            String message = "AI 运行时已退出（退出码 " + code + "）。"
                    + (tail.isBlank() ? "" : "\n\n" + tail);
            Platform.runLater(() -> {
                listener.onStatus("idle");
                listener.onError(message, false);
            });
        }
    }

    // ------------------------------------------------------------------- build

    /** Manual retry used by the build-failure card. */
    public void rebuild() {
        AiSession session = current;
        if (session == null) {
            return;
        }
        synchronized (buildLock) {
            if (building) {
                buildPending = true;
                buildPendingForce = true;
                return;
            }
            building = true;
        }
        builder.execute(() -> runBuild(session, true));
    }

    private void maybeBuild(AiSession session) {
        synchronized (buildLock) {
            if (building) {
                buildPending = true;
                return;
            }
            building = true;
        }
        builder.execute(() -> runBuild(session, false));
    }

    private void runBuild(AiSession session, boolean force) {
        ModBuildService.Result result;
        try {
            Path workspace = Path.of(session.workspace);
            result = buildService.build(workspace, session.target,
                    force ? 0 : session.lastSourceMtime);
        } catch (Exception e) {
            result = new ModBuildService.Result(false, false, null, -1, 0,
                    "构建无法启动：" + e.getMessage(), "", 0);
        }
        boolean skipped = result.skipped();
        if (!skipped) {
            session.lastBuildAt = System.currentTimeMillis();
            session.lastSourceMtime = result.sourceMtime();
            session.lastBuildPath = result.artifact() == null ? "" : result.artifact().toAbsolutePath().toString();
            store.touch(session);
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", "ss/build");
        event.addProperty("time", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        data.addProperty("success", result.success());
        data.addProperty("skipped", skipped);
        data.addProperty("summary", result.summary());
        if (result.artifact() != null) {
            data.addProperty("artifact", result.artifact().toAbsolutePath().toString());
        }
        event.add("data", data);
        if (!skipped) {
            store.appendEvent(session, event);
            ModBuildService.Result delivered = result;
            Platform.runLater(() -> listener.onBuild(session, event, delivered));
        }

        boolean again;
        boolean againForce;
        synchronized (buildLock) {
            building = false;
            again = buildPending;
            againForce = buildPendingForce;
            buildPending = false;
            buildPendingForce = false;
            if (again) {
                building = true;
            }
        }
        if (again) {
            boolean retryForce = againForce;
            builder.execute(() -> runBuild(session, retryForce));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(name);
    }

    private static String str(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        try {
            JsonElement element = object.get(name);
            return element.isJsonPrimitive() ? element.getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
