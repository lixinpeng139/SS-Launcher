# SS Launcher 引导服务器

只做三件事：**按房间号把玩家配对**、**交换双方的直连地址**、**观察双方的公网 UDP 端点**。
它不注册、也不接受 `/sslauncher/tunnel/1.0.0` 与 `/sslauncher/voice/1.0.0`，
所以游戏数据与语音都不会经过这台服务器；UDP 端口只用于 STUN 式端点发现，不转发任何玩家数据。

适用于阿里云 ECS + Ubuntu 24.04。默认监听 TCP 4001 与 UDP 4001。

## 1. 在本机构建发行包

在项目根目录执行（Windows）：

```bat
gradlew.bat :server:distTar
```

产物：`server/build/distributions/sslauncher-bootstrap-1.0.0.tar.gz`
（也可以执行 `gradlew.bat :server:distZip` 得到 zip）

## 2. 上传并安装

把 tar 包传到服务器（`<服务器公网IP>` 换成你的地址）：

```bash
scp server/build/distributions/sslauncher-bootstrap-1.0.0.tar.gz root@<服务器公网IP>:/tmp/
```

登录服务器后执行：

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless

# 运行账户与目录
sudo useradd --system --home /var/lib/sslauncher-bootstrap \
     --shell /usr/sbin/nologin sslauncher
sudo mkdir -p /opt/sslauncher-bootstrap /var/lib/sslauncher-bootstrap

# 解包发行目录（bin/ + lib/）
sudo tar -xzf /tmp/sslauncher-bootstrap-1.0.0.tar.gz \
     -C /opt/sslauncher-bootstrap --strip-components=1
sudo chown -R root:root /opt/sslauncher-bootstrap
sudo chown -R sslauncher:sslauncher /var/lib/sslauncher-bootstrap
sudo chmod +x /opt/sslauncher-bootstrap/bin/sslauncher-bootstrap

# systemd
sudo cp server/deploy/sslauncher-bootstrap.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now sslauncher-bootstrap
sudo systemctl status sslauncher-bootstrap --no-pager
```

如果服务器上没有这个仓库，把 `server/deploy/sslauncher-bootstrap.service`
单独 `scp` 上去，或按下面内容手工创建 `/etc/systemd/system/sslauncher-bootstrap.service`。

## 3. 放行端口

阿里云需要放行**两层**，而且 **TCP 与 UDP 都要放行**：

1. **安全组**：入方向分别添加 TCP 4001 与 **UDP 4001**（ECS 控制台 → 安全组 → 入方向规则）。
   只放行 TCP 时房间还能用，但两个都在 CGNAT/手机热点后的玩家无法打洞直连。
2. **系统防火墙**（如果启用了 ufw）：

```bash
sudo ufw allow 4001/tcp
sudo ufw allow 4001/udp
sudo ufw status
```

检查监听（应同时看到 tcp 与 udp）：

```bash
ss -lntup | grep 4001
```

从你自己的电脑测试 TCP 连通性：

```bash
nc -vz <服务器公网IP> 4001
```

UDP 没有简单的 `nc` 检查方式；最直接的验证是让客户端加入同一房间，日志出现
`UDP 端点已登记 peer=... udp=...` 即表示服务器收到了发现包并记录了端点。
服务器启动日志也会打印 `UDP 端点发现已监听 /ip4/0.0.0.0/udp/4001`。

## 4. 取得客户端要填的地址

```bash
sudo journalctl -u sslauncher-bootstrap -n 30 --no-pager
```

日志里会有：

```
PeerID : 12D3KooW...
客户端填写: /ip4/<服务器公网IP>/tcp/4001/p2p/12D3KooW...
```

如果启动前在 systemd 里设置了 `SS_PUBLIC_HOST=你的公网IP`，第二行会直接给出可复制地址。
把这一整串填进启动器的 **联机 → 节点设置 → 引导服务器**。

> 阿里云 ECS 的公网 IP 通常是 NAT/EIP 映射到私网网卡，这是正常的：
> 服务器监听 `0.0.0.0:4001`，客户端拨公网 IP 即可，**不要填私网 IP**。

## 5. 配置项

全部通过环境变量（或命令行 `--key=value`）设置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SS_PORT` | `4001` | 监听 TCP 端口 |
| `SS_UDP_PORT` | 与 `SS_PORT` 相同 | UDP 端点发现端口（需在安全组放行 UDP） |
| `SS_HOME` | `./data` | 身份文件目录，systemd 中为 `/var/lib/sslauncher-bootstrap` |
| `SS_PUBLIC_HOST` | 空 | 仅影响启动日志中打印的客户端地址 |
| `SS_MAX_ROOMS` | `500` | 同时存在的房间上限 |
| `SS_MAX_GUESTS` | `8` | 每个房间的客人上限（房主另算） |
| `SS_PING_SECONDS` | `45` | 服务器心跳间隔 |
| `SS_IDLE_SECONDS` | `150` | 无任何消息超过该秒数则断开 |

修改方式：编辑 `/etc/systemd/system/sslauncher-bootstrap.service` 里的 `Environment=`，
然后 `sudo systemctl daemon-reload && sudo systemctl restart sslauncher-bootstrap`。

## 6. 身份文件

`/var/lib/sslauncher-bootstrap/identity.key` 保存服务器的 libp2p 私钥，
**PeerID 由它决定**。丢失或更换这个文件会导致 PeerID 变化，所有客户端的
引导服务器地址都必须同步修改。升级程序时保留该目录即可。

## 7. 升级

```bash
sudo systemctl stop sslauncher-bootstrap
sudo tar -xzf /tmp/sslauncher-bootstrap-1.0.0.tar.gz \
     -C /opt/sslauncher-bootstrap --strip-components=1
sudo chown -R root:root /opt/sslauncher-bootstrap
sudo systemctl start sslauncher-bootstrap
```

## 8. 安全说明

- 服务器从不接受隧道或语音协议，即使被扫描也无法被当作游戏数据/音频中转。
- UDP 监听只处理 `SSUDP1` + 令牌的发现包，回复观察到的源端点；端口不会转发任何玩家数据。
- 客户端先在信令流上注册随机令牌，UDP 发现包必须携带该令牌才会被回复，
  因此互联网上的扫描者无法查询其他玩家的映射。
- 服务器为每个房间生成 32 字节随机 `roomKey`，通过已加密的信令流下发；
  打洞包用它的 HMAC 认证，KCP 载荷用由它派生的每对玩家密钥做 AES-256-GCM 加密。
  服务器只知道房间密钥本身，不参与也不转发加密流量。
- 房间成员的 `listenPort`、`addresses` 与 `udp` 端点会发给同一房间内的其他成员
  （直连与语音全互联需要），但不会发给房间外的人。
- 客户端身份由 libp2p 的 Noise 握手认证，PeerID 取自 `stream.remotePeerId()`，
  不信任客户端 JSON 中自报的身份。
- 已内置基本的请求频率限制、名称/地址长度限制和房间数量限制。
- 服务器只保存内存中的房间状态，不落盘任何玩家信息；重启后房间全部失效，
  客户端重新创建/加入即可。

## 9. 协议文档

完整信令格式见仓库根目录的 [`docs/P2P-PROTOCOL.md`](../docs/P2P-PROTOCOL.md)。
