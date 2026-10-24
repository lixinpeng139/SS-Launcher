package com.sslauncher.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class AssetIndex {
    public Map<String, Asset> objects = new LinkedHashMap<>();

    public static class Asset {
        public String hash;
        public long size;
    }
}
