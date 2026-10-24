---
name: minecraft-mod-dev
description: 指导 AI 在用户工作区按指定 Minecraft Java 版本与加载器创建、修改并构建 Fabric、NeoForge、Forge 或 Quilt 模组。
whenToUse: 当用户要求创建或修改 Minecraft Java 模组、搭建开发环境、选择加载器/版本或排查构建与运行问题时使用。
---

# Minecraft Java 模组开发（Fabric / NeoForge / Forge / Quilt）
本技能用于在用户选定的工作区中创建、修改、构建 Minecraft Java Edition 模组。默认目标是客户端与服务端都可用的普通模组；除非用户明确要求，不要引入付费依赖、外部下载器或不明来源的 jar。

事实核对日期：2026-10-24。Minecraft、加载器和映射仍在更新；本文件的版本号是核对时的官方示例/MDK 值，执行前按第 12 节端点复核。无法确认的事实必须标注“需核实”，不要编造。

## 0. 先做加载器决策
```text
用户明确指定加载器？
├─ 是 → 严格使用指定加载器，不替换、不混装
└─ 否
   ├─ MC 1.20.1 的旧整合包/旧教程生态 → Forge 47.x 或 Fabric
   ├─ MC >= 1.20.2 且需要现代 Forge 生态 → NeoForge
   ├─ 需要轻量、快速适配、跨版本或大量现成 Fabric 库 → Fabric
   ├─ 需要 Quilt 专属 API/QSL → Quilt 原生工程
   └─ 只是想让模组跑在 Quilt 上 → 继续做 Fabric 模组（多数 Fabric 模组可被 Quilt Loader 加载）
```

规则：

- 一个工程只使用一个加载器。Fabric API 不能放进 NeoForge/Forge 工程，反之亦然。
- 用户给了加载器和加载器版本时，加载器版本优先于本文件的“最新版本”。
- 不要为了“更新”而升级 MC、加载器、Gradle 或映射；跨版本升级是独立任务。
- 1.20.1 的 NeoForge 存在 47.1.x，但没有对应的官方 NeoForge MDK；新工程优先 Forge 47.4.28 或 Fabric。
- 1.21.11 之前仍有混淆；26.1+ 已不混淆，Yarn 不再维护，详见 1.3。
- 启动器可选 26.3 只证明 Mojang 版本存在；Fabric 26.3 已核对，NeoForge/Forge 的 26.x 支持未确认，必须单独查官方发布页。

## 1. 版本、Java 与构建工具矩阵
### 1.1 常用 MC 版本
| MC | Java | `pack_format` | Fabric API | Fabric Loader | Yarn | NeoForge | Forge |
|---|---:|---:|---|---|---|---|---|
| 1.20.1 | 17 | 15 | `0.92.12+1.20.1` | `0.20.1` | `1.20.1+build.10` | `47.1.x`（无官方 MDK，需查 Maven） | `47.4.28` |
| 1.20.4 | 17 | 22 | `0.97.3+1.20.4` | `0.20.1` | `1.20.4+build.3` | `20.4.253` | `49.2.13` |
| 1.20.6 | 21 | 32 | `0.100.8+1.20.6` | `0.20.1` | `1.20.6+build.3` | `20.6.144` | `50.2.14` |
| 1.21.1 | 21 | 34 | `0.116.17+1.21.1` | `0.20.1` | `1.21.1+build.3` | `21.1.264` | `52.1.20` |
| 1.21.4 | 21 | 46 | `0.119.4+1.21.4` | `0.20.1` | `1.21.4+build.8` | `21.4.161` | `54.1.22` |
| 1.21.8 | 21 | 64 | `0.136.1+1.21.8` | `0.20.1` | `1.21.8+build.1` | `21.8.57` | `58.1.26` |
| 26.1+（26.3 最新） | 25 | 需核实 | `0.163.0+26.3`（补丁值见下） | `0.19.5` | 不适用（无 Yarn） | 未确认 | 未确认 |

说明：

- Fabric API 的值是 `net.fabricmc.fabric-api:fabric-api` 的版本，不是加载器版本。
- Fabric 官方示例各分支仍把 `loader_version` 锁在 `0.19.5`；`0.20.1` 是当前稳定版。构建时优先使用启动器提供的加载器版本；元数据中的 `fabricloader` 下限要与之一致。
- NeoForge 的版本号不是 Forge 版本号。NeoForge 1.21.1 是 21.1.x，Forge 1.21.1 是 52.1.x。
- Forge 1.20.1 必须使用 `47.x`，1.20.4 使用 `49.x`，1.20.6 使用 `50.x`，1.21.1 使用 `52.x`，1.21.4 使用 `54.x`，1.21.8 使用 `58.x`。
- `pack_format` 是资源包格式；数据包格式可能另有值，写 `pack.mcmeta` 时以资源包格式为准。
- Mojang manifest 显示 26.1、26.1.1、26.1.2、26.2、26.3 均为 Java 25；26.3 为最新版本。
- Fabric 26.3 官方示例已核对：Loader `0.19.5`、API `0.163.0+26.3`、Loom `1.18-SNAPSHOT`、Gradle `9.7.1`；26.1.1/26.1.2/26.2 的 API 分别为 `0.145.4+26.1.1`、`0.155.3+26.1.2`、`0.161.0+26.2`。NeoForge/Forge 26.x 支持以官方发布页/Maven 为准；26.x 的 `pack_format` 需核实。

### 1.2 构建工具链
| MC | Fabric Loom / Gradle | NeoForge ModDevGradle / Gradle | ForgeGradle / Gradle |
|---|---|---|---|
| 1.20.1 | `1.18-SNAPSHOT` / `9.7.1` | 无官方 MDK | `[6.0,6.2)` / `8.8` |
| 1.20.4 | `1.18-SNAPSHOT` / `9.7.1` | `2.0.148` / `9.2.1` | `[6.0.16,6.2)` / `8.12.1` |
| 1.20.6 | `1.18-SNAPSHOT` / `9.7.1` | `2.0.148` / `9.2.1` | `[7.0.3,8)` / `9.3.1` |
| 1.21.1 | `1.18-SNAPSHOT` / `9.7.1` | `2.0.148` / `9.2.1` | `[7.0.3,8)` / `9.3.1` |
| 1.21.4 | `1.18-SNAPSHOT` / `9.7.1` | `2.0.148` / `9.2.1` | `[7.0.3,8)` / `9.3.1` |
| 1.21.8 | `1.18-SNAPSHOT` / `9.7.1` | `2.0.148` / `9.2.1` | `[7.0.3,8)` / `9.3.1` |
| 26.1+ | `1.18-SNAPSHOT` / `9.7.1`（`net.fabricmc.fabric-loom`，非 remap） | 未确认（以官方 MDK 为准） | 未确认 |

