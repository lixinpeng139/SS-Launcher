package com.sslauncher.ai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.ai.AiConfig;
import com.sslauncher.ai.AiPaths;
import com.sslauncher.ai.AiProvider;
import com.sslauncher.ai.AiSession;
import com.sslauncher.ai.AiSessionStore;
import com.sslauncher.core.AppPaths;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end probe for the in-process AI 助手 loop. A loopback HTTP server
 * impersonates an OpenAI-compatible model; no real key or external request is
 * involved. Run with {@code ./gradlew assistantProbe}.
 */
public final class AssistantProbe {
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static volatile String failure = "";

    private AssistantProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args.length > 0 ? args[0] : "build/assistant-probe-home");
        Files.createDirectories(home);
        System.setProperty("ss.home", home.toAbsolutePath().toString());
        AppPaths.init();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                String response = respond(body);
                byte[] data = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            } catch (Exception e) {
                failure = e.toString();
                byte[] data = ("{\"error\":{\"message\":\""
                        + e.getMessage().replace("\"", "'") + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, data.length);
                exchange.getResponseBody().write(data);
            } finally {
                exchange.close();
            }
        });
        server.start();

        AiConfig config = AiConfig.get();
        config.provider = AiProvider.CUSTOM.id;
        config.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        config.model = "probe-model";
        config.reasoningEffort = "";
        config.apiKey = "sk-probe-not-a-real-key";

        AiSessionStore store = new AiSessionStore();
        LauncherTools tools = new LauncherTools(AssistantUi.HEADLESS);
        List<JsonObject> events = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<AiSession> opened = new AtomicReference<>();
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch turnLatch = new CountDownLatch(1);
        AssistantConversation.Listener listener = new AssistantConversation.Listener() {
            @Override
            public void onSessionOpened(AiSession session) {
                opened.set(session);
                openLatch.countDown();
            }

            @Override
            public void onEvent(JsonObject event) {
                events.add(event);
                if ("turn/end".equals(str(event, "type"))) {
                    turnLatch.countDown();
                }
            }

            @Override
            public void onStatus(String status) {
                statuses.add(status);
            }

            @Override
            public void onError(String message, boolean fatal) {
                error.set(message);
            }
        };
        AssistantConversation conversation = new AssistantConversation(
                store, listener, tools, Runnable::run);
        try {
            conversation.createAssistant(null);
            check(openLatch.await(10, TimeUnit.SECONDS), "assistant session opens");
            AiSession session = opened.get();
            check(session != null, "session is delivered to the page");
            check(session != null && session.isAssistant(), "session kind is assistant");

            conversation.send("请查看启动器状态", List.of());
            check(turnLatch.await(20, TimeUnit.SECONDS), "turn finishes");
            check(error.get() == null, "no error: " + error.get());
            check(failure.isBlank(), "fake model saw no error: " + failure);
            check(REQUESTS.get() == 2, "model is called twice (tool + final answer), was "
                    + REQUESTS.get());

            check(hasEvent(events, "user/message"), "user message is persisted");
            check(hasEvent(events, "tool/call"), "tool call is displayed");
            check(hasEvent(events, "tool/result"), "tool result is displayed");
            check(hasEvent(events, "turn/end"), "turn end is persisted");
            String toolResult = eventText(events, "tool/result");
            check(toolResult.contains("SS Launcher"), "launcher_status runs in-process");
            String finalText = assistantText(events);
            check(finalText.contains("已经查到"), "final model text is displayed");
            check(session != null && !"新对话".equals(session.displayTitle()),
                    "session title is derived from the first prompt");
            check(statuses.contains("running"), "running status is emitted");
            check(statuses.contains("idle"), "idle status is emitted");

            Path messages = AiPaths.sessionMessages(session.id);
            check(Files.isRegularFile(messages), "neutral transcript is written");
            String saved = Files.readString(messages, StandardCharsets.UTF_8);
            check(saved.contains("\"role\": \"tool\""), "tool result is in model history");
            check(saved.contains("\"launcher_status\""), "tool call is in model history");
            check(!saved.contains("sk-probe-not-a-real-key"), "API key is never persisted");

            // Persist the fake loopback config so a UI screenshot can open the
            // session created above. The key is a probe value, never a real one.
            AiConfig.save();
            System.out.println("Assistant probe passed: " + events.size() + " events, "
                    + REQUESTS.get() + " loopback model requests");
        } finally {
            conversation.close();
            server.stop(0);
        }
        if (!failure.isBlank() || error.get() != null) {
            throw new IllegalStateException("probe failed: " + failure + " / " + error.get());
        }
    }

    /** Two-step fake model: ask for launcher_status, then summarize the result. */
    private static String respond(String body) {
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        JsonArray messages = request.getAsJsonArray("messages");
        int call = REQUESTS.incrementAndGet();
        if (call == 1) {
            check(request.getAsJsonArray("tools").size() == 10, "all 10 tools are advertised");
            check(messages.get(messages.size() - 1).getAsJsonObject()
                    .get("role").getAsString().equals("user"), "first request ends with the user");
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("id", "call_probe");
            toolCall.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", "launcher_status");
            function.addProperty("arguments", "{}");
            toolCall.add("function", function);
            JsonArray calls = new JsonArray();
            calls.add(toolCall);
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", "");
            message.add("tool_calls", calls);
            JsonArray choices = new JsonArray();
            choices.add(new JsonObject());
            JsonObject first = new JsonObject();
            first.add("message", message);
            choices.set(0, first);
            JsonObject response = new JsonObject();
            response.add("choices", choices);
            return response.toString();
        }
        boolean toolSeen = false;
        for (int i = 0; i < messages.size(); i++) {
            JsonObject message = messages.get(i).getAsJsonObject();
            if ("tool".equals(message.get("role").getAsString())
                    && message.get("content").getAsString().contains("SS Launcher")) {
                toolSeen = true;
            }
        }
        check(toolSeen, "second model request carries the in-process tool result");
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", "已经查到启动器状态。");
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }

    private static boolean hasEvent(List<JsonObject> events, String type) {
        for (JsonObject event : events) {
            if (type.equals(str(event, "type"))) {
                return true;
            }
        }
        return false;
    }

    private static String assistantText(List<JsonObject> events) {
        StringBuilder text = new StringBuilder();
        for (JsonObject event : events) {
            if (!"assistant/message".equals(str(event, "type"))) {
                continue;
            }
            JsonObject message = event.getAsJsonObject("data").getAsJsonObject("message");
            JsonArray content = message.getAsJsonArray("content");
            for (int i = 0; i < content.size(); i++) {
                JsonObject block = content.get(i).getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) {
                    text.append(block.get("text").getAsString());
                }
            }
        }
        return text.toString();
    }

    private static String eventText(List<JsonObject> events, String type) {
        for (JsonObject event : events) {
            if (!type.equals(str(event, "type"))) {
                continue;
            }
            JsonObject message = event.getAsJsonObject("data").getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            JsonArray content = message.getAsJsonArray("content");
            for (int i = 0; i < content.size(); i++) {
                JsonObject block = content.get(i).getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) {
                    text.append(block.get("text").getAsString());
                }
            }
            return text.toString();
        }
        return "";
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("FAIL: " + message);
        }
        System.out.println("PASS: " + message);
    }
}
