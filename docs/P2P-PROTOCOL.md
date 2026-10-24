# SS Launcher 联机协议（客户端 ↔ 引导服务器）

本文档描述启动器 P2P 联机功能的线上协议，供引导服务器（bootstrap / rendezvous server）实现使用。
**引导服务器只负责“房间匹配 + 交换地址 + 观察 UDP 公网端点”，不转发游戏数据，也不转发语音**；
游戏与语音流量始终由玩家之间直连。

## 1. 总体结构

```
玩家 A（房主） ── libp2p 信令流 ──> 引导服务器 <── libp2p 信令流 ── 玩家 B（客人）
      │                              ▲  ▲                              │
      │                              │  └── UDP 端点观察（STUN 式）─────┤
      │                              │                                 │
      ├──────── 直连 TCP（libp2p）/ 直连 UDP（打洞 + KCP）──────────────┤
      │                                                                    │
      └──────────── 每连接一条隧道流，或一条 KCP 游戏流 ───────────────────┘

语音（可选，房间内全互联）：A ←── 直连语音流（TCP 或 UDP/KCP）──> B、C
```

- 身份：每个客户端在 `%APPDATA%\SSLauncher\p2p\identity.key` 持久化 ED25519 密钥；
  服务器用 libp2p 的 `stream.remotePeerId()` 识别玩家，**不要信任 JSON 中自报的 PeerID**。
- 监听：客户端监听 `/ip4/0.0.0.0/tcp/<port>`（默认 4001，被占用时自动 +1，最多试 10 个端口），
  并在**同一个数字端口**上绑定 UDP 用于端点发现、打洞与 KCP 传输（TCP 与 UDP 端口空间独立，
  不冲突）。若该 UDP 端口已被其他程序占用，则退回临时端口；此时 IPv6 UDP 端点无法从
  TCP 地址推导，只能依赖服务器观察到的 IPv4 UDP 端点。
- 信令连接：客户端主动拨打服务器，打开一条 `/sslauncher/rendezvous/1.0.0` 流。
- 直连连接：客户端之间优先同时尝试 libp2p TCP（Noise + yamux）与 UDP 打洞，
  哪条先可用就用哪条；服务器只帮助双方发现彼此的公网 UDP 端点，**从不转发任何载荷**。
  因为 UDP 与 TCP 使用同一数字端口，客户端还会从对方公布的 `/ip6/<addr>/tcp/<port>`
  和公网 IPv4 地址直接推导 UDP 候选，不依赖服务器具备 IPv6。
- 房间密钥：服务器为每个房间生成 32 字节随机密钥，仅通过已加密的信令流下发，
  用于认证打洞包和加密 UDP/KCP 数据。服务器本身不需要、也不会看到 KCP 流量。

协议 ID：

| 用途 | 协议 ID |
| --- | --- |
| 信令（房间匹配） | `/sslauncher/rendezvous/1.0.0` |
| 游戏隧道 | `/sslauncher/tunnel/1.0.0` |
| 语音（房间内直连） | `/sslauncher/voice/1.0.0` |

## 2. 信令流格式

一条信令流使用**按行分隔的 UTF-8 JSON**（NDJSON）：每条消息一行，以 `\n` 结尾；
单行上限 256 KiB。客户端使用 `LineBasedFrameDecoder` + `StringDecoder` 解析，
服务器实现同样建议使用行解码器（不要把多条 JSON 拼在一个 libp2p 帧里）。

请求/响应均带 `id` 字段（字符串，客户端自增）。服务器必须在响应中原样回传 `id`；
客户端用 `id` 完成对应请求。服务器主动推送的事件（`peer-joined` 等）不需要 `id`。

客户端请求超时为 20 秒；超时后该 `id` 的响应会被忽略。

### 2.1 `hello`（客户端 → 服务器）

客户端建立信令流后的第一条消息。

```json
{"id":"1","type":"hello","v":1,"name":"Steve","listenPort":4001}
```

服务器回复 `welcome`：

```json
{"id":"1","type":"welcome","publicIp":"203.0.113.9"}
```

- `v` 目前固定为 1；服务器可据此拒绝过旧客户端。
- `publicIp` 可选，客户端仅记录日志；可返回服务器观察到的客户端公网 IP。
- `listenPort` 是客户端 P2P 监听端口，用于服务器为其他房间成员构造候选地址。

### 2.2 `udp-token`（客户端 → 服务器）