- 上表是官方示例分支/MDK 的值。Fabric Loom 已有 `1.19.3` release，但不要擅自替换示例分支锁定的 `1.18-SNAPSHOT`；要升级就单独验证。
- Gradle wrapper 的版本必须与项目匹配；优先复制官方 MDK/示例的 `gradle/wrapper`，不要只改 `build.gradle` 而不改 wrapper。
- Gradle 运行所需的 JDK 与 `java.toolchain` 目标不是一回事：1.20.1/1.20.4 目标 Java 17；1.20.6–1.21.8 目标 Java 21；26.1+ 目标 Java 25。

### 1.3 映射选择
| MC | Yarn | Parchment（NeoForge MDK 示例值） |
|---|---|---|
| 1.20.1 | `1.20.1+build.10` | `2023.09.03`（需按 Parchment 站点复核） |
| 1.20.4 | `1.20.4+build.3` | `2024.04.14` |
| 1.20.6 | `1.20.6+build.3` | `2024.06.16` |
| 1.21.1 | `1.21.1+build.3` | `2024.11.17` |
| 1.21.4 | `1.21.4+build.8` | `2025.03.23` |
| 1.21.8 | `1.21.8+build.1` | `2025.09.14` |
| 26.1+ | 不适用（无 Yarn） | 不适用（未混淆；Fabric 示例无 `mappings`） |

- Fabric 官方示例（1.20.1 到 1.21.8 各分支）现在使用 Mojang 官方映射：`mappings loom.officialMojangMappings()`。
- Yarn 仍可用于上述版本，但已在 2025 年后被弃用；26.1+ 不再提供 Yarn，使用 Mojang 官方名/无映射。Fabric 26.3 官方示例改用 `net.fabricmc.fabric-loom`（非 `-remap`），`build.gradle` 中没有 `mappings` 行，不要照抄 1.21.8 的 `loom.officialMojangMappings()`。
- Parchment 是叠加在 Mojang 映射上的参数名和 Javadoc，不改变类名。Fabric Loom 的标准写法：

```groovy
repositories {
    maven { name = 'ParchmentMC'; url = 'https://maven.parchmentmc.org' }
}
dependencies {
    mappings loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${project.minecraft_version}:${project.parchment_version}@zip")
    }
}
```

- NeoForge 在 `neoForge { parchment { ... } }` 中配置，见 3.3。
- ForgeGradle 6 的 Parchment 通常通过 Librarian 插件和 `mappings channel: 'parchment', version: '<YYYY.MM.DD>-<mc>'`；ForgeGradle 7 的 Parchment 配置未在本次核对中确认，使用前查 Forge 文档/MDK，无法确认就保持 `official`。

## 2. 工程结构与最小文件
### 2.1 Fabric（split sources）
```text
project/
├─ build.gradle, settings.gradle, gradle.properties, gradlew(.bat)
├─ gradle/wrapper/gradle-wrapper.properties
└─ src/
   ├─ main/java/com/example/examplemod/...
   ├─ main/resources/{fabric.mod.json, examplemod.mixins.json, assets/...}
   └─ client/java/com/example/examplemod/client/...
      └─ client/resources/
```

Fabric 1.21.x 官方示例使用 `splitEnvironmentSourceSets()`，`main` 与 `client` 是两个 source set；客户端专属类必须放在 `src/client/java`。

### 2.2 NeoForge / Forge（单 source set）
```text
project/
├─ build.gradle, settings.gradle, gradle.properties, gradlew(.bat)
├─ gradle/wrapper/gradle-wrapper.properties
└─ src/main/
   ├─ java/com/example/examplemod/{ExampleMod,ExampleModClient}.java
   └─ resources/
      ├─ META-INF/neoforge.mods.toml   # NeoForge 1.20.5+
      ├─ META-INF/mods.toml            # NeoForge 1.20.1-1.20.4 / Forge
      ├─ pack.mcmeta
      └─ assets/examplemod/...
```

NeoForge 官方 MDK 还把元数据放在 `src/main/templates/META-INF/`，通过 `generateModMetadata` 展开 `${...}`。不使用模板时，直接把最终值写进 `src/main/resources/META-INF/` 也有效。Forge MDK 使用 `processResources` 展开 `mods.toml` 中的占位符。

### 2.3 最小文件清单
| 加载器 | 必需文件 | 说明 |
|---|---|---|
| Fabric | `settings.gradle`、`build.gradle`、`gradle.properties`、wrapper、`fabric.mod.json`、入口类、mixin 配置（若用 Mixin） | `fabric.mod.json` 必须在 `src/main/resources` 根目录 |
| NeoForge | `settings.gradle`、`build.gradle`、`gradle.properties`、wrapper、`@Mod` 类、`META-INF/neoforge.mods.toml` 或 `mods.toml` | 1.20.4 及以下用 `mods.toml`；1.20.5+ 用 `neoforge.mods.toml` |
| Forge | 同上，但始终是 `META-INF/mods.toml` | Forge 1.20.1/1.20.4 需要 FG6 的 reobf |
| Quilt 原生 | `quilt.mod.json`、Quilt Loom 工程、入口类 | 仅当用户要求 QSL 时使用；否则优先 Fabric |

## 3. Gradle 配置
### 3.1 Gradle wrapper
- 项目有 `gradlew.bat`（Windows）或 `gradlew`（Unix）时，始终使用它。
- Windows Git Bash：`./gradlew.bat build`；cmd：`gradlew.bat build`；Unix：`./gradlew build`。
- 查看版本：`gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl`。
- wrapper 缺失时，不要直接 `gradle build`；先从官方 MDK 复制整个 wrapper，或用已安装的 Gradle 生成：

```bat
gradle wrapper --gradle-version 9.2.1
```

生成后检查 `distributionUrl` 和 `gradle-wrapper.jar` 是否存在。不要手写或从不明来源下载 wrapper jar。

### 3.2 Fabric
`settings.gradle` 的 `pluginManagement.repositories` 必须包含 `maven { url = 'https://maven.fabricmc.net/' }`、`mavenCentral()`、`gradlePluginPortal()`，并设置 `rootProject.name = 'examplemod'`。

