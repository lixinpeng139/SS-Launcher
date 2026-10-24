package com.sslauncher.model;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mirrors Mojang's version.json (including inherited loader versions). */
public class VersionMeta {
    public String id;
    public String type;
    public String mainClass;
    public String inheritsFrom;
    public String assets;
    public String minecraftArguments;
    public String releaseTime;
    public String time;
    public Integer complianceLevel;
    public AssetIndex assetIndex;
    public Downloads downloads = new Downloads();
    public JavaVersion javaVersion;
    public List<Library> libraries = new ArrayList<>();
    public Arguments arguments;
    public Logging logging = new Logging();

    public static class AssetIndex {
        public String id;
        public String sha1;
        public long size;
        public long totalSize;
        public String url;
        public boolean virtual;
        public boolean mapToResources;
    }

    public static class Downloads {
        public Download client;
        public Download server;
        public Download clientMappings;
        public Download serverMappings;
    }

    public static class Download {
        public String id;
        public String sha1;
        public String sha256;
        public long size;
        public String url;
        public String path;
    }

    public static class JavaVersion {
        public int majorVersion;
        public String component;
    }

    public static class Arguments {
        public List<JsonElement> game = new ArrayList<>();
        public List<JsonElement> jvm = new ArrayList<>();
    }

    public static class Library {
        public String name;
        public String url;
        /** Top-level hashes used by Fabric/Quilt profile JSON (no downloads block). */
        public String sha1;
        public String sha256;
        public long size;
        public LibDownloads downloads;
        public Map<String, String> natives;
        public List<Rule> rules;
        public Extract extract;
    }

    public static class LibDownloads {
        public Download artifact;
        public Map<String, Download> classifiers = new LinkedHashMap<>();
    }

    public static class Extract {
        public List<String> exclude = new ArrayList<>();
    }

    public static class Rule {
        public String action;
        public Os os;
        public Map<String, Boolean> features;

        public boolean isAllow() {
            return "allow".equals(action);
        }
    }

    public static class Os {
        public String name;
        public String version;
        public String arch;
    }

    public static class Logging {
        public LoggingClient client;
    }


    public static class LoggingClient {
        public String type;
        public String argument;
        public Download file;
    }
}
