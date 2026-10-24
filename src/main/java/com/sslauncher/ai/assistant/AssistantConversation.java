package com.sslauncher.ai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.ai.AiConfig;
import com.sslauncher.ai.AiPaths;
import com.sslauncher.ai.AiSession;
import com.sslauncher.ai.AiSessionStore;
import com.sslauncher.ai.AssistantPrompts;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Log;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The AI 助手 conversation loop. Unlike AI 制作, this does not start an
 * external agent runtime: it calls the configured model API directly and
 * executes the launcher tools on its own worker thread, inside the launcher
 * process.
 */
public final class AssistantConversation {
    public interface Listener {
        default void onSessionOpened(AiSession session) {
        }

        default void onEvent(JsonObject event) {
        }

        default void onStatus(String status) {
        }

        default void onError(String message, boolean fatal) {
        }

        default void onTitle(AiSession session) {
        }
    }

    private static final int MAX_STEPS = 16;
    private static final int MAX_TOOL_RESULT_CHARS = 20_000;
    private static final int MAX_ERROR_CHARS = 4_000;

    private final AiSessionStore store;
    private final Listener listener;
    private final LauncherTools tools;
    private final Consumer<Runnable> uiExecutor;
    private final List<AssistantModel.Message> history = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ss-ai-assistant");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean turnActive = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile AiSession current;
    private volatile Future<?> turn;
    private volatile boolean closing;

    public AssistantConversation(AiSessionStore store, Listener listener, LauncherTools tools) {
        this(store, listener, tools, Platform::runLater);
    }

    /** Headless probes pass a direct executor instead of the JavaFX thread. */
    AssistantConversation(AiSessionStore store, Listener listener, LauncherTools tools,
                          Consumer<Runnable> uiExecutor) {
        this.store = store;
        this.listener = listener;
        this.tools = tools == null ? new LauncherTools(AssistantUi.HEADLESS) : tools;
        this.uiExecutor = uiExecutor == null ? Platform::runLater : uiExecutor;
    }

    private void runOnUi(Runnable action) {
        uiExecutor.accept(action);
    }

    public AiSession currentSession() {
        return current;
    }

    public boolean isRunning() {
        return turnActive.get();
    }

    // --------------------------------------------------------------- lifecycle

    public void createAssistant(Runnable created) {
        if (closing) {
            return;
        }
        cancelCurrentTurn();
        worker.execute(() -> {
            if (closing) {
                return;
            }
            AiSession session = store.createAssistant();
            current = session;
            history.clear();
            runOnUi(() -> {
                if (closing) {
                    return;
                }
                if (created != null) {
                    created.run();
                }
                listener.onSessionOpened(session);
                listener.onStatus("idle");
            });
        });
    }

    public void open(AiSession session) {
        if (session == null || closing) {
            return;
        }
        cancelCurrentTurn();
        worker.execute(() -> {
            if (closing) {
                return;
            }
            current = session;
            history.clear();
            history.addAll(loadMessages(session));
            runOnUi(() -> {
                if (closing) {
                    return;
                }
                listener.onSessionOpened(session);
                listener.onStatus("idle");
            });
        });
    }

    /** Settings are read for every request, so a restart only reloads the session. */
    public void restart() {
        open(current);
    }

    public void stop() {
        cancelCurrentTurn();
    }

    private void cancelCurrentTurn() {
        cancelRequested.set(true);
        Future<?> running = turn;
        if (running != null) {
            running.cancel(true);
        }
    }

