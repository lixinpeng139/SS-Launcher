package com.sslauncher.service;

import com.sslauncher.auth.AuthException;
import com.sslauncher.auth.AuthService;
import com.sslauncher.auth.AuthlibInjector;
import com.sslauncher.auth.MicrosoftAuth;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;
import com.sslauncher.core.Secrets;
import com.sslauncher.core.ZipUtil;
import com.sslauncher.model.Account;
import com.sslauncher.model.Instance;
import com.sslauncher.model.VersionMeta;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Assembles the Java command line and starts Minecraft. */
public final class LaunchService {
    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private LaunchService() {
    }

    public record Launched(Process process, Path logFile, List<String> command, Path java) {
    }

    public static Launched launch(Instance instance, Account account, Progress progress,
                                  BooleanSupplier cancelled, Consumer<String> output)
            throws IOException, InterruptedException, AuthException {
        if (instance == null) {
            throw new IOException("请先选择一个实例");
        }
        if (!VersionService.isInstalled(instance.versionId)) {
            throw new IOException("版本 " + instance.versionId + " 尚未安装，请先安装或重新选择实例");
        }
        progress.setIndeterminate(true);
        progress.setStage("检查账号登录状态");
        Account fresh = AuthService.ensureFresh(account, progress::setStage);
        if (fresh == null) {
            throw new IOException("请先登录账号");
        }

        VersionService.ResolvedVersion version = VersionService.load(instance.versionId);
        VersionService.ensureVersionJar(version);
        progress.setStage("检查游戏文件");
        progress.setIndeterminate(false);
        List<com.sslauncher.core.Downloader.Item> libraryItems = LibraryService.downloadItems(version);
        if (!libraryItems.isEmpty()) {
            progress.setStage("检查依赖库");
            com.sslauncher.core.Downloader.downloadAll(libraryItems, progress,
                    Math.max(4, Math.min(8, Config.get().downloadThreads)), cancelled);
        }
        AssetService.ensureAssets(version.assetIndex, progress, cancelled);
        Path loggingConfig = AssetService.ensureLoggingConfig(version, progress, cancelled);
        String jarId = version.jarVersionId();
        Path clientJar = VersionService.versionJarPath(jarId);
        if (!Files.exists(clientJar)) {
            throw new IOException("缺少游戏本体 " + jarId + ".jar，请重新安装该版本");
        }

        progress.setStage("准备 Java 运行环境");
        int requiredJava = version.requiredJavaMajor();
        JavaService.JavaRuntime java = resolveJava(instance, requiredJava, progress, cancelled);

        AuthlibInjector.Injection injection = null;
        if (fresh.isYggdrasil()) {
            injection = AuthService.prepareSkinSite(fresh, progress, cancelled);
        }

        progress.setStage("解压运行库");
        Path natives = instance.gameDir().resolve("natives");
        extractNatives(version, natives);

        progress.setStage("构建启动参数");
        List<Path> classpath = LibraryService.classpath(version);
        classpath.add(clientJar);
        for (Path p : classpath) {
            if (!Files.exists(p)) {
                throw new IOException("缺少依赖库 " + p.getFileName() + "，请重新安装该版本");
            }
        }
        Path assetsRoot = version.assetIndex != null && version.assetIndex.virtual
                ? AppPaths.ASSET_VIRTUAL.resolve(version.assetIndex.id)
                : AppPaths.ASSETS;
        Map<String, String> vars = variables(instance, fresh, version, natives, classpath,
                assetsRoot, jarId, loggingConfig);
        List<String> command = buildCommand(instance, version, java, vars, classpath, natives, loggingConfig,
                injection);

        progress.setStage("启动 Minecraft");
        progress.setIndeterminate(true);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(instance.gameDir().toFile());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("无法启动 Java（" + java.exe() + "）：" + e.getMessage(), e);
        }
        Path logFile = instance.gameDir().resolve("logs")
                .resolve("launcher-" + LocalDateTime.now().format(LOG_STAMP) + ".log");
        pumpOutput(process, logFile, output);
        Log.info("已启动 " + instance.name + "（" + java.label() + "）");
        Log.info("命令行: " + Secrets.maskCommand(command));
        return new Launched(process, logFile, command, java.exe());
    }

    private static JavaService.JavaRuntime resolveJava(Instance instance, int required, Progress progress,
                                                       BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String custom = instance.javaPath;
        if (custom == null || custom.isBlank()) {
            custom = Config.get().javaPath;
        }
        if (custom != null && !custom.isBlank()) {
            Path exe = Path.of(custom);
            if (Files.isDirectory(exe)) {
                exe = exe.resolve("bin").resolve("java.exe");
            }
            if (Files.exists(exe)) {
                JavaService.JavaRuntime runtime = JavaService.probe(exe);
                if (runtime != null) {
                    if (runtime.major() < required) {
                        Log.warn("指定的 Java " + runtime.major() + " 低于该版本要求的 " + required + "，仍将尝试启动");
                    }
                    return runtime;
                }
                throw new IOException("无法识别指定的 Java：" + exe);
            }
            throw new IOException("指定的 Java 不存在：" + exe);
        }
        return JavaService.ensure(required, progress, cancelled);
    }

    private static void extractNatives(VersionService.ResolvedVersion version, Path natives) throws IOException {
        try {
            if (Files.isDirectory(natives)) {
                try (var stream = Files.walk(natives)) {
                    for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException e) {
            Log.warn("清理旧运行库失败: " + e.getMessage());
        }
        Files.createDirectories(natives);
        for (VersionMeta.Library library : version.libraries) {
            if (library == null || !VersionService.rulesAllow(library.rules)) {
                continue;
            }
            String classifier = VersionService.nativeClassifier(library);
            if (classifier == null || library.downloads == null || library.downloads.classifiers == null) {
                continue;
            }
            VersionMeta.Download nativeJar = library.downloads.classifiers.get(classifier);
            if (nativeJar == null || nativeJar.path == null) {
                continue;
            }
            Path jar = LibraryService.pathOf(nativeJar.path);
            if (!Files.exists(jar)) {
                continue;
            }
            List<String> excludes = library.extract == null ? List.of() : library.extract.exclude;
            ZipUtil.extractNatives(jar, natives, excludes);
        }
    }

    private static Map<String, String> variables(Instance instance, Account account,
                                                 VersionService.ResolvedVersion version, Path natives,
                                                 List<Path> classpath, Path assetsRoot, String jarId,
                                                 Path loggingConfig) {
        Map<String, String> vars = new LinkedHashMap<>();
        String classpathString = String.join(AppPaths.classpathSeparator(),
                classpath.stream().map(Path::toString).toList());
        String uuid = account.uuid == null ? "" : account.uuid.replace("-", "");
        String token = account.accessToken == null ? "" : account.accessToken;
        String userType = account.isMicrosoft() ? "msa" : account.isYggdrasil() ? "mojang" : "legacy";
        String clientId = account.isMicrosoft() ? MicrosoftAuth.clientId() : account.clientToken;
        String session = "token:" + token + ":" + uuid;

        vars.put("auth_player_name", account.name);
        vars.put("version_name", version.id);
        vars.put("game_directory", instance.gameDir().toString());
        vars.put("assets_root", assetsRoot.toString());
        vars.put("game_assets", assetsRoot.toString());
        vars.put("assets_index_name", version.assetIndex != null && version.assetIndex.id != null
                ? version.assetIndex.id : (version.assets == null ? "legacy" : version.assets));
        vars.put("auth_uuid", uuid);
        vars.put("uuid", uuid);
        vars.put("auth_access_token", token);
        vars.put("accessToken", token);
        vars.put("auth_session", session);
        vars.put("session", session);
        vars.put("user_type", userType);
        vars.put("auth_xuid", account.xuid == null ? "" : account.xuid);
        vars.put("clientid", clientId == null ? "" : clientId);
        vars.put("client_token", account.clientToken == null ? "" : account.clientToken);
        vars.put("user_properties", "{}");
        vars.put("natives_directory", natives.toString());
        vars.put("launcher_name", "SS Launcher");
        vars.put("launcher_version", "1.0.0");
        vars.put("classpath", classpathString);
        vars.put("classpath_separator", AppPaths.classpathSeparator());
        vars.put("library_directory", AppPaths.LIBRARIES.toString());
        vars.put("primary_jar", VersionService.versionJarPath(jarId).toString());
        vars.put("version_type", version.versionType == null ? "release" : version.versionType);
        vars.put("path", loggingConfig == null ? "" : loggingConfig.toString());
        vars.put("resolution_width", "");
        vars.put("resolution_height", "");
        return vars;
    }

    private static List<String> buildCommand(Instance instance, VersionService.ResolvedVersion version,
                                             JavaService.JavaRuntime java, Map<String, String> vars,
                                             List<Path> classpath, Path natives, Path loggingConfig,
                                             AuthlibInjector.Injection injection) {
        List<String> command = new ArrayList<>();
        command.add(java.exe().toString());
        boolean modded = instance.loaderType() != com.sslauncher.model.LoaderType.VANILLA;
        int memory = instance.memoryMb > 0
                ? instance.memoryMb
                : Config.get().effectiveMemoryMb(modded);
        command.add("-Xmx" + memory + "M");
        command.add("-Xms" + Math.max(256, Math.min(Config.get().minMemoryMb, memory)) + "M");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        if (java.major() >= 17) {
            command.add("-XX:+UseG1GC");
            command.add("-XX:+ParallelRefProcEnabled");
            command.add("-XX:MaxGCPauseMillis=200");
            command.add("-XX:+UnlockExperimentalVMOptions");
            command.add("-XX:+DisableExplicitGC");
            command.add("-XX:+AlwaysPreTouch");
        }

        List<String> jvm = VersionService.resolveArguments(
                version.arguments == null ? List.of() : version.arguments.jvm, vars);
        // Newer version JSONs already carry these properties; older ones do not.
        if (jvm.stream().noneMatch(a -> a.startsWith("-Dminecraft.launcher.brand="))) {
            command.add("-Dminecraft.launcher.brand=SS Launcher");
        }
        if (jvm.stream().noneMatch(a -> a.startsWith("-Dminecraft.launcher.version="))) {
            command.add("-Dminecraft.launcher.version=1.0.0");
        }
        command.addAll(jvm);
        boolean hasCp = jvm.stream().anyMatch(a -> "-cp".equals(a) || "-classpath".equals(a));
        boolean hasLibraryPath = jvm.stream().anyMatch(a -> a.startsWith("-Djava.library.path="));
        if (!hasCp) {
            command.add("-cp");
            command.add(vars.get("classpath"));
        }
        if (!hasLibraryPath) {
            command.add("-Djava.library.path=" + natives);
        }
        if (loggingConfig != null && version.logging != null && version.logging.client != null
                && version.logging.client.argument != null) {
            String arg = VersionService.substitute(version.logging.client.argument, vars);
            if (!arg.isBlank()) {
                command.add(arg);
            }
        }
        if (injection != null) {
            command.add("-javaagent:" + injection.jar() + "=" + injection.serverUrl());
            if (injection.prefetched() != null && !injection.prefetched().isBlank()) {
                command.add("-Dauthlibinjector.yggdrasil.prefetched=" + injection.prefetched());
            }
        }
        command.addAll(splitArgs(Config.get().extraJvmArgs));

        command.add(version.mainClass == null ? "net.minecraft.client.main.Main" : version.mainClass);
        if (version.arguments != null && version.arguments.game != null && !version.arguments.game.isEmpty()) {
            command.addAll(VersionService.resolveArguments(version.arguments.game, vars));
        } else if (version.minecraftArguments != null) {
            for (String part : version.minecraftArguments.trim().split("\\s+")) {
                if (!part.isBlank()) {
                    command.add(VersionService.substitute(part, vars));
                }
            }
        }
        command.addAll(splitArgs(Config.get().extraGameArgs));
        return command;
    }

    /** Splits a user supplied argument string, honouring double quotes. */
    public static List<String> splitArgs(String input) {
        List<String> out = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static void pumpOutput(Process process, Path logFile, Consumer<String> output) {
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException ignored) {
        }
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    if (output != null) {
                        try {
                            output.accept(line);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "ss-game-output");
        thread.setDaemon(true);
        thread.start();
        process.onExit().thenRun(() -> Log.info("Minecraft 进程已退出，代码 " + process.exitValue()
                + "，日志: " + logFile));
    }

}
