package com.sslauncher.service;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.Mirror;
import com.sslauncher.model.VersionMeta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class LibraryService {
    private LibraryService() {
    }

    public static Path pathOf(String relative) {
        return AppPaths.LIBRARIES.resolve(relative);
    }

    public static List<Downloader.Item> downloadItems(VersionService.ResolvedVersion version) {
        return downloadItems(version, false);
    }

    /**
     * @param verifyExisting re-hash jars that already exist; installs use this
     *                       to repair files accepted by an older build whose
     *                       downloads were not verified. Launches keep the
     *                       cheaper size check so startup stays fast.
     */
    public static List<Downloader.Item> downloadItems(VersionService.ResolvedVersion version,
                                                      boolean verifyExisting) {
        List<Downloader.Item> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (VersionMeta.Library library : version.libraries) {
            if (library == null || !VersionService.rulesAllow(library.rules)) {
                continue;
            }
            String path = VersionService.libraryPath(library);
            if (path != null && seen.add(path)) {
                String url = VersionService.libraryUrl(library, path);
                VersionMeta.Download artifact = library.downloads == null ? null : library.downloads.artifact;
                // Fabric/Quilt profiles carry the digests at the top level of
                // the library entry instead of a Mojang-style downloads block.
                String sha1 = artifact != null && artifact.sha1 != null ? artifact.sha1 : library.sha1;
                String sha256 = artifact != null && artifact.sha256 != null ? artifact.sha256 : library.sha256;
                long size = artifact != null && artifact.size > 0 ? artifact.size : library.size;
                items.add(new Downloader.Item(library.name, Mirror.candidates(url), pathOf(path),
                        sha1, sha256, size, verifyExisting));
            }
            String classifier = VersionService.nativeClassifier(library);
            if (classifier != null && library.downloads != null && library.downloads.classifiers != null) {
                VersionMeta.Download nativeJar = library.downloads.classifiers.get(classifier);
                if (nativeJar != null && nativeJar.path != null && seen.add(nativeJar.path)) {
                    items.add(new Downloader.Item(library.name + " (" + classifier + ")",
                            Mirror.candidates(nativeJar.url), pathOf(nativeJar.path),
                            nativeJar.sha1, nativeJar.sha256, nativeJar.size, verifyExisting));
                }
            }
        }
        return items;
    }

    /** All libraries that belong on the classpath, in order. */
    public static List<Path> classpath(VersionService.ResolvedVersion version) {
        List<Path> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (VersionMeta.Library library : version.libraries) {
            if (library == null || !VersionService.rulesAllow(library.rules)) {
                continue;
            }
            String path = VersionService.libraryPath(library);
            if (path != null && seen.add(path)) {
                out.add(pathOf(path));
            }
        }
        return out;
    }

    public static List<Path> nativeJars(VersionService.ResolvedVersion version) {
        List<Path> out = new ArrayList<>();
        for (VersionMeta.Library library : version.libraries) {
            if (library == null || !VersionService.rulesAllow(library.rules)) {
                continue;
            }
            String classifier = VersionService.nativeClassifier(library);
            if (classifier == null || library.downloads == null || library.downloads.classifiers == null) {
                continue;
            }
            VersionMeta.Download nativeJar = library.downloads.classifiers.get(classifier);
            if (nativeJar != null && nativeJar.path != null) {
                Path p = pathOf(nativeJar.path);
                if (Files.exists(p) && !out.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }
}
