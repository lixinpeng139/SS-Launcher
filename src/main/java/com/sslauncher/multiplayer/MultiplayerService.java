package com.sslauncher.multiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sslauncher.core.Config;
import com.sslauncher.core.Log;
import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.MultiaddrComponent;
import io.libp2p.core.multiformats.Protocol;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Direct peer-to-peer multiplayer.
 *
 * <p>A libp2p host is started locally. The bootstrap (rendezvous) node is used
 * only to match room codes and exchange peer addresses; game traffic always
 * flows over a direct libp2p connection — there is deliberately no relay or
 * circuit transport. When neither side can accept an inbound connection the
 * player is told to configure port mapping instead of silently falling back
 * to a proxy.</p>
 */
public final class MultiplayerService {
    private static final MultiplayerService INSTANCE = new MultiplayerService();
    private static final long REQUEST_TIMEOUT_SECONDS = 20;
    private static final long DIAL_TIMEOUT_SECONDS = 20;

    public static MultiplayerService get() {
        return INSTANCE;
    }

    public enum Status {
        OFFLINE,
        STARTING,
        ONLINE,
        CONNECTING,
        HOSTING,
        JOINING,
        CONNECTED,
        ERROR
    }

    public record Snapshot(Status status, String detail, String peerId, String listenAddress,
                           String roomCode, String hostPeerId, String localAddress,
                           String externalAddress, int gamePort, boolean hosting,
                           List<RoomMember> members) {
        public Snapshot {
            members = members == null ? List.of() : List.copyOf(members);
        }

        public boolean busy() {
            return status == Status.STARTING || status == Status.CONNECTING || status == Status.JOINING;
        }

        public boolean inRoom() {
            return hosting || status == Status.CONNECTED || !roomCode.isEmpty();
        }
    }

    /**
     * One player in the current room, as reported by the rendezvous server.
     *
     * <p>{@code addresses} are the peer's published libp2p listen addresses.
     * They are used by the mesh voice chat to open a direct stream to every
     * other player in the room without involving the signalling server.</p>
     */
    public record RoomMember(String peerId, String name, boolean host, boolean self,
                             List<String> addresses, UdpEndpoint udp) {
        public RoomMember {
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
        }

        /** Convenience overload for callers that only have TCP addresses. */
        public RoomMember(String peerId, String name, boolean host, boolean self,
                          List<String> addresses) {
            this(peerId, name, host, self, addresses, null);
        }

        /** Convenience overload for callers that do not need the addresses. */
        public RoomMember(String peerId, String name, boolean host, boolean self) {
            this(peerId, name, host, self, List.of(), null);
        }
    }

    /**
     * One guest's attempt to reach the host. TCP (every published address,
     * including IPv6/LAN and reverse dial) and UDP punching run at the same
     * time; the first usable path wins. Failure is only reported after both
     * have had a chance, and a late UDP success can still upgrade the link.
     */
    private static final class GuestRace {
        final long generation;
        final CompletableFuture<Connection> tcp = new CompletableFuture<>();
        final CompletableFuture<Void> udp;
        final AtomicBoolean chosen = new AtomicBoolean();
        final AtomicBoolean failed = new AtomicBoolean();

