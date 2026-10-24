package com.sslauncher.resource;

/** UI-level source filter; projects themselves always keep a concrete source. */
public enum SourceFilter {
    ALL("全部来源"),
    MODRINTH("Modrinth"),
    CURSEFORGE("CurseForge");

    public final String label;

    SourceFilter(String label) {
        this.label = label;
    }

    public boolean includes(ResourceSource source) {
        return this == ALL || (this == MODRINTH && source == ResourceSource.MODRINTH)
                || (this == CURSEFORGE && source == ResourceSource.CURSEFORGE);
    }
}
