package com.sslauncher.resource;

import java.util.Set;

/** A mod, pack or modpack returned by a search. */
public record ResourceProject(ResourceSource source, String id, String slug, String title,
                              String author, String description, String iconUrl, String pageUrl,
                              long downloads, long follows, long updatedAt,
                              Set<String> gameVersions, Set<String> loaders, ResourceKind kind) {
    public String key() {
        return source.name() + ":" + id;
    }

    public boolean supportsGameVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()
                || gameVersions == null || gameVersions.isEmpty()) {
            return true;
        }
        return gameVersions.contains(mcVersion);
    }

    public boolean supportsLoader(String loader) {
        if (!kind.needsLoader() || loaders == null || loaders.isEmpty()) {
            return true;
        }
        return ResourceVersion.loaderMatches(loader, loaders);
    }
}