`gradle.properties`（1.21.8 官方示例值）：

```properties
org.gradle.jvmargs=-Xmx1G
org.gradle.parallel=true
org.gradle.configuration-cache=true
minecraft_version=1.21.8
loader_version=0.19.5
loom_version=1.18-SNAPSHOT
fabric_api_version=0.136.1+1.21.8
version=1.0.0
group=com.example
```

`build.gradle` 核心：

```groovy
plugins { id 'net.fabricmc.fabric-loom-remap' version "${loom_version}" }
loom {
    splitEnvironmentSourceSets()
    mods {
        "examplemod" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}
processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") { expand "version": project.version }
}
tasks.withType(JavaCompile).configureEach { it.options.release = 21 } // 1.20.1/1.20.4 用 17
java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

Yarn 写法：把 `mappings loom.officialMojangMappings()` 换成 `mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"`，并在 `gradle.properties` 加 `yarn_mappings=1.21.8+build.1`。

26.x 写法：用 `plugins { id 'net.fabricmc.fabric-loom' version "${loom_version}" }`（不是 `-remap`），删除 `mappings` 行，依赖改用 `implementation`；26.3 示例为 `minecraft_version=26.3`、`loader_version=0.19.5`、`fabric_api_version=0.163.0+26.3`、Gradle `9.7.1`、Java release 25。

### 3.3 NeoForge（ModDevGradle）
`settings.gradle` 需要 `pluginManagement { repositories { gradlePluginPortal() } }`，并应用 `id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'`。

`gradle.properties`（1.21.8）：

```properties
minecraft_version=1.21.8
minecraft_version_range=[1.21.8]
neo_version=21.8.57
parchment_minecraft_version=1.21.8
parchment_mappings_version=2025.09.14
mod_id=examplemod
mod_name=Example Mod
mod_license=All Rights Reserved
mod_version=1.0.0
mod_group_id=com.example.examplemod
```

`build.gradle` 核心：

```groovy
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '2.0.148'
}
version = mod_version
group = mod_group_id
base { archivesName = mod_id }
sourceSets.main.resources { srcDir 'src/generated/resources' }
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
neoForge {
    version = project.neo_version
    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }
    runs {
        client { client() }
        server { server(); programArgument '--nogui' }
        data {
            clientData()
            programArguments.addAll '--mod', project.mod_id, '--all',
                '--output', file('src/generated/resources/').getAbsolutePath(),
                '--existing', file('src/main/resources/').getAbsolutePath()
        }
    }
    mods { "${mod_id}" { sourceSet(sourceSets.main) } }
}
```

1.20.4 把 toolchain 改为 Java 17；1.20.4/1.20.6 的 NeoForge 版本和 Parchment 值见 1.1/1.3。26.x：本轮无法复核 NeoForge 26.x 的发布/MDK，版本、Gradle、Parchment 与依赖坐标均未确认；不要沿用 1.21.8 的 `21.8.57` 或 Parchment 值，按官方 26.x 发布页/MDK 生成。

### 3.4 Forge
Forge 1.20.1 的 `build.gradle` 核心：

```groovy
plugins { id 'net.minecraftforge.gradle' version '[6.0,6.2)' }
java.toolchain.languageVersion = JavaLanguageVersion.of(17)
minecraft {
    mappings channel: 'official', version: '1.20.1'
    runs {
        client { }
        server { args '--nogui' }
        data {
            args '--mod', 'examplemod', '--all',
                 '--output', file('src/generated/resources'),
                 '--existing', file('src/main/resources')
        }
    }
}
dependencies { minecraft "net.minecraftforge:forge:1.20.1-47.4.28" }
```

Forge 1.21.8 的差异：FG 改为 `[7.0.3,8)`，Java 改为 21，`mappings channel: 'official', version: '1.21.8'`，依赖改为 `implementation minecraft.dependency('net.minecraftforge:forge:1.21.8-58.1.26')`，并加 `annotationProcessor 'net.minecraftforge:eventbus-validator:7.0.1'`。FG7 还需要：

```groovy
repositories {
    minecraft.mavenizer(it)
    maven fg.forgeMaven
    maven fg.minecraftLibsMaven
    mavenCentral()
}
```

Forge 1.20.4 使用 FG `[6.0.16,6.2)`、Gradle 8.12.1、`net.minecraftforge:forge:1.20.4-49.2.13`；1.20.6+ 使用 FG7，不再需要传统的 reobf 产物。

## 4. 元数据文件
### 4.1 Fabric `fabric.mod.json`
1.21.8 可用模板（1.20.x 只需把 Java/loader/minecraft 版本替换成目标值）：

```json
{
  "schemaVersion": 1,
  "id": "examplemod",
  "version": "${version}",
  "name": "Example Mod",
  "description": "Example mod description.",
  "authors": ["YourName"],
  "license": "MIT",
  "icon": "assets/examplemod/icon.png",
  "environment": "*",
  "entrypoints": {
    "main": ["com.example.examplemod.ExampleMod"],
    "client": ["com.example.examplemod.client.ExampleModClient"]
  },
  "mixins": [
    "examplemod.mixins.json",
    { "config": "examplemod.client.mixins.json", "environment": "client" }
  ],
  "depends": {
    "fabricloader": ">=0.19.5",
    "minecraft": "~1.21.8",
    "java": ">=21",
    "fabric-api": "*"
  }
}
```

- `id` 只能使用小写字母、数字、下划线，且与包名、资源命名空间一致。
- `main` 入口实现 `net.fabricmc.api.ModInitializer`；`client` 入口实现 `ClientModInitializer`。
- `minecraft` 的 `~1.21.8` 表示同一次要版本；若要跨补丁版本，按实际兼容性改为范围。
- `environment` 为 `client` 时，专用于纯客户端模组；普通双端模组写 `*`。
- `processResources` 必须展开 `${version}`，否则加载器会报版本格式错误。
- 26.3 模板：`"minecraft": "~26.3"`、`"java": ">=25"`、`"fabricloader": ">=0.19.5"`；其余字段不变。

### 4.2 NeoForge `neoforge.mods.toml`
1.20.5+ 的文件名必须是 `META-INF/neoforge.mods.toml`；1.20.1-1.20.4 使用 `META-INF/mods.toml`。