客户端在 `create` / `join` 之前注册一个随机令牌，随后用同一个 UDP 套接字向服务器的
UDP 端口发送发现包。服务器据此把“令牌 → 会话”关联起来，并记录观察到的公网 UDP 端点。

```json
{"id":"2","type":"udp-token","token":"c2VjcmV0LXRva2VuLTE2" }
```

```json
{"id":"2","type":"udp-token-ok"}
```

- `token` 为 16–128 个 ASCII 字符（客户端用 16 字节随机数的 base64url，无填充）。
- 服务器应保存 `token → session` 映射，会话关闭时删除；令牌只用于 UDP 发现包，
  不能拿来查询其他玩家的端点。
- 服务器没有 UDP 监听或令牌无效时，客户端会自动退回“仅 TCP 直连”，房间功能不受影响。
- **UDP 发现包格式**（直接发给服务器 `udpPort`，默认与 TCP 端口相同）：

| 方向 | 格式 |
| --- | --- |
| 请求 | ASCII `SSUDP1` + 令牌 |
| 应答 | ASCII `SSUDP2` + 令牌 + 1 字节地址族（4/6）+ 4 或 16 字节地址 + 2 字节大端端口 |

服务器只在令牌有效时回应，内容是从 UDP 数据报源地址观察到的公网端点。服务器
**不得**转发发现包，也不得把端点告诉房间外的玩家。

### 2.3 `create`（客户端 → 服务器）

房主创建房间。

```json
{
  "id": "2",
  "type": "create",
  "gamePort": 25565,
  "game": "Minecraft 1.21.1",
  "name": "Steve",
  "listenPort": 4001,
  "addresses": [
    "/ip4/192.168.1.10/tcp/4001",
    "/ip4/203.0.113.9/tcp/4001"
  ]
}
```

服务器回复：

```json
{"id":"2","type":"created","code":"AB12CD","roomKey":"<base64 32 字节>"}
```

- `code`：房间号，客户端会规范化为大写字母/数字，长度 4–10。
- `roomKey`：本房间的 32 字节随机密钥，标准 Base64（可带 `=` 填充）。
  服务器为每个房间生成一次，在 `created` / `joined` 中下发给房间成员。
  它只用于客户端之间的打洞认证与载荷加密，服务器不保存它的用途以外的状态。
- 服务器应把房主的 `peerId`（取自 `stream.remotePeerId()`）、`name`、`gamePort`、
  `addresses` 以及**创建时观察到的 UDP 端点**保存在该房间下，供 `join` 使用。
- 同一玩家重复 `create` 时，服务器应先关闭其旧房间。

### 2.4 `join`（客户端 → 服务器）

客人加入房间。

```json
{
  "id": "3",
  "type": "join",
  "code": "AB12CD",
  "name": "Alex",
  "listenPort": 4001,
  "addresses": ["/ip4/192.168.1.20/tcp/4001"]
}
```

服务器回复（成功）：

```json
{
  "id": "3",
  "type": "joined",
  "code": "AB12CD",
  "roomKey": "<base64 32 字节>",
  "host": {
    "peerId": "12D3KooW...",
    "name": "Steve",
    "listenPort": 4001,
    "gamePort": 25565,
    "game": "Minecraft 1.21.1",
    "addresses": ["/ip4/192.168.1.10/tcp/4001", "/ip4/203.0.113.9/tcp/4001"],
    "udp": {"host": "203.0.113.9", "port": 52100}
  },
  "guests": [
    {
      "peerId": "12D3KooW...",
      "name": "Notch",
      "listenPort": 4001,
      "addresses": ["/ip4/192.168.1.30/tcp/4001"],
      "udp": {"host": "198.51.100.7", "port": 41337}
    }
  ]
}
```

`udp` 是服务器通过 2.2 的发现包观察到的该玩家公网 UDP 端点；没有观察到时省略该字段。
客户端用它和 `roomKey` 同时向对方发包打洞。`udp` 与 `addresses` 一样**只发给同一房间的成员**。

`guests` 是加入前已在房间里的其他客人，用于一次性补全房间好友列表。
条目含 `peerId`、`name`、`listenPort`、`addresses` 和可选的 `udp`：语音聊天是房间内全互联，
每位客人需要直接拨号到其他客人，服务器只交换地址、不转发音频。
加入者自己不要出现在这个数组里。

失败时返回 `error`（见 2.7），例如房间不存在或已满。

服务器随后向房间内其他玩家推送 `peer-joined`：

