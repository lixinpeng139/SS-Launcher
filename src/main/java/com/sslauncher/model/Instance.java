package com.sslauncher.model;

import com.sslauncher.core.AppPaths;

import java.nio.file.Path;

public class Instance {
    public String id;
    public String name;
    public String versionId;
    public String loader = "vanilla";
    public String iconColor = "blue";
    public long createdAt;
    public long lastPlayedAt;
    public int memoryMb;         // 0 = use global
    public String javaPath = ""; // empty = use global

    public Instance() {
    }

    public Instance(String id, String name, String versionId, LoaderType loader) {
        this.id = id;
        this.name = name;
        this.versionId = versionId;
        this.loader = loader.id;
        this.createdAt = System.currentTimeMillis();
    }

    public Path gameDir() {
        return AppPaths.INSTANCES.resolve(id);
    }

    public LoaderType loaderType() {
        return LoaderType.fromId(loader);
    }

    public String versionLabel() {
        LoaderType t = loaderType();
        if (t == LoaderType.VANILLA) {
            return versionId;
        }
        return versionId + " · " + t.label;
    }
}