    public void close() {
        closing = true;
        cancelCurrentTurn();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ prompt

    public void send(String text, List<Path> attachments) {
        if (text == null || text.isBlank() || closing) {
            return;
        }
        AiSession session = current;
        if (session == null) {
            runOnUi(() -> listener.onError("请先新建对话。", false));
            return;
        }
        AiConfig config = AiConfig.get();
        if (!config.isConfigured()) {
            runOnUi(() -> listener.onError(
                    "还没有配置 API Key 与模型。请到「设置 → AI 助手与制作」填写后再试。", false));
            return;
        }
        if (!turnActive.compareAndSet(false, true)) {
            runOnUi(() -> listener.onError("上一条回复正在结束，请稍候再发送。", false));
            return;
        }
        cancelRequested.set(false);
        String prompt = text.trim();
        runOnUi(() -> listener.onStatus("running"));
        turn = worker.submit(() -> runTurn(session, prompt));
    }

    private void runTurn(AiSession session, String prompt) {
        try {
            if (closing) {
                return;
            }
            if (session.title == null || session.title.isBlank()) {
                String title = titleFor(prompt);
                store.applyTitle(session, title);
                runOnUi(() -> listener.onTitle(session));
            }
            appendUser(session, prompt);
            emit(session, turnStart());

            for (int step = 0; step < MAX_STEPS; step++) {
                if (cancelled()) {
                    finishCancelled(session);
                    return;
                }
                AiConfig config = AiConfig.get();
                if (!config.isConfigured()) {
                    throw new IOException("API Key 或模型配置已失效，请到设置中检查。");
                }
                AssistantModel.Reply reply = AssistantModel.complete(
                        config, systemPrompt(config), history, tools.all());
                if (cancelled()) {
                    finishCancelled(session);
                    return;
                }
                List<AssistantModel.ToolCall> calls = reply.calls() == null
                        ? List.of() : reply.calls();
                String replyText = nullSafe(reply.text());
                if (calls.isEmpty() && replyText.isBlank()
                        && nullSafe(reply.reasoning()).isBlank()) {
                    fail(session, "模型没有返回内容，请重试或更换模型。");
                    return;
                }
                history.add(AssistantModel.Message.assistant(
                        replyText, nullSafe(reply.reasoning()), calls));
                saveMessages(session);
                emit(session, assistantEvent(config, replyText, reply.reasoning()));

                if (calls.isEmpty()) {
                    finishCompleted(session);
                    return;
                }
                for (AssistantModel.ToolCall call : calls) {
                    if (cancelled()) {
                        finishCancelled(session);
                        return;
                    }
                    emit(session, toolCallEvent(call));
                    ToolResult result = execute(call);
                    history.add(AssistantModel.Message.tool(
                            call.id(), call.name(), result.text(), result.error()));
                    saveMessages(session);
                    emit(session, toolResultEvent(call, result));
                    if (cancelled()) {
                        finishCancelled(session);
                        return;
                    }
                }
            }
            fail(session, "助手连续调用工具次数过多，已停止。可以缩小任务范围后重试。");
        } catch (InterruptedException e) {
            Thread.interrupted();
            finishCancelled(session);
        } catch (Exception e) {
            String message = safeMessage(e);
            Log.warn("AI 助手请求失败: " + Redact.text(message));
            fail(session, message);
        } finally {
            turnActive.set(false);
            turn = null;
            store.touch(session);
            saveMessages(session);
            if (!closing) {
                runOnUi(() -> listener.onStatus("idle"));
            }
        }
    }

    private boolean cancelled() {
        return cancelRequested.get() || closing;
    }

    // -------------------------------------------------------------------- tools

    private ToolResult execute(AssistantModel.ToolCall call) {
        if (call == null || call.name() == null || call.name().isBlank()) {
            return new ToolResult("模型返回了空的工具名。", true);
        }
        LauncherTools.Tool tool = tools.find(call.name());
        if (tool == null) {
            return new ToolResult("未知工具：" + call.name()
                    + "。只能使用启动器提供的工具。", true);
        }
        JsonObject arguments;
        String raw = call.arguments() == null || call.arguments().isBlank()
                ? "{}" : call.arguments();
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed == null || !parsed.isJsonObject()) {
                return new ToolResult("工具参数必须是 JSON 对象。", true);
            }
            arguments = parsed.getAsJsonObject();
        } catch (Exception e) {
            return new ToolResult("工具参数不是有效 JSON，无法执行。", true);
        }
        try {
            String output = tools.call(call.name(), arguments);
            return new ToolResult(cap(output, MAX_TOOL_RESULT_CHARS), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult("工具执行被中断。", true);
        } catch (Exception e) {
            return new ToolResult(cap(safeMessage(e), MAX_ERROR_CHARS), true);
        }
    }

    private record ToolResult(String text, boolean error) {
    }

    // ------------------------------------------------------------------- events