```toml
license="All Rights Reserved"
[[mods]]
modId="examplemod"
version="1.0.0"
displayName="Example Mod"
description='''Example mod description.'''
[[dependencies.examplemod]]
modId="neoforge"
type="required"
versionRange="[21.8.57,)"
ordering="NONE"
side="BOTH"
[[dependencies.examplemod]]
modId="minecraft"
type="required"
versionRange="[1.21.8]"
ordering="NONE"
side="BOTH"
```

- `modLoader` 默认 `javafml`，`loaderVersion` 默认接受任意版本；需要显式限制时再加。
- `type` 可为 `required`、`optional`、`incompatible`、`discouraged`。
- 依赖的 `versionRange` 要与 `gradle.properties` 的 `neo_version` 和 `minecraft_version_range` 对应，不要把 1.21.8 的范围写进 1.21.1 工程。
- 若使用 MDK 的 `src/main/templates`，TOML 中写 `${mod_id}` 等占位符，并保留 `generateModMetadata` 任务。

### 4.3 Forge `mods.toml`
1.21.8 示例：

```toml
modLoader="javafml"
loaderVersion="[58,)"
license="All Rights Reserved"
[[mods]]
modId="examplemod"
version="1.0.0"
displayName="Example Mod"
description='''Example mod description.'''
[[dependencies.examplemod]]
modId="forge"
mandatory=true
versionRange="[58,)"
ordering="NONE"
side="BOTH"
[[dependencies.examplemod]]
modId="minecraft"
mandatory=true
versionRange="[1.21.8,1.22,)"
ordering="NONE"
side="BOTH"
```

1.20.1 对应值：`loaderVersion="[47,)"`、`modId="forge"`、`versionRange="[47,)"`、`minecraft` 范围 `[1.20.1,1.21)`。Forge 使用 `mandatory=true/false`，NeoForge 使用 `type="required"`，不要混用。

### 4.4 Mixin 配置
Fabric 常用两个配置，避免客户端 mixin 在服务端加载：

`examplemod.mixins.json`：