- 给房主：完整 `peerInfo`（含 `listenPort`、`addresses`、`udp`），房主需要它做反向直连与打洞；
- 给其他客人：与 `guests` 条目相同，含 `peerId`、`name`、`listenPort`、`addresses`、`udp`，
  供语音/隧道直接拨号（不含 `gamePort` / `game`）。

```json
{
  "type": "peer-joined",
  "peer": {
    "peerId": "12D3KooW...",
    "name": "Alex",
    "listenPort": 4001,
    "addresses": ["/ip4/192.168.1.20/tcp/4001"],
    "udp": {"host": "192.0.2.44", "port": 60912}
  }
}
```

房主收到后会登记该 PeerID（用于校验隧道来源），尝试反向直连，同时开始 UDP 打洞（失败属正常）。
客人收到后更新房间好友列表，并对新成员做同样的 UDP 尝试。

### 2.5 `leave`（客户端 → 服务器）

```json
{"type":"leave"}
```

无 `id`、无回复要求。服务器应：

- 若发送者是房主：解散房间，向所有客人推送 `room-closed`；
- 若发送者是客人：从房间移除，并向房主和房间内其余客人推送 `peer-left`。

信令流断开（`channelInactive` / 流关闭）也应视为 `leave`，否则房间会泄漏。

`peer-left`（服务器 → 房主和其余客人）：

```json
{"type":"peer-left","peerId":"12D3KooW..."}
```

### 2.6 服务器推送的其他事件

房间被房主结束（服务器 → 客人）：

```json
{"type":"room-closed","reason":"房主离开了房间"}
```

心跳（服务器 → 客户端）：

```json
{"type":"ping","t":123456789}
```

客户端会回复 `{"type":"pong","t":123456789}`。**不要给 `ping` 加 `id`**，
客户端在 `ping` 分支中只回传 `t`，不会回传 `id`；带 `id` 的响应会被当作未知请求。

服务器可自定义推送间隔；客户端没有强制心跳超时，连接断开依赖 libp2p 流事件。

### 2.7 错误响应

```json
{"id":"3","type":"error","message":"房间不存在"}
```

- 若 `id` 与某个待处理请求匹配，客户端把该请求标记为失败并显示 `message`。
- 若没有 `id`，客户端直接把它当作状态错误显示。

## 3. 客户端直连流程

房主 `create` 成功后进入 `HOSTING`，等待；客人 `join` 成功后**同时**启动多条直连路径：

1. **TCP 正向拨号**：从 `joined.host.addresses` 中筛选 `/tcp/` 地址（去掉 `/p2p` 后缀），
   并行拨号所有候选（IPv6、局域网 IPv4、UPnP 公网地址），单次 8 秒超时；
   全部失败后间隔 3 秒重试，最多 3 轮。
2. **TCP 反向拨号**：房主收到 `peer-joined` 后也向客人公布的地址拨号。客人接受来自
   `hostPeerId` 的入站连接，把它当作本次竞速的一条 TCP 候选。
3. **UDP 打洞**：双方用服务器观察到的 `udp` 端点、以及从对方 `/ip6/.../tcp/<port>`
   和公网 IPv4 multiaddr 推导出的同端口 UDP 候选，向**所有候选**发送打洞包；任意一个
   认证通过的打洞包到达，就认为该路径可用（源地址会覆盖服务器观察到的地址，
   以兼容对称 NAT 的不同映射）。私网 IPv4 不参与 UDP 打洞，局域网直连交给 TCP。

竞速规则：

- 第一个成功的路径立即启用本地代理，界面显示“已通过 TCP 直连房主”或“已通过 UDP 打洞直连房主”；
  失败的路径不再重试，晚到的成功路径会被关闭。
- 两条路径都失败（TCP 重试耗尽 + UDP 打洞超时）才显示失败，最长等待约 32 秒。
- 若 TCP 路径先成功但随后断开，而 UDP 打洞已经可用，则自动切换到 UDP，无需重新加入房间。
- 本地代理（`127.0.0.1:<随机端口>`）的语义不变：Minecraft 每建立一个本地 TCP 连接，
  客户端就打开一条游戏隧道。TCP 隧道是 `/sslauncher/tunnel/1.0.0` 流；
  UDP 隧道是同一个已打洞 UDP 套接字上的一条 KCP 会话。

房主收到隧道后校验：自己正在开房、`gamePort > 0`、且发送者属于本房间
（来自 `peer-joined`，TCP 用 `stream.remotePeerId()`，UDP 用 HELLO 中的 `from` 且
该 PeerID 已在房间名单内），否则立即关闭该隧道。校验通过则连接
`127.0.0.1:<gamePort>` 并双向转发字节；隧道关闭时关闭对应 socket，反之亦然。

