package com.sslauncher.ui.ai;

import com.sslauncher.ai.AiProvider;

import java.util.List;

/** Thinking-level choices exposed by the model forms. */
public final class AiEffort {
    public record Option(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Option DEFAULT = new Option("", "模型默认");
    private static final Option OFF = new Option("off", "关闭思考");
    private static final Option LOW = new Option("low", "低");
    private static final Option MEDIUM = new Option("medium", "中");
    private static final Option HIGH = new Option("high", "高");
    private static final Option MAX = new Option("max", "最高");

    private AiEffort() {
    }

    /**
     * DeepSeek's built-in catalog exposes off/low/high/max; asking it for
     * "medium" makes the runtime reject the handshake, so it is omitted there.
     */
    public static List<Option> options(AiProvider provider) {
        if (provider == AiProvider.DEEPSEEK) {
            return List.of(DEFAULT, OFF, LOW, HIGH, MAX);
        }
        return List.of(DEFAULT, OFF, LOW, MEDIUM, HIGH, MAX);
    }

    public static Option find(List<Option> options, String id) {
        String wanted = id == null ? "" : id.trim();
        for (Option option : options) {
            if (option.id().equals(wanted)) {
                return option;
            }
        }
        return null;
    }
}
