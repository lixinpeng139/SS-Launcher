package com.sslauncher.ai;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Http;
import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Locates the official {@code @deepseek-ai/dsh} SDK runtime, installing Node
 * and the package into the launcher's data directory on first use. The dsh
 * profile, agent loop, tools, sandbox, prompts and skills all come from that
 * package; the launcher only supplies a generated Cordis patch and a skill.
 */
public final class DshRuntime {
    public static final String DSH_VERSION = "0.2.1-rc.3";
    private static final String NODE_VERSION = "24.21.0";
    private static final String NODE_DIST = "https://nodejs.org/dist/v" + NODE_VERSION;
    /**
     * Digests of the Node.js archives this build installs. They are a fallback
     * for when {@code SHASUMS256.txt} cannot be fetched; when the official list
     * is reachable it is authoritative (and a mismatch is fatal), so rotating
     * NODE_VERSION does not silently pin an unverified runtime.
     */
    private static final Map<String, String> PINNED_NODE_SHA256 = Map.of(
            "node-v24.21.0-win-x64.zip",
            "158f7685b44de51f6c0df1d153526cbcd3e1bc739a8dfc607721cef75de9e541",
            "node-v24.21.0-win-arm64.zip",
            "8779b1bde1d39f8d420e3b57aa657b39891af434d3de44a919044cec06785921");
    private static final Duration NODE_PROBE_TIMEOUT = Duration.ofSeconds(12);
    private static final long INSTALL_TIMEOUT_MINUTES = 45;

    public record Installation(Path node, Path bin, Path home, boolean managed) {
        public boolean valid() {
            return node != null && Files.isRegularFile(node)
                    && bin != null && Files.isRegularFile(bin);
        }
    }

    private static volatile Installation cached;
    private static volatile Process activeInstall;

    private DshRuntime() {
    }

    public static synchronized void invalidate() {
        cached = null;
    }

    public static Installation ensure(Progress progress) throws Exception {
        Installation ready = cached;
        if (ready != null && ready.valid()) {
            return ready;
        }
        synchronized (DshRuntime.class) {
            ready = cached;
            if (ready != null && ready.valid()) {
                return ready;
            }
            AiPaths.init();
            progress.setStage("检查 AI 运行时");
            Installation manual = manualInstallation(progress);
            if (manual != null) {
                installSkill();
                cached = manual;
                return manual;
            }

            Path node = findNode(progress);
            Path bin = AiPaths.DSH_BIN;
            if (!Files.isRegularFile(bin)) {
                installDsh(node, progress);
            }
            if (!Files.isRegularFile(bin)) {
                throw new IOException("AI 运行时安装不完整：找不到 " + bin);
            }
            installSkill();
            writeMarker(node, bin);
            cached = new Installation(node, bin, AiPaths.DSH_HOME, true);
            return cached;
        }
    }

