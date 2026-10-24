package com.sslauncher.resource;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** One downloadable file version of a project. */
public record ResourceVersion(ResourceSource source, String projectId, String versionId,
                              String name, String versionNumber, String channel,
                              List<String> gameVersions, List<String> loaders,
                              String fileName, String downloadUrl, String sha1, long size,
                              long publishedAt, List<ResourceDependency> dependencies) {

    public ResourceVersion withDependencies(List<ResourceDependency> resolved) {
        return new ResourceVersion(source, projectId, versionId, name, versionNumber, channel,
                gameVersions, loaders, fileName, downloadUrl, sha1, size, publishedAt,
                resolved == null ? List.of() : resolved);
    }

    public boolean supportsGameVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()
                || gameVersions == null || gameVersions.isEmpty()) {
            return true;
        }
        return gameVersions.contains(mcVersion);
    }

    public boolean supportsLoader(String loader) {
        if (loaders == null || loaders.isEmpty()) {
            return true;
        }
        return loaderMatches(loader, loaders);
    }

    /** Quilt can load Fabric mods, so it is the one deliberate alias. */
    public static boolean loaderMatches(String wanted, Collection<String> available) {
        if (available == null || available.isEmpty()) {
            return true;
        }
        String target = normalizeLoader(wanted);
        if (target.isBlank()) {
            return false;
        }
        for (String value : available) {
            String candidate = normalizeLoader(value);
            if (candidate.equals(target)) {
                return true;
            }
            if ("quilt".equals(target) && "fabric".equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeLoader(String loader) {
        if (loader == null) {
            return "";
        }
        String value = loader.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "fabric-loader", "fabric_loader" -> "fabric";
            case "quilt-loader", "quilt_loader" -> "quilt";
            case "neo-forge", "neo forge" -> "neoforge";
            default -> value;
        };
    }

    public String gameVersionSummary(int max) {
        if (gameVersions == null || gameVersions.isEmpty()) {
            return "通用";
        }
        List<String> values = gameVersions;
        int limit = Math.max(1, max);
        if (values.size() <= limit) {
            return String.join(", ", values);
        }
        return String.join(", ", values.subList(0, limit)) + " 等 " + values.size() + " 个版本";
    }

    public String loaderSummary() {
        if (loaders == null || loaders.isEmpty()) {
            return "通用";
        }
        return String.join(", ", loaders);
    }
}
