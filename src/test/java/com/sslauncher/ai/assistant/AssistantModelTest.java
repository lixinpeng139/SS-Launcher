package com.sslauncher.ai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.ai.AiConfig;
import com.sslauncher.ai.AiProvider;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-format checks for the direct model client; never contacts a real API. */
class AssistantModelTest {
    private HttpServer server;
    private String base;
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private int status = 200;

    private String savedProvider;
    private String savedBaseUrl;
    private String savedModel;
    private String savedEffort;
    private String savedKey;

    @BeforeEach
    void setUp() throws IOException {
        AiConfig config = AiConfig.get();
        savedProvider = config.provider;
        savedBaseUrl = config.baseUrl;
        savedModel = config.model;
        savedEffort = config.reasoningEffort;
        savedKey = config.apiKey;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] data = (reply.get() == null ? "{}" : reply.get())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        AiConfig config = AiConfig.get();
        config.provider = savedProvider;
        config.baseUrl = savedBaseUrl;
        config.model = savedModel;
        config.reasoningEffort = savedEffort;
        config.apiKey = savedKey;
    }

    @Test
    void openAiTurnSendsToolsAndParsesToolCalls() throws Exception {
        AiConfig config = config(AiProvider.CUSTOM);
        reply.set("""
                {"choices":[{"message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"call_abc","type":"function",
                "function":{"name":"launcher_status","arguments":"{}"}}]}}]}
                """);

        AssistantModel.Reply result = AssistantModel.complete(
                config, "系统提示", List.of(AssistantModel.Message.user("看看状态")), tools());

        assertEquals("Bearer sk-probe-not-a-real-key", authorization.get());
        assertEquals("call_abc", result.calls().get(0).id());
        assertEquals("launcher_status", result.calls().get(0).name());
        assertEquals("{}", result.calls().get(0).arguments());

        JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertEquals("test-model", body.get("model").getAsString());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals(2, tools.size());
        assertEquals("launcher_status", tools.get(0).getAsJsonObject()
                .getAsJsonObject("function").get("name").getAsString());
        assertFalse(tools.get(0).getAsJsonObject().getAsJsonObject("function")
                .getAsJsonObject("parameters").get("additionalProperties").getAsBoolean());
    }

    @Test
    void openAiTurnReplaysToolResults() throws Exception {
        AiConfig config = config(AiProvider.CUSTOM);
        reply.set("""
                {"choices":[{"message":{"role":"assistant","content":"完成了"}}]}
                """);
        AssistantModel.Message assistant = AssistantModel.Message.assistant("", "",
                List.of(new AssistantModel.ToolCall("call_abc", "launcher_status", "{}")));
        AssistantModel.Message tool = AssistantModel.Message.tool(
                "call_abc", "launcher_status", "SS Launcher 1.0.0", false);

        AssistantModel.Reply result = AssistantModel.complete(config, "系统提示",
                List.of(AssistantModel.Message.user("看看状态"), assistant, tool), tools());

        assertEquals("完成了", result.text());
        JsonArray messages = JsonParser.parseString(requestBody.get()).getAsJsonObject()
                .getAsJsonArray("messages");
        JsonObject assistantEntry = messages.get(2).getAsJsonObject();
        assertEquals("assistant", assistantEntry.get("role").getAsString());
        assertEquals("call_abc", assistantEntry.getAsJsonArray("tool_calls").get(0)
                .getAsJsonObject().get("id").getAsString());
        JsonObject toolEntry = messages.get(3).getAsJsonObject();
        assertEquals("tool", toolEntry.get("role").getAsString());
        assertEquals("call_abc", toolEntry.get("tool_call_id").getAsString());
        assertEquals("SS Launcher 1.0.0", toolEntry.get("content").getAsString());
    }

    @Test
    void anthropicTurnSendsSystemAndParsesToolUse() throws Exception {
        AiConfig config = config(AiProvider.ANTHROPIC);
        reply.set("""
                {"content":[{"type":"thinking","thinking":"先查状态"},
                {"type":"text","text":"我来看看。"},
                {"type":"tool_use","id":"toolu_1","name":"launcher_status","input":{}}]}
                """);

        AssistantModel.Reply result = AssistantModel.complete(
                config, "系统提示", List.of(AssistantModel.Message.user("看看状态")), tools());

        assertEquals("sk-probe-not-a-real-key", apiKeyHeader.get());
        assertEquals("我来看看。", result.text());
        assertEquals("先查状态", result.reasoning());
        assertEquals("toolu_1", result.calls().get(0).id());
        assertEquals("launcher_status", result.calls().get(0).name());

        JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertEquals("系统提示", body.get("system").getAsString());
        assertEquals(8192, body.get("max_tokens").getAsInt());
        JsonObject definition = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("launcher_status", definition.get("name").getAsString());
        assertNotNull(definition.getAsJsonObject("input_schema"));
        assertFalse(definition.has("inputSchema"));
    }

    @Test
    void anthropicGroupsToolResultsInOneUserMessage() throws Exception {
        AiConfig config = config(AiProvider.ANTHROPIC);
        reply.set("""
                {"content":[{"type":"text","text":"完成了"}]}
                """);
        AssistantModel.Message assistant = AssistantModel.Message.assistant("", "",
                List.of(new AssistantModel.ToolCall("toolu_1", "launcher_status", "{}")));
        AssistantModel.Message first = AssistantModel.Message.tool(
                "toolu_1", "launcher_status", "状态正常", false);

        AssistantModel.Reply result = AssistantModel.complete(config, "系统提示",
                List.of(AssistantModel.Message.user("看看状态"), assistant, first), tools());

        assertEquals("完成了", result.text());
        JsonArray messages = JsonParser.parseString(requestBody.get()).getAsJsonObject()
                .getAsJsonArray("messages");
        JsonObject toolsMessage = messages.get(messages.size() - 1).getAsJsonObject();
        assertEquals("user", toolsMessage.get("role").getAsString());
        JsonObject block = toolsMessage.getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("tool_result", block.get("type").getAsString());
        assertEquals("toolu_1", block.get("tool_use_id").getAsString());
        assertEquals("状态正常", block.get("content").getAsString());
    }

    @Test
    void httpErrorsAreReportedWithoutLeakingTheKey() {
        status = 401;
        reply.set("{\"error\":{\"message\":\"invalid key sk-secret123456789\"}}");
        AiConfig config = config(AiProvider.CUSTOM);

        IOException error = assertThrows(IOException.class, () -> AssistantModel.complete(
                config, "系统提示", List.of(AssistantModel.Message.user("hi")), tools()));

        assertTrue(error.getMessage().contains("HTTP 401"));
        assertFalse(error.getMessage().contains("secret123456789"));
    }

    private AiConfig config(AiProvider provider) {
        AiConfig config = AiConfig.get();
        config.provider = provider.id;
        config.baseUrl = base;
        config.model = "test-model";
        config.reasoningEffort = "";
        config.apiKey = "sk-probe-not-a-real-key";
        return config;
    }

    private static List<LauncherTools.Tool> tools() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return List.of(
                new LauncherTools.Tool("launcher_status", "读取启动器状态", schema, args -> "ok"),
                new LauncherTools.Tool("install_game", "安装游戏", schema, args -> "ok"));
    }
}
