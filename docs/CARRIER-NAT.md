# 运营商网络与无中转联机

本文汇总 2026-10 可查到的公开资料、运营商/设备文档和社区实测，说明中国电信、中国联通、中国移动、中国广电的**家用宽带**与**手机流量热点**下，Minecraft Java 版无中转直连的可行路径。

> 结论会随省份、套餐、号段、光猫型号和系统版本变化。本文把“有官方/协议依据”“多方社区实测”“推断”分开标注；不要把矩阵当成运营商承诺。
>
> 本项目的硬约束是**不使用中转/TURN**。引导服务器只交换地址、观察 UDP 端点、分发房间密钥，不转发游戏或语音数据。

## 1. 先理解三层限制

一条直连能否建立，取决于三层，任何一层失败都不通：

1. **地址层**：双方是否有可路由的全局地址（公网 IPv4，或 `2000::/3` 的 IPv6 GUA）。
2. **映射层**：IPv4 NAT 是否允许“先出后进”。对称 NAT 对每个目的地址/端口分配不同公网端口，双方拿到的端点对不上，UDP 打洞会失败。
3. **过滤层**：运营商核心网、光猫/路由器、手机热点、Windows 防火墙是否放行入站。**有公网 IPv6 ≠ 能被拨入**。

IPv6 没有 NAT，通常只剩“过滤层”；这是它比 IPv4 打洞可靠的根本原因。但很多中国运营商在蜂窝核心网或光猫上默认丢弃入站，因此仍需“双方同时主动发包”来打开状态防火墙，或由用户放行。

## 2. IPv6 号段

以下号段分配是公开事实，社区普遍用首段判断运营商：

| 运营商 | IPv6 前缀 | 备注 |
|---|---|---|
| 中国电信 | `240e::/16` | 家宽与蜂窝均常见 |
| 中国联通 | `2408::/16` | 同一张卡可能因重拨拿到不同子前缀 |
| 中国移动 | `2409::/16` | 家宽常见 `2409` GUA |
| 中国广电 | `240a:4000::/21` | 全国一网规划号段；部分地区家宽仍未下发 |

判断“能不能直连”不能只看首段，还要看具体子前缀、省份和网络类型。

## 3. 运营商矩阵

### 3.1 手机流量热点

| 网络 | IPv4 | IPv6 | 作为房主（被入站） | 作为客人（主动出站） |
|---|---|---|---|---|
| **电信热点** | CGNAT，通常无 UPnP | 通常下发 GUA；部分机型把 `/64` 共享给热点客户端 | **最可行**。多地实测可 ping、可 TCP 入站；仍受省份、号段、Windows 防火墙影响 | 最容易成功 |
| **联通热点** | CGNAT，通常无 UPnP | `2408` GUA；同卡可能拿到不同子前缀 | **看号段**。社区实测 `2408:841d` 可不封入站，`2408:841e` 全端口封禁；4G/5G、省份差异大 | 较可行，取决于房主 |
| **移动热点** | CGNAT，通常无 UPnP | `2409` GUA；热点是否下发 GUA 取决于机型/系统 | **多数地区不可行**。北京、河北、陕西等地实测入站全封；个别省份/时段/基站可通 | 较可行，但常只能加入电信/开放房主 |
| **广电热点** | CGNAT，通常无 UPnP | `240a` GUA，但核心网常封入站 | **基本不可行**。社区实测 ping/端口均不通，核心网安全网关拦截 | 可出站，能否加入取决于房主 |

关键点：

- **手机热点不是“路由器”**。Android 用 iptables `MASQUERADE` 做 IPv4 NAT，不实现 UPnP IGD/NAT-PMP/PCP；iPhone 个人热点使用 `172.20.10.0/28` 内置 NAT，也没有端口转发界面。热点层无法为 IPv4 创建入站映射。
- **IPv6 热点共享因机型而异**。部分 Android/iPhone 会把运营商 `/64` 直接下发给热点客户端（客户端各自持有 GUA，不需要 NAT66）；另一些只给 ULA 或 link-local。不能假设“手机有 IPv6，电脑就一定有”。
- **电信是唯一在多地社区实测中较稳定允许蜂窝 IPv6 入站的运营商**；联通看子前缀；移动、广电整体最严。
- 以上“封/不封”多为社区实测，不是运营商公开承诺；同一张卡重拨后可能换前缀，结果也会变。
- **2026-10-23 本项目实测**：电信、移动、联通三家的热点/网络可以互相直连；广电热点仍无法作为房主连接。该结果与上表的运营商入站差异一致。

### 3.2 家用宽带

