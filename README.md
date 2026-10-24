# SS Launcher

一个简洁的 Minecraft Java 版第三方启动器。

支持第三方皮肤站（authlib-injector / Yggdrasil）、Microsoft 正版和离线三种登录方式，内置原版与 Fabric、Quilt、Forge、NeoForge 的安装，并在安装时自选游戏版本与加载器。

## 功能

- **登录方式**
  - 第三方登录：填写任意兼容 authlib-injector 的 Yggdrasil 服务器地址即可登录，登录后自动同步皮肤。
  - Microsoft 正版：OAuth 2.0 设备代码流，客户端配置已内置于启动器，无需填写 Client ID。
  - 离线登录：在本机生成离线 UUID，不访问网络。
- **账号皮肤**
  - 每个账号可单独更换皮肤；账号列表、顶栏与账号卡的头像直接显示该皮肤的脸部（含外层帽子），没有皮肤时才使用生成的占位头像。
  - Microsoft 账号可以直接上传皮肤或恢复默认皮肤；第三方账号在皮肤站支持 authlib-injector 上传接口时可直接上传，否则可打开皮肤站更换后一键同步。
  - 支持 64×32、64×64 与高清 PNG、经典/纤细模型、拖放预览，以及“仅设为启动器头像”。
- **版本与加载器**
  - 原版、Fabric、Quilt、Forge、NeoForge 全部可在安装界面选择。
  - 加载器版本列表会区分稳定版与预览版，并标记推荐版本。
  - 每个实例拥有独立的存档、模组、资源包、光影和配置目录。
- **游戏文件管理**
  - 按实例管理模组、材质包、光影包和存档：添加/导入、启用/禁用、删除、打开所在文件夹。
  - 支持把文件或存档文件夹直接从资源管理器拖入列表。
  - 一键跳转到实例根目录、模组/材质包/光影包/存档目录、日志与截图目录。
- **资源下载**
  - 从 Modrinth 与 CurseForge 搜索模组、材质包、资源包、光影包和整合包。
  - 可按当前实例的游戏版本与加载器筛选，并在版本列表中标注必需前置、可选前置与内置依赖。
  - 下载完成后选择要安装的实例并自动放入对应目录；整合包会创建独立实例并自动安装加载器与全部文件。
  - 支持把本地 `.mrpack` 或 CurseForge `.zip` 整合包直接拖入窗口，自动识别格式、创建实例并下载安装清单内容；清单在压缩包根目录或顶层文件夹内都可以识别。
  - 资源页面提供“导入整合包”按钮，不方便拖拽时可以直接选择文件。
- **游戏运行**
  - 自动读取版本 JSON 的 libraries、natives、资源索引和 JVM / 游戏参数。
  - 自动选择合适的本机 Java；缺失时下载 Mojang 官方运行时。
  - 支持 Forge / NeoForge 的 BootstrapLauncher 与新式 FML 启动流程。
  - 内置游戏控制台与日志窗口。
- **外观**
  - 顶部导航栏；背景图片可更换，并可调节模糊与遮罩强度，导入的图片保存在数据目录的 `backgrounds/` 中。
  - 界面配色按背景图片自动生成（莫奈取色），所有页面、对话框与控制台统一跟随；不使用渐变。
  - Minecraft 风格元素：立体按钮、物品栏格子、快捷栏式启动区（数字键 1–9 选择实例，Enter 启动）、经验条式进度条。
- **点对点联机与语音**
  - 通过房间号与好友直连 Minecraft：TCP 直连、房主反向拨号与 UDP 打洞同时尝试，哪条先成功就用哪条；引导服务器只交换房间信息、地址与观察到的 UDP 端点，不转发游戏数据。
  - 房间内语音采用全互联 P2P 直连：每位玩家与其他成员各建立一条语音流（TCP 或 UDP/KCP），音频不经过引导服务器，也不经过房主。
  - 可调节麦克风输入与扬声器输出音量（0–200%），可单独静音某位玩家，并实时显示谁在说话。
- **下载**
  - 默认优先使用国内镜像（QLU、MC 光速镜像、BMCLAPI），失败时回退官方源，可在设置中切换。
  - Forge / NeoForge 安装器与依赖库自动走实测最快的 QLU 镜像；版本元数据选择完整且可用的源，避免等待超时。
  - 所有下载做 SHA-1 校验，支持断点重试。
  - 资源文件按哈希去重，多个实例共享同一份游戏资源。

