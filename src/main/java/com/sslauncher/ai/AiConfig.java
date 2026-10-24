package com.sslauncher.ai;

import com.sslauncher.core.Json;
import com.sslauncher.core.Log;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Persistent AI settings. The API key is kept only in memory; on disk it is
 * sealed by {@link SecretBox}. The provider default is DeepSeek so a fresh
 * install only needs one key paste.
 */
public final class AiConfig {
    private static volatile AiConfig instance = new AiConfig();

    public String provider = AiProvider.DEEPSEEK.id;
    public String baseUrl = AiProvider.DEEPSEEK.defaultBaseUrl;
    public String model = "deepseek-flash";
    /** One of "", off, low, medium, high, max. Empty means the model default. */
    public String reasoningEffort = "";
    /** Optional path to a dsh lib/bin.js; empty uses the managed runtime. */
    public String runtimePath = "";
    public String lastWorkspace = "";
    public String apiKeySealed = "";

    /** In memory only. Gson skips transient fields. */
    public transient String apiKey = "";

    private AiConfig() {
    }

    public static AiConfig get() {
        return instance;
    }

    public static synchronized void load() {
        try {
            AiPaths.init();
        } catch (IOException e) {
            Log.warn("无法创建 AI 数据目录: " + e.getMessage());
        }
        if (Files.isRegularFile(AiPaths.CONFIG_FILE)) {
            try {
                AiConfig loaded = Json.read(AiPaths.CONFIG_FILE, AiConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (IOException e) {
                Log.warn("AI 配置读取失败，使用默认值: " + e.getMessage());
                instance = new AiConfig();
            }
        }
        instance.sanitize();
        instance.apiKey = SecretBox.unseal(instance.apiKeySealed);
    }

    public static synchronized void save() {
        AiConfig c = instance;
        c.sanitize();
        c.apiKeySealed = SecretBox.seal(c.apiKey == null ? "" : c.apiKey.trim());
        try {
            AiPaths.init();
            Json.write(AiPaths.CONFIG_FILE, c);
        } catch (IOException e) {
            Log.warn("AI 配置保存失败: " + e.getMessage());
        }
    }

    private void sanitize() {
        provider = AiProvider.fromId(provider).id;
        AiProvider p = provider();
        if (baseUrl == null) {
            baseUrl = p.defaultBaseUrl;
        }
        baseUrl = baseUrl.trim();
        if (baseUrl.isEmpty()) {
            baseUrl = p.defaultBaseUrl;
        }
        model = model == null ? "" : model.trim();
        if (model.isEmpty()) {
            model = p.defaultModel();
        }
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
        if (!reasoningEffort.isEmpty()
                && !reasoningEffort.equals("off")
                && !reasoningEffort.equals("low")
                && !reasoningEffort.equals("medium")
                && !reasoningEffort.equals("high")
                && !reasoningEffort.equals("max")) {
            reasoningEffort = "";
        }
        runtimePath = runtimePath == null ? "" : runtimePath.trim();
        lastWorkspace = lastWorkspace == null ? "" : lastWorkspace.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        apiKeySealed = apiKeySealed == null ? "" : apiKeySealed;
    }

    public AiProvider provider() {
        return AiProvider.fromId(provider);
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isConfigured() {
        return hasKey() && model != null && !model.isBlank();
    }

    /**
     * DeepSeek's built-in adapter exposes exactly these two models. Other
     * ids on a DeepSeek-compatible endpoint go through a generated pi-ai
     * route instead.
     */
    public boolean usesBuiltInDeepSeekRoute() {
        return provider().equals(AiProvider.DEEPSEEK)
                && AiProvider.isOfficialDeepSeekBase(baseUrl)
                && ("deepseek-flash".equals(model) || "deepseek-v4-pro".equals(model));
    }

    public String routeId() {
        return usesBuiltInDeepSeekRoute() ? "deepseek-official" : "ss-" + provider().id;
    }

    /** Environment variable the runtime reads the key from. */
    public String apiKeyEnvName() {
        return usesBuiltInDeepSeekRoute() ? "DEEPSEEK_API_KEY" : "SS_AI_KEY";
    }

    /** Fingerprint used to decide whether a live runtime must be restarted. */
    public String fingerprint() {
        return String.join("\u0000", provider, baseUrl, model, reasoningEffort,
                runtimePath, apiKey == null ? "" : apiKey);
    }

    public static int defaultContextWindow(String provider, String model) {
        AiProvider p = AiProvider.fromId(provider);
        if (p == AiProvider.DEEPSEEK) {
            return 1_048_576;
        }
        return 131_072;
    }
}
