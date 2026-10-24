package com.sslauncher.ai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.ai.AiConfig;
import com.sslauncher.ai.AiProvider;
import com.sslauncher.core.Http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Direct model access for the in-launcher assistant. There is no agent
 * runtime, child process or loopback bridge: this class turns the neutral
 * transcript into the provider's wire format, sends it, and returns the
 * assistant text plus any launcher tool calls.
 */
final class AssistantModel {
    private static final int MAX_TOKENS = 8192;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    record ToolCall(String id, String name, String arguments) {
    }

    /**
     * One neutral transcript entry. {@code role} is user, assistant or tool.
     * Assistant entries keep the text, optional reasoning and tool calls;
     * tool entries point back at the call they answer.
     */
    record Message(String role, String text, String reasoning, List<ToolCall> calls,
                   String toolCallId, String toolName, boolean error) {
        static Message user(String text) {
            return new Message("user", text, "", List.of(), "", "", false);
        }

        static Message assistant(String text, String reasoning, List<ToolCall> calls) {
            return new Message("assistant", text, reasoning,
                    calls == null ? List.of() : List.copyOf(calls), "", "", false);
        }

        static Message tool(String callId, String name, String text, boolean error) {
            return new Message("tool", text, "", List.of(), callId, name, error);
        }
    }

    record Reply(String text, String reasoning, List<ToolCall> calls) {
    }

    private AssistantModel() {
    }

    static Reply complete(AiConfig config, String system, List<Message> history,
                          List<LauncherTools.Tool> tools) throws IOException, InterruptedException {
        AiProvider provider = config.provider();
        if (provider == AiProvider.ANTHROPIC) {
            return anthropic(config, system, history, tools);
        }
        return openAiCompatible(config, system, history, tools);
    }

    // ------------------------------------------------------- OpenAI-compatible

    private static Reply openAiCompatible(AiConfig config, String system, List<Message> history,
                                          List<LauncherTools.Tool> tools)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("stream", false);
        String effort = openAiEffort(config.reasoningEffort);
        if (!effort.isBlank()) {
            body.addProperty("reasoning_effort", effort);
        }