## 环境要求

- Windows 10 / 11（x64）
- JDK 21（构建与运行启动器本身）
- 首次构建需要联网下载 Gradle 依赖

游戏运行所需的 Java 由启动器自动检测；找不到时会下载 Mojang 官方运行时，不需要手动安装 Java 8/17/21/25。

## 快速开始

在项目根目录执行：

```bat
gradlew.bat run
```

或者直接双击 `run.bat`。首次启动会创建数据目录：

```text
%APPDATA%\SSLauncher
```

该目录包含账号、实例、版本、依赖库、资源、皮肤缓存与日志。卸载启动器不会自动删除这些文件。

### 构建可分发目录

```bat
gradlew.bat build installDist
```

生成：

```text
build\install\SS-Launcher\bin\SS-Launcher.bat
```

整个 `build\install\SS-Launcher` 目录可以复制到其他机器运行（目标机器仍需安装 JDK 21）。

### 打包为 Windows 应用

使用 JDK 21 的 `jpackage` 生成自带运行时的应用目录。务必传入 `--icon`，否则 exe 会使用 JDK 默认图标，和应用窗口、任务栏上的图标不一致：

```bat
jpackage --type app-image --name SS-Launcher ^
  --dest build\jpackage ^
  --input build\install\SS-Launcher\lib ^
  --main-jar SS-Launcher-1.0.0.jar ^
  --main-class com.sslauncher.Main ^
  --icon packaging\SS-Launcher.ico ^
  --app-version 1.0.0 ^
  --add-modules ALL-MODULE-PATH ^
  --java-options -Dfile.encoding=UTF-8 ^
  --java-options -Dsun.stdout.encoding=UTF-8
```

`packaging\SS-Launcher.ico` 与 `src\main\resources\com\sslauncher\assets\icon.png` 使用同一张草方块图标，因此资源管理器中的 exe、窗口标题栏和任务栏图标会保持一致。

## Microsoft 正版登录

正版登录使用 Microsoft OAuth 2.0 设备代码流。Azure 应用的 Client ID 已内嵌在启动器中，用户无需填写，也无法在设置、配置文件或启动参数中修改。

在账号页选择 **Microsoft**，点击“获取登录代码”，然后在浏览器打开 `https://www.microsoft.com/link` 输入一次性代码即可完成登录。

