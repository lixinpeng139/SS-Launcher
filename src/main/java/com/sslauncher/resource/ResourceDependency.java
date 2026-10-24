package com.sslauncher.resource;

/** A prerequisite declared by one specific file version. */
public record ResourceDependency(ResourceSource source, String projectId, String versionId,
                                 DependencyType type, String name) {
    public ResourceDependency withName(String resolved) {
        return new ResourceDependency(source, projectId, versionId, type,
                resolved == null ? "" : resolved);
    }

    public String displayName() {
        return name == null || name.isBlank() ? "未知项目 " + projectId : name;
    }
}
