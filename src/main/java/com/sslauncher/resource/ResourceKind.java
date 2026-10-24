package com.sslauncher.resource;

/** Kinds of community content the launcher can browse and install. */
public enum ResourceKind {
    MOD("mod", "模组", "mods", "mod"),
    RESOURCE_PACK("resourcepack", "材质包 / 资源包", "resourcepacks", "resourcepack"),
    SHADER("shader", "光影包", "shaderpacks", "shader"),
    MODPACK("modpack", "整合包", null, "modpack");

    /** Value used by Modrinth's {@code project_type} facet. */
    public final String modrinthType;
    public final String label;
    /** Folder below the instance game directory, or {@code null} for modpacks. */
    public final String folder;
    /** Path segment used by Modrinth project pages. */
    public final String pageSegment;

    ResourceKind(String modrinthType, String label, String folder, String pageSegment) {
        this.modrinthType = modrinthType;
        this.label = label;
        this.folder = folder;
        this.pageSegment = pageSegment;
    }

    public boolean needsLoader() {
        return this == MOD || this == MODPACK;
    }

    public static ResourceKind fromModrinthType(String type) {
        if (type != null) {
            for (ResourceKind kind : values()) {
                if (kind.modrinthType.equalsIgnoreCase(type)) {
                    return kind;
                }
            }
        }
        return MOD;
    }
}
