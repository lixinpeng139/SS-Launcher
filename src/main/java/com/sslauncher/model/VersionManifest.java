package com.sslauncher.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VersionManifest {
    public Latest latest = new Latest();
    public List<Entry> versions = new ArrayList<>();

    public static class Latest {
        public String release;
        public String snapshot;
    }

    public static class Entry {
        public String id;
        public String type;
        public String url;
        public String time;
        public String releaseTime;
        public String sha1;
        public int complianceLevel;

        public boolean isRelease() {
            return "release".equals(type);
        }

        public boolean isSnapshot() {
            return "snapshot".equals(type);
        }

        public boolean isOld() {
            return type != null && (type.startsWith("old_") || "old_beta".equals(type) || "old_alpha".equals(type));
        }

        public String typeLabel() {
            if (isRelease()) {
                return "正式版";
            }
            if (isSnapshot()) {
                return "快照";
            }
            return "远古版";
        }

        public static Map<String, Entry> byId(VersionManifest manifest) {
            Map<String, Entry> map = new LinkedHashMap<>();
            if (manifest != null && manifest.versions != null) {
                for (Entry e : manifest.versions) {
                    if (e.id != null) {
                        map.put(e.id, e);
                    }
                }
            }
            return map;
        }
    }
}
