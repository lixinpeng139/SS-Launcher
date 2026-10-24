package com.sslauncher.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the Cordis patch passed to {@code dsh --patch} for AI 制作. The
 * patch keeps the shipped SDK composition intact and only changes the
 * deployment persona, the model route for non-DeepSeek providers, and the
 * approval policy.
 *
 * <p>The file is written as JSON, which is a subset of YAML 1.2, so no
 * hand-rolled string escaping can produce an unparsable patch.</p>
 */
public final class DshPatch {
    private DshPatch() {
    }

    public static Path write(AiConfig config, ModTarget target, Path workspace) throws IOException {
        return write(config, target, workspace, null);
    }

    public static Path write(AiConfig config, ModTarget target, Path workspace, String sessionId)
            throws IOException {
        Path patch = writePatch(config, persona(target, workspace), workspace, sessionId);
        writeTargetFile(target, workspace);
        return patch;
    }

    private static Path writePatch(AiConfig config, String persona, Path workspace,
                                   String sessionId) throws IOException {
        AiPaths.init();
        JsonArray patch = new JsonArray();

        JsonObject prompt = new JsonObject();
        prompt.addProperty("id", "system-prompt");
        JsonObject promptConfig = new JsonObject();
        promptConfig.addProperty("personaPrefix", persona);
        prompt.add("config", promptConfig);
        patch.add(prompt);

        // dsh's fail-safe default is read-only. The embedded agent must be able
        // to write its own workspace, and only its workspace: workspace-write
        // makes the session cwd the single writable boundary.
        JsonObject sandbox = new JsonObject();
        sandbox.addProperty("id", "sandbox-policy");
        JsonObject sandboxConfig = new JsonObject();
        sandboxConfig.addProperty("mode", "workspace-write");
        sandboxConfig.addProperty("workspaceRoot", workspace.toAbsolutePath().toString());
        sandbox.add("config", sandboxConfig);
        patch.add(sandbox);

        // The shipped composition only activates the permission service when
        // the sandbox + approval pair matches a configured preset
        // (workspace-write + ask). The SDK host does not enable approval
        // bridging, so an escalation request simply fails closed instead of
        // waiting on a prompt the embedded chat cannot show.
        JsonObject approval = new JsonObject();
        approval.addProperty("id", "approval");
        JsonObject approvalConfig = new JsonObject();
        approvalConfig.addProperty("policy", "ask");
        approval.add("config", approvalConfig);
        patch.add(approval);

        if (!config.usesBuiltInDeepSeekRoute()) {
            patch.add(route(config));
        }

        byte[] data = Json.GSON.toJson(patch).getBytes(StandardCharsets.UTF_8);
        Path output = sessionId == null || sessionId.isBlank()
                ? AiPaths.RUNTIME_PATCH : AiPaths.sessionPatch(sessionId);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        AppPaths.atomicWrite(output, data);
        return output;
    }

