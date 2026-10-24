package com.sslauncher.ai;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Log;
import com.sslauncher.model.LoaderType;
import com.sslauncher.service.JavaService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a mod workspace after the agent finishes a turn. Gradle projects use
 * the project's own wrapper when present; otherwise the launcher scaffolds the
 * wrapper JAR it ships with (the same wrapper used by this launcher) and runs
 * {@code build}. Maven projects fall back to {@code mvn package}.
 */
public final class ModBuildService {
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(20);
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "build", "target", ".gradle", ".git", ".idea", ".vscode", "out", "run",
            ".ss-ai", ".ss-uploads", "node_modules");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "gradle", "properties", "json", "toml", "mcmeta",
            "xml", "yml", "yaml", "accesswidener", "aw", "cfg", "lang", "txt",
            "png", "jpg", "jpeg", "ogg", "wav", "nbt");
    /** Mojang's year-based release (26.3) or snapshot (26w01a) ids. */
    private static final Pattern YEAR = Pattern.compile("^(\\d{2})(?:\\.|w)");

    public record Result(boolean success, boolean skipped, Path artifact, int exitCode,
                         long durationMs, String message, String output, long sourceMtime) {
        public String summary() {
            if (skipped) {
                return message;
            }
            StringBuilder b = new StringBuilder();
            if (success) {
                b.append("构建成功（").append(String.format(Locale.ROOT, "%.1f", durationMs / 1000.0)).append(" 秒）");
                if (artifact != null) {
                    b.append("\n产物：").append(artifact.toAbsolutePath());
                } else {
                    b.append("\n构建完成，但没有找到 jar 产物。");
                }
            } else {
                b.append("构建失败（退出码 ").append(exitCode).append("）");
                if (message != null && !message.isBlank() && !"构建失败".equals(message)) {
                    b.append("\n").append(message);
                }
                String tail = tail(output, 40);
                if (!tail.isBlank()) {
                    b.append("\n\n").append(tail);
                }
            }
            return b.toString();
        }
    }

    private volatile Process active;

    public void cancel() {
        Process process = active;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    public long sourceMtime(Path workspace) {
        long[] max = {0};
        try (var stream = Files.walk(workspace, 12)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                if (excluded(workspace, p)) {
                    return;
                }
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                int dot = name.lastIndexOf('.');
                if (dot < 0 || !SOURCE_EXTENSIONS.contains(name.substring(dot + 1))) {
                    return;
                }
                try {
                    max[0] = Math.max(max[0], Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return max[0];
    }

    private static boolean excluded(Path root, Path file) {
        Path relative;
        try {
            relative = root.relativize(file);
        } catch (Exception e) {
            return true;
        }
        for (Path part : relative) {
            if (EXCLUDED_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public Result build(Path workspace, ModTarget target, long lastSourceMtime) {
        long mtime = sourceMtime(workspace);
        if (mtime == 0) {
            return new Result(false, true, null, 0, 0,
                    "工作区中还没有可构建的源码。", "", mtime);
        }
        if (lastSourceMtime > 0 && mtime <= lastSourceMtime) {
            return new Result(false, true, null, 0, 0,
                    "本次回复没有修改工作区源码，跳过构建。", "", mtime);
        }
        boolean gradle = Files.isRegularFile(workspace.resolve("build.gradle"))
                || Files.isRegularFile(workspace.resolve("build.gradle.kts"))
                || Files.isRegularFile(workspace.resolve("settings.gradle"))
                || Files.isRegularFile(workspace.resolve("settings.gradle.kts"));
        boolean maven = Files.isRegularFile(workspace.resolve("pom.xml"));
        if (!gradle && !maven) {
            return new Result(false, true, null, 0, 0,
                    "工作区中还没有 build.gradle 或 pom.xml，暂时无法构建。", "", mtime);
        }
        if (gradle) {
            return buildGradle(workspace, target, mtime);
        }
        return buildMaven(workspace, target, mtime);
    }

    // ----------------------------------------------------------------- gradle

    private Result buildGradle(Path workspace, ModTarget target, long mtime) {
        long started = System.currentTimeMillis();
        try {
            JavaService.JavaRuntime jdk = buildJdk(target);
            if (jdk == null) {
                return new Result(false, false, null, -1, 0,
                        "找不到 JDK " + (target == null ? 21 : target.javaMajor())
                                + "。请安装 Eclipse Temurin JDK 后重试：https://adoptium.net/temurin/releases/",
                        "", mtime);
            }
            Path wrapperJar = workspace.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar");
            if (!Files.isRegularFile(wrapperJar)) {
                scaffoldWrapper(workspace, gradleDistribution(target));
            }
            if (!Files.isRegularFile(wrapperJar)) {
                return new Result(false, false, null, -1, 0,
                        "无法准备 Gradle Wrapper：" + wrapperJar, "", mtime);
            }
            // The scaffold just wrote wrapper files into the workspace. Record
            // them, otherwise the next turn would think sources changed.
            mtime = Math.max(mtime, sourceMtime(workspace));
            Path java = jdk.exe();
            List<String> command = new ArrayList<>();
            command.add(java.toString());
            command.add("-Xmx2g");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8");
            command.add("-Dorg.gradle.java.home=" + java.getParent().getParent());
            command.add("-classpath");
            command.add(wrapperJar.toAbsolutePath().toString());
            command.add("org.gradle.wrapper.GradleWrapperMain");
            command.add("build");
            command.add("--console=plain");
            command.add("--no-daemon");
            command.add("--warning-mode=none");

            AiPaths.init();
            Path gradleHome = AiPaths.ROOT.resolve("gradle-home");
            Files.createDirectories(gradleHome);
            Map<String, String> env = Map.of(
                    "JAVA_HOME", java.getParent().getParent().toString(),
                    "GRADLE_USER_HOME", gradleHome.toString(),
                    "GRADLE_OPTS", "-Dfile.encoding=UTF-8");
            Execution execution = run(command, workspace, env);
            long elapsed = System.currentTimeMillis() - started;
            if (execution.exitCode != 0) {
                AiLog.write("build.log", "gradle failed (" + execution.exitCode + "):\n" + tail(execution.output, 80));
                return new Result(false, false, null, execution.exitCode, elapsed,
                        "构建失败", execution.output, mtime);
            }
            Path artifact = findArtifact(workspace.resolve("build").resolve("libs"));
            AiLog.write("build.log", "gradle ok in " + elapsed + "ms, artifact=" + artifact);
            return new Result(true, false, artifact, 0, elapsed, "构建成功", execution.output, mtime);
        } catch (Exception e) {
            Log.warn("模组构建失败: " + e);
            AiLog.write("build.log", "gradle error: " + stack(e));
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(false, false, null, -1, System.currentTimeMillis() - started,
                    "构建无法启动：" + message, "", mtime);
        }
    }

    /**
     * Picks the Gradle release that the loader's current plugin generation
     * expects. The project's own wrapper always wins; this is only the
     * scaffold used when the agent did not write one.
     */
    private static String gradleDistribution(ModTarget target) {
        LoaderType loader = target == null ? LoaderType.VANILLA : target.loaderType();
        switch (loader) {
            case FABRIC, QUILT:
                return gradle("9.7.1");
            case NEOFORGE:
                return gradle(needsGradle9(target) ? "9.7.1" : "9.2.1");
            case FORGE: {
                int[] v = minecraftNumbers(target);
                if (v != null && v[0] == 1 && v[1] == 20) {
                    if (v[2] <= 1) {
                        return gradle("8.8");
                    }
                    if (v[2] == 4) {
                        return gradle("8.12.1");
                    }
                }
                return gradle("9.3.1");
            }
            default:
                return gradle("8.14.3");
        }
    }

    private static String gradle(String version) {
        return "https\\://mirrors.cloud.tencent.com/gradle/gradle-" + version + "-bin.zip";
    }

    /** {major, minor, patch} for 1.x ids, or null for year-based/unknown ids. */
    private static int[] minecraftNumbers(ModTarget target) {
        String mc = target == null || target.mcVersion == null ? "" : target.mcVersion.trim();
        Matcher m = Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?").matcher(mc);
        if (!m.find()) {
            return null;
        }
        return new int[]{1, Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2))};
    }

    /** Mojang's 26.x line needs Gradle 9 to run the matching Loom generation. */
    private static boolean needsGradle9(ModTarget target) {
        String mc = target == null || target.mcVersion == null ? "" : target.mcVersion.trim();
        Matcher year = YEAR.matcher(mc);
        return year.find() && Integer.parseInt(year.group(1)) >= 26;
    }

    private void scaffoldWrapper(Path workspace, String distributionUrl) throws IOException {
        Path wrapperDir = workspace.resolve("gradle").resolve("wrapper");
        Files.createDirectories(wrapperDir);
        Path jar = wrapperDir.resolve("gradle-wrapper.jar");
        if (!Files.isRegularFile(jar)) {
            try (InputStream in = ModBuildService.class.getResourceAsStream(
                    "/com/sslauncher/ai/gradle-wrapper.jar")) {
                if (in != null) {
                    Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path properties = wrapperDir.resolve("gradle-wrapper.properties");
        if (!Files.isRegularFile(properties)) {
            String text = "distributionBase=GRADLE_USER_HOME\n"
                    + "distributionPath=wrapper/dists\n"
                    + "distributionUrl=" + distributionUrl + "\n"
                    + "networkTimeout=10000\n"
                    + "validateDistributionUrl=true\n"
                    + "zipStoreBase=GRADLE_USER_HOME\n"
                    + "zipStorePath=wrapper/dists\n";
            AppPaths.atomicWrite(properties, text.getBytes(StandardCharsets.UTF_8));
        }
        Path bat = workspace.resolve("gradlew.bat");
        if (!Files.isRegularFile(bat)) {
            String text = "@echo off\r\n"
                    + "set DIR=%~dp0\r\n"
                    + "if defined JAVA_HOME (set JAVA_EXE=%JAVA_HOME%\\bin\\java.exe) else (set JAVA_EXE=java.exe)\r\n"
                    + "\"%JAVA_EXE%\" -Dorg.gradle.appname=gradlew -classpath \"%DIR%gradle\\wrapper\\gradle-wrapper.jar\" org.gradle.wrapper.GradleWrapperMain %*\r\n";
            AppPaths.atomicWrite(bat, text.getBytes(StandardCharsets.UTF_8));
        }
        Path sh = workspace.resolve("gradlew");
        if (!Files.isRegularFile(sh)) {
            String text = "#!/bin/sh\n"
                    + "APP_HOME=$(cd \"$(dirname \"$0\")\" && pwd)\n"
                    + "if [ -n \"$JAVA_HOME\" ]; then JAVA_EXE=\"$JAVA_HOME/bin/java\"; else JAVA_EXE=java; fi\n"
                    + "exec \"$JAVA_EXE\" -Dorg.gradle.appname=gradlew -classpath \"$APP_HOME/gradle/wrapper/gradle-wrapper.jar\" org.gradle.wrapper.GradleWrapperMain \"$@\"\n";
            AppPaths.atomicWrite(sh, text.getBytes(StandardCharsets.UTF_8));
            if (!AppPaths.osName().equals("windows")) {
                try {
                    Set<PosixFilePermission> perms = EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(sh, perms);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ----------------------------------------------------------------- maven

    private Result buildMaven(Path workspace, ModTarget target, long mtime) {
        long started = System.currentTimeMillis();
        try {
            Path tool = null;
            Path mvnw = workspace.resolve(AppPaths.osName().equals("windows") ? "mvnw.cmd" : "mvnw");
            if (Files.isRegularFile(mvnw)) {
                tool = mvnw;
            } else {
                tool = findOnPath(AppPaths.osName().equals("windows") ? "mvn.cmd" : "mvn");
            }
            if (tool == null) {
                return new Result(false, false, null, -1, 0,
                        "找不到 Maven。请安装 Maven 或为项目添加 Maven Wrapper。", "", mtime);
            }
            JavaService.JavaRuntime jdk = buildJdk(target);
            Map<String, String> env = jdk == null ? Map.of()
                    : Map.of("JAVA_HOME", jdk.exe().getParent().getParent().toString());
            List<String> command = new ArrayList<>();
            if (tool.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                command.add("cmd.exe");
                command.add("/c");
            }
            command.add(tool.toString());
            command.add("-B");
            command.add("-DskipTests");
            command.add("package");
            Execution execution = run(command, workspace, env);
            long elapsed = System.currentTimeMillis() - started;
            if (execution.exitCode != 0) {
                AiLog.write("build.log", "maven failed (" + execution.exitCode + "):\n" + tail(execution.output, 80));
                return new Result(false, false, null, execution.exitCode, elapsed,
                        "构建失败", execution.output, mtime);
            }
            Path artifact = findArtifact(workspace.resolve("target"));
            AiLog.write("build.log", "maven ok in " + elapsed + "ms, artifact=" + artifact);
            return new Result(true, false, artifact, 0, elapsed, "构建成功", execution.output, mtime);
        } catch (Exception e) {
            AiLog.write("build.log", "maven error: " + e);
            return new Result(false, false, null, -1, System.currentTimeMillis() - started,
                    "构建无法启动：" + e.getMessage(), "", mtime);
        }
    }

    // ---------------------------------------------------------------- helpers

    private record Execution(int exitCode, String output) {
    }

    private Execution run(List<String> command, Path workspace, Map<String, String> env)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        Process process = builder.start();
        active = process;
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                while (lines.size() > 400) {
                    lines.removeFirst();
                }
            }
        }
        if (!process.waitFor(BUILD_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
            process.destroyForcibly();
            return new Execution(-1, String.join("\n", lines) + "\n构建超时（20 分钟）。");
        }
        active = null;
        return new Execution(process.exitValue(), String.join("\n", lines));
    }

    /** The local JDK a build for this target would use, or null when missing. */
    public static JavaService.JavaRuntime buildJdk(ModTarget target) {
        return findJdk(target == null ? 21 : target.javaMajor(), !usesLegacyGradle(target));
    }

    /**
     * Targets that still build with Gradle 8.x; those cannot run on a JDK
     * newer than 24, so a newer local JDK is not a valid substitute.
     */
    private static boolean usesLegacyGradle(ModTarget target) {
        LoaderType loader = target == null ? LoaderType.VANILLA : target.loaderType();
        if (loader == LoaderType.VANILLA) {
            return true;
        }
        if (loader != LoaderType.FORGE) {
            return false;
        }
        int[] v = minecraftNumbers(target);
        return v != null && v[0] == 1 && v[1] == 20 && v[2] <= 4;
    }

    /**
     * @param modernGradle whether the build runs on Gradle 9+. Gradle 8.14 only
     *                     supports running up to Java 24, so a newer JDK is not
     *                     a safe substitute there.
     */
    private static JavaService.JavaRuntime findJdk(int required, boolean modernGradle) {
        int maxHigher = modernGradle ? required + 5 : Math.min(required + 5, 24);
        boolean windows = AppPaths.osName().equals("windows");
        JavaService.JavaRuntime exact = null;
        JavaService.JavaRuntime higher = null;
        for (JavaService.JavaRuntime runtime : JavaService.detect()) {
            Path parent = runtime.exe().getParent();
            Path home = parent == null ? null : parent.getParent();
            Path javac = parent == null ? null : parent.resolve(windows ? "javac.exe" : "javac");
            // Oracle's javapath shim exposes java/javac directly and is not a
            // valid JAVA_HOME; Gradle needs the real JDK root with bin/java.
            if (home == null || !Files.isRegularFile(home.resolve("bin")
                    .resolve(windows ? "java.exe" : "java"))) {
                continue;
            }
            if (javac == null || !Files.isRegularFile(javac)) {
                continue;
            }
            if (runtime.major() == required) {
                if (exact == null) {
                    exact = runtime;
                }
            } else if (runtime.major() > required
                    && (higher == null || runtime.major() < higher.major())) {
                higher = runtime;
            }
        }
        if (exact != null) {
            return exact;
        }
        if (higher != null && higher.major() <= maxHigher) {
            return higher;
        }
        return null;
    }

    private static Path findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path findArtifact(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return !name.contains("-sources") && !name.contains("-javadoc")
                                && !name.startsWith("original-");
                    })
                    .sorted(Comparator
                            .comparing((Path p) -> {
                                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                return name.endsWith("-dev.jar") || name.contains("-dev-shadow") ? 1 : 0;
                            })
                            .thenComparing((Path p) -> {
                                try {
                                    return Files.getLastModifiedTime(p).toMillis();
                                } catch (IOException e) {
                                    return 0L;
                                }
                            }, Comparator.reverseOrder()))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String tail(String output, int lines) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] all = output.split("\\R");
        int start = Math.max(0, all.length - lines);
        StringBuilder b = new StringBuilder();
        for (int i = start; i < all.length; i++) {
            if (b.length() > 0) {
                b.append('\n');
            }
            String line = all[i];
            b.append(line.length() > 500 ? line.substring(0, 500) + "…" : line);
        }
        return b.toString();
    }
}
