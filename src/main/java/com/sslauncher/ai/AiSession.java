package com.sslauncher.ai;

/**
 * Launcher-side metadata for one dsh session. The transcript itself lives in
 * {@code sessions/<id>/events.jsonl} in dsh's own event shape, so it can be
 * replayed without reimplementing the runtime's history format.
 */
public class AiSession {
    /** {@code mod} for AI 制作, {@code assistant} for the launcher assistant. */
    public static final String KIND_MOD = "mod";
    public static final String KIND_ASSISTANT = "assistant";

    public String id = "";
    public String title = "";
    /** Empty on sessions written before this field existed; those are mod sessions. */
    public String kind = "";
    public long createdAt;
    public long updatedAt;
    public String workspace = "";
    public ModTarget target = new ModTarget();
    public String provider = "";
    public String model = "";
    public String reasoningEffort = "";
    public String lastBuildPath = "";
    public long lastBuildAt;
    /** Max source mtime seen when the last build/turn finished. */
    public long lastSourceMtime;
    /** True once dsh has persisted this session and it may be resumed. */
    public boolean dshCreated;

    public String kind() {
        return kind == null || kind.isBlank() ? KIND_MOD : kind;
    }

    public boolean isAssistant() {
        return KIND_ASSISTANT.equals(kind());
    }

    public String displayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (target != null && target.modName != null && !target.modName.isBlank()) {
            return target.modName;
        }
        return isAssistant() ? "新对话" : "未命名会话";
    }
}