TCP 隧道是**裸 TCP 字节流**：没有长度前缀、没有握手、没有额外加密层（libp2p 的 Noise 已加密）。
半关闭语义：一端 `closeWrite` 表示不再发送，对端读取到 EOF 后应关闭本端写方向。

### 3.1 UDP 打洞与 KCP 隧道

- 打洞包：ASCII `SSPUNCH1` + 16 字节 `HMAC-SHA256(roomKey, "sslauncher-punch")` 前缀
  + 发送方 PeerID + 接收方 PeerID + 8 字节随机数 + 1 字节标志（0 探测 / 1 应答）。
  未打通时每 100 ms 向所有候选各发一次（12 秒后降为 500 ms，60 秒后降为 1.5 秒），
  打通后只向学到的源地址每 15 秒保活一次。
- 收到打洞包的源地址就是对方 NAT 为我们这一侧创建的映射，直接用于后续发送。
- 游戏/语音载荷走 KCP（消息模式，MTU 1200，nodelay）。每个游戏隧道和每条语音流
  使用独立的 KCP conv，互不阻塞。
- KCP 数据包格式：8 字节发送方标签（`SHA-256(roomKey, "sslauncher-tag", peerId)` 前 8 字节）
  + 12 字节随机 nonce + AES-256-GCM 密文。明文首字节是消息类型
  （0 DATA / 1 FIN / 2 CLOSE / 3 PING / 4 PONG / 127 HELLO），其余为载荷。
  GCM 的 AAD 为“发送方标签 + KCP conv”，防止把包重放到别的会话。
- 每对玩家的密钥为 `SHA-256(roomKey, "sslauncher-kcp", 两个 PeerID 排序后以换行连接)`，
  同一房间内不同玩家对之间互不相同。
- 首条消息必须是 HELLO（JSON，含 `from`、`kind`、`code`），接收方校验发送者在房间内、
  房间号一致后才创建隧道/语音会话；15 秒内没有 HELLO 的入站会话会被回收。

## 4. 房间语音（全互联直连）

语音只在玩家处于同一房间时启用。客户端加入房间后，会为房间内每位其他玩家打开一条
出站语音流，并接受对方打开的一条入站流；因此每两个玩家之间有两组单向流（A→B 与 B→A）。
音频不经过引导服务器，也不经过房主。

- 发起方只写、接收方只读，不需要双向协商或半关闭语义。
- TCP 语音流使用 `/sslauncher/voice/1.0.0`；UDP 语音流使用独立 KCP conv，
  帧就是一条 KCP 消息（无需长度前缀）。两种实现由 `VoiceStream` 统一。
- 接收方校验发送者属于当前房间，否则立即关闭该流。
- TCP 帧格式：大端 `int32` 长度 + 恰好一个 Opus 包；UDP 帧为裸 Opus 包。
  单声道 48 kHz，20 ms 一帧，约 32 kbps；长度上限 4096 字节。
- 断流后客户端每 4 秒重试一次，直到离开房间或语音被关闭。
- 每个语音连接同样先竞速：UDP 打洞已就绪时同时尝试 UDP 与 TCP，先成功者胜出；
  另一条稍后成功会被立即关闭。打洞尚未完成时先走 TCP，4 秒后重试时会再次尝试 UDP。
- 房主与客人之间会复用已经建立的游戏直连（libp2p 的 `connect` 发现同一 PeerID 的
  现有连接时直接返回它），因此即使客人位于 NAT 后，房主也能沿着这条入站连接向客人
  开语音流。客人之间没有现成连接，但现在可以通过 UDP 打洞建立；只有双方都在
  **对称 NAT / 封禁 UDP** 的环境下才无法建立——此时仍不会回退到中转。

隐私说明：为了让房间内任意两人都能直接传音频，服务器必须把同一房间成员的
`listenPort`、`addresses` 和观察到的 `udp` 端点发给其他成员。这不向房间外泄露地址，
但同房间的客人确实能互相看到对方公布的 IP（公网 IPv4/IPv6 与局域网地址）。

## 5. 参考实现要点（jvm-libp2p）

本仓库的 `server/` 目录已经提供了完整可部署的实现（含 Ubuntu 24.04 部署脚本说明），
下面的内容仅供理解。

```java
Host server = new HostBuilder()
        .keyType(KeyType.ED25519)
        .listen("/ip4/0.0.0.0/tcp/4000")
        .protocol(new StrictProtocolBinding<>(RendezvousProtocol.ID, new ServerProtocol()))
        .build();
server.start().get();
```

