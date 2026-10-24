package com.sslauncher.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Http;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Queries a provider's OpenAI/Anthropic-compatible model listing. Discovery
 * failures never block the form: the user can always type a model id.
 */
public final class AiModelService {
    private AiModelService() {
    }

    public static List<AiModel> fetch(AiProvider provider, String apiKey, String baseUrl)
            throws IOException, InterruptedException {
        if (provider == null) {
            provider = AiProvider.DEEPSEEK;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("请先填写 API Key");
        }
        String url = provider.modelsUrl(baseUrl);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (provider == AiProvider.ANTHROPIC) {
            headers.put("x-api-key", apiKey.trim());
            headers.put("anthropic-version", "2023-06-01");
        } else {
            headers.put("Authorization", "Bearer " + apiKey.trim());
        }
        if (provider == AiProvider.OPENROUTER) {
            headers.put("HTTP-Referer", "https://sslauncher.local");
            headers.put("X-Title", "SS Launcher");
        }
        HttpResponse<String> resp = Http.getResponse(url, headers);
        if (resp.statusCode() / 100 != 2) {
            String detail = "";
            try {
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (root.has("error")) {
                    JsonElement error = root.get("error");
                    if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                        detail = error.getAsJsonObject().get("message").getAsString();
                    } else {
                        detail = error.toString();
                    }
                }
            } catch (Exception ignored) {
            }
            if (detail.isBlank()) {
                detail = abbreviate(resp.body(), 180);
            }
            throw new IOException("HTTP " + resp.statusCode() + (detail.isBlank() ? "" : " · " + detail));
        }
        return parse(resp.body());
    }

    static List<AiModel> parse(String body) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (Exception e) {
            throw new IOException("模型列表不是有效的 JSON");
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("模型列表格式无法识别");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement list = root.has("data") ? root.get("data")
                : root.has("models") ? root.get("models") : null;
        if (list == null) {
            throw new IOException("模型列表缺少 data/models 字段");
        }
        List<AiModel> out = new ArrayList<>();
        if (list.isJsonArray()) {
            for (JsonElement e : list.getAsJsonArray()) {
                AiModel m = parseEntry(e, null);
                if (m != null) {
                    out.add(m);
                }
            }
        } else if (list.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : list.getAsJsonObject().entrySet()) {
                AiModel m = parseEntry(e.getValue(), e.getKey());
                if (m != null) {
                    out.add(m);
                }
            }
        } else {
            throw new IOException("模型列表格式无法识别");
        }
        // De-duplicate while preserving the provider's ordering.
        Map<String, AiModel> unique = new LinkedHashMap<>();
        for (AiModel m : out) {
            unique.putIfAbsent(m.id(), m);
        }
        List<AiModel> models = new ArrayList<>(unique.values());
        if (models.size() > 400) {
            models = new ArrayList<>(models.subList(0, 400));
        }
        // A stable alphabetical order is easier to scan than arbitrary catalog order.
        models.sort(Comparator.comparing(m -> m.id().toLowerCase(Locale.ROOT)));
        return models;
    }

    private static AiModel parseEntry(JsonElement element, String objectKey) {
        String id = objectKey;
        String name = null;
        int context = 0;
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            if (value == null || value.isBlank()) {
                return null;
            }
            return new AiModel(value.trim(), value.trim(), 0);
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        if (id == null || id.isBlank()) {
            id = string(obj, "id");
        }
        if (id == null || id.isBlank()) {
            id = string(obj, "name");
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        name = firstString(obj, "display_name", "displayName", "name", "title");
        context = firstInt(obj, "context_window", "contextWindow", "context_length",
                "contextLength", "max_context_length", "max_input_tokens", "max_model_len");
        if (context <= 0) {
            context = 0;
        }
        return new AiModel(id.trim(), name == null || name.isBlank() ? id.trim() : name.trim(), context);
    }

    private static String string(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            String value = string(obj, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int firstInt(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                try {
                    return obj.get(key).getAsInt();
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** Fallback entries shown when discovery is unavailable. */
    public static List<AiModel> builtIn(AiProvider provider) {
        if (provider == AiProvider.DEEPSEEK) {
            return List.of(new AiModel("deepseek-flash", "DeepSeek-V4.1-Flash", 1_048_576),
                    new AiModel("deepseek-v4-pro", "DeepSeek-V4-Pro", 1_048_576));
        }
        return List.of();
    }

    public static int contextWindowFor(AiProvider provider, String modelId) {
        for (AiModel m : builtIn(provider)) {
            if (m.id().equals(modelId)) {
                return m.contextWindow();
            }
        }
        return AiConfig.defaultContextWindow(provider.id, modelId);
    }
}
