package com.sslauncher.ai;

import com.sslauncher.core.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Files owned by the AI mod-making feature. Everything lives under
 * {@code <data>/ai} so the user can inspect or remove it independently of
 * game instances.
 *
 * <pre>
 *   config.json           provider / key (sealed) / model
 *   runtime/              managed Node + @deepseek-ai/dsh installation
 *   dsh-home/             DSH_HOME (sessions, skills, credentials)
 *   sessions/&lt;id&gt;/        our own transcript event log, model history and patch
 *   assistant/&lt;id&gt;/       scratch workspace of an AI 助手 conversation
 *   sessions.json         session index used by the history list
 *   logs/                 AI runtime diagnostics
 * </pre>
 */
public final class AiPaths {
    public static final Path ROOT;
    public static final Path CONFIG_FILE;
    public static final Path RUNTIME;
    public static final Path NODE_DIR;
    public static final Path DSH_DIR;
    public static final Path DSH_BIN;
    public static final Path RUNTIME_MARKER;
    public static final Path RUNTIME_PATCH;
    public static final Path DSH_HOME;
    public static final Path SKILLS;
    public static final Path SESSIONS;
    public static final Path SESSION_INDEX;
    public static final Path ASSISTANT;
    public static final Path LOGS;
    public static final Path NPM_CACHE;

    static {
        AppPaths.init();
        ROOT = AppPaths.ROOT.resolve("ai");
        CONFIG_FILE = ROOT.resolve("config.json");
        RUNTIME = ROOT.resolve("runtime");
        NODE_DIR = RUNTIME.resolve("node");
        DSH_DIR = RUNTIME.resolve("dsh");
        DSH_BIN = DSH_DIR.resolve("node_modules")
                .resolve("@deepseek-ai").resolve("dsh").resolve("lib").resolve("bin.js");
        RUNTIME_MARKER = RUNTIME.resolve("runtime.json");
        RUNTIME_PATCH = RUNTIME.resolve("patch.yml");
        DSH_HOME = ROOT.resolve("dsh-home");
        SKILLS = DSH_HOME.resolve("skills");
        SESSIONS = ROOT.resolve("sessions");
        SESSION_INDEX = ROOT.resolve("sessions.json");
        ASSISTANT = ROOT.resolve("assistant");
        LOGS = ROOT.resolve("logs");
        NPM_CACHE = RUNTIME.resolve("npm-cache");
    }

    private AiPaths() {
    }

    public static synchronized void init() throws IOException {
        for (Path p : new Path[]{ROOT, RUNTIME, DSH_HOME, SKILLS, SESSIONS,
                ASSISTANT, LOGS, NPM_CACHE}) {
            Files.createDirectories(p);
        }
    }

    public static Path nodeExe() {
        return NODE_DIR.resolve(com.sslauncher.core.AppPaths.osName().equals("windows")
                ? "node.exe" : "bin/node");
    }

    public static Path sessionDir(String id) {
        return SESSIONS.resolve(id);
    }

    public static Path sessionEvents(String id) {
        return sessionDir(id).resolve("events.jsonl");
    }

    /**
     * Neutral tool-calling transcript of an AI 助手 conversation. It is the
     * exact history replayed to the model; events.jsonl remains the UI log.
     */
    public static Path sessionMessages(String id) {
        return sessionDir(id).resolve("messages.json");
    }

    /**
     * Each live dsh runtime gets its own patch so provider, model and the
     * session workspace stay isolated between AI 制作 sessions.
     */
    public static Path sessionPatch(String id) {
        return sessionDir(id).resolve("patch.json");
    }

    public static Path assistantWorkspace(String id) {
        return ASSISTANT.resolve(id);
    }

    public static Path logFile(String name) {
        return LOGS.resolve(name);
    }
}