| 网络 | IPv4 | IPv6 | 默认入站 | 作为房主 |
|---|---|---|---|---|
| **电信家宽** | 不少地区仍给公网 IPv4，越来越多 CGNAT | `240e` GUA，通常有 `/56`–`/60` | 相对开放；主要拦在光猫/路由器 IPv6 防火墙和 80/443 等敏感端口 | **最佳固定宽带房主**。改桥接或关闭/放行光猫 IPv6 防火墙后，高位端口通常可通 |
| **联通家宽** | 多为 NAT4，公网 IPv4 需申请/看地区 | `2408` GUA | 光猫默认 drop；部分光猫不提供关闭开关 | 改桥接由路由器 PPPoE，或超管/telnet/白名单放行；高位端口可用 |
| **移动家宽** | 大范围 CGNAT | `2409` GUA，常见 `/60` PD（地区差异） | 光猫“IPv6 session 防火墙”默认拦截入站 | 改桥接或关闭光猫 IPv6 防火墙后高位端口通常可用；80/443/8080/25 常被封 |
| **广电家宽** | 普遍 CGNAT；个别高价套餐/老用户有公网 IPv4 | 很多地区尚无家宽 IPv6；有则为 `240a` | 即使有 GUA 也常被广电防火墙拦 | **最差**。只有桥接 CPE + 开放 IPv6 防火墙，或真公网 IPv4 的少数情况可行 |

共同点：

- 家宽运营商通常封 **80/443/8080/25** 等常用入站端口；**高位端口（如 4001、8443、2053）通常可用**。本项目默认使用 4001 及之后的端口，方向正确。
- 光猫的“端口转发”页面大多只处理 IPv4；IPv6 需要在**防火墙/通信规则**中放行，或直接关闭 IPv6 防火墙。
- 光猫重启、远程升级后防火墙可能被重新打开；桥接 + 自己路由器拨号更稳定。
- 家庭宽带即使 WAN 地址是 `100.64.0.0/10`，UPnP 也只能映射内层路由器，无法穿透运营商 CGNAT。

## 4. 无中转可用路径（按成功率排序）

### 4.1 IPv6 直连（首选）

条件：房主有全局 IPv6，且运营商/光猫/Windows 防火墙允许该端口入站；客人也有 IPv6。

- 这是唯一能规模化绕开 IPv4 CGNAT 的方式。
- 本项目公布所有全局 IPv6 的 `/ip6/<addr>/tcp/<port>`，客人并行拨号；Windows 上的 libp2p 监听是双栈的。
- 已过滤 ULA（`fc00::/7`）、link-local、6to4（`2002::/16`）和 Teredo（`2001::/32`），避免把不可路由地址当成候选。
- **“有 IPv6 地址”不等于“可入站”**。失败提示会显示房主 IPv6 的运营商号段，并提示检查光猫和 Windows 防火墙。

### 4.2 IPv6 UDP 打洞（IPv6 直连被状态防火墙拦住时）

很多光猫/运营商对“未经请求的入站 TCP”直接丢弃，但对 UDP 采用状态防火墙：双方同时向对方 `IPv6:port` 发包后，回包会被放行。

- 本项目让 UDP 与 TCP **绑定同一数字端口**，因此客人可以从房主公布的 `/ip6/.../tcp/<port>` 直接推导出 `/ip6/.../udp/<port>`，不需要引导服务器具备 IPv6，也不需要额外的 UDP 端口信令。
- 双方每 100ms 向所有候选端点发认证探测包；哪个方向先通就用哪个。
- 这是当前客户端最重要的新增路径：它救不了“核心网全封 IPv6”的移动/广电热点，但能救“有 GUA、只是默认拒绝主动入站”的家宽和一些电信热点。

### 4.3 IPv4 UDP 打洞

只有在**至少一侧**的 NAT 映射/过滤较宽松时才可靠：

| 房主 NAT \ 客人 NAT | 宽松（EIM/EIF 或 ADF） | 对称（APDM/APDF） |
|---|---|---|
| **宽松** | 同时发包通常成功 | 一侧可学习对方源端点，可能成功 |
| **对称** | 可能成功 | **基本失败** |

- 蜂窝 CGNAT 常见对称映射；手机热点又叠加一层 NAT，双方都在热点后时成功率很低。
- 端口预测（N+1）在随机端口、端口块分配、多级 NAT 下基本失效；生日扫描在双侧对称时需要数十万次探测，代价高且会触发风控，本项目不作为默认路径。
- 引导服务器观察到的是“该 UDP socket 对服务器方向的映射”；对称 NAT 下它对客人的方向会变成另一个端口，所以客人还必须使用从房主 TCP 地址推导出的候选端点，并接受“从对方源地址学习”的机制。