- 自定义 `ProtocolHandler<...>` 时构造器参数是**入站流量配额（字节）**，不是消息大小限制；
  写 `super(0, 0)` 会让第一条入站数据就抛 `ProtocolViolationException`。
  信令协议建议 `super(Long.MAX_VALUE, Long.MAX_VALUE)`。
- `onStartResponder(Stream stream)` 中拿 `stream.remotePeerId().toBase58()` 作为玩家 ID。
- 每条信令流对应一个客户端会话；一个玩家只保留最新会话，旧会话关闭时不要覆盖新会话。
- 服务器**不要**注册 `/sslauncher/tunnel/1.0.0`，也不要注册 `/sslauncher/voice/1.0.0`，
  更不要转发游戏或语音数据。
- 服务器需要在 `udpPort` 上运行 UDP 发现监听（见 2.2）。它只读取 `SSUDP1` 包、
  记录源端点并回复 `SSUDP2`，不应把游戏/语音数据包转发给任何玩家。

## 6. 网络可达性与限制

当前客户端与服务器基于 `io.libp2p:jvm-libp2p:1.3.8-RELEASE`（截至 2026-10 的最新发布版），
但 UDP 打洞与 KCP 传输是本项目自己实现的，不依赖库中的 DCUtR 或 QUIC：

- 直连路径分三类，客户端会并行尝试：TCP 正向拨号（所有公布地址）、房主 TCP 反向拨号、
  UDP 打洞（服务器观察端点 + 从 TCP 地址推导的同端口 IPv6/公网 IPv4 候选，双方同时发包）。
  第一条可用路径胜出。
- **关键前提是房主（或任一方）有可入站的地址。** Minecraft Java 本身只走 TCP；
  UDP 打洞只是把 TCP 字节流封装进 KCP，用于双方都无法接受入站 TCP 的场景。
- IPv6 没有 NAT，但仍可能被运营商或热点防火墙拦截入站。2026-10 的社区实测汇总：
  - 电信 `240e` 手机热点/家宽：入站相对最开放，是最可靠的手机热点房主方案；
  - 联通 `2408`：分省、分号段，`2408:841e` 等号段被报告全端口封禁；
  - 移动 `2409`：蜂窝网络普遍拦截入站；
  - 广电 `240a`：多数地区拦截入站，家宽常常没有 IPv6 或处于 NAT4。
  这些是社区样本而非运营商承诺；同一运营商不同省份也可能不同。
  更完整的运营商矩阵、来源和家庭宽带光猫设置见 [CARRIER-NAT.md](CARRIER-NAT.md)。
- 若双方都在**对称 NAT / CGNAT** 后，经典 UDP 打洞通常失败（映射端口随目标变化）；
  若运营商单独封禁 UDP，打洞也会失败。此时仍有 TCP 路径可尝试，但双方都没有可入站
  地址时直连无法建立。客户端会显示具体诊断（是否检测到 IPv6、房主地址属于哪个号段、
  是否像 iPhone 热点等），**不会回退到中转**（按需求不使用中转）。
- 客户端会在节点设置中尝试 **UPnP 端口映射**（默认开启），同时映射 **TCP 与 UDP**，
  外网端口等于内网端口，每 15 分钟续期一次。UPnP 映射只在路由器 WAN 口有公网 IPv4 时
  有意义；如果 WAN 地址本身是 `100.64/10` 等 CGNAT 地址，映射无法穿过运营商 NAT。
- 客户端会把本机的**公网 IPv6 地址**（`/ip6/<addr>/tcp/<port>`）一并公布。
  Windows 上的通配监听套接字是双栈的，所以同一个 4001 端口同时接受 IPv4 和 IPv6 连接。
- **部署要求**：引导服务器的安全组/防火墙必须放行 **TCP 与 UDP 的同一端口**
  （默认 4001）。只放行 TCP 时房间和 TCP 直连仍可用，但 UDP 打洞会全部失败。
- Windows 防火墙同样需要允许启动器（`SS-Launcher.exe`）的**入站 TCP 与 UDP**，
  尤其是在手机热点下网络被识别为“公用”时；否则即使运营商放行，本机也收不到连接。
- 打洞包与 KCP 载荷都使用房间密钥认证/加密；服务器只知道每个玩家的公网端点，
  无法解密或伪造游戏/语音数据，也没有任何代码路径会转发这些数据。
