package com.sslauncher.model;

public enum LoaderType {
    VANILLA("vanilla", "原版", "纯净的 Mojang 体验"),
    FABRIC("fabric", "Fabric", "轻量、更新快的模组加载器"),
    QUILT("quilt", "Quilt", "Fabric 的社区分支"),
    FORGE("forge", "Forge", "经典模组生态，整合包首选"),
    NEOFORGE("neoforge", "NeoForge", "面向新版本的 Forge 分支");

    public final String id;
    public final String label;
    public final String description;

    LoaderType(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    public static LoaderType fromId(String id) {
        if (id != null) {
            for (LoaderType t : values()) {
                if (t.id.equalsIgnoreCase(id)) {
                    return t;
                }
            }
        }
        return VANILLA;
    }

    public boolean needsLoaderVersion() {
        return this != VANILLA;
    }
}