    /**
     * Uses an explicitly configured runtime. The path may point at a dsh
     * package's {@code lib/bin.js}, the package directory, or a prefix that
     * contains {@code node_modules/@deepseek-ai/dsh}.
     */
    private static Installation manualInstallation(Progress progress) throws Exception {
        List<String> candidates = new ArrayList<>();
        String property = System.getProperty("ss.dshBin");
        if (property != null && !property.isBlank()) {
            candidates.add(property.trim());
        }
        String env = System.getenv("DSH_BIN");
        if (env != null && !env.isBlank()) {
            candidates.add(env.trim());
        }
        String configured = AiConfig.get().runtimePath;
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured.trim());
        }
        for (String value : candidates) {
            Path path = Path.of(value);
            Path bin = resolveBin(path);
            if (bin == null) {
                continue;
            }
            Path node = findNode(progress);
            if (node == null) {
                throw new IOException("找不到 Node.js " + NODE_VERSION + "，无法启动自定义 AI 运行时");
            }
            Log.info("使用自定义 dsh 运行时: " + bin);
            return new Installation(node, bin, AiPaths.DSH_HOME, false);
        }
        if (!candidates.isEmpty()) {
            throw new IOException("AI 运行时路径无效：" + candidates.get(0));
        }
        return null;
    }

    private static Path resolveBin(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        if (Files.isRegularFile(path)) {
            return path.getFileName().toString().endsWith(".js") ? path : null;
        }
        Path[] probes = {
                path.resolve("lib").resolve("bin.js"),
                path.resolve("node_modules").resolve("@deepseek-ai").resolve("dsh")
                        .resolve("lib").resolve("bin.js"),
                path.resolve("dsh").resolve("node_modules").resolve("@deepseek-ai").resolve("dsh")
                        .resolve("lib").resolve("bin.js")
        };
        for (Path probe : probes) {
            if (Files.isRegularFile(probe)) {
                return probe;
            }
        }
        return null;
    }

    private static void writeMarker(Path node, Path bin) {
        String json = "{\n  \"dshVersion\": \"" + DSH_VERSION + "\",\n"
                + "  \"node\": " + quote(node.toAbsolutePath().toString()) + ",\n"
                + "  \"bin\": " + quote(bin.toAbsolutePath().toString()) + ",\n"
                + "  \"installedAt\": " + System.currentTimeMillis() + "\n}\n";
        try {
            AppPaths.atomicWrite(AiPaths.RUNTIME_MARKER, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------ node

    private static Path findNode(Progress progress) throws Exception {
        Set<Path> candidates = new LinkedHashSet<>();
        String property = System.getProperty("ss.node");
        if (property != null && !property.isBlank()) {
            candidates.add(Path.of(property.trim()));
        }
        Path portable = AiPaths.nodeExe();
        candidates.add(portable);

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] names = AppPaths.osName().equals("windows")
                    ? new String[]{"node.exe", "node.cmd", "node"}
                    : new String[]{"node"};
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                for (String name : names) {
                    candidates.add(Path.of(dir, name));
                }
            }
        }
        if (AppPaths.osName().equals("windows")) {
            candidates.add(Path.of("C:/Program Files/nodejs/node.exe"));
            candidates.add(Path.of("C:/Program Files (x86)/nodejs/node.exe"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                candidates.add(Path.of(local, "Programs", "nodejs", "node.exe"));
            }
            String roaming = System.getenv("APPDATA");
            if (roaming != null) {
                candidates.add(Path.of(roaming, "npm", "node.exe"));
            }
        }

        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            String version = nodeVersion(candidate);
            if (version != null && supportedNode(version)) {
                return candidate;
            }
        }

        if (!AppPaths.osName().equals("windows")) {
            throw new IOException("AI 制作需要 Node.js 24.9 或更高版本，请先安装 Node.js");
        }
        return installNode(progress);
    }

    private static boolean supportedNode(String version) {
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        String[] parts = v.split("\\.");
        try {
            int major = Integer.parseInt(parts[0].replaceAll("[^0-9].*$", ""));
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")) : 0;
            return major > 24 || (major == 24 && minor >= 9);
        } catch (Exception e) {
            return false;
        }
    }

    private static String nodeVersion(Path node) {
        try {
            Process process = new ProcessBuilder(node.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(NODE_PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return output.isBlank() ? null : output.split("\\s+")[0];
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Official SHA-256 of a Node.js archive, read from nodejs.org's
     * {@code SHASUMS256.txt}. If that list is unreachable, the digest built
     * into this launcher is used; with neither available the install fails
     * closed rather than executing an unverified runtime.
     */
    private static String nodeSha256(String file) throws IOException, InterruptedException {
        String sums = null;
        try {
            sums = Http.getDirect(NODE_DIST + "/SHASUMS256.txt",
                    Map.of("Accept", "text/plain, */*"));
        } catch (IOException e) {
            AiLog.write("install.log", "Node SHASUMS256.txt fetch failed: " + e.getMessage());
        }
        if (sums != null) {
            for (String line : sums.split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[1].equals(file)
                        && parts[0].matches("(?i)[0-9a-f]{64}")) {
                    String official = parts[0].toLowerCase(Locale.ROOT);
                    String pinned = PINNED_NODE_SHA256.get(file);
                    if (pinned != null && !pinned.equalsIgnoreCase(official)) {
                        throw new IOException("Node.js 官方校验值与内置值不一致，已拒绝安装");
                    }
                    return official;
                }
            }
            AiLog.write("install.log", "Node archive not found in official SHASUMS256.txt: " + file);
        }
        String pinned = PINNED_NODE_SHA256.get(file);
        if (pinned != null) {
            return pinned;
        }
        throw new IOException("无法获取 Node.js 官方 SHA-256 校验值，已拒绝安装未校验的运行时");
    }

    private static Path installNode(Progress progress) throws Exception {
        AiPaths.init();
        progress.setStage("下载 Node.js 运行时");
        progress.setIndeterminate(true);
        progress.setDetail("首次使用 AI 制作需要安装一次 Node.js");
        Path zip = AiPaths.RUNTIME.resolve("node.zip");
        if (Files.exists(zip)) {
            Files.deleteIfExists(zip);
        }
        String arch = AppPaths.osArch().equals("arm64") ? "arm64" : "x64";
        String file = "node-v" + NODE_VERSION + "-win-" + arch + ".zip";
        progress.setStage("校验 Node.js 运行时");
        String expectedSha256 = nodeSha256(file);
        progress.setStage("下载 Node.js 运行时");
        List<String> urls = List.of(
                "https://npmmirror.com/mirrors/node/v" + NODE_VERSION + "/" + file,
                NODE_DIST + "/" + file);
        Exception last = null;
        for (String url : urls) {
            try {
                progress.setDetail("从 " + host(url) + " 下载");
                // The mirror may serve the bytes, but only the official digest
                // from nodejs.org decides whether they are accepted.
                Http.downloadTo(zip, url, null, expectedSha256, progress);
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                AiLog.write("install.log", "Node download failed: " + url + " :: " + e.getMessage());
                Files.deleteIfExists(zip);
            }
        }
        if (last != null) {
            throw new IOException("Node.js 下载失败：" + last.getMessage(), last);
        }
        progress.setStage("解压 Node.js 运行时");
        progress.setIndeterminate(true);
        if (Files.exists(AiPaths.NODE_DIR)) {
            deleteTree(AiPaths.NODE_DIR);
        }
        extractZipFlat(zip, AiPaths.NODE_DIR);
        Files.deleteIfExists(zip);
        Path node = AiPaths.nodeExe();
        if (!Files.isRegularFile(node)) {
            // Some archives keep a top-level folder; find node.exe defensively.
            try (var stream = Files.walk(AiPaths.NODE_DIR, 3)) {
                node = stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("node.exe"))
                        .findFirst().orElse(null);
            }
        }
        if (node == null || !Files.isRegularFile(node)) {
            throw new IOException("Node.js 解压后找不到 node.exe");
        }
        String version = nodeVersion(node);
        if (version == null || !supportedNode(version)) {
            throw new IOException("下载的 Node.js 版本不可用");
        }
        progress.setDetail("Node " + version + " 已就绪");
        return node;
    }

    private static String host(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** Extracts a zip while stripping the common archive-root directory. */
    private static void extractZipFlat(Path zip, Path target) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        String prefix = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank()) {
                    continue;
                }
                if (prefix == null && name.contains("/")) {
                    // node-vX-win-x64/... -> strip that root; node.exe without a
                    // root keeps its name.
                    String first = name.substring(0, name.indexOf('/') + 1);
                    prefix = first;
                }
                if (prefix != null && name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                }
                if (name.isBlank()) {
                    continue;
                }
                Path dest = root.resolve(name).normalize();
                if (!dest.startsWith(root)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (OutputStream out = Files.newOutputStream(dest)) {
                    in.transferTo(out);
                }
            }
        }
    }

    // ------------------------------------------------------------------- dsh

    private static void installDsh(Path node, Progress progress) throws Exception {
        AiPaths.init();
        progress.setStage("安装 DeepSeek Harness 运行时");
        progress.setIndeterminate(true);
        progress.setDetail("首次使用需要下载约 800 MB 的依赖，耗时取决于网络");
        Path npmCli = findNpmCli(node);
        List<String> base = new ArrayList<>();
        if (npmCli != null) {
            base.add(node.toString());
            base.add(npmCli.toString());
        } else {
            // Fall back to the npm shim on PATH.
            base.add(AppPaths.osName().equals("windows") ? "npm.cmd" : "npm");
        }
        base.add("install");
        base.add("--prefix");
        base.add(AiPaths.DSH_DIR.toString());
        base.add("@deepseek-ai/dsh@" + DSH_VERSION);
        base.add("--no-audit");
        base.add("--no-fund");
        base.add("--loglevel=error");
        base.add("--fetch-retries=4");
        base.add("--fetch-timeout=600000");
        base.add("--force");

        List<String> registries = List.of(
                "https://registry.npmmirror.com",
                "https://registry.npmjs.org");
        IOException last = null;
        for (int i = 0; i < registries.size(); i++) {
            String registry = registries.get(i);
            progress.setDetail(i == 0
                    ? "从 npmmirror 安装（约 800 MB）"
                    : "切换官方 npm 源重试");
            try {
                int code = runStreaming(base, AiPaths.ROOT, Map.of(
                        "npm_config_registry", registry,
                        "npm_config_cache", AiPaths.NPM_CACHE.toString(),
                        "npm_config_update_notifier", "false"), progress, INSTALL_TIMEOUT_MINUTES);
                if (code == 0 && Files.isRegularFile(AiPaths.DSH_BIN)) {
                    progress.setDetail("AI 运行时安装完成");
                    return;
                }
                last = new IOException("npm 安装失败，退出码 " + code + tailDetail());
                AiLog.write("install.log", "npm registry " + registry + " failed: " + last.getMessage());
            } catch (IOException e) {
                last = e;
                AiLog.write("install.log", "npm registry " + registry + " failed: " + e.getMessage());
            }
        }
        throw new IOException("AI 运行时安装失败。请检查网络后重试，或在设置中填写自定义运行时路径。"
                + (last == null ? "" : "\n\n" + last.getMessage()), last);
    }

    private static String lastInstallDetail = "";

    private static String tailDetail() {
        String value = lastInstallDetail;
        return value == null || value.isBlank() ? "" : "\n" + value;
    }

    private static Path findNpmCli(Path node) {
        Path dir = node.toAbsolutePath().getParent();
        if (dir == null) {
            return null;
        }
        Path[] probes = {
                dir.resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js"),
                dir.resolve("lib").resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js"),
                dir.resolve("..").resolve("lib").resolve("node_modules").resolve("npm")
                        .resolve("bin").resolve("npm-cli.js")
        };
        for (Path probe : probes) {
            if (Files.isRegularFile(probe.normalize())) {
                return probe.normalize();
            }
        }
        return null;
    }

    /**
     * Runs an installer, streaming its output into the progress detail and a
     * small tail kept for the error modal.
     */
    private static int runStreaming(List<String> command, Path cwd, Map<String, String> env,
                                    Progress progress, long timeoutMinutes) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        if (env != null) {
            builder.environment().putAll(env);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException first) {
            // npm.cmd needs cmd.exe on Windows.
            if (command.get(0).toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                List<String> wrapped = new ArrayList<>();
                wrapped.add("cmd.exe");
                wrapped.add("/c");
                wrapped.addAll(command);
                process = new ProcessBuilder(wrapped).directory(cwd.toFile())
                        .redirectErrorStream(true).start();
            } else {
                throw first;
            }
        }
        activeInstall = process;
        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.trim();
                if (clean.isEmpty()) {
                    continue;
                }
                tail.addLast(clean);
                while (tail.size() > 24) {
                    tail.removeFirst();
                }
                if (clean.length() > 220) {
                    clean = clean.substring(0, 220) + "…";
                }
                progress.setDetail(clean);
                lastInstallDetail = clean;
            }
        }
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("安装超时（" + timeoutMinutes + " 分钟）");
        }
        if (process.exitValue() != 0 && !tail.isEmpty()) {
            lastInstallDetail = String.join("\n", tail);
        }
        return process.exitValue();
    }

    private static void installSkill() throws IOException {
        AiPaths.init();
        String resource = "/com/sslauncher/ai/skills/minecraft-mod-dev/SKILL.md";
        try (InputStream in = DshRuntime.class.getResourceAsStream(resource)) {
            if (in == null) {
                Log.warn("内置 Minecraft 模组技能资源缺失: " + resource);
                return;
            }
            byte[] data = in.readAllBytes();
            Path dir = AiPaths.SKILLS.resolve("minecraft-mod-dev");
            Files.createDirectories(dir);
            Path target = dir.resolve("SKILL.md");
            if (!Files.isRegularFile(target) || !java.util.Arrays.equals(Files.readAllBytes(target), data)) {
                AppPaths.atomicWrite(target, data);
                Log.info("已安装内置模组制作技能: " + target);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    /** The path shown by the settings form for a managed install. */
    public static String managedDescription() {
        return AiPaths.DSH_BIN.toString();
    }
}