如果最后一步提示 **Invalid app registration**，说明内嵌的 Azure 应用尚未获得 Minecraft API 访问权限。这与账号是否购买过 Minecraft 无关，需要在 [aka.ms/mce-reviewappid](https://aka.ms/mce-reviewappid) 提交应用审核；审核通过后无需更新启动器。

## 第三方登录

第三方登录基于 [authlib-injector](https://github.com/yushijinhun/authlib-injector) 与 Yggdrasil 协议，适用于 Blessing Skin 等皮肤站。

- 在登录窗口的 **第三方登录** 页填写服务器地址，例如 `https://example.com/api/yggdrasil`。
- 也可以把皮肤站页面上的 **“将此按钮拖动至启动器”** 按钮直接拖入启动器窗口（包括已经打开的登录窗口和服务器地址框），或拖动浏览器地址、authlib-injector 一键登录链接：启动器会兼容浏览器提供的纯文本、HTML、URI 列表等拖放格式，自动补全 `/api/yggdrasil`、读取皮肤站名称并打开登录窗口。拖动时窗口中央会显示虚线提示框，松开即可。
- 如果皮肤站按钮的拖拽脚本缺失，浏览器只提供 Chromium 内部格式，启动器会根据拖动内容的页面来源自动探测该地址是否提供 Yggdrasil 接口；探测成功才会打开登录窗口，否则会提示该内容不是皮肤站登录按钮。
- 地址也可以在 **设置 → 第三方登录与正版** 中预先配置，登录时会自动填入。
- 启动器会读取服务器元数据中的名称，并在启动游戏时注入该服务器的会话以同步皮肤。
- 服务器未提供主页时，界面不会显示“打开皮肤站”入口。

## 账号与皮肤

账号页的每个账号都有独立的皮肤设置。点击账号头像或行末的调色板按钮即可打开 **更换皮肤**：

- **头像**：账号列表、顶栏和账号卡的头像直接使用该账号皮肤的脸部采样（含帽子/外层）；没有可用皮肤时才回退到程序生成的像素头像。
- **选择文件**：支持 64×32、64×64 以及它们的整倍数高清 PNG（最大 1024×1024、4 MB）。选择后可在 **经典**（4 像素手臂）与 **纤细**（3 像素手臂）之间切换；也可以把 PNG 直接拖入预览框。左侧会显示头身预览。
- **Microsoft 账号**：上传会写入 Minecraft 个人档案，在正版服务器中立即生效；也可以恢复默认皮肤。上传前会自动刷新过期的 Microsoft 登录。
- **第三方账号**：启动器从皮肤站的会话服务器读取 `textures` 属性来显示真实皮肤。如果站点实现了 authlib-injector 的材质上传接口（`PUT /api/user/profile/{uuid}/skin`，并通过 `uploadableTextures` 声明支持 `skin`），可直接在这里上传；包括 Blessing Skin 默认配置在内的许多站点只允许在网站上传，此时界面会提示，并提供 **打开皮肤站** 与 **从皮肤站同步**——在网站换好后点“同步”即可更新启动器头像。
- **仅设为启动器头像**：图片只保存在本机，不影响游戏内皮肤；适合离线账号或不希望修改账号皮肤的玩家。
- **恢复默认**：Microsoft 与支持上传的第三方站点会尝试清除账号当前皮肤；如果站点没有上传接口，可以只清除启动器缓存，或继续使用“仅设为启动器头像”。

皮肤缓存按账号保存在 `%APPDATA%\SSLauncher\skins\<账号哈希>\`：`server.png` / `server.json` 是服务器皮肤缓存，`local.png` / `local.json` 是启动器专用头像。服务器皮肤每十分钟刷新一次，删除账号时会一并清理。

## 联机与语音

左侧导航的 **联机** 页面提供点对点（P2P）联机：

- **创建房间**：让游戏「对局域网开放」或启动服务器，点击 **自动检测** 识别日志中的游戏端口后才能创建房间；检测时也会检查该实例是否安装了 LAN World Plug-n-Play，缺少时会提示安装。创建后把房间号发给好友。
- **加入房间**：输入房间号即可。连接建立后，把页面显示的本地地址填入 Minecraft 的「直接连接」。
- 引导服务器只负责匹配房间、交换地址，并以 STUN 的方式观察双方的公网 UDP 端点；游戏流量始终在两个玩家之间直连，不经过中转。
- 客人加入后会**同时**尝试 TCP 正向拨号、房主反向拨号和 UDP 打洞，第一条可用的路径胜出；两条都失败才会显示连接失败（最长约 32 秒）。
- 能否直连最终取决于**房主一侧是否允许入站**：公网 IPv6 最可靠；电信手机热点通常最开放，联通（部分号段）、移动、广电的手机热点常拦截 IPv6 入站，双方都在对称 CGNAT 后时 UDP 打洞通常也会失败。失败提示会给出本机 IPv6、房主公网地址数量、IPv6 号段和疑似 iPhone 热点等诊断。
- 启用 UPnP 时会**同时映射 TCP 与 UDP**；UDP 与 TCP 使用同一数字端口，因此客户端可以从对方公布的 `/ip6/.../tcp/<port>` 直接推导 IPv6 UDP 端点，不要求引导服务器具备 IPv6。
- 四大运营商手机热点/家用宽带的 IPv6 入站差异、可用路径与资料来源，见 [docs/CARRIER-NAT.md](docs/CARRIER-NAT.md)。

语音聊天集成在同一个页面：

- 勾选 **启用语音聊天** 后，加入房间会自动连接房间内所有玩家的语音流。默认使用系统默认麦克风与扬声器。
- **麦克风音量** 与 **输出音量** 均为 0–200%，100% 为原始音量；输入增益也可用来放大较安静的头戴麦克风。
- 房间好友列表中，每位非自己的玩家都有 **静音 / 取消静音** 按钮，只影响本机播放；说话中的玩家会显示绿色标记。
- 麦克风只会在房间内、且语音已启用时打开；退出房间或关闭语音会立即释放音频设备。
- 语音与游戏一样是点对点直连，**音频不会经过引导服务器，也不会经过房主**。代价是同一房间内的玩家会互相获得对方公布的连接地址（包括公网/局域网 IP），这是建立直连所必需的。
- 房主与客人之间会复用已有的游戏连接，所以客人即使在 NAT 后也能与房主双向语音。客人之间会通过 TCP 或 UDP 打洞建立语音；只有在双方都处于对称 NAT 且 UDP 被封禁时才无法建立，此时语音按要求不使用中转。
- UDP 打洞需要引导服务器同时放行 **TCP 与 UDP 的 4001 端口**（安全组和系统防火墙都要）。只放行 TCP 时房间仍可创建，但双方都在 CGNAT 后时无法直连。
- 房主电脑还需要在 Windows 防火墙中放行启动器的**入站 TCP 与 UDP**；手机热点下网络常被识别为“公用”，只在“专用”放行不会生效。

> 语音与 UDP 打洞是后加入的协议。引导服务器需要升级到最新版本，才会下发房间密钥和观察到的 UDP 端点；否则客户端会自动退回仅 TCP 直连。协议细节见 [docs/P2P-PROTOCOL.md](docs/P2P-PROTOCOL.md)。

## 游戏文件管理

左侧导航的 **游戏文件** 页面按实例整理游戏内容，顶部的实例下拉框可以在不同实例之间切换。

- **模组**：添加 `.jar` 文件；禁用时重命名为 `*.jar.disabled`，文件仍保留，可随时重新启用。
- **材质包 / 光影包**：添加 `.zip` 文件，也支持文件夹形式的内容；同样通过 `.disabled` 后缀启用或禁用。
- **存档**：列出包含 `level.dat` 的世界文件夹，可以打开世界目录、导入其他存档文件夹或删除存档。
- **删除**：优先移动到系统回收站；系统不支持回收站时才会永久删除。
- **跳转到文件夹**：页面右上角可以快速打开模组、材质包、光影包、存档、日志、截图或实例根目录。
- **拖入添加**：把 `.jar`、`.zip` 或存档文件夹从资源管理器拖到文件列表即可导入。

实例列表右侧的文件夹按钮、右键菜单（⋯）和主页的“游戏文件”入口都可以直接进入当前实例的管理页面。

## 资源下载

左侧导航的 **资源下载** 页面从 Modrinth 与 CurseForge 获取内容：

- **类型**：模组、材质包、资源包、光影包、整合包。
- **实例筛选**：选择实例后，搜索会按该实例的 Minecraft 版本与加载器过滤；不选实例则显示全部结果。每张卡片会显示与所选实例的兼容情况。
- **版本与前置**：打开下载对话框后可以选择具体文件版本。版本列表会标注 Beta/Alpha，并列出该版本声明的依赖：
  - **必需前置**（金色）：安装前需要另外安装的模组。
  - **可选前置**：可选联动内容。
  - **已内置**：文件已经打包在下载内容中，无需单独安装。
  - **工具 / 不兼容**：仅作提示。
  - 依赖旁的“搜索”按钮会直接以该名称在当前实例条件下搜索模组。
- **安装位置**：普通资源下载完成后会弹出实例选择，确认后自动复制到该实例的 `mods`、`resourcepacks` 或 `shaderpacks` 目录，并处理重名文件。
- **整合包**：选择整合包后会先读取清单中的 Minecraft 版本与加载器，创建新的独立实例，安装对应加载器后下载全部文件并解压 overrides。安装失败或取消时会自动删除未完成的实例。
- **拖入整合包**：把本地 `.mrpack` 或 CurseForge 导出的 `.zip` 拖到启动器窗口任意位置，松开后会读取清单、确认实例名称并自动下载安装。清单位于压缩包根目录或顶层文件夹内都可以识别。普通 `.zip` 资源包拖到「游戏文件」列表时仍按原方式导入，不会被误判为整合包；CurseForge 整合包需要已配置 API Key。
- **手动导入**：在「资源」页点击“导入整合包”，选择 `.mrpack` 或 `.zip` 文件，效果与拖入相同。如果启动器以管理员身份运行，Windows 会阻止从资源管理器拖放，此时请使用该按钮。
- **资源图标**：卡片会显示 Modrinth / CurseForge 的项目图标（支持 WebP），下载后缓存在数据目录的 `cache/icons/`，之后的搜索会直接使用本地缓存；图标下载失败或项目没有图标时仍显示首字母占位图。
- **下载速度**：进度框会显示实时速度。大文件（≥ 8 MB）自动分段、多连接下载；连接 45 秒没有收到数据会被主动放弃并重试，避免卡在一个失效连接上。Modrinth / ForgeCDN 直连失败时会自动回退到 MCIM 国内镜像，所有文件仍按 SHA-1 校验。

### CurseForge API Key

Modrinth 无需配置即可使用。CurseForge 的官方 API 要求 Key：到 [console.curseforge.com](https://console.curseforge.com/) 免费申请后，在 **设置 → 资源下载 → CurseForge API Key** 中填写并点击“测试连接”。Key 只保存在本机 `%APPDATA%\SSLauncher\config.json`，不会上传到其他位置。

未配置 Key 时，“全部来源”只会返回 Modrinth 结果，页面会给出提示；仍可正常安装 Modrinth 资源。配置后可在来源栏切换“全部来源 / Modrinth / CurseForge”。

## 下载源

设置中提供四种模式，游戏本体、依赖库、资源文件、Java 运行时与加载器下载都会遵循该选择：

- **国内镜像（自动）**：加载器与依赖库优先 QLU（`mirrors4.qlu.edu.cn`），其余文件依次尝试 MC 光速镜像、BMCLAPI，失败后回退官方源（默认）。
- **MC 光速镜像**：Maven 依赖优先 QLU，之后使用 MC 光速镜像（`mirror.hkmc.online`），适合国内网络。
- **BMCLAPI 镜像**：Maven 依赖优先 QLU，之后使用 BMCLAPI，失败后回退官方源。
- **Mojang 官方**：只使用官方源（NeoForge 使用其官方 CDN）。

所有镜像下载都会用版本清单中的 SHA-1 校验；文件不匹配时会丢弃并从下一个来源重试。加载器版本元数据按实测速度排序并在取到可用列表后立即停止，不再逐个等待失效的镜像；Forge 元数据使用完整的 MC 光速镜像，NeoForge 使用 QLU 并以官方 CDN 兜底。

资源下载（Modrinth / CurseForge 的模组、整合包等）不受上面的游戏文件镜像影响，始终优先使用官方 CDN：这些文件由 Fastly / ForgeCDN 提供，国内镜像只缓存部分模组，因此 MCIM 仅作为直连失败后的兜底，不会替代官方源。

## 开发与验证

```bat
gradlew.bat build
```

端到端探针（独立数据目录 `%APPDATA%\SSLauncher-e2e`，不会影响正式数据）：

```bat
gradlew.bat e2e --args="list forge 1.20.1"
gradlew.bat e2e --args="install fabric 1.20.1"
gradlew.bat e2e --args="launch fabric-loader-0.19.5-1.20.1"
gradlew.bat e2e --args="skin https://example.com/api/yggdrasil"
gradlew.bat e2e --args="resource sodium <实例 id>"
```

`skin` 模式也可以省略地址，此时使用设置中已保存的服务器。

UI 截图探针（需要桌面环境）：

```bat
gradlew.bat uiShot -Ppage=HOME -Pshot=build\ui-home.png
gradlew.bat uiShot -Pdialog=login -Pshot=build\ui-login.png
gradlew.bat uiShot -Ppage=ACCOUNTS -Pdialog=skin -Pshot=build\ui-skin.png
```

## 目录结构

```text
src/main/java/com/sslauncher/
  auth/       第三方登录、Microsoft、离线登录与 authlib-injector
  core/       配置、下载、HTTP、路径、实例存储
  model/      版本 JSON、资源索引、账号、实例模型
  multiplayer/ libp2p 联机、游戏隧道、房间语音与 Opus 音频引擎
  resource/   Modrinth / CurseForge 搜索、版本、依赖与整合包安装
  service/    安装、启动、Java 运行时、加载器与资源服务
  skin/       账号皮肤同步、上传、校验与本地缓存
  ui/         JavaFX 界面、页面与对话框
src/main/resources/com/sslauncher/
  assets/     启动器图标与背景图
  css/        样式（颜色由 ui/Theme 按背景图片生成）
src/test/java/... 开发用安装/启动/UI 探针
```

## 说明

- 本项目是第三方启动器，与 Mojang Studios / Microsoft 无隶属关系。Minecraft 是 Mojang Studios 的商标。
- 架构设计参考了 Prism Launcher（GPL-3.0），未复制其源代码。
- 第三方登录依赖 [authlib-injector](https://github.com/yushijinhun/authlib-injector)（AGPL-3.0）。
- 联机与语音使用 [jvm-libp2p](https://github.com/libp2p/jvm-libp2p)（Apache-2.0）。
- 语音编解码使用 [Concentus](https://github.com/lostromb/concentus)（纯 Java Opus 实现，BSD-3-Clause）；Opus 由 Xiph.Org 开发（BSD-3-Clause）。音频采集与播放使用 JDK 自带的 Java Sound。