    private void appendUser(AiSession session, String prompt) {
        history.add(AssistantModel.Message.user(prompt));
        saveMessages(session);
        JsonObject event = new JsonObject();
        event.addProperty("type", "user/message");
        event.addProperty("time", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        JsonObject message = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("kind", "user");
        message.add("source", source);
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", prompt);
        content.add(text);
        message.add("content", content);
        data.add("message", message);
        event.add("data", data);
        emit(session, event);
    }

    private JsonObject assistantEvent(AiConfig config, String text, String reasoning) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "assistant/message");
        event.addProperty("time", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        JsonObject message = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("kind", "assistant");
        source.addProperty("model", config.model);
        message.add("source", source);
        JsonArray content = new JsonArray();
        if (!nullSafe(reasoning).isBlank()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "reasoning");
            block.addProperty("text", reasoning);
            content.add(block);
        }
        if (!nullSafe(text).isBlank()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text);
            content.add(block);
        }
        message.add("content", content);
        data.add("message", message);
        event.add("data", data);
        return event;
    }

    private JsonObject toolCallEvent(AssistantModel.ToolCall call) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "tool/call");
        event.addProperty("time", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        data.addProperty("callId", call.id());
        data.addProperty("name", call.name());
        data.addProperty("arguments", nullSafe(call.arguments()));
        event.add("data", data);
        return event;
    }

    private JsonObject toolResultEvent(AssistantModel.ToolCall call, ToolResult result) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "tool/result");
        event.addProperty("time", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("toolCallId", call.id());
        message.addProperty("isError", result.error());
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", nullSafe(result.text()));
        content.add(text);
        message.add("content", content);
        data.add("message", message);
        event.add("data", data);
        return event;
    }

    private JsonObject turnStart() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "turn/start");
        event.addProperty("time", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        data.addProperty("turnId", UUID.randomUUID().toString());
        event.add("data", data);
        return event;
    }

    private void finishCompleted(AiSession session) {
        emit(session, turnEnd("completed", ""));
    }

    private void finishCancelled(AiSession session) {
        emit(session, turnEnd("cancelled", ""));
    }

    private void fail(AiSession session, String message) {
        String clean = cap(Redact.text(message), MAX_ERROR_CHARS);
        emit(session, turnEnd("error", clean));
        runOnUi(() -> listener.onError(clean, false));
    }

    private JsonObject turnEnd(String kind, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "turn/end");
        event.addProperty("time", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        JsonObject reason = new JsonObject();
        reason.addProperty("kind", kind);
        if ("error".equals(kind)) {
            JsonObject error = new JsonObject();
            error.addProperty("message", message);
            reason.add("error", error);
        }
        data.add("reason", reason);
        event.add("data", data);
        return event;
    }

    private void emit(AiSession session, JsonObject event) {
        if (closing) {
            return;
        }
        store.appendEvent(session, event);
        if (!closing) {
            runOnUi(() -> {
                if (!closing) {
                    listener.onEvent(event);
                }
            });
        }
    }

    // ---------------------------------------------------------------- transcript

    private List<AssistantModel.Message> loadMessages(AiSession session) {
        Path file = AiPaths.sessionMessages(session.id);
        if (Files.isRegularFile(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (parsed != null && parsed.isJsonArray()) {
                    return parseMessages(parsed.getAsJsonArray());
                }
            } catch (Exception e) {
                Log.warn("AI 助手历史读取失败: " + e.getMessage());
            }
        }
        return reconstruct(session);
    }

    private List<AssistantModel.Message> parseMessages(JsonArray array) {
        List<AssistantModel.Message> messages = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String role = str(object, "role");
            if (role.isBlank()) {
                continue;
            }
            String text = str(object, "text");
            String reasoning = str(object, "reasoning");
            List<AssistantModel.ToolCall> calls = new ArrayList<>();
            if (object.has("calls") && object.get("calls").isJsonArray()) {
                for (JsonElement callElement : object.getAsJsonArray("calls")) {
                    if (callElement == null || !callElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject call = callElement.getAsJsonObject();
                    String name = str(call, "name");
                    if (name.isBlank()) {
                        continue;
                    }
                    calls.add(new AssistantModel.ToolCall(str(call, "id"), name,
                            str(call, "arguments")));
                }
            }
            messages.add(new AssistantModel.Message(role, text, reasoning, calls,
                    str(object, "toolCallId"), str(object, "toolName"),
                    bool(object, "error")));
        }
        return messages;
    }

    /**
     * Old assistant sessions written by the dsh runtime have no neutral
     * transcript file. Recover the user and assistant text; tool traffic is
     * omitted because it cannot be paired with its original call reliably.
     */
    private List<AssistantModel.Message> reconstruct(AiSession session) {
        List<AssistantModel.Message> messages = new ArrayList<>();
        for (JsonObject event : store.loadEvents(session)) {
            String type = str(event, "type");
            JsonObject data = object(event, "data");
            JsonObject message = object(data, "message");
            if ("user/message".equals(type)) {
                String kind = str(object(message, "source"), "kind");
                if (!"user".equals(kind)) {
                    continue;
                }
                String text = contentText(message);
                if (!text.isBlank()) {
                    messages.add(AssistantModel.Message.user(text));
                }
            } else if ("assistant/message".equals(type)) {
                StringBuilder text = new StringBuilder();
                StringBuilder reasoning = new StringBuilder();
                JsonArray content = array(message, "content");
                for (JsonElement element : content) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject block = element.getAsJsonObject();
                    String blockType = str(block, "type");
                    if ("text".equals(blockType)) {
                        append(text, str(block, "text"));
                    } else if ("reasoning".equals(blockType)) {
                        append(reasoning, str(block, "text"));
                    }
                }
                if (!text.isEmpty() || !reasoning.isEmpty()) {
                    messages.add(AssistantModel.Message.assistant(
                            text.toString(), reasoning.toString(), List.of()));
                }
            }
        }
        return messages;
    }

    private void saveMessages(AiSession session) {
        if (session == null || session.id == null || session.id.isBlank()) {
            return;
        }
        try {
            JsonArray array = new JsonArray();
            for (AssistantModel.Message message : history) {
                if (message == null || message.role() == null) {
                    continue;
                }
                JsonObject object = new JsonObject();
                object.addProperty("role", message.role());
                object.addProperty("text", nullSafe(message.text()));
                if (!nullSafe(message.reasoning()).isBlank()) {
                    object.addProperty("reasoning", message.reasoning());
                }
                if (!nullSafe(message.toolCallId()).isBlank()) {
                    object.addProperty("toolCallId", message.toolCallId());
                    object.addProperty("toolName", nullSafe(message.toolName()));
                }
                if (message.error()) {
                    object.addProperty("error", true);
                }
                if (message.calls() != null && !message.calls().isEmpty()) {
                    JsonArray calls = new JsonArray();
                    for (AssistantModel.ToolCall call : message.calls()) {
                        if (call == null) {
                            continue;
                        }
                        JsonObject item = new JsonObject();
                        item.addProperty("id", nullSafe(call.id()));
                        item.addProperty("name", nullSafe(call.name()));
                        item.addProperty("arguments", nullSafe(call.arguments()));
                        calls.add(item);
                    }
                    object.add("calls", calls);
                }
                array.add(object);
            }
            Files.createDirectories(AiPaths.sessionDir(session.id));
            AppPaths.atomicWrite(AiPaths.sessionMessages(session.id),
                    com.sslauncher.core.Json.GSON.toJson(array)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("AI 助手历史保存失败: " + e.getMessage());
        }
    }

    private String systemPrompt(AiConfig config) {
        String persona = AssistantPrompts.system();
        String model = config.model == null ? "" : config.model;
        return persona.replace("{{model}}", model)
                + "\n\n当前由 " + model + " 模型驱动。所有启动器工具都由启动器进程在本机直接执行。";
    }

    private static String titleFor(String prompt) {
        String line = prompt.replace('\n', ' ').replace('\r', ' ').trim();
        if (line.isBlank()) {
            return "新对话";
        }
        return line.length() <= 30 ? line : line.substring(0, 30) + "…";
    }

    // ------------------------------------------------------------------ helpers

    private static String contentText(JsonObject message) {
        StringBuilder text = new StringBuilder();
        for (JsonElement element : array(message, "content")) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if ("text".equals(str(block, "type"))) {
                append(text, str(block, "text"));
            }
        }
        return text.toString().trim();
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        return cap(Redact.text(message), MAX_ERROR_CHARS);
    }

    private static String cap(String value, int max) {
        String text = value == null ? "" : value;
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（内容过长，已截断）";
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(value);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
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

    private static boolean bool(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return false;
        }
        try {
            return object.get(name).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            return new JsonArray();
        }
        return parent.getAsJsonArray(name);
    }
}