### 4.4 TCP 正向/反向拨号

- 房主有公网 IPv4 或成功 UPnP 映射，且防火墙放行时，直接拨号最稳定。
- 反向拨号只在客人本身可被房主拨入时有意义；双方都在 CGNAT 后时无效。
- TCP 同时打开（simultaneous open）在 Java 标准 Socket 上实现困难、NAT 兼容性差，社区大规模测量成功率也明显低于 UDP 打洞，本项目不实现。

### 4.5 UPnP / NAT-PMP / PCP

- 只对“家庭路由器拿到公网 IPv4”有效；CGNAT、手机热点、光猫路由模式下通常无效。
- 本项目启用 UPnP 时**同时映射 TCP 与 UDP**，外部端口等于内部端口，并在退出房间时删除映射。
- 不实现 NAT-PMP/PCP 和 IGDv2 IPv6 pinhole：消费级 CPE 支持率低，且需要引入额外库；后续可作为可选增强。

### 4.6 不可用或不应作为默认的方案

- **中转/TURN**：违背本项目约束，不实现。
- **TCP over TCP 长隧道**：外层丢包会触发两层重传，延迟和吞吐都会恶化；UDP/KCP 可用时应优先 UDP，TCP 只作兜底。
- **全端口扫描/长时间生日扫描**：对双侧对称 NAT 收益接近零，还会触发运营商/主机安全策略。

## 5. 本项目已做的优化

| 改动 | 作用 |
|---|---|
| UDP 绑定 TCP 同端口 | 客人可从 `/ip6/.../tcp/<port>` 推导 UDP 端点，IPv6 UDP 打洞不依赖服务器 IPv6 |
| 多候选并行探测 | 同时探测服务器观察的 IPv4、每个全局 IPv6、公网 IPv4；单个候选失败不阻塞其他候选 |
| 从认证探测的源地址学习 | 对称 NAT 下使用对方实际出站映射，而不是服务器观察到的旧端口 |
| UPnP 同时映射 TCP+UDP | 公网 IPv4 房主可为两条路径都开门 |
| 只公布全局 IPv6 | 过滤 ULA/link-local/6to4/Teredo，减少无效拨号和误导性诊断 |
| 失败诊断 | 显示本机 IPv6、房主公网地址数量、IPv6 运营商号段、iPhone 热点特征、CGNAT 判断和可操作建议 |
| 晚到 UDP 升级 | TCP 先通后 UDP 才打穿时，可升级到 UDP，避免 TCP-over-TCP |
| TCP_NODELAY | 本地代理和游戏连接都关闭 Nagle，降低小包延迟 |

**未做**：IPv6 TCP 同时打开、PCP/IGDv2 pinhole、生日扫描、中转。它们要么收益低，要么需要额外依赖/服务器改动，要么违反无中转约束。

## 6. 结论与使用建议

1. **房主决定成败。** 客人主动出站通常没问题；房主必须有一个可被拨入的地址。
2. **首选房主：电信手机热点或电信家宽。** 电信蜂窝 IPv6 在多地被实测为可入站；电信家宽关闭光猫 IPv6 防火墙后高位端口通常可用。
3. **联通热点看号段**：`2408:841d` 等部分子前缀可入站，`841e` 等号段全封；重拨换前缀可能改变结果。
4. **移动热点多数只能当客人**；移动家宽改桥接/关光猫防火墙后可当房主。
5. **广电热点/家宽基本不适合当房主**；除非确认拿到了未被过滤的 IPv6 或真公网 IPv4。
6. **双方都在对称 CGNAT 且没有 IPv6/端口映射时，无中转就是无解。** 正确做法是尽快、明确地失败，并提示换网络/开 IPv6 入站，而不是偷偷中继。
7. **Windows 防火墙必须放行启动器的入站 TCP 和 UDP**；手机热点常被识别为“公用网络”，只在“专用”放行不会生效。

## 7. 需要服务器改动吗？

当前优化**不需要修改或重新部署引导服务器**：

- 服务器只做房间匹配、地址交换、UDP 端点观察和房间密钥分发，继续不接触游戏/语音载荷。
- 客户端从 TCP 地址推导 IPv6 UDP 端点，因此服务器没有 IPv6 也不影响 IPv6 UDP 打洞。

可选、但需要另行请示的服务器增强：