        GuestRace(long generation, CompletableFuture<Void> udp) {
            this.generation = generation;
            this.udp = udp;
        }
    }

    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "ss-p2p");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<JsonObject>> requests = new ConcurrentHashMap<>();
    private final AtomicLong requestSeq = new AtomicLong();
    private final Set<String> roomPeers = ConcurrentHashMap.newKeySet();
    /** Insertion order: host/self first, then guests in join order. */
    private final Map<String, RoomMember> roomMembers = new LinkedHashMap<>();

    private volatile Snapshot snapshot = new Snapshot(Status.OFFLINE, "联机节点未启动", "",
            "", "", "", "", "", 0, false, List.of());
    private volatile Host host;
    private CompletableFuture<Host> starting;
    private volatile int listenPort;
    private volatile int ipv6Count;
    private volatile String peerId = "";

    private volatile RendezvousClient rendezvous;
    private volatile LocalTunnelProxy proxy;
    private volatile Connection hostConnection;
    private volatile List<String> lastHostAddresses = List.of();
    private volatile PortMapper portMapper;
    private volatile String externalAddress = "";
    private volatile String upnpAddress = "";
    private volatile boolean upnpTcpMapped;
    private volatile boolean upnpUdpMapped;
    private volatile String observedPublicIp = "";

    /** UDP punching + KCP transport for the current room, when available. */
    private volatile UdpTransport udpTransport;
    private volatile byte[] roomKey;
    private volatile boolean udpPrimary;
    private volatile GuestRace guestRace;
    /** Reverse-dial connection that arrived before onJoined set up the race. */
    private volatile Connection pendingInbound;
    private final AtomicLong roomGeneration = new AtomicLong();

    private volatile boolean hosting;
    private volatile int gamePort;
    private volatile String roomCode = "";
    private volatile String hostPeerId = "";
    private volatile String localAddress = "";
    private volatile String nickname = "";

    private MultiplayerService() {
    }

    // ----------------------------------------------------------------- state

    public Snapshot snapshot() {
        return snapshot;
    }

    public void addListener(Consumer<Snapshot> listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        try {
            listener.accept(snapshot);
        } catch (Throwable ignored) {
            // A broken UI listener must not affect the node.
        }
    }

    public void removeListener(Consumer<Snapshot> listener) {
        listeners.remove(listener);
    }

    private void publish(Status status, String detail) {
        Snapshot next = new Snapshot(status, detail, peerId, listenAddress(), roomCode,
                hostPeerId, localAddress, externalAddress, gamePort, hosting,
                membersSnapshot());
        snapshot = next;
        for (Consumer<Snapshot> listener : listeners) {
            try {
                listener.accept(next);
            } catch (Throwable t) {
                Log.warn("联机：状态监听器异常: " + t.getMessage());
            }
        }
    }

    private void addMember(String id, String name, boolean isHost, boolean isSelf) {
        addMember(id, name, isHost, isSelf, List.of());
    }

    private void addMember(String id, String name, boolean isHost, boolean isSelf,
                           List<String> addresses) {
        addMember(id, name, isHost, isSelf, addresses, null);
    }

    private void addMember(String id, String name, boolean isHost, boolean isSelf,
                           List<String> addresses, UdpEndpoint udp) {
        if (id == null || id.isBlank()) {
            return;
        }
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            clean = "玩家" + (id.length() > 4 ? id.substring(id.length() - 4) : id);
        } else if (clean.length() > 24) {
            clean = clean.substring(0, 24);
        }
        synchronized (roomMembers) {
            roomMembers.put(id, new RoomMember(id, clean, isHost, isSelf, addresses, udp));
        }
    }

    private void removeMember(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        synchronized (roomMembers) {
            roomMembers.remove(id);
        }
    }

    private void clearMembers() {
        synchronized (roomMembers) {
            roomMembers.clear();
        }
    }

    private List<RoomMember> membersSnapshot() {
        synchronized (roomMembers) {
            return List.copyOf(roomMembers.values());
        }
    }

    /** Re-emits the current state after roster-only changes. */
    private void republish() {
        Snapshot current = snapshot;
        publish(current.status(), current.detail());
    }

    private String listenAddress() {
        if (listenPort <= 0) {
            return "";
        }
        return "/ip4/0.0.0.0/tcp/" + listenPort
                + (ipv6Count > 0
                ? "（另有 " + ipv6Count + " 个 IPv6 地址）"
                : "（未检测到 IPv6）");
    }

    // ------------------------------------------------------------- node life

    public CompletableFuture<Void> startNode() {
        return ensureNode().thenApply(host -> null);
    }

    public CompletableFuture<Void> restartNode() {
        return stopNode().thenCompose(ignored -> startNode());
    }

    public CompletableFuture<Void> stopNode() {
        leaveRoom();
        // A UPnP mapping belongs to the listening port, so it must be dropped
        // together with the node; the next start may bind a different port.
        PortMapper mapper = portMapper;
        portMapper = null;
        upnpAddress = "";
        externalAddress = "";
        upnpTcpMapped = false;
        upnpUdpMapped = false;
        if (mapper != null) {
            mapper.close();
        }
        Host previous;
        synchronized (this) {
            previous = host;
            host = null;
        }
        if (previous == null) {
            publish(Status.OFFLINE, "联机节点已停止");
            return CompletableFuture.completedFuture(null);
        }
        publish(Status.STARTING, "正在停止联机节点…");
        CompletableFuture<Void> stopped = previous.stop().handle((value, error) -> null);
        stopped.whenComplete((value, error) -> {
            peerId = "";
            listenPort = 0;
            ipv6Count = 0;
            publish(Status.OFFLINE, "联机节点已停止");
        });
        return stopped;
    }

    private synchronized CompletableFuture<Host> ensureNode() {
        if (host != null) {
            return CompletableFuture.completedFuture(host);
        }
        if (starting != null) {
            return starting;
        }
        publish(Status.STARTING, "正在启动 P2P 节点…");
        CompletableFuture<Host> future = CompletableFuture.supplyAsync(() -> {
            try {
                return createHost();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, exec);
        starting = future;
        future.whenComplete((created, error) -> {
            synchronized (MultiplayerService.this) {
                starting = null;
            }
            if (error != null) {
                publish(Status.ERROR, "P2P 节点启动失败：" + friendly(error));
            }
        });
        return future;
    }

    private Host createHost() throws Exception {
        PrivKey key = P2pIdentity.loadOrCreate();
        int preferred = Config.get().multiplayerPort;
        Exception last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            int candidate = preferred + attempt;
            if (candidate > 65535) {
                break;
            }
            Host created = null;
            try {
                created = buildHost(key, candidate);
                created.start().get(20, TimeUnit.SECONDS);
                synchronized (this) {
                    host = created;
                    listenPort = candidate;
                    ipv6Count = localIpv6Addresses().size();
                    peerId = created.getPeerId().toBase58();
                }
                // A reverse dial from the host may be the only path that works
                // through strict NAT, so treat it as a successful connection.
                // Register after listenPort is set: libp2p also invokes this
                // handler for outbound dials, and the handler needs the port
                // to tell the two directions apart.
                created.addConnectionHandler(this::onInboundConnection);
                Log.info("联机：P2P 节点监听 /ip4/0.0.0.0/tcp/" + candidate
                        + "（Windows 下为双栈，同时接受 IPv6），PeerID " + peerId);
                // Publish before completing the start future so callers that
                // chain on it already see the ONLINE snapshot.
                publish(Status.ONLINE, "P2P 节点已就绪");
                scheduleMappingRenewal();
                return created;
            } catch (Exception e) {
                last = e;
                if (created != null) {
                    try {
                        created.stop().get(3, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // Best effort.
                    }
                }
                Log.warn("联机：端口 " + candidate + " 启动失败: " + friendly(e));
            }
        }
        throw new IOException("无法监听 P2P 端口（从 " + preferred + " 开始）。请在设置中更换端口。", last);
    }

    private Host buildHost(PrivKey key, int port) {
        TunnelBinding tunnel = new TunnelBinding(new TunnelProtocol(this::onInboundTunnel));
        RendezvousBinding rendezvousBinding = new RendezvousBinding(new RendezvousProtocol());
        VoiceBinding voice = new VoiceBinding(new VoiceProtocol(
                VoiceChatService.get()::onInboundStream));
        return new HostBuilder()
                .keyType(KeyType.ED25519)
                // On Windows the wildcard socket is dual-stack, so this also
                // accepts inbound IPv6 — which is what lets two mobile-hotspot
                // players connect without port mapping.
                .listen("/ip4/0.0.0.0/tcp/" + port)
                .protocol(tunnel, rendezvousBinding, voice)
                .builderModifier(builder -> builder.getIdentity().setFactory(() -> key))
                .build();
    }

    // --------------------------------------------------------------- hosting

    public CompletableFuture<Void> createRoom(int gamePort, String gameName, String nickname) {
        if (gamePort < 1 || gamePort > 65535) {
            return failed(new IOException("游戏端口无效，请填写 1–65535"));
        }
        cleanupRoom();
        this.hosting = true;
        this.gamePort = gamePort;
        this.nickname = normalizeNickname(nickname);
        publish(Status.JOINING, "正在准备房间…");

        CompletableFuture<Void> future = ensureNode()
                .thenCompose(node -> preparePublicAddress().thenApply(ignored -> node))
                .thenCompose(this::connectRendezvous)
                .thenCompose(client -> handshake(client, nickname)
                        .thenCompose(ignored -> prepareUdp(client))
                        .thenCompose(ignored -> request(client, "create", body -> {
                            body.addProperty("gamePort", gamePort);
                            body.addProperty("game", gameName == null ? "" : gameName);
                            addPeerDetails(body);
                        }))
                        .thenAccept(response -> {
                            String code = string(response, "code");
                            if (code.isBlank()) {
                                throw new IllegalStateException("引导服务器没有返回房间号");
                            }
                            roomCode = normalizeCode(code);
                            applyRoomKey(response);
                            roomPeers.add(peerId);
                            clearMembers();
                            addMember(peerId, nickname, true, true);
                            List<String> ownAddresses = candidateAddresses();
                            String warning = "";
                            if (!hasRoutableAddress()) {
                                warning = "当前网络没有公网 IPv6 或可映射端口，"
                                        + "只有同一局域网的好友能直连";
                            } else if (externalAddress.isEmpty() && !upnpUdpMapped
                                    && hasRiskyCarrierIpv6(ownAddresses)) {
                                warning = "当前网络的 IPv6 入站可能被运营商或光猫防火墙拦截（"
                                        + ipv6CarrierHint(ownAddresses)
                                        + "），好友可能无法直连；连不上时请关闭光猫 IPv6 防火墙，"
                                        + "或改用电信热点，或让使用电信/公网 IPv6 的好友创建房间";
                            }
                            publish(Status.HOSTING, warning.isEmpty()
                                    ? "房间已创建，等待好友加入"
                                    : "房间已创建，但" + warning);
                        }));
        future.whenComplete((value, error) -> {
            if (error != null) {
                cleanupRoom();
                failRoom("创建房间失败", error);
            }
        });
        return future;
    }

    private void onPeerJoined(JsonObject message) {
        JsonObject peer = object(message, "peer");
        if (peer == null) {
            return;
        }
        String id = string(peer, "peerId");
        if (id.isEmpty() || id.equals(peerId)) {
            return;
        }
        roomPeers.add(id);
        List<String> addresses = readAddresses(peer);
        UdpEndpoint udp = UdpEndpoint.fromJson(peer);
        addMember(id, string(peer, "name"), false, false, addresses, udp);
        UdpTransport transport = udpTransport;
        addUdpCandidates(transport, id, udp, addresses);
        if (hosting) {
            exec.execute(() -> reverseDial(id, addresses));
            if (transport != null && udp != null) {
                transport.punch(id).thenRun(() -> {
                    if (hosting && roomPeers.contains(id)) {
                        publish(Status.HOSTING, "好友 " + displayName(id) + " 已通过 UDP 打洞直连");
                    }
                });
            }
            publish(Status.HOSTING, "有好友加入，正在同时尝试 TCP 直连与 UDP 打洞…");
        } else {
            republish();
        }
    }

    private String displayName(String id) {
        synchronized (roomMembers) {
            RoomMember member = roomMembers.get(id);
            return member == null ? shortId(id) : member.name();
        }
    }

    private void onPeerLeft(JsonObject message) {
        String id = string(message, "peerId");
        if (id.isEmpty() && object(message, "peer") != null) {
            id = string(object(message, "peer"), "peerId");
        }
        if (!id.isEmpty()) {
            roomPeers.remove(id);
            removeMember(id);
            UdpTransport transport = udpTransport;
            if (transport != null) {
                transport.removePeer(id);
            }
            if (hosting) {
                publish(Status.HOSTING, "有好友离开了房间");
            } else {
                republish();
            }
        }
    }

    private void onRoomClosed(JsonObject message) {
        String reason = string(message, "reason");
        boolean wasHosting = hosting;
        cleanupRoom();
        RendezvousClient client = rendezvous;
        rendezvous = null;
        if (client != null) {
            client.close();
        }
        publish(wasHosting ? Status.ONLINE : Status.ERROR,
                wasHosting ? "房间已结束" : "房间已关闭"
                        + (reason.isBlank() ? "" : "：" + reason));
    }

    private void reverseDial(String peer, List<String> addresses) {
        dial(peer, addresses).orTimeout(12, TimeUnit.SECONDS).whenComplete((connection, error) -> {
            if (error != null) {
                Log.warn("联机：反向直连 " + shortId(peer) + " 失败（对方网络不可达时属正常）: "
                        + friendly(error));
            } else {
                Log.info("联机：已与 " + shortId(peer) + " 建立直连");
            }
        });
    }

    // ---------------------------------------------------------------- joining

    public CompletableFuture<Void> joinRoom(String code, String nickname) {
        String normalized = normalizeCode(code);
        if (normalized.length() < 4) {
            return failed(new IOException("请输入 4–10 位房间号"));
        }
        cleanupRoom();
        this.hosting = false;
        this.roomCode = normalized;
        this.nickname = normalizeNickname(nickname);
        publish(Status.JOINING, "正在加入房间 " + normalized + "…");

        CompletableFuture<Void> future = ensureNode()
                .thenCompose(node -> preparePublicAddress().thenApply(ignored -> node))
                .thenCompose(this::connectRendezvous)
                .thenCompose(client -> handshake(client, nickname)
                        .thenCompose(ignored -> prepareUdp(client))
                        .thenCompose(ignored -> request(client, "join", body -> {
                            body.addProperty("code", normalized);
                            addPeerDetails(body);
                        }))
                        .thenAccept(this::onJoined));
        future.whenComplete((value, error) -> {
            if (error != null) {
                cleanupRoom();
                failRoom("加入房间失败", error);
            }
        });
        return future;
    }

    private void onJoined(JsonObject response) {
        String code = string(response, "code");
        if (!code.isBlank()) {
            roomCode = normalizeCode(code);
        }
        JsonObject hostInfo = object(response, "host");
        if (hostInfo == null) {
            throw new IllegalStateException("引导服务器没有返回房主信息");
        }
        hostPeerId = string(hostInfo, "peerId");
        if (hostPeerId.isEmpty()) {
            throw new IllegalStateException("房主信息缺少 PeerID");
        }
        gamePort = intValue(hostInfo, "gamePort", 0);
        applyRoomKey(response);
        roomPeers.add(hostPeerId);
        clearMembers();
        lastHostAddresses = readAddresses(hostInfo);
        UdpEndpoint hostUdp = UdpEndpoint.fromJson(hostInfo);
        addMember(hostPeerId, string(hostInfo, "name"), true, false,
                lastHostAddresses, hostUdp);
        addMember(peerId, nickname, false, true);
        UdpTransport transport = udpTransport;
        addUdpCandidates(transport, hostPeerId, hostUdp, lastHostAddresses);
        if (response.has("guests") && response.get("guests").isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray("guests")) {
                if (element != null && element.isJsonObject()) {
                    JsonObject guest = element.getAsJsonObject();
                    String guestId = string(guest, "peerId");
                    // Older servers include the joining player in the guest list;
                    // keep the self entry added above instead of overwriting it.
                    if (guestId.isEmpty() || guestId.equals(peerId)) {
                        continue;
                    }
                    // Guests now know each other too: the voice mesh opens a
                    // direct stream between every pair of room members.
                    roomPeers.add(guestId);
                    UdpEndpoint guestUdp = UdpEndpoint.fromJson(guest);
                    List<String> guestAddresses = readAddresses(guest);
                    addMember(guestId, string(guest, "name"), false, false,
                            guestAddresses, guestUdp);
                    addUdpCandidates(transport, guestId, guestUdp, guestAddresses);
                }
            }
        }
        publish(Status.JOINING, "正在同时尝试 TCP 直连与 UDP 打洞…");
        beginGuestRace();
    }

    private void beginGuestRace() {
        if (hosting || roomCode.isEmpty() || hostPeerId.isEmpty()) {
            return;
        }
        long generation = roomGeneration.get();
        CompletableFuture<Void> punch;
        UdpTransport transport = udpTransport;
        if (transport != null) {
            punch = transport.punch(hostPeerId);
        } else {
            punch = failed(new IOException("UDP 打洞未启用"));
        }
        GuestRace race = new GuestRace(generation, punch);
        guestRace = race;
        Connection early = pendingInbound;
        pendingInbound = null;
        punch.whenComplete((value, error) -> {
            if (error == null) {
                onUdpWon(race);
            } else {
                maybeFailGuestRace(race, "UDP：" + friendly(error));
            }
        });
        race.tcp.whenComplete((connection, error) -> {
            if (error == null) {
                onTcpWon(race, connection);
            } else {
                maybeFailGuestRace(race, "TCP：" + friendly(error));
            }
        });
        // Report failure only after both paths have had a fair chance. A late
        // success still upgrades the link, so a briefly sad status can recover.
        try {
            exec.schedule(() -> {
                if (!race.chosen.get() && race == guestRace
                        && race.failed.compareAndSet(false, true)) {
                    String udpState = race.udp.isDone() ? "" : "（UDP 无响应）";
                    publish(Status.ERROR, "无法直连房主：TCP 直连与 UDP 打洞均失败"
                            + udpState + "。" + directFailureHint());
                }
            }, 32, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Executor is shutting down.
        }
        if (early != null && !early.closeFuture().isDone()) {
            race.tcp.complete(early);
        } else {
            connectGuest(0, race);
        }
    }

    private void connectGuest(int attempt, GuestRace race) {
        if (race != guestRace || race.chosen.get() || hosting || roomCode.isEmpty()) {
            return;
        }
        List<String> addresses = lastHostAddresses;
        String target = hostPeerId;
        if (target.isEmpty() || addresses.isEmpty()) {
            race.tcp.completeExceptionally(new IOException("房主没有公布 TCP 地址"));
            return;
        }
        dial(target, addresses).orTimeout(DIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((connection, error) -> {
                    if (error == null) {
                        race.tcp.complete(connection);
                        return;
                    }
                    if (race != guestRace || race.chosen.get()) {
                        return;
                    }
                    if (attempt < 2 && !hosting) {
                        try {
                            exec.schedule(() -> {
                                if (race == guestRace && !race.chosen.get() && !hosting
                                        && !roomCode.isEmpty()) {
                                    connectGuest(attempt + 1, race);
                                }
                            }, 3, TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                            // Executor is shutting down.
                        }
                    } else {
                        boolean onlyPrivate = addresses.stream()
                                .allMatch(MultiplayerService::isPrivateIpv4Address);
                        String detail = friendly(error)
                                + (onlyPrivate
                                ? "。房主只公布了内网地址，当前网络也没有可直连的 IPv6"
                                : "。TCP 端口可能被防火墙或运营商 NAT 拦截");
                        race.tcp.completeExceptionally(new IOException(detail));
                    }
                });
    }

    private void maybeFailGuestRace(GuestRace race, String detail) {
        if (race != guestRace || race.chosen.get() || hosting || roomCode.isEmpty()) {
            return;
        }
        if (!race.tcp.isDone() || !race.udp.isDone()) {
            return;
        }
        if (!race.failed.compareAndSet(false, true)) {
            return;
        }
        Throwable tcpError = race.tcp.isCompletedExceptionally()
                ? race.tcp.handle((value, error) -> error).join() : null;
        Throwable udpError = race.udp.isCompletedExceptionally()
                ? race.udp.handle((value, error) -> error).join() : null;
        String tcpText = tcpError == null ? "TCP 未成功" : friendly(tcpError);
        String udpText = udpError == null ? "UDP 未成功" : friendly(udpError);
        publish(Status.ERROR, "无法直连房主：TCP 直连与 UDP 打洞均失败（"
                + tcpText + "；" + udpText + "）。" + directFailureHint());
    }

    private void onTcpWon(GuestRace race, Connection connection) {
        if (race != guestRace || hosting || roomCode.isEmpty()) {
            disconnect(connection);
            return;
        }
        if (!race.chosen.compareAndSet(false, true)) {
            // UDP won first; keep one primary path and drop this late TCP link.
            disconnect(connection);
            return;
        }
        hostConnection = connection;
        udpPrimary = false;
        connection.closeFuture().thenRun(() -> onTcpHostClosed(race, connection));
        startProxy();
        publish(Status.CONNECTED, "已通过 TCP 直连房主，可以在 Minecraft 中连接本地地址");
    }

    private void onTcpHostClosed(GuestRace race, Connection connection) {
        if (hostConnection != connection) {
            return;
        }
        hostConnection = null;
        if (hosting || roomCode.isEmpty() || race != guestRace) {
            return;
        }
        UdpTransport transport = udpTransport;
        if (transport != null && transport.isPeerReady(hostPeerId)) {
            udpPrimary = true;
            startProxy();
            publish(Status.CONNECTED, "TCP 直连已断开，已切换到 UDP 打洞直连");
            return;
        }
        if (proxy != null) {
            publish(Status.JOINING, "与房主的连接已断开，正在同时尝试 TCP 与 UDP 重连…");
            beginGuestRace();
        }
    }

    private void onUdpWon(GuestRace race) {
        if (race != guestRace || hosting || roomCode.isEmpty()) {
            return;
        }
        if (!race.chosen.compareAndSet(false, true)) {
            return;
        }
        udpPrimary = true;
        Log.info("联机：UDP 打洞成功，已连接房主 " + shortId(hostPeerId));
        startProxy();
        publish(Status.CONNECTED, "已通过 UDP 打洞直连房主，可以在 Minecraft 中连接本地地址");
    }

    private void onInboundConnection(Connection connection) {
        // libp2p hands outbound dials to the same connection handler. Only an
        // inbound connection has our listen port as its local TCP port; the
        // guest's own dials use ephemeral ports. Without this guard the race
        // can complete and disconnect its own winning link, causing a storm of
        // reconnects.
        if (!isInboundConnection(connection, listenPort)) {
            return;
        }
        if (hosting || roomCode.isEmpty() || hostPeerId.isEmpty()) {
            return;
        }
        String remote;
        try {
            remote = connection.secureSession().getRemoteId().toBase58();
        } catch (RuntimeException e) {
            return;
        }
        if (!remote.equals(hostPeerId)) {
            return;
        }
        GuestRace race = guestRace;
        if (race == null) {
            // The host's reverse dial can win the race against the guest's
            // processing of the join response. Hold it for beginGuestRace.
            Connection previous = pendingInbound;
            pendingInbound = connection;
            if (previous != null && previous != connection) {
                disconnect(previous);
            }
            return;
        }
        if (race.tcp.isDone()) {
            disconnect(connection);
            return;
        }
        if (!race.tcp.complete(connection)) {
            disconnect(connection);
        }
    }

    private void disconnect(Connection connection) {
        if (connection == null) {
            return;
        }
        Host node = host;
        if (node != null) {
            try {
                node.getNetwork().disconnect(connection);
            } catch (RuntimeException ignored) {
                // Already disconnected.
            }
        }
    }

    private void startProxy() {
        synchronized (this) {
            if (proxy != null) {
                localAddress = proxy.address();
                return;
            }
            try {
                LocalTunnelProxy created = new LocalTunnelProxy(this::openTunnel);
                created.start();
                proxy = created;
                localAddress = created.address();
                Log.info("联机：本地代理监听 " + localAddress);
            } catch (IOException e) {
                publish(Status.ERROR, "无法创建本地代理端口：" + e.getMessage());
            }
        }
    }

    private ByteTunnel openTunnel() throws Exception {
        Host node = host;
        if (node == null) {
            throw new IOException("P2P 节点尚未启动");
        }
        if (udpPrimary) {
            UdpTransport udp = udpTransport;
            if (udp == null) {
                throw new IOException("UDP 通道已关闭");
            }
            try {
                return udp.openTunnel(hostPeerId);
            } catch (IOException error) {
                Connection fallback = hostConnection;
                if (fallback == null || fallback.closeFuture().isDone()) {
                    throw error;
                }
                // The punched path died but the TCP backup is still alive.
                udpPrimary = false;
            }
        }
        Connection connection = hostConnection;
        if (connection != null && !connection.closeFuture().isDone()) {
            StreamPromise<TunnelSession> promise =
                    node.newStream(List.of(TunnelProtocol.ID), connection);
            return promise.getController().get(15, TimeUnit.SECONDS);
        }
        UdpTransport udp = udpTransport;
        if (udp != null && udp.isPeerReady(hostPeerId)) {
            udpPrimary = true;
            return udp.openTunnel(hostPeerId);
        }
        throw new IOException("与房主的连接已断开");
    }

    private void onInboundTunnel(ByteTunnel session) {
        String remote = session.remotePeerId();
        int port = gamePort;
        boolean allowed = hosting && port > 0 && roomPeers.contains(remote);
        if (!allowed) {
            Log.warn("联机：拒绝来自 " + shortId(remote) + " 的隧道（不在当前房间）");
            session.close();
            return;
        }
        exec.execute(() -> bridgeToGame(session, port));
    }

    private void bridgeToGame(ByteTunnel session, int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
            TunnelBridge.setTcpNoDelay(socket);
            TunnelBridge.bridge(socket, session);
        } catch (IOException e) {
            Log.warn("联机：无法连接本机游戏端口 " + port + ": " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            session.close();
            publish(Status.HOSTING, "好友已连接，但本机游戏端口 " + port
                    + " 没有响应。请在游戏中「对局域网开放」或启动服务器。");
        }
    }

    // -------------------------------------------------------------- signalling

    private CompletableFuture<Void> handshake(RendezvousClient client, String displayName) {
        RendezvousClient previous = rendezvous;
        rendezvous = client;
        client.attach(message -> dispatch(client, message), () -> onRendezvousClosed(client));
        if (previous != null && previous != client) {
            previous.close();
        }
        return request(client, "hello", body -> {
            body.addProperty("v", 1);
            body.addProperty("name", normalizeNickname(displayName));
            body.addProperty("listenPort", listenPort);
        }).thenAccept(welcome -> {
            observedPublicIp = string(welcome, "publicIp");
            Log.info("联机：已连接引导服务器，公网 IP=" + observedPublicIp);
        }).thenApply(ignored -> null);
    }

    private CompletableFuture<JsonObject> request(RendezvousClient client, String type,
                                                  Consumer<JsonObject> filler) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        String id = Long.toString(requestSeq.incrementAndGet());
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("type", type);
        try {
            filler.accept(message);
        } catch (Throwable t) {
            future.completeExceptionally(t);
            return future;
        }
        requests.put(id, future);
        try {
            client.send(message);
        } catch (IOException e) {
            requests.remove(id);
            future.completeExceptionally(e);
            return future;
        }
        exec.schedule(() -> {
            CompletableFuture<JsonObject> waiting = requests.remove(id);
            if (waiting != null) {
                waiting.completeExceptionally(new IOException("引导服务器响应超时（" + type + "）"));
            }
        }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return future;
    }

    private CompletableFuture<RendezvousClient> connectRendezvous(Host node) {
        RendezvousClient existing = rendezvous;
        if (existing != null && !existing.isClosed()) {
            return CompletableFuture.completedFuture(existing);
        }
        String text;
        try {
            text = BootstrapServer.address();
        } catch (RuntimeException e) {
            return failed(new IOException(e.getMessage(), e));
        }
        Multiaddr address;
        PeerId server;
        try {
            address = new Multiaddr(text.trim());
            server = address.getPeerId();
        } catch (RuntimeException e) {
            return failed(new IOException("内置联机服务器地址无效，请重新安装启动器", e));
        }
        if (server == null) {
            return failed(new IOException("内置联机服务器地址无效，请重新安装启动器"));
        }
        if (server.toBase58().equals(peerId)) {
            return failed(new IOException("引导服务器不能是本机节点"));
        }
        Multiaddr dialAddress = withoutPeerId(address);
        publish(Status.CONNECTING, "正在连接引导服务器…");
        CompletableFuture<RendezvousClient> result = new CompletableFuture<>();
        node.getNetwork().connect(server, dialAddress)
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((connection, error) -> {
                    if (error != null) {
                        result.completeExceptionally(new IOException(
                                "无法连接引导服务器：" + friendly(error), error));
                        return;
                    }
                    StreamPromise<RendezvousClient> promise;
                    try {
                        promise = node.newStream(List.of(RendezvousProtocol.ID), connection);
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                        return;
                    }
                    promise.getController().orTimeout(15, TimeUnit.SECONDS)
                            .whenComplete((client, streamError) -> {
                                if (streamError != null) {
                                    result.completeExceptionally(new IOException(
                                            "引导服务器不支持联机协议：" + friendly(streamError),
                                            streamError));
                                } else {
                                    result.complete(client);
                                }
                            });
                });
        return result;
    }

    private void dispatch(RendezvousClient client, JsonObject message) {
        String type = string(message, "type");
        String id = string(message, "id");

        if (!id.isEmpty()) {
            CompletableFuture<JsonObject> waiting = requests.remove(id);
            if (waiting != null) {
                if ("error".equals(type)) {
                    waiting.completeExceptionally(new IOException(errorText(message)));
                } else {
                    waiting.complete(message);
                }
            }
        }

        switch (type) {
            case "peer-joined" -> onPeerJoined(message);
            case "peer-left" -> onPeerLeft(message);
            case "room-closed" -> onRoomClosed(message);
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                if (message.has("t")) {
                    pong.add("t", message.get("t"));
                }
                try {
                    client.send(pong);
                } catch (IOException ignored) {
                    // The close handler will take care of the state.
                }
            }
            case "error" -> {
                if (id.isEmpty()) {
                    publish(Status.ERROR, errorText(message));
                }
            }
            default -> {
                // Responses (welcome/created/joined/pong) are handled above.
            }
        }
    }

    private void onRendezvousClosed(RendezvousClient client) {
        if (rendezvous != client) {
            return;
        }
        rendezvous = null;
        boolean direct = proxy != null;
        publish(direct ? Status.CONNECTED : (hosting ? Status.HOSTING : Status.ERROR),
                direct ? "引导服务器已断开，直连仍然可用"
                        : "引导服务器连接已断开，请重新创建或加入房间");
    }

    private void failRoom(String prefix, Throwable error) {
        Log.warn("联机：" + prefix + ": " + friendly(error));
        publish(Status.ERROR, prefix + "：" + friendly(error));
    }

    // ------------------------------------------------------------- room leave

    public void leaveRoom() {
        RendezvousClient client = rendezvous;
        rendezvous = null;
        if (client != null) {
            try {
                JsonObject leave = new JsonObject();
                leave.addProperty("type", "leave");
                client.send(leave);
            } catch (IOException ignored) {
                // Closing anyway.
            }
            client.close();
        }
        Connection connection = hostConnection;
        hostConnection = null;
        cleanupRoom();
        Host node = host;
        if (connection != null && node != null) {
            try {
                node.getNetwork().disconnect(connection);
            } catch (RuntimeException ignored) {
                // Already disconnected.
            }
        }
        publish(node != null ? Status.ONLINE : Status.OFFLINE, "已退出房间");
    }

    private void cleanupRoom() {
        // Invalidate pending dials and punch waiters before tearing down the
        // transport they are attached to.
        roomGeneration.incrementAndGet();
        guestRace = null;
        Connection early = pendingInbound;
        pendingInbound = null;
        if (early != null) {
            disconnect(early);
        }
        udpPrimary = false;
        roomKey = null;
        UdpTransport udp = udpTransport;
        udpTransport = null;
        if (udp != null) {
            udp.close();
        }
        LocalTunnelProxy previous = proxy;
        proxy = null;
        if (previous != null) {
            previous.close();
        }
        Connection connection = hostConnection;
        hostConnection = null;
        Host node = host;
        if (connection != null && node != null) {
            try {
                node.getNetwork().disconnect(connection);
            } catch (RuntimeException ignored) {
                // Already disconnected.
            }
        }
        localAddress = "";
        roomCode = "";
        hostPeerId = "";
        gamePort = 0;
        hosting = false;
        roomPeers.clear();
        clearMembers();
        lastHostAddresses = List.of();
    }

    /** Stops everything and releases the Netty threads before the launcher exits. */
    public void shutdown() {
        leaveRoom();
        Host node;
        synchronized (this) {
            node = host;
            host = null;
        }
        PortMapper mapper = portMapper;
        portMapper = null;
        Thread stopper = new Thread(() -> {
            if (mapper != null) {
                mapper.close();
            }
            if (node != null) {
                try {
                    node.stop().get(6, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // The process is going away.
                }
            }
        }, "ss-p2p-stop");
        stopper.setDaemon(true);
        stopper.start();
        exec.shutdown();
        peerId = "";
        listenPort = 0;
        publish(Status.OFFLINE, "联机节点已停止");
    }

    // -------------------------------------------------------------- addresses

    private CompletableFuture<Void> preparePublicAddress() {
        return CompletableFuture.runAsync(() -> {
            // Recompute on every room attempt so a stale UPnP result from a
            // previous port is not advertised.
            if (portMapper != null) {
                externalAddress = upnpAddress;
                return;
            }
            upnpAddress = "";
            externalAddress = "";
            upnpTcpMapped = false;
            upnpUdpMapped = false;
            if (!Config.get().multiplayerAutoMapPort) {
                Log.info("联机：未启用端口映射，仅公布局域网地址");
                return;
            }
            PortMapper mapper = new PortMapper();
            PortMapper.Result result = mapper.open(listenPort);
            if (result.mapped || result.udpMapped) {
                portMapper = mapper;
                upnpTcpMapped = result.mapped;
                upnpUdpMapped = result.udpMapped;
                // The WAN address returned by a router behind CGNAT is not
                // reachable from the internet; advertising it only makes peers
                // waste time dialing. The server-observed UDP endpoint still
                // gives them the real path when one exists.
                if (result.mapped && isPublicIpv4(result.externalIp)) {
                    upnpAddress = "/ip4/" + result.externalIp + "/tcp/" + listenPort;
                    externalAddress = upnpAddress;
                } else if (result.mapped) {
                    Log.info("联机：UPnP WAN 地址 " + result.externalIp
                            + " 不是公网 IPv4（可能处于运营商级 NAT），不公布 TCP 地址");
                }
                Log.info("联机：UPnP 已映射 " + (result.mapped ? "TCP " : "")
                        + (result.udpMapped ? "UDP " : "") + listenPort);
            } else {
                mapper.close();
                Log.info("联机：未启用端口映射，仅公布局域网地址");
            }
        }, exec);
    }

    private void scheduleMappingRenewal() {
        exec.scheduleAtFixedRate(() -> {
            PortMapper mapper = portMapper;
            if (mapper != null) {
                mapper.renew();
            }
        }, 15, 15, TimeUnit.MINUTES);
    }

    /** True for RFC1918/loopback/link-local/CGNAT IPv4 addresses. */
    private static boolean isPrivateIpv4Address(String address) {
        String text = address.toLowerCase(Locale.ROOT);
        if (!text.startsWith("/ip4/")) {
            return false;
        }
        int end = text.indexOf("/tcp/");
        String host = end > 5 ? text.substring(5, end) : text.substring(5);
        return isPrivateIpv4Host(host);
    }

    private static boolean isPrivateIpv4Host(String host) {
        return host.startsWith("10.")
                || host.startsWith("127.")
                || host.startsWith("169.254.")
                || host.startsWith("192.168.")
                || host.startsWith("100.64.")
                || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /** A globally routable IPv4 literal, excluding RFC1918, CGNAT and special ranges. */
    private static boolean isPublicIpv4(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet4Address)
                    || address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()) {
                return false;
            }
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10 is carrier-grade NAT, not a public address.
            return !(first == 100 && second >= 64 && second <= 127);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Rejects loopback/link-local/multicast, private IPv4 and non-global IPv6
     * before sending probes. ULA addresses never route between networks.
     */
    private static boolean isUsableUdpHost(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address instanceof Inet4Address) {
                return isPublicIpv4(host);
            }
            return address instanceof Inet6Address ipv6 && isGlobalIpv6(ipv6);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A global-unicast IPv6 literal (2000::/3). ULA, link-local, multicast,
     * 6to4 and Teredo addresses are not usable for direct carrier paths.
     */
    private static boolean isGlobalIpv6(Inet6Address address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        if ((first & 0xE0) != 0x20) {
            return false;
        }
        if (first == 0x20 && second == 0x02) {
            return false; // 6to4 2002::/16
        }
        return !(first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0);
    }

    private boolean hasRoutableAddress() {
        if (upnpUdpMapped) {
            return true;
        }
        for (String address : candidateAddresses()) {
            if (address.startsWith("/ip6/")
                    || (address.startsWith("/ip4/") && !isPrivateIpv4Address(address))) {
                return true;
            }
        }
        return false;
    }

    /** A short, actionable explanation when every direct path failed. */
    private String directFailureHint() {
        List<String> hostAddresses = lastHostAddresses;
        int hostIpv6 = 0;
        int hostPublicIpv4 = 0;
        boolean iphoneHotspot = false;
        for (String address : hostAddresses) {
            if (address.startsWith("/ip6/")) {
                hostIpv6++;
            } else if (address.startsWith("/ip4/172.20.10.")) {
                // iPhone Personal Hotspot always hands out 172.20.10.0/28.
                iphoneHotspot = true;
            } else if (address.startsWith("/ip4/") && !isPrivateIpv4Address(address)) {
                hostPublicIpv4++;
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(ipv6Count > 0 ? "本机有 IPv6" : "本机没有公网 IPv6");
        if (!observedPublicIp.isBlank()) {
            text.append("，服务器看到的公网 IPv4 是 ").append(observedPublicIp);
            if (!isPublicIpv4(observedPublicIp)) {
                text.append("（运营商级 NAT）");
            }
        }
        text.append("；房主公布了 ").append(hostIpv6).append(" 个 IPv6、")
                .append(hostPublicIpv4).append(" 个公网 IPv4 地址");
        String carrier = ipv6CarrierHint(hostAddresses);
        if (!carrier.isBlank()) {
            text.append("，房主 IPv6 属于").append(carrier);
        }
        if (iphoneHotspot) {
            text.append("，房主的 172.20.10.x 地址很像 iPhone 个人热点（没有端口映射）");
        }
        if (hostIpv6 == 0 && hostPublicIpv4 == 0 && !upnpUdpMapped) {
            text.append("。房主没有可被外网连接的地址，通常是手机热点或运营商 CGNAT，"
                    + "无中转时无法直连。");
        } else {
            text.append("。地址存在但连不上，常见原因是房主 Windows 防火墙未放行，"
                    + "或运营商拦截 IPv6/UDP 入站。");
        }
        text.append("建议：房主改用电信手机热点或家庭宽带，并在 Windows 防火墙放行 "
                + "SS-Launcher 的 TCP 和 UDP 入站；联通、移动、广电的手机热点做房主成功率低。");
        return text.toString();
    }

    /** Carrier hints for Chinese IPv6 prefixes, used only to make failure messages actionable. */
    private static String ipv6CarrierHint(List<String> addresses) {
        boolean unicom = false;
        boolean mobile = false;
        boolean broadnet = false;
        boolean telecom = false;
        for (String address : addresses) {
            if (address == null) {
                continue;
            }
            String text = address.toLowerCase(Locale.ROOT);
            if (!text.startsWith("/ip6/")) {
                continue;
            }
            int end = text.indexOf("/tcp/");
            String host = end > 5 ? text.substring(5, end) : text.substring(5);
            if (host.startsWith("2408:")) {
                unicom = true;
            } else if (host.startsWith("2409:")) {
                mobile = true;
            } else if (host.startsWith("240a:")) {
                broadnet = true;
            } else if (host.startsWith("240e:")) {
                telecom = true;
            }
        }
        List<String> hints = new ArrayList<>();
        if (unicom) {
            hints.add("联通 2408 段（部分号段如 841e 封禁全部入站）");
        }
        if (mobile) {
            hints.add("移动 2409 段（蜂窝网络常拦截入站）");
        }
        if (broadnet) {
            hints.add("广电 240a 段（多数地区拦截入站）");
        }
        if (telecom) {
            hints.add("电信 240e 段（入站相对最开放）");
        }
        return String.join("、", hints);
    }

    private static boolean hasRiskyCarrierIpv6(List<String> addresses) {
        String hint = ipv6CarrierHint(addresses);
        return hint.contains("联通") || hint.contains("移动") || hint.contains("广电");
    }

    private List<String> candidateAddresses() {
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        if (externalAddress != null && !externalAddress.isEmpty()) {
            addresses.add(externalAddress);
        }
        // Global IPv6 first: it is reachable across carrier NAT without any
        // port mapping, while a private IPv4 address is usually a dead end
        // for players on another network.
        for (String ip : localIpv6Addresses()) {
            addresses.add("/ip6/" + ip + "/tcp/" + listenPort);
        }
        for (String ip : localIpv4Addresses()) {
            addresses.add("/ip4/" + ip + "/tcp/" + listenPort);
        }
        return new ArrayList<>(addresses);
    }

    private static List<String> localIpv4Addresses() {
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address
                            && !address.isAnyLocalAddress()
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()
                            && !address.isMulticastAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // Fall back to loopback below.
        }
        if (addresses.isEmpty()) {
            addresses.add("127.0.0.1");
        }
        return new ArrayList<>(addresses);
    }

    /**
     * IPv6 global-unicast addresses (2000::/3). ULA and link-local addresses
     * cannot be reached from another network, so advertising them only delays
     * the real candidates; 6to4/Teredo are excluded as well because carriers
     * filter them in practice.
     */
    private static List<String> localIpv6Addresses() {
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet6Address ipv6) || !isGlobalIpv6(ipv6)) {
                        continue;
                    }
                    String text = ipv6.getHostAddress();
                    if (text.indexOf('%') >= 0) {
                        continue;
                    }
                    addresses.add(text);
                }
            }
        } catch (SocketException ignored) {
            // IPv6 is optional.
        }
        return new ArrayList<>(addresses);
    }

    private void addPeerDetails(JsonObject body) {
        body.addProperty("name", normalizeNickname(nickname));
        body.addProperty("listenPort", listenPort);
        List<String> candidates = candidateAddresses();
        Log.info("联机：向引导服务器公布地址 " + candidates);
        JsonArray array = new JsonArray();
        for (String address : candidates) {
            array.add(address);
        }
        body.add("addresses", array);
    }

    /**
     * Registers the server-observed UDP endpoint and every endpoint that can
     * be derived from the peer's TCP multiaddrs. The client binds UDP to the
     * same numeric port as its TCP listener, so an IPv6 (or public IPv4)
     * {@code /tcp/<port>} address is also a UDP candidate. This is what lets
     * UDP work when the bootstrap server has no IPv6 or when the IPv4 mapping
     * observed by the server is useless behind symmetric CGNAT.
     */
    private void addUdpCandidates(UdpTransport transport, String peerId,
                                  UdpEndpoint observed, List<String> addresses) {
        if (transport == null || peerId == null || peerId.isBlank()) {
            return;
        }
        if (observed != null) {
            transport.addPeer(peerId, observed);
        }
        List<UdpEndpoint> derived = udpCandidates(addresses);
        if (!derived.isEmpty()) {
            Log.info("联机：UDP 候选 " + shortId(peerId)
                    + " 服务器观察=" + observed + " 从地址推导=" + derived);
        }
        for (UdpEndpoint endpoint : derived) {
            transport.addPeer(peerId, endpoint);
        }
    }

    /** Extracts UDP candidates from advertised {@code /ip4|ip6/.../tcp/<port>} addresses. */
    static List<UdpEndpoint> udpCandidates(List<String> addresses) {
        LinkedHashSet<UdpEndpoint> endpoints = new LinkedHashSet<>();
        if (addresses == null) {
            return new ArrayList<>();
        }
        for (String text : addresses) {
            if (endpoints.size() >= 12) {
                break;
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                Multiaddr address = new Multiaddr(text);
                MultiaddrComponent tcp = address.getFirstComponent(Protocol.TCP);
                if (tcp == null) {
                    continue;
                }
                int port;
                try {
                    port = Integer.parseInt(tcp.getStringValue());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (port < 1 || port > 65535) {
                    continue;
                }
                MultiaddrComponent ip6 = address.getFirstComponent(Protocol.IP6);
                MultiaddrComponent ip4 = address.getFirstComponent(Protocol.IP4);
                String host = ip6 != null ? ip6.getStringValue()
                        : ip4 != null ? ip4.getStringValue() : "";
                // Link-local IPv6 needs a zone id and is never useful remotely.
                // Private IPv4 is handled by the TCP LAN path; sending UDP
                // probes to random private addresses would only add noise.
                if (host.isBlank() || host.indexOf('%') >= 0 || !isUsableUdpHost(host)) {
                    continue;
                }
                endpoints.add(new UdpEndpoint(host, port));
            } catch (RuntimeException ignored) {
                // Ignore malformed or non-IP addresses (for example /dns/).
            }
        }
        return new ArrayList<>(endpoints);
    }

    // ------------------------------------------------------------------- UDP

    /**
     * Creates the UDP transport before the room request, registers its
     * discovery token with the server, and waits for the server to observe the
     * socket's public endpoint. The endpoint must be known before create/join,
     * because that is when the server snapshots it for the other players.
     *
     * <p>Failure here is never fatal: the room still works over TCP, and an
     * old server that does not know {@code udp-token} simply disables UDP.</p>
     */
    private CompletableFuture<Void> prepareUdp(RendezvousClient client) {
        long generation = roomGeneration.get();
        UdpTransport transport;
        try {
            // Bind UDP to the TCP listen port so peers can derive this
            // endpoint from the address they already receive from the server.
            transport = new UdpTransport(peerId, roomCode, this::isRoomMember,
                    this::onInboundTunnel, VoiceChatService.get()::onInboundStream,
                    listenPort);
        } catch (IOException | RuntimeException e) {
            Log.warn("联机：UDP 打洞不可用，仅使用 TCP 直连: " + friendly(e));
            return CompletableFuture.completedFuture(null);
        }
        return request(client, "udp-token", body ->
                body.addProperty("token", transport.token()))
                .orTimeout(5, TimeUnit.SECONDS)
                .thenCompose(response -> transport.discovery()
                        .orTimeout(3, TimeUnit.SECONDS))
                .handle((endpoint, error) -> {
                    if (generation != roomGeneration.get()) {
                        transport.close();
                        return null;
                    }
                    if (error != null) {
                        Log.warn("联机：UDP 端点发现失败，本次仅使用 TCP 直连: "
                                + friendly(error));
                        transport.close();
                        return null;
                    }
                    udpTransport = transport;
                    Log.info("联机：UDP 打洞已就绪，公网端点 "
                            + endpoint.host() + ":" + endpoint.port()
                            + "，本地 UDP 端口 " + transport.localPort()
                            + "（TCP " + listenPort + "）");
                    return null;
                });
    }

    /**
     * Installs the per-room secret distributed by the server. Without it UDP
     * packets cannot be authenticated, so the transport is dropped and the
     * room continues over TCP only.
     */
    private void applyRoomKey(JsonObject response) {
        UdpTransport transport = udpTransport;
        String encoded = string(response, "roomKey");
        byte[] key = null;
        if (!encoded.isBlank()) {
            try {
                key = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                Log.warn("联机：引导服务器返回的 UDP 密钥格式不正确");
            }
        }
        if (key == null || key.length < 16) {
            if (transport != null) {
                Log.warn("联机：引导服务器未提供 UDP 密钥，本次仅使用 TCP 直连");
                udpTransport = null;
                transport.close();
            }
            roomKey = null;
            return;
        }
        roomKey = key;
        if (transport != null) {
            transport.setRoomCode(roomCode);
            transport.setRoomKey(key);
        }
    }

    // --------------------------------------------------------------- voice

    /**
     * Opens one outbound voice stream to another room member. Voice is a full
     * mesh: every player sends one stream to every other player, so audio
     * never passes through the signalling server or through another player.
     */
    CompletableFuture<VoiceStream> openVoiceStream(String peer, List<String> addresses) {
        Host node = host;
        if (node == null) {
            return failed(new IOException("P2P 节点尚未启动"));
        }
        if (peer == null || peer.isBlank() || !roomPeers.contains(peer)) {
            return failed(new IOException("对方不在当前房间"));
        }
        CompletableFuture<VoiceStream> tcp = dial(peer, addresses).thenCompose(connection -> {
            StreamPromise<VoiceSession> promise;
            try {
                promise = node.newStream(List.of(VoiceProtocol.ID), connection);
            } catch (RuntimeException e) {
                return failed(e);
            }
            return promise.getController().orTimeout(10, TimeUnit.SECONDS)
                    .thenApply(session -> (VoiceStream) session);
        });
        UdpTransport transport = udpTransport;
        CompletableFuture<VoiceStream> udp;
        if (transport != null && transport.isPeerReady(peer)) {
            try {
                udp = CompletableFuture.completedFuture(transport.openVoice(peer));
            } catch (IOException | RuntimeException e) {
                udp = failed(e);
            }
        } else {
            udp = failed(new IOException("UDP 打洞尚未成功"));
        }
        // Same race policy as the game tunnel: whichever path is usable first
        // carries audio, and the loser is closed as soon as it arrives.
        CompletableFuture<VoiceStream> result = new CompletableFuture<>();
        AtomicBoolean chosen = new AtomicBoolean();
        AtomicInteger remaining = new AtomicInteger(2);
        udp.whenComplete((stream, error) -> {
            if (error == null) {
                if (chosen.compareAndSet(false, true)) {
                    result.complete(stream);
                } else {
                    stream.close();
                }
            } else if (remaining.decrementAndGet() == 0) {
                result.completeExceptionally(error);
            }
        });
        tcp.whenComplete((stream, error) -> {
            if (error == null) {
                if (chosen.compareAndSet(false, true)) {
                    result.complete(stream);
                } else {
                    stream.close();
                }
            } else if (remaining.decrementAndGet() == 0) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    /** True while the given peer belongs to the current room roster. */
    boolean isRoomMember(String peer) {
        return peer != null && !peer.isBlank() && roomPeers.contains(peer);
    }

    private CompletableFuture<Connection> dial(String peer, List<String> addresses) {
        Host node = host;
        if (node == null) {
            return failed(new IOException("P2P 节点尚未启动"));
        }
        PeerId target;
        try {
            target = PeerId.fromBase58(peer);
        } catch (RuntimeException e) {
            return failed(new IOException("无效的 PeerID：" + shortId(peer), e));
        }
        List<Multiaddr> parsed = new ArrayList<>();
        for (String text : addresses) {
            try {
                Multiaddr address = new Multiaddr(text);
                if (!address.has(Protocol.TCP)) {
                    continue;
                }
                parsed.add(withoutPeerId(address));
            } catch (RuntimeException e) {
                Log.warn("联机：忽略无效地址 " + text);
            }
        }
        if (parsed.isEmpty()) {
            return failed(new IOException("对方没有可直连的 TCP 地址"));
        }
        // Race every candidate (IPv6, LAN IPv4, manual public address...).
        // The first successful connection wins; a failed address does not stop
        // the others, and the caller only reports an error once all are out.
        CompletableFuture<Connection> result = new CompletableFuture<>();
        AtomicReference<Connection> winner = new AtomicReference<>();
        AtomicInteger remaining = new AtomicInteger(parsed.size());
        for (Multiaddr address : parsed) {
            CompletableFuture<Connection> attempt;
            try {
                attempt = node.getNetwork().connect(target, address);
            } catch (RuntimeException e) {
                if (remaining.decrementAndGet() == 0) {
                    result.completeExceptionally(e);
                }
                continue;
            }
            attempt.orTimeout(8, TimeUnit.SECONDS).whenComplete((connection, error) -> {
                if (error == null) {
                    if (winner.compareAndSet(null, connection)) {
                        Log.info("联机：已通过 " + address + " 连接 " + shortId(peer));
                        result.complete(connection);
                    } else if (winner.get() != connection) {
                        // Another address won; keep only one link to the peer.
                        node.getNetwork().disconnect(connection);
                    }
                } else if (remaining.decrementAndGet() == 0 && winner.get() == null) {
                    result.completeExceptionally(error);
                }
            });
        }
        return result;
    }

    private static Multiaddr withoutPeerId(Multiaddr address) {
        List<MultiaddrComponent> components = address.getComponents().stream()
                .filter(component -> component.getProtocol() != Protocol.P2P)
                .toList();
        return new Multiaddr(components);
    }

    /**
     * True when this connection was accepted by our own listener rather than
     * created by an outbound dial. The local TCP port is the reliable signal:
     * inbound connections carry the listen port, outbound connections get an
     * ephemeral port from the OS.
     */
    static boolean isInboundConnection(Connection connection, int listenPort) {
        if (connection == null || listenPort <= 0) {
            return false;
        }
        try {
            MultiaddrComponent tcp = connection.localAddress()
                    .getFirstComponent(Protocol.TCP);
            return tcp != null && Integer.parseInt(tcp.getStringValue()) == listenPort;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Room codes are uppercase A–Z / 0–9, 4–10 characters. */
    public static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        String value = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return value.length() > 10 ? value.substring(0, 10) : value;
    }

    private String normalizeNickname(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            name = Config.get().multiplayerName;
        }
        if (name == null || name.isBlank()) {
            name = "玩家" + (peerId.length() > 4 ? peerId.substring(peerId.length() - 4) : "");
        }
        return name.length() > 24 ? name.substring(0, 24) : name;
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 10) {
            return String.valueOf(id);
        }
        return id.substring(0, 6) + "…" + id.substring(id.length() - 4);
    }

    private static JsonObject object(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonObject()) {
            return null;
        }
        return source.getAsJsonObject(key);
    }

    private static String string(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = source.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static int intValue(JsonObject source, String key, int fallback) {
        try {
            return source.has(key) ? source.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static List<String> readAddresses(JsonObject source) {
        List<String> addresses = new ArrayList<>();
        if (source == null || !source.has("addresses") || !source.get("addresses").isJsonArray()) {
            return addresses;
        }
        for (JsonElement element : source.getAsJsonArray("addresses")) {
            if (element.isJsonPrimitive()) {
                String text = element.getAsString();
                if (text != null && !text.isBlank()) {
                    addresses.add(text.trim());
                }
            }
        }
        return addresses;
    }

    private String errorText(JsonObject message) {
        String text = string(message, "message");
        if (text.isBlank()) {
            text = string(message, "code");
        }
        return text.isBlank() ? "引导服务器返回了错误" : text;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static String friendly(Throwable error) {
        Throwable t = error;
        if (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
