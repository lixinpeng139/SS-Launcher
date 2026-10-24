package com.sslauncher.core;

import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class InstanceStore {
    private static final InstanceStore INSTANCE = new InstanceStore();

    private List<Instance> instances = new ArrayList<>();
    private boolean loaded;

    private InstanceStore() {
    }

    public static InstanceStore get() {
        return INSTANCE;
    }

    public static class Data {
        public List<Instance> instances = new ArrayList<>();
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (Files.exists(AppPaths.INSTANCES_FILE)) {
            try {
                Data data = Json.read(AppPaths.INSTANCES_FILE, Data.class);
                if (data.instances != null) {
                    instances = data.instances;
                }
            } catch (IOException e) {
                Log.error("实例列表读取失败", e);
                Path backup = AppPaths.INSTANCES_FILE.resolveSibling("instances.broken.json");
                try {
                    Files.move(AppPaths.INSTANCES_FILE, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        instances.removeIf(i -> i == null || i.id == null || i.versionId == null);
        for (Instance instance : instances) {
            ensureDirs(instance);
            if (instance.name == null || instance.name.isBlank()) {
                instance.name = instance.versionId;
            }
        }
    }

    public synchronized List<Instance> all() {
        load();
        return List.copyOf(instances);
    }

    public synchronized Instance byId(String id) {
        if (id == null) {
            return null;
        }
        load();
        for (Instance i : instances) {
            if (id.equals(i.id)) {
                return i;
            }
        }
        return null;
    }

    public synchronized Instance selected() {
        load();
        Instance i = byId(Config.get().selectedInstanceId);
        if (i == null && !instances.isEmpty()) {
            i = instances.get(0);
        }
        return i;
    }

    public synchronized void select(Instance instance) {
        if (instance != null) {
            Config.get().selectedInstanceId = instance.id;
            Config.save();
        }
    }

    public synchronized Instance create(String versionId, LoaderType loader, String displayName) {
        load();
        String baseName = displayName == null || displayName.isBlank() ? defaultName(versionId, loader) : displayName.trim();
        String name = baseName;
        int n = 2;
        while (nameExists(name)) {
            name = baseName + " (" + n++ + ")";
        }
        Instance instance = new Instance(newId(), name, versionId, loader);
        instance.iconColor = colorFor(loader);
        instances.add(instance);
        ensureDirs(instance);
        Config.get().selectedInstanceId = instance.id;
        save();
        return instance;
    }

    private String defaultName(String versionId, LoaderType loader) {
        return loader == LoaderType.VANILLA ? versionId : versionId + " · " + loader.label;
    }

    private String colorFor(LoaderType loader) {
        return switch (loader) {
            case FABRIC -> "tan";
            case QUILT -> "purple";
            case FORGE -> "red";
            case NEOFORGE -> "orange";
            default -> "blue";
        };
    }

    private boolean nameExists(String name) {
        return instances.stream().anyMatch(i -> name.equalsIgnoreCase(i.name));
    }

    public synchronized void rename(Instance instance, String name) {
        if (instance == null || name == null || name.isBlank()) {
            return;
        }
        instance.name = name.trim();
        save();
    }

    public synchronized void remove(Instance instance) {
        if (instance == null) {
            return;
        }
        instances.removeIf(i -> Objects.equals(i.id, instance.id));
        if (Objects.equals(Config.get().selectedInstanceId, instance.id)) {
            Config.get().selectedInstanceId = instances.isEmpty() ? "" : instances.get(0).id;
        }
        save();
        try {
            deleteRecursively(instance.gameDir());
        } catch (IOException e) {
            Log.warn("删除实例目录失败: " + e.getMessage());
        }
    }

    public synchronized void touch(Instance instance) {
        if (instance != null) {
            instance.lastPlayedAt = System.currentTimeMillis();
            save();
        }
    }

    public synchronized void save() {
        try {
            Data data = new Data();
            data.instances = instances;
            Json.write(AppPaths.INSTANCES_FILE, data);
        } catch (IOException e) {
            Log.error("保存实例列表失败", e);
        }
    }

    private void ensureDirs(Instance instance) {
        try {
            Files.createDirectories(instance.gameDir());
            for (String sub : new String[]{"mods", "saves", "resourcepacks", "shaderpacks", "config", "logs", "screenshots"}) {
                Files.createDirectories(instance.gameDir().resolve(sub));
            }
        } catch (IOException e) {
            Log.warn("创建实例目录失败: " + e.getMessage());
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