- 引导服务器增加 IPv6 监听，直接观察并下发 `observedUdp6`。
- 增加第二个 UDP 观察端口，用于判断 NAT 映射/过滤类型，从而更快失败并给出更准确的提示。
- 支持 IPv6 前缀变化后的 `peer-update` 重新公布。
- 用 `punch-sync` 时间戳让双方同时发包，进一步缩短打洞时间。

这些都不是当前联机失败的必要条件；在获得明确许可前不会修改或部署服务器。

## 8. 资料来源与可信度

**协议与实现**

- RFC 4787《NAT Behavioral Requirements for Unicast UDP》（EIM/ADM/APDM 与过滤行为）：https://www.rfc-editor.org/rfc/rfc4787.html
- RFC 5128《State of P2P Communication across NATs》：https://www.rfc-editor.org/rfc/rfc5128.html
- RFC 5780《NAT Behavior Discovery Using STUN》：https://www.rfc-editor.org/rfc/rfc5780.html
- RFC 8305《Happy Eyeballs Version 2》：https://www.rfc-editor.org/rfc/rfc8305.html
- RFC 6092《Recommended Simple Security Capabilities in CPE for Residential IPv6》：https://www.rfc-editor.org/rfc/rfc6092.html
- Tailscale《How NAT traversal works》：https://tailscale.com/blog/how-nat-traversal-works
- libp2p DCUtR 规范：https://github.com/libp2p/specs/blob/master/relay/DCUtR.md
- Android AOSP `IPv6TetheringCoordinator`（IPv6 tethering 无 NAT、依赖前缀）：https://android.googlesource.com/platform/frameworks/base/+/aa8cecec810466f60452bdb0df0f4f6e341d5cd2/packages/Tethering/src/com/android/networkstack/tethering/IPv6TetheringCoordinator.java
- Android Developers：DHCPv6 Prefix Delegation（前缀共享给 tethered 设备仍是后续能力）：https://android-developers.googleblog.com/2025/09/simplifying-advanced-networking-with.html
- Apple StackExchange：iPhone 个人热点没有端口转发/UPnP：https://apple.stackexchange.com/questions/218845/port-forwarding-on-iphone（问题页，回答明确 iOS 不提供 NAT 配置）
- LWN：Pixel 热点 IPv6 流量不经 NAT、直接分配 `/64`：https://lwn.net/Articles/831907/

**中国运营商与设备（社区实测，按可信度打折）**

- 恩山论坛，北京联通/电信/移动手机卡 IPv6 入站测试：https://www.right.com.cn/forum/forum.php?mod=viewthread&tid=8441696
- Chiphell，河北联通 5G `2408:841d` 可入站、`2408:841e` 全封：https://www.chiphell.com/thread-2632552-1-530.html
- Chiphell，联通手机热点 IPv6 远程访问讨论：https://www.chiphell.com/thread-2640405-1-1.html
- V2EX，广电移动数据 IPv6 是否允许入站：https://global.v2ex.co/t/1014555
- V2EX，电信家宽 IPv6 入站与光猫防火墙：https://global.v2ex.co/t/869492
- V2EX，北京联通家宽光猫 IPv6 防火墙：https://global.v2ex.co/t/1050485
- V2EX，联通家宽 IPv6 公网访问问题：https://global.v2ex.co/t/922016
- 上海移动光猫关闭 IPv6 防火墙实操：https://www.zheguisoft.com/staff_blogs/jacklondon_chen/2025/08-sh-ipv6
- 恩山论坛，光猫 IPv6 防火墙/桥接与 `ip6tables` 分析：https://www.right.com.cn/forum/forum.php?mod=viewthread&tid=8447143
- V2EX，深圳广电（天威视讯）IPv6/公网 IPv4 情况：https://global.v2ex.co/t/1174012
- linux.do，广电家宽能否申请公网 IPv4/CGNAT：https://linux.do/t/topic/785032
- V2EX，移动数据网络 IPv6 入站限制讨论：https://global.v2ex.co/t/843359
- 恩山论坛，移动家宽 `2409` 入站被 ISP 屏蔽讨论：https://www.right.com.cn/forum/forum.php?mod=viewthread&tid=8412876

**说明**

- 运营商号段来自公开分配与社区长期使用；具体省份/套餐可能使用其他前缀。
- “某号段全封”“某地可入站”均为社区单点或小范围实测，会随核心网策略、基站、光猫固件、系统版本变化。
- Minecraft Java 版游戏协议是 TCP；本项目通过 TCP 隧道或 UDP/KCP 隧道承载同一条 TCP 字节流。UDP 路径成功时应优先使用，避免 TCP-over-TCP。
