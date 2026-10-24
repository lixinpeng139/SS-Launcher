package com.sslauncher.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the assistant persona from the packaged resource. Keeping it as a
 * resource lets the prompt be reviewed and regenerated without touching the
 * conversation plumbing.
 */
public final class AssistantPrompts {
    static final String RESOURCE = "/com/sslauncher/ai/assistant-persona.md";

    private static volatile String cached;

    private AssistantPrompts() {
    }

    public static String system() {
        String value = cached;
        if (value != null) {
            return value;
        }
        synchronized (AssistantPrompts.class) {
            if (cached != null) {
                return cached;
            }
            cached = load();
            return cached;
        }
    }

    private static String load() {
        try (InputStream in = AssistantPrompts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return fallback();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return text.isBlank() ? fallback() : text;
        } catch (IOException e) {
            return fallback();
        }
    }

    private static String fallback() {
        return "你是 SS Launcher 内置的 Minecraft 游戏与启动器助手，由 {{model}} 模型驱动。\n"
                + "启动器工具在本机直接执行；用它们查询状态、版本、实例与资源，"
                + "并在用户明确请求后安装游戏、模组、材质包、光影或整合包。\n"
                + "安装前先说明目标；启动器会弹出确认框。绝不索取或输出 API Key、账号令牌或密码。\n"
                + "只依据工具返回的数据回答，不要编造版本号、文件名或下载地址。默认使用简体中文。";
    }
}
