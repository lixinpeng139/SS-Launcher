package com.sslauncher.resource;

/** Mirrors Modrinth's dependency types and the closest CurseForge relation. */
public enum DependencyType {
    REQUIRED("必需前置"),
    OPTIONAL("可选前置"),
    EMBEDDED("已内置"),
    TOOL("工具"),
    INCOMPATIBLE("不兼容");

    public final String label;

    DependencyType(String label) {
        this.label = label;
    }

    public static DependencyType fromModrinth(String value) {
        if (value == null) {
            return REQUIRED;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "optional" -> OPTIONAL;
            case "embedded" -> EMBEDDED;
            case "incompatible" -> INCOMPATIBLE;
            case "tool" -> TOOL;
            default -> REQUIRED;
        };
    }

    public static DependencyType fromCurseForge(int relation) {
        return switch (relation) {
            case 2 -> OPTIONAL;
            case 1, 6 -> EMBEDDED;
            case 4 -> TOOL;
            case 5 -> INCOMPATIBLE;
            default -> REQUIRED;
        };
    }
}