```json
{
  "required": true,
  "package": "com.example.examplemod.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["PlayerTickMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

`examplemod.client.mixins.json`：

```json
{
  "required": true,
  "package": "com.example.examplemod.mixin.client",
  "compatibilityLevel": "JAVA_21",
  "client": ["MinecraftMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

- 1.20.1/1.20.4 把 `compatibilityLevel` 改为 `JAVA_17`。
- Fabric 在 `fabric.mod.json` 的 `mixins` 数组里声明配置；客户端配置加 `"environment": "client"`。
- NeoForge/Forge 在 `mods.toml`/`neoforge.mods.toml` 中声明：

```toml
[[mixins]]
config="examplemod.mixins.json"
```

Forge/NeoForge 的 Mixin 支持与 refmap 生成方式以对应 MDK 的 Mixin 示例为准；Forge 1.20.1 通常还需要 MixinGradle 生成 refmap。Fabric Loom 会自动处理 refmap，不要手工伪造 refmap 文件。

### 4.5 `pack.mcmeta`
```json
{
  "pack": {
    "description": "Example Mod resources",
    "pack_format": 64
  }
}
```

`pack_format` 按 1.1 表：1.20.1=15、1.20.4=22、1.20.6=32、1.21.1=34、1.21.4=46、1.21.8=64；26.x 的 `pack_format` 未确认，需查官方资源包格式。放错格式通常只导致资源包警告/不加载，不会导致模组类加载失败。

## 5. 入口与注册示例
### 5.1 Fabric 主类
以下代码省略 `package` 和 `import`；主类位于 `com.example.examplemod`，客户端入口位于 `com.example.examplemod.client` 且在 `src/client/java`。

```java
public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "examplemod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
    @Override
    public void onInitialize() {
        ModItems.initialize();
        ModBlocks.initialize();
        LOGGER.info("ExampleMod initialized");
    }
}
```

```java
public class ExampleModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // client-only setup
    }
}
```

### 5.2 Fabric 物品/方块（1.21.8，Mojmap）
以下 Java 代码省略 `package` 和 `import`，均位于 `com.example.examplemod`。

```java
public final class ModItems {
    public static final ResourceKey<Item> RUBY_KEY =
        ResourceKey.create(Registries.ITEM, ExampleMod.id("ruby"));
    public static final Item RUBY = Registry.register(
        BuiltInRegistries.ITEM, RUBY_KEY,
        new Item(new Item.Properties().setId(RUBY_KEY)));
    private ModItems() { }
    public static void initialize() { }
}
```

```java
public final class ModBlocks {
    public static final ResourceKey<Block> RUBY_BLOCK_KEY =
        ResourceKey.create(Registries.BLOCK, ExampleMod.id("ruby_block"));
    public static final Block RUBY_BLOCK = Registry.register(
        BuiltInRegistries.BLOCK, RUBY_BLOCK_KEY,
        new Block(BlockBehaviour.Properties.of()
            .setId(RUBY_BLOCK_KEY)
            .strength(4.0f)
            .requiresCorrectToolForDrops()));
    public static final ResourceKey<Item> RUBY_BLOCK_ITEM_KEY =
        ResourceKey.create(Registries.ITEM, ExampleMod.id("ruby_block"));
    public static final Item RUBY_BLOCK_ITEM = Registry.register(
        BuiltInRegistries.ITEM, RUBY_BLOCK_ITEM_KEY,
        new BlockItem(RUBY_BLOCK, new Item.Properties().setId(RUBY_BLOCK_ITEM_KEY)));
    private ModBlocks() { }
    public static void initialize() { }
}
```

版本差异：

- 1.20.1 用 `new ResourceLocation(MOD_ID, "ruby")`；1.21+ 用 `ResourceLocation.fromNamespaceAndPath(...)`。
- 1.21.2+ 的 `Item.Properties`/`BlockBehaviour.Properties` 需要 `.setId(...)`；1.20.x/1.21.1 通常不需要。
- 1.20.x 的 `BlockBehaviour.Properties.of()` 用 `.requiresCorrectToolForDrops()`；不要混用 Yarn 的 `requiresTool()`。
- Fabric 注册必须在 `onInitialize` 或静态初始化阶段完成；不要在游戏运行中注册。

### 5.3 Fabric 创造模式标签页
```java
public static final ResourceKey<CreativeModeTab> TAB_KEY =
    ResourceKey.create(Registries.CREATIVE_MODE_TAB, ExampleMod.id("main"));
public static final CreativeModeTab TAB = FabricCreativeModeTab.builder()
    .title(Component.translatable("itemGroup.examplemod"))
    .icon(() -> new ItemStack(ModItems.RUBY))
    .displayItems((params, output) -> {
        output.accept(ModItems.RUBY);
        output.accept(ModItems.RUBY_BLOCK_ITEM);
    })
    .build();
// 在 initialize() 中：
Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY, TAB);
```

### 5.4 NeoForge 1.21.8
以下代码省略 `package` 和 `import`，均位于 `com.example.examplemod`。

```java
@Mod(ExampleMod.MODID)
public class ExampleMod {
    public static final String MODID = "examplemod";
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredBlock<Block> EXAMPLE_BLOCK =
        BLOCKS.registerSimpleBlock("example_block",
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);
    public static final DeferredItem<Item> EXAMPLE_ITEM =
        ITEMS.registerSimpleItem("example_item", new Item.Properties());
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB =
        TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.examplemod"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((params, output) -> output.accept(EXAMPLE_ITEM.get()))
            .build());
    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        TABS.register(modEventBus);
    }
}
```

NeoForge 1.20.4 的 `DeferredRegister.Blocks/Items` 和 `registerSimpleBlock/registerSimpleItem` 可用；1.20.1 NeoForge 没有官方 MDK，除非用户明确要求，否则改用 Forge。

### 5.5 NeoForge 客户端类
同样省略 `package` 和 `import`。
```java
@Mod(value = ExampleMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT)
public class ExampleModClient {
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        ExampleMod.LOGGER.info("Client user: {}", Minecraft.getInstance().getUser().getName());
    }
}
```

`@Mod(value=..., dist=Dist.CLIENT)` 和 `@EventBusSubscriber(..., value=Dist.CLIENT)` 一起使用，保证类不会在专用服务端加载。

### 5.6 Forge 1.21.8
以下代码省略 `package` 和 `import`，均位于 `com.example.examplemod`。

```java
@Mod(ExampleMod.MODID)
public final class ExampleMod {
    public static final String MODID = "examplemod";
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block",
        () -> new Block(BlockBehaviour.Properties.of()
            .setId(BLOCKS.key("example_block"))
            .mapColor(MapColor.STONE)));
    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block",
        () -> new BlockItem(EXAMPLE_BLOCK.get(),
            new Item.Properties().setId(ITEMS.key("example_block"))));
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item",
        () -> new Item(new Item.Properties().setId(ITEMS.key("example_item"))));
    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = TABS.register("main",
        () -> CreativeModeTab.builder()
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((params, output) -> output.accept(EXAMPLE_ITEM.get()))
            .build());
    public ExampleMod(FMLJavaModLoadingContext context) {
        var modBusGroup = context.getModBusGroup();
        BLOCKS.register(modBusGroup);
        ITEMS.register(modBusGroup);
        TABS.register(modBusGroup);
    }
}
```

Forge 1.20.1 的入口构造器参数是 `FMLJavaModLoadingContext`，用 `context.getModEventBus()`；`DeferredRegister` 用 `RegistryObject`，没有 `.setId(...)`。Forge 1.21.8 使用 EventBus 7 的 `getModBusGroup()` 和 `register(modBusGroup)`，不要照抄 1.20.1 的 `IEventBus` 写法。

## 6. 资源、配方、战利品表与数据生成
### 6.1 物品资源（1.21.4+）
```text
src/main/resources/assets/examplemod/
├─ items/ruby.json
├─ models/item/ruby.json
├─ textures/item/ruby.png
└─ lang/en_us.json
```

`assets/examplemod/items/ruby.json`（1.21.4+ 必需）：

```json
{
  "model": {
    "type": "minecraft:model",
    "model": "examplemod:item/ruby"
  }
}
```

`assets/examplemod/models/item/ruby.json`：

```json
{
  "parent": "minecraft:item/generated",
  "textures": { "layer0": "examplemod:item/ruby" }
}
```

1.21.1 及更早：不要创建 `items/ruby.json`，只保留 `models/item/ruby.json` 和纹理。1.21.4+ 缺少 `items/<id>.json` 时，物品在游戏里会没有模型。

### 6.2 方块资源
```text
assets/examplemod/blockstates/ruby_block.json
assets/examplemod/models/block/ruby_block.json
assets/examplemod/textures/block/ruby_block.png
```

`blockstates/ruby_block.json`：

```json
{ "variants": { "": { "model": "examplemod:block/ruby_block" } } }
```

`models/block/ruby_block.json`：

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": { "all": "examplemod:block/ruby_block" }
}
```

方块物品模型：

- 1.21.4+：`assets/examplemod/items/ruby_block.json`，内容 `{"model":{"type":"minecraft:model","model":"examplemod:block/ruby_block"}}`。
- 1.21.1 及更早：`assets/examplemod/models/item/ruby_block.json`，内容 `{"parent":"examplemod:block/ruby_block"}`。

语言文件：

```json
{
  "item.examplemod.ruby": "Ruby",
  "block.examplemod.ruby_block": "Ruby Block",
  "itemGroup.examplemod": "Example Mod"
}
```

### 6.3 配方
目录：

- 1.21+：`data/<namespace>/recipe/<name>.json`（单数）。
- 1.20.6 及更早：`data/<namespace>/recipes/<name>.json`（复数）。

1.21.2+ 的有序配方：

```json
{
  "type": "minecraft:crafting_shaped",
  "category": "misc",
  "pattern": ["RRR", "RIR", "RRR"],
  "key": {
    "R": "minecraft:redstone",
    "I": "minecraft:iron_ingot"
  },
  "result": {
    "id": "examplemod:ruby",
    "count": 1
  }
}
```

1.20.1 到 1.21.1 的同类配方使用对象形式：

```json
{
  "type": "minecraft:crafting_shaped",
  "pattern": ["RRR", "RIR", "RRR"],
  "key": {
    "R": { "item": "minecraft:redstone" },
    "I": { "item": "minecraft:iron_ingot" }
  },
  "result": {
    "item": "examplemod:ruby",
    "count": 1
  }
}
```

标签用 `#minecraft:logs` 这种字符串时，1.21.2+ 写成 `"#minecraft:logs"`；旧格式写成 `{"tag":"minecraft:logs"}`。配方不会自动出现在配方书中，还需要配方进度（advancement）解锁；用 datagen 的 `.unlockedBy(...)` 最稳妥。

### 6.4 方块战利品表
1.21+ 路径：`data/examplemod/loot_table/blocks/ruby_block.json`；1.20.6 及更早：`loot_tables/blocks/`。

```json
{
  "type": "minecraft:block",
  "pools": [
    {
      "rolls": 1,
      "bonus_rolls": 0,
      "entries": [
        { "type": "minecraft:item", "name": "examplemod:ruby_block" }
      ],
      "conditions": [
        { "condition": "minecraft:survives_explosion" }
      ]
    }
  ]
}
```

需要精准采集、时运、工具标签时，优先用 datagen 的 `BlockLootSubProvider`/`FabricBlockLootTableProvider`，不要手写复杂函数。

### 6.5 数据生成
Fabric：

```groovy
fabricApi {
    configureDataGeneration()
}
```

`fabric.mod.json` 增加：

```json
"entrypoints": {
  "main": ["com.example.examplemod.ExampleMod"],
  "fabric-datagen": ["com.example.examplemod.datagen.ModDataGenerator"]
}
```

生成器：

```java
public class ModDataGenerator implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();
        pack.addProvider(ModRecipeProvider::new);
        pack.addProvider(ModBlockLootProvider::new);
    }
}
```

运行 `gradlew.bat runDatagen`，输出默认在 `src/main/generated`。Loom 的 `configureDataGeneration()` 会把它接入构建；若手工配置，确认该目录被加入 resources。

NeoForge/Forge：使用 `runData` 运行配置，输出 `src/generated/resources`，并确保 `sourceSets.main.resources { srcDir 'src/generated/resources' }`。NeoForge 的 `data { clientData() }` 与 Forge 的 `data { args '--mod', ... }` 都要把 `--existing src/main/resources` 指对，否则已有资源不会参与生成。

数据生成不是必需的；小项目可以手写 JSON。手写后至少运行一次 `build`，并在开发客户端里检查模型、配方和掉落。

## 7. Mixin 最小写法
```java
package com.example.examplemod.mixin;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Player.class)
public abstract class PlayerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void examplemod$onTick(CallbackInfo ci) {
        // common-side logic only
    }
}
```

- 方法名加模组前缀（如 `examplemod$`），避免冲突。
- 目标方法签名必须与当前映射一致；用 IDE 跳转或 `javap` 确认，不要凭记忆写 `method = "..."`。
- 客户端 Mixin 放客户端配置，不要在 common 配置里引用 `net.minecraft.client.*`。
- Fabric 的 `fabric.mod.json` 声明配置；NeoForge/Forge 用 TOML 的 `[[mixins]]` 声明。
- 出现 `Critical injection failure` 时，先确认 MC 版本、映射、方法描述符和 refmap；不要靠 `require = 0` 掩盖错误。

## 8. 客户端与专用服务端分离
必须遵守：

- `src/main/java`（Fabric）或 common 包中不能出现 `net.minecraft.client.*`、`Minecraft.getInstance()`、渲染/屏幕/按键类。
- Fabric 把客户端代码放 `src/client/java`，并在 `fabric.mod.json` 中用 `client` 入口。
- NeoForge 用 `@Mod(value = MODID, dist = Dist.CLIENT)` 和 `@EventBusSubscriber(value = Dist.CLIENT)` 隔离客户端类。
- Forge 用 `@Mod.EventBusSubscriber(value = Dist.CLIENT, ...)` 或 `DistExecutor`/`FMLEnvironment.dist` 延迟加载客户端代码。
- 仅加 `@Environment(EnvType.CLIENT)` 不等于安全隔离；类加载发生在服务端时仍可能抛 `NoClassDefFoundError`。
- 每次改动后至少构建；能运行时再跑 `runServer` 验证专用服务端，不要只测客户端。

## 9. 构建、运行与产物
### 9.1 命令
| 目的 | Windows | Unix |
|---|---|---|
| 构建 | `gradlew.bat build` | `./gradlew build` |
| 清理 | `gradlew.bat clean` | `./gradlew clean` |
| 开发客户端 | `gradlew.bat runClient` | `./gradlew runClient` |
| 开发服务端 | `gradlew.bat runServer` | `./gradlew runServer` |
| Fabric 数据生成 | `gradlew.bat runDatagen` | `./gradlew runDatagen` |
| NeoForge/Forge 数据生成 | `gradlew.bat runData` | `./gradlew runData` |
| 停止 daemon | `gradlew.bat --stop` | `./gradlew --stop` |

Windows Git Bash 中若 `./gradlew.bat` 不工作，改用 `cmd //c gradlew.bat build`。不要用 `gradle` 代替 wrapper。

### 9.2 产物位置与区别
| 加载器 | 玩家安装的 jar | 不要安装的 jar |
|---|---|---|
| Fabric | `build/libs/<name>-<version>.jar`（1.21.8 及更早为 remap 后主产物；26.x 为非 remap） | `-sources.jar`、旧 Loom 的 `-dev.jar` |
| NeoForge | `build/libs/<modid>-<version>.jar` | `-sources.jar`（若启用了 `withSourcesJar`） |
| Forge 1.20.1/1.20.4 | `build/libs/<modid>-<version>.jar`，由 `reobfJar` 处理后 | `-sources.jar`、未 reobf 的 dev jar |
| Forge 1.20.6+ | `build/libs/<modid>-<version>.jar` | `-sources.jar` |

- `build` 成功不等于主 jar 可用；检查 jar 内是否包含 `fabric.mod.json` 或 `META-INF/neoforge.mods.toml`/`META-INF/mods.toml`，以及入口类。
- 不要用 `compileJava` 代替 `build`；不要用 `jar` 任务绕过 remap/reobf。
- 最终回复必须给出绝对路径和文件名，例如 `C:\...\build\libs\examplemod-1.0.0.jar`，并说明它对应哪个 MC/加载器版本。
- `-sources.jar` 只用于开发调试，安装后不会加载。

## 10. 常见错误与修复
| 现象 | 常见原因 | 修复 |
|---|---|---|
| `Unsupported class file major version` / `invalid source release` | JDK 与目标 Java 不符 | 1.20.1/1.20.4 用 JDK 17，1.20.6–1.21.8 用 JDK 21，26.1+ 用 JDK 25；设置 toolchain 或 `JAVA_HOME` |
| 找不到 `net.fabricmc:fabric-loom` / 插件解析失败 | 缺少 Fabric maven 或插件 id 写错 | 在 `settings.gradle` 的 `pluginManagement.repositories` 加 `https://maven.fabricmc.net/`；1.21.8 及更早用 `net.fabricmc.fabric-loom-remap`，26.x 用 `net.fabricmc.fabric-loom` |
| `Could not find method modImplementation()` | 没有应用 Loom 插件、dependencies 放错，或 26.x 仍照抄 1.21.8 | 应用 Fabric Loom；26.x 官方示例用 `implementation`；把依赖放进 `dependencies {}` |
| 26.3 加载器加载失败/依赖缺失 | 误以为 NeoForge/Forge 已支持 26.x | 先查官方发布页/Maven；未确认时交付 Fabric 26.3 并说明 |
| 启动报 `fabric.mod.json` 缺失/无效 | 资源路径错、`${version}` 未展开 | 文件放 `src/main/resources/fabric.mod.json`；保留 `processResources` 展开 |
| Mixin `Critical injection failure` / refmap 错误 | 方法签名、映射或 refmap 不匹配 | 用 IDE 确认目标方法；Fabric 重新构建；Forge 检查 MixinGradle/refmap |
| 专用服务端 `NoClassDefFoundError: net/minecraft/client/...` | common 代码引用了客户端类 | 移到 client source set / `Dist.CLIENT` 类；重新构建并 `runServer` |
| `Registry is already frozen` / 重复注册 | 注册时机过晚或重复 `new` | 只在 mod 初始化阶段用 DeferredRegister/Registry 注册；不要重复注册同一 id |
| 物品/方块紫黑模型 | 模型、纹理路径或 `items/` 定义缺失 | 检查命名空间与路径；1.21.4+ 增加 `assets/<ns>/items/<id>.json` |
| 配方不出现/不能合成 | 目录复数、JSON 格式代际不符、未解锁 | 1.21+ 用 `recipe/` 单数；1.21.2+ 用字符串 ingredient/`result.id`；加 advancement |
| 方块不掉落 | 战利品表路径/格式错、工具标签缺失 | 1.21+ 用 `loot_table/` 单数；检查 `type: minecraft:block`；加 `mineable/pickaxe` 标签 |
| 模组不在模组列表 | 元数据文件名/依赖范围错 | NeoForge 1.20.5+ 用 `neoforge.mods.toml`；检查 `versionRange` 与 MC/loader 一致 |
| `Missing or unsupported mandatory dependencies` | 依赖范围过窄或加载器版本不匹配 | 放宽 `minecraft`/loader 范围，或改用启动器实际版本 |
| Forge 1.21.8 `@SubscribeEvent` 不触发 | EventBus 7 注册方式变化 | 用 `context.getModBusGroup()`、`FMLCommonSetupEvent.getBus(...)` 或 MDK 示例写法 |
| `runData` 没有生成文件 | 输出目录未加入 resources 或参数错 | 检查 `--output`/`--existing`；加 `src/generated/resources` source dir |
| Gradle OOM/卡死 | 内存不足或 daemon 状态坏 | `org.gradle.jvmargs=-Xmx2G`（大项目 3G）；`gradlew.bat --stop`；必要时 `--no-daemon` |
| 安装后没有效果 | 装错 `-sources`/`-dev` jar，或 MC/loader 不匹配 | 安装无 classifier 的主 jar；核对启动器版本 |
| JSON 解析失败 | 尾逗号、注释、格式版本错误 | 用严格 JSON；对照目标版本的格式；不要给 `.json` 加注释 |
| Quilt `quilt.mod.json` 无效 | schema/入口字段错 | 使用 Quilt 模板；核对 `schema_version` 和 `quilt_loader` 结构 |

## 11. AI 代理工作流
1. 读取启动器输入：MC 版本、加载器、加载器版本、Java 版本、工作区路径。用户明确指定的值优先。
2. 先检查工作区：列出文件，查找 `gradlew`、`build.gradle`、`settings.gradle`、`gradle.properties`、`fabric.mod.json`、`META-INF/mods.toml`、`META-INF/neoforge.mods.toml`、`src/main/java`。
3. 若已有工程：识别加载器、MC、modid、包名、mappings；只做用户要求的最小修改。不要重新脚手架、不要覆盖已有源码、不要擅自换加载器/降级版本。
4. 若工作区为空：按第 0 节选择加载器；优先复制对应官方 MDK/示例的最小结构，而不是从记忆手写全部 Gradle 配置。
5. 锁定版本：从启动器或官方端点获取精确版本；不要使用 `latest`、`+` 或不存在的版本号。无法确认就明确说明并给出验证方法。
6. 先让空工程 `build` 通过，再加入物品/方块/配方；每批改动后重新 `build`。
7. 注册代码遵守目标加载器 API：Fabric 用 `Registry` + `ModInitializer`；NeoForge 用 `DeferredRegister` + `@Mod`；Forge 注意 FG6/FG7 与 EventBus 7 差异。
8. 资源文件与代码 id、命名空间、翻译键保持一致；1.21.4+ 不要漏 `items/` 模型定义。
9. 构建后检查 `build/libs` 主 jar 的内容和时间戳；能在本地运行时再跑 `runClient`/`runServer`。
10. 最终报告：绝对工程路径、构建命令、主 jar 绝对路径、目标 MC/加载器/loader 版本、已做的验证、仍不确定的点。

硬性规则：

- 不修改与任务无关的文件；不删除用户代码；不覆盖非空工程。
- 不把 Fabric API 依赖加入 NeoForge/Forge，反之亦然。
- 不声称“构建成功”而没有实际运行 `build`；不伪造产物路径。
- 不在未验证时断言某个版本存在；用第 12 节端点核实。
- 不把 `-sources.jar`/`-dev.jar` 当玩家安装包。

## 12. 按启动器指定版本落地与核实
### 12.1 启动器字段映射
| 启动器字段 | Fabric | NeoForge | Forge |
|---|---|---|---|
| MC 版本 | `minecraft_version`、`depends.minecraft` | `minecraft_version`、`minecraft_version_range` | `minecraft_version`、`minecraft_version_range` |
| 加载器版本 | `loader_version`、`depends.fabricloader` | `neo_version`、依赖 `neoforge` 的 `versionRange` | `forge_version`、`loaderVersion`/依赖 `forge` |
| Java 版本 | `options.release`、toolchain | `java.toolchain.languageVersion` | `java.toolchain.languageVersion` |
| 加载器类型 | Loom 工程 + Fabric 元数据 | ModDevGradle + `neoforge.mods.toml`/`mods.toml` | ForgeGradle + `mods.toml` |

示例：启动器给出 MC `1.21.1`、NeoForge `21.1.264`、Java `21`：

- `minecraft_version=1.21.1`
- `minecraft_version_range=[1.21.1]`
- `neo_version=21.1.264`
- toolchain Java 21
- `neoforge.mods.toml` 中 `neoforge` 依赖 `versionRange="[21.1.264,)"`，`minecraft` 依赖 `[1.21.1]`

如果启动器给出的加载器版本与 MC 不匹配，报告冲突，不要静默替换成另一个版本。

### 12.2 官方核实端点
| 内容 | URL |
|---|---|
| Fabric 开发版本页 | `https://fabricmc.net/develop` |
| Fabric Loader 版本 | `https://meta.fabricmc.net/v2/versions/loader` |
| Fabric Yarn 版本 | `https://meta.fabricmc.net/v2/versions/yarn/<mc>` |
| Fabric Maven | `https://maven.fabricmc.net` |
| Fabric 示例工程 | `https://github.com/FabricMC/fabric-example-mod/tree/<mc>` |
| Fabric 文档 | `https://docs.fabricmc.net/develop` |
| NeoForge 版本页 | `https://projects.neoforged.net/neoforged/neoforge` |
| NeoForge Maven 元数据 | `https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml` |
| NeoForge MDK | `https://github.com/NeoForgeMDKs` |
| NeoForge 文档 | `https://docs.neoforged.net` |
| Forge 下载/推荐版本 | `https://files.minecraftforge.net` |
| Forge Maven 元数据 | `https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml` |
| Forge MDK 示例 | `https://github.com/MinecraftForge/MDKExamples` |
| Forge 文档 | `https://docs.minecraftforge.net` |
| Parchment 文档 | `https://parchmentmc.org/docs/getting-started` |
| Parchment Maven | `https://maven.parchmentmc.org` |
| Mojang 版本清单（含 Java 版本） | `https://launchermeta.mojang.com/mc/game/version_manifest_v2.json` |
| Quilt Loader 版本 | `https://meta.quiltmc.org/v3/versions/loader` |
| Quilt Maven | `https://maven.quiltmc.org/repository/release/` |

### 12.3 为任意版本找配对的方法
1. 从 Mojang `version_manifest_v2.json` 找到目标版本的 JSON，读取 `javaVersion.majorVersion`。
2. Fabric：用 `/v2/versions/loader/<mc>` 查可用 loader；用 `/v2/versions/yarn/<mc>` 查 Yarn；在 Fabric Maven 查 Fabric API 的 `<version>+<mc>`。
3. NeoForge：查 Maven 元数据中与 MC 对应的 `20.x`/`21.x` 版本；到 NeoForgeMDKs 找同名 MDK 分支；没有 MDK 的版本标记“无官方模板”。
4. Forge：查 Maven 元数据中的 `<mc>-<forge>`；1.20.1/1.20.4 用 FG6，1.20.6+ 用 FG7；再查 MDKExamples 的对应分支。
5. Parchment：在 Parchment 站点/Maven 查 `parchment-<mc>` 的日期版本；Loom 用 `@zip` 坐标，NeoForge 用 `mappingsVersion`。
6. 26.1+：不再使用 Yarn，使用未混淆代码；Fabric 26.3 官方示例为 Loom `1.18-SNAPSHOT`（`net.fabricmc.fabric-loom`，非 remap）、Gradle `9.7.1`、Loader `0.19.5`、API `0.163.0+26.3`、Java 25。NeoForge/Forge/Quilt 的 26.x 支持与构建配置未确认时，查官方发布页/MDK/模板，不要猜测。

## 13. Quilt
- Quilt Loader 通常能加载 Fabric 模组；如果用户只是想在 Quilt 上运行，优先交付 Fabric 模组，并在 `fabric.mod.json` 中不要声明 Quilt 不兼容。
- 原生 Quilt 工程使用 `quilt.mod.json`，入口通常为 `init` 和 `client_init`，API 使用 QSL（Quilt Standard Libraries），而不是 Fabric API。
- `quilt.mod.json` 的最小结构（字段和版本范围需按 Quilt 模板/规范核实）：

```json
{
  "schema_version": 1,
  "quilt_loader": {
    "group": "com.example",
    "id": "examplemod",
    "version": "1.0.0",
    "metadata": {
      "name": "Example Mod",
      "description": "Example mod description.",
      "license": "MIT"
    },
    "entrypoints": {
      "init": ["com.example.examplemod.ExampleMod"],
      "client_init": ["com.example.examplemod.client.ExampleModClient"]
    },
    "depends": [
      { "id": "quilt_loader", "versions": ">=0.20.0" },
      { "id": "minecraft", "versions": ">=1.21.1" },
      { "id": "java", "versions": ">=21" }
    ]
  }
}
```

- Quilt Loom 插件 id、版本和 Gradle 兼容范围未在本次核对中确认；使用 Quilt 官方模板生成工程，不要凭记忆写插件版本。
- 如果项目依赖 Fabric API 的生态，继续使用 Fabric 工程通常比原生 Quilt 更省事；只有需要 QSL 专属 API 时才做原生 Quilt。
- 2026-10-24 核对：Quilt meta 将 26.3 标为 stable，对应 loader 为 `0.20.0-beta.9`；原生 Quilt/QSL 对 26.x 的工程模板与配置需核实。

## 14. 参考入口
优先按以下顺序查证，而不是依赖记忆：

1. 对应 MC 的官方 MDK/示例分支：Fabric `fabric-example-mod`、NeoForgeMDKs、MinecraftForge/MDKExamples。
2. 加载器官方文档：Fabric Docs、NeoForge Docs、Forge Docs。
3. Maven 元数据与版本页：Fabric Maven/meta、NeoForge Maven/projects、Forge Maven/files。
4. Parchment 文档与 Maven。
5. Mojang 版本清单（Java 版本、MC 元数据）。
6. Modrinth 可用于确认模组/依赖是否发布，但不要用 Modrinth 页面替代加载器官方版本矩阵。

当官方页面与本文件冲突时，以官方页面和实际构建结果为准；在最终回复中说明差异和核实时间。
