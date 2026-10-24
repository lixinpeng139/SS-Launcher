package com.sslauncher.ai;

import java.util.Locale;

/**
 * Model providers offered by the AI settings form. OpenAI-compatible
 * providers all share the {@code openai-completions} wire protocol; the
 * DeepSeek official route is handled by dsh's built-in adapter when possible.
 */
public enum AiProvider {
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "openai-completions", true),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", "openai-completions", true),
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1", "anthropic-messages", true),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai-completions", true),
    SILICONFLOW("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "openai-completions", true),
    CUSTOM("custom", "自定义（OpenAI 兼容）", "https://api.openai.com/v1", "openai-completions", false);

    public final String id;
    public final String label;
    public final String defaultBaseUrl;
    /** dsh pi-ai adapter protocol name. */
    public final String api;
    /** Whether model discovery can query the provider's {@code /models} endpoint. */
    public final boolean discoverable;

    AiProvider(String id, String label, String defaultBaseUrl, String api, boolean discoverable) {
        this.id = id;
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.api = api;
        this.discoverable = discoverable;
    }

    public static AiProvider fromId(String id) {
        if (id != null) {
            for (AiProvider p : values()) {
                if (p.id.equalsIgnoreCase(id.trim())) {
                    return p;
                }
            }
        }
        return DEEPSEEK;
    }

    public String defaultModel() {
        return this == DEEPSEEK ? "deepseek-flash" : "";
    }

    /** Base URL used for the pi-ai route, with DeepSeek's OpenAI path added. */
    public String effectiveBaseUrl(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) {
            value = defaultBaseUrl;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (this == DEEPSEEK && isOfficialDeepSeekBase(value) && !value.endsWith("/v1")) {
            return value + "/v1";
        }
        return value;
    }

    public static boolean isOfficialDeepSeekBase(String baseUrl) {
        if (baseUrl == null) {
            return true;
        }
        String value = baseUrl.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty()
                || value.equals("https://api.deepseek.com")
                || value.equals("https://api.deepseek.com/v1");
    }

    /** URL queried by the settings form's "fetch models" action. */
    public String modelsUrl(String configured) {
        String base = effectiveBaseUrl(configured);
        if (this == ANTHROPIC) {
            String root = base.endsWith("/v1") ? base : base + "/v1";
            return root + "/models?limit=1000";
        }
        if (base.endsWith("/models")) {
            return base;
        }
        return base + "/models";
    }
}
