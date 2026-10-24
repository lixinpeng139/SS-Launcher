package com.sslauncher.service;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Downloader;
import com.sslauncher.core.Http;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.core.Mirror;
import com.sslauncher.core.Progress;
import com.sslauncher.model.AssetIndex;
import com.sslauncher.model.VersionMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class AssetService {
    private AssetService() {
    }

    public static Path indexFile(String indexId) {
        return AppPaths.ASSET_INDEXES.resolve(indexId + ".json");
    }

    public static Path objectFile(String hash) {
        return AppPaths.ASSET_OBJECTS.resolve(hash.substring(0, 2)).resolve(hash);
    }

    public static AssetIndex readIndex(String indexId) throws IOException {
        return Json.read(indexFile(indexId), AssetIndex.class);
    }

    public static Path ensureIndex(VersionMeta.AssetIndex ref, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (ref == null || ref.id == null) {
            return null;
        }
        Path dest = indexFile(ref.id);
        if (Files.exists(dest) && Files.size(dest) > 0) {
            return dest;
        }
        progress.setStage("获取资源索引");
        Downloader.Item item = new Downloader.Item("资源索引 " + ref.id,
                Mirror.candidates(ref.url), dest, ref.sha1, ref.size);
        Downloader.downloadAll(List.of(item), progress, 1, cancelled);
        return dest;
    }

    /**
     * Downloads every missing asset object. Returns the index so callers can
     * handle legacy virtual assets if needed.
     */
    public static AssetIndex ensureAssets(VersionMeta.AssetIndex ref, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (ref == null || ref.id == null) {
            return null;
        }
        Path index = ensureIndex(ref, progress, cancelled);
        AssetIndex assetIndex = readIndex(ref.id);
        if (assetIndex.objects == null || assetIndex.objects.isEmpty()) {
            return assetIndex;
        }
        List<Downloader.Item> items = new ArrayList<>();
        java.util.Set<String> seenHashes = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, AssetIndex.Asset> e : assetIndex.objects.entrySet()) {
            AssetIndex.Asset asset = e.getValue();
            if (asset == null || asset.hash == null || asset.hash.length() < 2) {
                continue;
            }
            if (!seenHashes.add(asset.hash)) {
                continue;
            }
            Path dest = objectFile(asset.hash);
            String url = "https://resources.download.minecraft.net/"
                    + asset.hash.substring(0, 2) + "/" + asset.hash;
            items.add(new Downloader.Item(e.getKey(), Mirror.candidates(url), dest, asset.hash, asset.size));
        }
        progress.setStage("下载游戏资源");
        Downloader.downloadAll(items, progress, Math.max(8, com.sslauncher.core.Config.get().downloadThreads),
                cancelled);
        if (ref.virtual) {
            buildVirtualAssets(ref.id, assetIndex, progress, cancelled);
        }
        return assetIndex;
    }

    private static void buildVirtualAssets(String indexId, AssetIndex index, Progress progress,
                                           BooleanSupplier cancelled) throws IOException {
        progress.setStage("整理旧版资源");
        Path target = AppPaths.ASSET_VIRTUAL.resolve(indexId);
        Files.createDirectories(target);
        for (Map.Entry<String, AssetIndex.Asset> e : index.objects.entrySet()) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            Path src = objectFile(e.getValue().hash);
            Path dest = target.resolve(e.getKey());
            if (!Files.exists(src) || Files.exists(dest)) {
                continue;
            }
            Files.createDirectories(dest.getParent());
            try {
                Files.createLink(dest, src);
            } catch (Exception e2) {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Log.info("已生成旧版虚拟资源目录: " + target);
    }

    /**
     * Mojang's own config prints XML to stdout (the official launcher parses
     * it). We use an equivalent config with a readable console pattern so the
     * in-app console and the mirrored log file stay human friendly.
     */
    private static final String LOG4J_CONFIG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Configuration status="WARN">
                <Appenders>
                    <Console name="SysOut" target="SYSTEM_OUT">
                        <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n"/>
                    </Console>
                    <RollingRandomAccessFile name="File" fileName="logs/latest.log" filePattern="logs/%d{yyyy-MM-dd}-%i.log.gz">
                        <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n"/>
                        <Policies>
                            <TimeBasedTriggeringPolicy/>
                            <OnStartupTriggeringPolicy/>
                        </Policies>
                    </RollingRandomAccessFile>
                </Appenders>
                <Loggers>
                    <Root level="info">
                        <filters>
                            <MarkerFilter marker="NETWORK_PACKETS" onMatch="DENY" onMismatch="NEUTRAL"/>
                        </filters>
                        <AppenderRef ref="SysOut"/>
                        <AppenderRef ref="File"/>
                    </Root>
                </Loggers>
            </Configuration>
            """;

    /** Returns a readable log4j configuration when the version declares one. */
    public static Path ensureLoggingConfig(VersionService.ResolvedVersion version, Progress progress,
                                           BooleanSupplier cancelled) throws IOException {
        if (version.logging == null || version.logging.client == null) {
            return null;
        }
        Path dest = AppPaths.ASSET_LOGS.resolve("ss-client.xml");
        if (!Files.exists(dest) || !LOG4J_CONFIG.equals(Files.readString(dest))) {
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, LOG4J_CONFIG);
        }
        return dest;
    }
}
