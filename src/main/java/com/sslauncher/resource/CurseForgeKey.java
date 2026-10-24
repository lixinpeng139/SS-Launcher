package com.sslauncher.resource;

/**
 * The built-in CurseForge API key.
 *
 * <p>本仓库为公开分发，不包含真实 API Key。在
 * {@code https://console.curseforge.com/} 申请你自己的 key 后，让
 * {@link #apiKey()} 返回它即可。返回空字符串时，{@code CurseForgeProvider}
 * 会把 CurseForge 视为“未配置”。</p>
 */
public final class CurseForgeKey {
    private CurseForgeKey() {
    }

    /** The CurseForge API key, or {@code ""} when not configured. */
    public static String apiKey() {
        return "";
    }
}