        JsonArray messages = new JsonArray();
        if (system != null && !system.isBlank()) {
            JsonObject prompt = new JsonObject();
            prompt.addProperty("role", "system");
            prompt.addProperty("content", system);
            messages.add(prompt);
        }
        for (Message message : history) {
            if (message == null || message.role() == null) {
                continue;
            }
            switch (message.role()) {
                case "user" -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("role", "user");
                    entry.addProperty("content", nullSafe(message.text()));
                    messages.add(entry);
                }
                case "assistant" -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("role", "assistant");
                    entry.addProperty("content", nullSafe(message.text()));
                    List<ToolCall> calls = message.calls() == null ? List.of() : message.calls();
                    if (!calls.isEmpty()) {
                        JsonArray array = new JsonArray();
                        for (ToolCall call : calls) {
                            if (call == null || blank(call.name())) {
                                continue;
                            }
                            JsonObject item = new JsonObject();
                            item.addProperty("id", callId(call));
                            item.addProperty("type", "function");
                            JsonObject function = new JsonObject();
                            function.addProperty("name", call.name());
                            function.addProperty("arguments", nullSafe(call.arguments()));
                            item.add("function", function);
                            array.add(item);
                        }
                        if (array.size() > 0) {
                            entry.add("tool_calls", array);
                        }
                    }
                    // A completely empty assistant turn gives some gateways
                    // nothing to attach the next tool result to.
                    if (blank(message.text())) {
                        entry.addProperty("content", "");
                    }
                    messages.add(entry);
                }
                case "tool" -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("role", "tool");
                    entry.addProperty("tool_call_id", nullSafe(message.toolCallId()));
                    entry.addProperty("content", toolContent(message));
                    messages.add(entry);
                }
                default -> {
                }
            }
        }
        body.add("messages", messages);

        JsonArray definitions = new JsonArray();
        for (LauncherTools.Tool tool : tools) {
            if (tool == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.inputSchema() == null
                    ? new JsonObject() : tool.inputSchema());
            entry.add("function", function);
            definitions.add(entry);
        }
        if (definitions.size() > 0) {
            body.add("tools", definitions);
        }

        String url = openAiUrl(config);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + nullSafe(config.apiKey).trim());
        if (config.provider() == AiProvider.OPENROUTER) {
            headers.put("HTTP-Referer", "https://sslauncher.local");
            headers.put("X-Title", "SS Launcher");
        }
        JsonObject root = post(url, body, headers);
        JsonArray choices = array(root, "choices");
        if (choices.size() == 0) {
            throw new IOException("模型没有返回候选结果。");
        }
        JsonObject choice = object(choices.get(0));
        JsonObject message = choice == null ? null : object(choice.get("message"));
        if (message == null) {
            throw new IOException("模型响应缺少 message 字段。");
        }

        String text = contentText(message.get("content"));
        String reasoning = firstString(message, "reasoning_content", "reasoning");
        List<ToolCall> calls = new ArrayList<>();
        JsonArray toolCalls = array(message, "tool_calls");
        for (JsonElement element : toolCalls) {
            JsonObject item = object(element);
            if (item == null) {
                continue;
            }
            JsonObject function = object(item.get("function"));
            if (function == null) {
                continue;
            }
            String name = firstString(function, "name");
            if (blank(name)) {
                continue;
            }
            JsonElement arguments = function.get("arguments");
            String raw = arguments == null || arguments.isJsonNull()
                    ? "{}"
                    : arguments.isJsonPrimitive() ? arguments.getAsString()
                    : arguments.toString();
            String id = firstString(item, "id");
            calls.add(new ToolCall(blank(id) ? newCallId() : id, name, raw));
        }
        return new Reply(text, reasoning, calls);
    }

    private static String openAiUrl(AiConfig config) throws IOException {
        String base = config.provider().effectiveBaseUrl(config.baseUrl);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw new IOException("接口地址必须以 http:// 或 https:// 开头。");
        }
        return base + "/chat/completions";
    }

    private static String openAiEffort(String effort) {
        if (effort == null) {
            return "";
        }
        return switch (effort.toLowerCase(Locale.ROOT)) {
            case "low" -> "low";
            case "medium" -> "medium";
            case "high", "max" -> "high";
            default -> "";
        };
    }

    // --------------------------------------------------------------- Anthropic

    private static Reply anthropic(AiConfig config, String system, List<Message> history,
                                   List<LauncherTools.Tool> tools)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("max_tokens", MAX_TOKENS);
        if (system != null && !system.isBlank()) {
            body.addProperty("system", system);
        }

        JsonArray messages = new JsonArray();
        for (int index = 0; index < history.size(); index++) {
            Message message = history.get(index);
            if (message == null || message.role() == null) {
                continue;
            }
            switch (message.role()) {
                case "user" -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("role", "user");
                    JsonArray content = new JsonArray();
                    content.add(textBlock(blank(message.text()) ? "（空消息）" : message.text()));
                    entry.add("content", content);
                    messages.add(entry);
                }
                case "assistant" -> {
                    JsonArray content = new JsonArray();
                    if (!blank(message.text())) {
                        content.add(textBlock(message.text()));
                    }
                    List<ToolCall> calls = message.calls() == null ? List.of() : message.calls();
                    for (ToolCall call : calls) {
                        if (call == null || blank(call.name())) {
                            continue;
                        }
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "tool_use");
                        block.addProperty("id", callId(call));
                        block.addProperty("name", call.name());
                        block.add("input", toolInput(call.arguments()));
                        content.add(block);
                    }
                    if (content.size() == 0) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("role", "assistant");
                    entry.add("content", content);
                    messages.add(entry);
                }
                case "tool" -> {
                    // Anthropic wants every tool_result for one assistant
                    // message in a single following user message.
                    JsonArray content = new JsonArray();
                    int cursor = index;
                    while (cursor < history.size()) {
                        Message result = history.get(cursor);
                        if (result == null || !"tool".equals(result.role())) {
                            break;
                        }
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "tool_result");
                        block.addProperty("tool_use_id", nullSafe(result.toolCallId()));
                        block.addProperty("content", toolContent(result));
                        if (result.error()) {
                            block.addProperty("is_error", true);
                        }
                        content.add(block);
                        cursor++;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("role", "user");
                    entry.add("content", content);
                    messages.add(entry);
                    index = cursor - 1;
                }
                default -> {
                }
            }
        }
        body.add("messages", messages);

        JsonArray definitions = new JsonArray();
        for (LauncherTools.Tool tool : tools) {
            if (tool == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.add("input_schema", tool.inputSchema() == null
                    ? new JsonObject() : tool.inputSchema());
            definitions.add(entry);
        }
        if (definitions.size() > 0) {
            body.add("tools", definitions);
        }

        String url = anthropicUrl(config);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", nullSafe(config.apiKey).trim());
        headers.put("anthropic-version", "2023-06-01");
        JsonObject root = post(url, body, headers);

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement element : array(root, "content")) {
            JsonObject block = object(element);
            if (block == null) {
                continue;
            }
            String type = firstString(block, "type");
            switch (type) {
                case "text" -> append(text, firstString(block, "text"));
                case "thinking" -> append(reasoning, firstString(block, "thinking"));
                case "tool_use" -> {
                    String name = firstString(block, "name");
                    if (blank(name)) {
                        continue;
                    }
                    String id = firstString(block, "id");
                    JsonElement input = block.get("input");
                    String raw = input == null || input.isJsonNull()
                            ? "{}" : input.toString();
                    calls.add(new ToolCall(blank(id) ? newCallId() : id, name, raw));
                }
                default -> {
                }
            }
        }
        return new Reply(text.toString().trim(), reasoning.toString().trim(), calls);
    }

    private static String anthropicUrl(AiConfig config) throws IOException {
        String base = config.provider().effectiveBaseUrl(config.baseUrl);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/messages")) {
            return base;
        }
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw new IOException("接口地址必须以 http:// 或 https:// 开头。");
        }
        if (base.endsWith("/v1")) {
            return base + "/messages";
        }
        return base + "/v1/messages";
    }

    // -------------------------------------------------------------------- HTTP

    private static JsonObject post(String url, JsonObject body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", Http.USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        com.sslauncher.core.Json.GSON.toJson(body), StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpResponse<String> response = Http.send(builder.build());
        if (response.statusCode() / 100 != 2) {
            String detail = errorDetail(response.body());
            throw new IOException("模型接口返回 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(response.body());
        } catch (Exception e) {
            throw new IOException("模型接口返回的不是有效 JSON。");
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IOException("模型接口返回的 JSON 格式无法识别。");
        }
        return parsed.getAsJsonObject();
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String detail = "";
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                JsonObject error = object(root.get("error"));
                if (error != null) {
                    detail = firstString(error, "message");
                }
                if (detail.isBlank()) {
                    detail = firstString(root, "message");
                }
            }
        } catch (Exception ignored) {
        }
        if (detail.isBlank()) {
            detail = body;
        }
        detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
        if (detail.length() > 400) {
            detail = detail.substring(0, 400) + "…";
        }
        return Redact.text(detail);
    }

    // ----------------------------------------------------------------- helpers

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text == null ? "" : text);
        return block;
    }

    private static JsonObject toolInput(String arguments) {
        try {
            JsonElement parsed = JsonParser.parseString(arguments == null ? "{}" : arguments);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("_invalid_arguments", arguments == null ? "" : arguments);
        return fallback;
    }

    private static String toolContent(Message message) {
        String text = nullSafe(message.text());
        return message.error() ? "工具执行失败：" + text : text;
    }

    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement element : content.getAsJsonArray()) {
                JsonObject block = object(element);
                if (block != null && "text".equals(firstString(block, "type"))) {
                    append(text, firstString(block, "text"));
                }
            }
            return text.toString().trim();
        }
        return "";
    }

    private static JsonObject object(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent == null ? null : object(parent.get(name));
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            return new JsonArray();
        }
        return parent.getAsJsonArray(name);
    }

    private static String firstString(JsonObject object, String... names) {
        if (object == null || names == null) {
            return "";
        }
        for (String name : names) {
            if (name == null || !object.has(name) || object.get(name).isJsonNull()) {
                continue;
            }
            JsonElement value = object.get(name);
            try {
                if (value.isJsonPrimitive()) {
                    return value.getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String callId(ToolCall call) {
        return blank(call.id()) ? newCallId() : call.id();
    }

    private static String newCallId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "");
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