    /**
     * Hands the model a machine-readable copy of the target. It is also useful
     * when a session is resumed after the launcher's prompt was formed.
     */
    public static void writeTargetFile(ModTarget target, Path workspace) throws IOException {
        if (target == null || workspace == null) {
            return;
        }
        Path dir = workspace.resolve(".ss-ai");
        Files.createDirectories(dir);
        AppPaths.atomicWrite(dir.resolve("target.json"),
                target.toJson().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject route(AiConfig config) {
        AiProvider provider = config.provider();
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "llm-pi-ai");
        JsonObject configObject = new JsonObject();
        JsonObject providers = new JsonObject();
        JsonObject route = new JsonObject();
        route.addProperty("displayName", provider.label);
        route.addProperty("api", provider.api);
        route.addProperty("apiKeyEnv", config.apiKeyEnvName());
        route.addProperty("baseURL", provider.effectiveBaseUrl(config.baseUrl));
        route.addProperty("defaultContextWindow", AiConfig.defaultContextWindow(provider.id, config.model));
        // Conservative: most OpenAI-compatible gateways accept 8192 output
        // tokens; models with a higher ceiling are unaffected in practice.
        route.addProperty("defaultMaxTokens", 8192);
        if (provider == AiProvider.DEEPSEEK) {
            JsonObject compat = new JsonObject();
            compat.addProperty("thinkingFormat", "deepseek");
            route.add("compat", compat);
        }
        JsonArray models = new JsonArray();
        JsonObject model = new JsonObject();
        model.addProperty("id", config.model);
        model.addProperty("name", config.model);
        model.addProperty("contextWindow", AiConfig.defaultContextWindow(provider.id, config.model));
        model.addProperty("maxTokens", 8192);
        model.add("reasoningEfforts", effortMap(provider));
        models.add(model);
        route.add("models", models);
        providers.add(config.routeId(), route);
        configObject.add("providers", providers);
        entry.add("config", configObject);
        return entry;
    }

    private static JsonObject effortMap(AiProvider provider) {
        JsonObject efforts = new JsonObject();
        efforts.add("off", JsonNull.INSTANCE);
        efforts.addProperty("low", "low");
        if (provider == AiProvider.DEEPSEEK) {
            // The DeepSeek catalog exposes off/low/high/max.
            efforts.addProperty("medium", "high");
        } else {
            efforts.addProperty("medium", "medium");
        }
        efforts.addProperty("high", "high");
        efforts.addProperty("max", "high");
        return efforts;
    }

    private static String persona(ModTarget target, Path workspace) {
        if (target == null) {
            target = new ModTarget();
        }
        String name = blank(target.modName) ? "未命名模组" : target.modName;
        String id = blank(target.modId) ? ModTarget.slug(name) : target.modId;
        String pkg = blank(target.packageName) ? ModTarget.packageFor(id) : target.packageName;
        StringBuilder b = new StringBuilder();
        b.append("你是 SS Launcher 内置的《我的世界》（Minecraft）模组制作智能体，由 {{model}} 模型驱动。\n")
                .append("你的任务是在当前工作区中与用户协作，从零编写、修改并交付一个可构建的 Minecraft 模组。\n\n")
                .append("## 工作区\n")
                .append("- 工作区根目录：").append(workspace.toAbsolutePath()).append("\n")
                .append("- 启动器只授予工作区写权限；不要尝试修改工作区之外的任何文件。\n")
                .append("- 用户上传的参考文件会放在 `.ss-ai/uploads/`，这些文件只读，不要改写。\n")
                .append("- `.ss-ai/target.json` 保存本会话的模组目标信息，可读取但不要修改。\n\n")
                .append("## 本次目标\n")
                .append("- 游戏版本：Minecraft ").append(text(target.mcVersion)).append("\n")
                .append("- 加载器：").append(target.loaderLabel())
                .append(blank(target.loaderVersion) ? "" : " " + target.loaderVersion).append("\n")
                .append("- 模组名称：").append(name).append("\n")
                .append("- 模组 ID：").append(id).append("\n")
                .append("- 主包名：").append(pkg).append("\n")
                .append("- 描述：").append(blank(target.description) ? "（用户未填写）" : target.description).append("\n")
                .append("- 作者：").append(blank(target.author) ? "（用户未填写）" : target.author).append("\n")
                .append("- 构建工具链：Java ").append(target.javaMajor()).append("\n\n")
                .append("## 必读技能\n")
                .append("在开始搭建或修改工程之前，必须调用 `skill` 工具加载 `minecraft-mod-dev` 技能，")
                .append("并以其中的版本矩阵、依赖坐标、入口点和构建配置为准。不要凭记忆臆造加载器 API。\n\n")
                .append("## 工作方式\n")
                .append("1. 先检查工作区现状：若为空或缺少工程骨架，先搭建完整的 Gradle 工程（settings、build 脚本、")
                .append("gradle.properties、源码目录与资源目录），并保证与目标游戏版本、加载器版本严格匹配。\n")
                .append("2. 每次只做一小步可验证的修改，保持工程随时可构建。优先复用加载器官方 API；")
                .append("不确定的 API 先在工作区或技能文档中查证。\n")
                .append("3. 不要在回复中粘贴大段源码；文件应直接写入工作区。回复用中文简要说明改了什么、为什么。\n")
                .append("4. 你每次回复结束后，启动器会自动执行构建，并把成功/失败摘要和产物路径显示给用户。")
                .append("你不需要重复运行完整构建；如果需要提前排查编译错误，可以运行 `gradlew build`（Windows）或 `./gradlew build`。\n")
                .append("5. 用户在构建失败卡片上选择「让 AI 修复」时，会把错误摘要发给你；先修复编译错误，再继续新功能。\n")
                .append("6. 不要输出、保存或询问 API Key；不要访问工作区之外的路径。\n")
                .append("7. 需要用户做选择时，用简洁的中文说明选项与推荐项，然后继续可推进的工作。\n\n")
                .append("## 交付标准\n")
                .append("- 模组元数据（mod id、名称、版本、作者、描述）与目标一致。\n")
                .append("- 至少包含一个能在游戏中生效的功能或命令，并提供中英文语言文件。\n")
                .append("- `build.gradle` 能在离线/在线依赖可用时成功执行 `build`，产物位于 `build/libs/`。\n");
        return b.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(String value) {
        return blank(value) ? "未指定" : value;
    }
}
