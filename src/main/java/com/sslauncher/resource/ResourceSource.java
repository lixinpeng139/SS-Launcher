package com.sslauncher.resource;

/** Where a resource project is hosted. */
public enum ResourceSource {
    MODRINTH("Modrinth"),
    CURSEFORGE("CurseForge");

    public final String label;

    ResourceSource(String label) {
        this.label = label;
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
