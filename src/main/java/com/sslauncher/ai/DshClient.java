package com.sslauncher.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Newline-delimited JSON-RPC client for {@code dsh --profile sdk}, the same
 * wire protocol as the official DeepSeek Harness SDK. The runtime owns the
 * agent loop; this class only spawns it, performs the handshake, and forwards
 * the session events that the UI renders.
 */
public final class DshClient implements AutoCloseable {
    public static final String PROTOCOL_NAME = "deepseek-harness-sdk-runtime";

    /** Notification and lifecycle callback, invoked in protocol order. */
    public interface Listener {
        default void onNotification(String method, JsonObject params) {
        }

        default void onExit(int code, String stderrTail) {
        }

        default void onMalformedFrame(String line) {
        }
    }

    /**
     * NDJSON requires exactly one object per line. The app-wide Gson instance
     * pretty-prints, which would split every frame across many lines.
     */
    private static final Gson WIRE_JSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static final Duration INIT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration RESUME_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final Process process;
    private final BufferedWriter stdin;
    private final Thread stdoutThread;
    private final Thread stderrThread;
    private final ExecutorService dispatcher;
    private final Listener listener;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean eof;
    private volatile String lastStderr = "";

    private DshClient(Process process, Listener listener, String label) {
        this.process = process;
        this.listener = listener == null ? new Listener() {
        } : listener;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ss-ai-events");
            t.setDaemon(true);
            return t;
        });
        this.stdoutThread = new Thread(() -> readStdout(label), "ss-ai-stdout");
        this.stdoutThread.setDaemon(true);
        this.stderrThread = new Thread(() -> readStderr(label), "ss-ai-stderr");
        this.stderrThread.setDaemon(true);
        this.stdoutThread.start();
        this.stderrThread.start();
        Thread watcher = new Thread(() -> watchExit(), "ss-ai-exit");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * Starts the runtime with the generated patch and waits until the process
     * is accepting requests. The caller is responsible for {@link #initialize}.
     */
    public static DshClient launch(DshRuntime.Installation installation, Path workspace,
                                   Path patchFile, AiConfig config, Listener listener) throws IOException {
        AiPaths.init();
        List<String> command = new ArrayList<>();
        command.add(installation.node().toString());
        command.add(installation.bin().toString());
        command.add("--profile");
        command.add("sdk");
        if (patchFile != null) {
            command.add("--patch");
            command.add(patchFile.toAbsolutePath().toString());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        Map<String, String> env = builder.environment();
        env.put("DSH_HOME", installation.home().toAbsolutePath().toString());
        env.put("DSH_TELEMETRY_DISABLED", "1");
        env.put("NO_COLOR", "1");
        env.put("FORCE_COLOR", "0");
        env.remove("ELECTRON_RUN_AS_NODE");
        if (config != null && config.apiKey != null && !config.apiKey.isBlank()) {
            env.put(config.apiKeyEnvName(), config.apiKey);
        }
        Process process = builder.start();
        DshClient client = new DshClient(process, listener, "runtime");
        AiLog.write("runtime.log", "spawned dsh " + DshRuntime.DSH_VERSION + " pid=" + process.pid()
                + " workspace=" + workspace);
        return client;
    }

    // -------------------------------------------------------------- handshake

    public JsonObject initialize(Path cwd, String provider, String model,
                                 String reasoningEffort, int maxTokens) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd.toAbsolutePath().toString());
        params.addProperty("provider", provider);
        params.addProperty("model", model);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            params.addProperty("reasoningEffort", reasoningEffort);
        }
        if (maxTokens > 0) {
            params.addProperty("maxTokens", maxTokens);
        }
        JsonObject result = call("initialize", params, INIT_TIMEOUT);
        JsonObject info = result == null ? null : result.getAsJsonObject("serverInfo");
        if (info != null && info.has("name")) {
            String name = str(info, "name");
            if (!PROTOCOL_NAME.equals(name)) {
                AiLog.write("runtime.log", "unexpected serverInfo.name=" + name);
            }
        }
        return result;
    }

    public JsonObject resume(String sessionId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        return call("session/resume", params, RESUME_TIMEOUT);
    }

    public JsonObject prompt(String sessionId, JsonArray contentBlocks) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.add("contentBlocks", contentBlocks);
        return call("session/prompt", params, REQUEST_TIMEOUT);
    }

    public JsonObject waitForSession(String sessionId, Duration timeout) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        return call("session/wait", params, timeout == null ? Duration.ofHours(24) : timeout);
    }

    public String workingDirectory(String sessionId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        return str(call("session/working-directory/get", params, REQUEST_TIMEOUT), "cwd");
    }

    public String setWorkingDirectory(String sessionId, Path directory) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.addProperty("path", directory.toAbsolutePath().toString());
        return str(call("session/working-directory/set", params, REQUEST_TIMEOUT), "cwd");
    }

    public boolean alive() {
        return !closed.get() && process.isAlive() && !eof;
    }

    public String stderrTail() {
        String value = lastStderr;
        return value == null ? "" : value;
    }

    // -------------------------------------------------------------- transport

    public JsonObject call(String method, JsonObject params, Duration timeout) throws IOException {
        if (closed.get()) {
            throw new IOException("AI 运行时已关闭");
        }
        long id = ids.incrementAndGet();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.addProperty("id", id);
        frame.addProperty("method", method);
        frame.add("params", params == null ? new JsonObject() : params);
        try {
            write(frame);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("AI 运行时响应超时：" + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new IOException("已取消：" + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause == null ? "AI 运行时请求失败" : cause.getMessage(), cause);
        }
    }

    private void write(JsonObject frame) throws IOException {
        String json = WIRE_JSON.toJson(frame);
        synchronized (writeLock) {
            if (closed.get()) {
                throw new IOException("AI 运行时已关闭");
            }
            stdin.write(json);
            stdin.newLine();
            stdin.flush();
        }
    }

    private void readStdout(String label) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject frame;
                try {
                    JsonElement element = WIRE_JSON.fromJson(line, JsonElement.class);
                    if (element == null || !element.isJsonObject()) {
                        malformed(line);
                        continue;
                    }
                    frame = element.getAsJsonObject();
                } catch (Exception e) {
                    malformed(line);
                    continue;
                }
                dispatch(frame);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                AiLog.write("runtime.log", "stdout read failed: " + e.getMessage());
            }
        } finally {
            eof = true;
        }
    }

    private void malformed(String line) {
        AiLog.write("runtime.log", "malformed frame: "
                + (line.length() > 400 ? line.substring(0, 400) + "…" : line));
        try {
            listener.onMalformedFrame(line);
        } catch (Throwable ignored) {
        }
    }

    private void dispatch(JsonObject frame) {
        boolean hasId = frame.has("id") && !frame.get("id").isJsonNull();
        boolean hasMethod = frame.has("method") && !frame.get("method").isJsonNull();
        if (hasId && hasMethod) {
            handleServerRequest(frame);
            return;
        }
        if (hasId) {
            handleResponse(frame);
            return;
        }
        if (hasMethod) {
            String method = str(frame, "method");
            JsonObject params = frame.has("params") && frame.get("params").isJsonObject()
                    ? frame.getAsJsonObject("params") : new JsonObject();
            dispatcher.execute(() -> {
                try {
                    listener.onNotification(method, params);
                } catch (Throwable t) {
                    AiLog.write("runtime.log", "notification handler failed (" + method + "): " + t);
                }
            });
        }
    }

    private void handleResponse(JsonObject frame) {
        long id;
        try {
            id = frame.get("id").getAsLong();
        } catch (Exception e) {
            return;
        }
        CompletableFuture<JsonObject> future = pending.remove(id);
        if (future == null) {
            return;
        }
        if (frame.has("error") && frame.get("error").isJsonObject()) {
            JsonObject error = frame.getAsJsonObject("error");
            String message = str(error, "message");
            if (message.isBlank()) {
                message = "AI 运行时返回错误";
            }
            int code = error.has("code") ? error.get("code").getAsInt() : 0;
            future.completeExceptionally(new IOException(message + (code == 0 ? "" : "（" + code + "）")));
            return;
        }
        JsonElement result = frame.get("result");
        future.complete(result != null && result.isJsonObject()
                ? result.getAsJsonObject() : new JsonObject());
    }

    /**
     * The published SDK runtime sends no server-to-client requests today. If a
     * newer runtime asks for approval or a user question, answer immediately so
     * the agent never blocks on an invisible prompt; workspace writes remain
     * allowed by the sandbox and anything outside it is denied.
     */
    private void handleServerRequest(JsonObject frame) {
        JsonElement id = frame.get("id");
        String method = str(frame, "method");
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        if ("approval/request".equals(method)) {
            JsonObject result = new JsonObject();
            result.addProperty("outcome", "rejected");
            response.add("result", result);
            AiLog.write("runtime.log", "auto-rejected approval request");
        } else if ("user-question/ask".equals(method)) {
            JsonObject error = new JsonObject();
            error.addProperty("code", -32601);
            error.addProperty("message", "该交互未在启动器中启用");
            response.add("error", error);
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("code", -32601);
            error.addProperty("message", "Unsupported request: " + method);
            response.add("error", error);
        }
        try {
            write(response);
        } catch (IOException e) {
            AiLog.write("runtime.log", "failed to answer server request " + method + ": " + e.getMessage());
        }
    }

    private void readStderr(String label) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.trim();
                if (clean.isEmpty()) {
                    continue;
                }
                synchronized (stderrTail) {
                    stderrTail.addLast(clean);
                    while (stderrTail.size() > 80) {
                        stderrTail.removeFirst();
                    }
                    lastStderr = String.join("\n", stderrTail);
                }
                AiLog.write("runtime.log", clean);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                AiLog.write("runtime.log", "stderr read failed: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Sends {@code shutdown}, closes stdin (the app's EOF path), then escalates
     * to a forced kill. Pending calls fail immediately.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.addProperty("id", ids.incrementAndGet());
        frame.addProperty("method", "shutdown");
        frame.add("params", new JsonObject());
        synchronized (writeLock) {
            try {
                stdin.write(WIRE_JSON.toJson(frame));
                stdin.newLine();
                stdin.flush();
            } catch (IOException ignored) {
            }
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
        }
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        for (Long id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<JsonObject> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(new IOException("AI 运行时已关闭"));
            }
        }
        dispatcher.shutdown();
        try {
            dispatcher.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dispatcher.shutdownNow();
    }

    /**
     * Reports process exit only after stdout has drained, so the UI sees every
     * final session event before the "runtime stopped" notice.
     */
    private void watchExit() {
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            stdoutThread.join(3000);
            stderrThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closed.get()) {
            return;
        }
        String tail = stderrTail();
        try {
            dispatcher.execute(() -> {
                try {
                    listener.onExit(code, tail);
                } catch (Throwable t) {
                    AiLog.write("runtime.log", "exit handler failed: " + t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private static String str(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        try {
            return object.get(name).getAsString();
        } catch (Exception e) {
            return "";
        }
    }
}
