package com.sslauncher.multiplayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Log;
import io.jpower.kcp.netty.Kcp;
import io.jpower.kcp.netty.KcpOutput;
import io.jpower.kcp.netty.Ukcp;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * UDP hole punching and a reliable KCP transport for rooms.
 *
 * <p>The bootstrap server only observes each player's public UDP endpoint.
 * Peers exchange small authenticated punch probes; once both directions work,
 * game tunnels and voice frames run over KCP on the same socket. The server
 * never sees or forwards any game/voice payload.</p>
 *
 * <p>All KCP messages are encrypted with AES-256-GCM using a per-pair key
 * derived from the room secret distributed over the encrypted signalling
 * stream. The KCP conversation id is authenticated as additional data, so a
 * packet cannot be redirected to another stream.</p>
 */
final class UdpTransport implements AutoCloseable {
    private static final byte[] PUNCH_MAGIC = "SSPUNCH1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DISCOVER_MAGIC = "SSUDP1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DISCOVER_REPLY = "SSUDP2".getBytes(StandardCharsets.US_ASCII);
    private static final int PUNCH_TOKEN_LENGTH = 16;
    private static final int MAX_PEER_ID_BYTES = 128;
    private static final int MAX_LINKS = 256;
    private static final int MAX_MESSAGE = 32 * 1024;
    private static final int RECEIVE_BUFFER = MAX_MESSAGE + 1024;
    /** Enough for the server-observed endpoint plus every advertised address. */
    private static final int MAX_PUNCH_CANDIDATES = 12;

    static final int MSG_DATA = 0x00;
    static final int MSG_FIN = 0x01;
    static final int MSG_CLOSE = 0x02;
    static final int MSG_PING = 0x03;
    static final int MSG_PONG = 0x04;
    static final int MSG_HELLO = 0x7F;

    private final String selfPeerId;
    private volatile String roomCode;
    private final Predicate<String> roomMember;
    private final Consumer<ByteTunnel> tunnelHandler;
    private final Consumer<VoiceStream> voiceHandler;
    private final DatagramChannel channel;
    private final int localPort;
    private final InetSocketAddress discoveryServer;
    private final String udpToken;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService exec;
    private final Thread reader;
    private final Object sendLock = new Object();
    private final Map<Integer, KcpLink> links = new ConcurrentHashMap<>();
    private final Map<String, PunchPeer> peers = new ConcurrentHashMap<>();
    private final Map<Long, String> tagToPeer = new ConcurrentHashMap<>();
    private final Map<String, byte[]> pairKeys = new ConcurrentHashMap<>();
    private final CompletableFuture<UdpEndpoint> discovered = new CompletableFuture<>();
    private final AtomicInteger pendingLinks = new AtomicInteger();
    private final ThreadLocal<Cipher> encryptCipher =
            ThreadLocal.withInitial(UdpTransport::newCipher);
    private final ThreadLocal<Cipher> decryptCipher =
            ThreadLocal.withInitial(UdpTransport::newCipher);

    private volatile byte[] roomKey;
    private volatile byte[] punchToken;
    private volatile long selfTag;
    private volatile boolean closed;
    private int discoveryAttempts;

    UdpTransport(String selfPeerId, String roomCode, Predicate<String> roomMember,
                 Consumer<ByteTunnel> tunnelHandler, Consumer<VoiceStream> voiceHandler)
            throws IOException {
        this(selfPeerId, roomCode, roomMember, tunnelHandler, voiceHandler,
                BootstrapServer.udpAddress(), 0);
    }

    /**
     * Uses the same numeric port as the TCP listener when possible. Peers can
     * then derive an IPv6 UDP endpoint from the advertised TCP multiaddr, which
     * makes UDP work even when the IPv4 endpoint observed by the server is
     * useless (symmetric CGNAT) or when the bootstrap server has no IPv6.
     */
    UdpTransport(String selfPeerId, String roomCode, Predicate<String> roomMember,
                 Consumer<ByteTunnel> tunnelHandler, Consumer<VoiceStream> voiceHandler,
                 int preferredPort) throws IOException {
        this(selfPeerId, roomCode, roomMember, tunnelHandler, voiceHandler,
                BootstrapServer.udpAddress(), preferredPort);
    }

    /** Package-private seam: a null discovery server disables discovery. */
    UdpTransport(String selfPeerId, String roomCode, Predicate<String> roomMember,
                 Consumer<ByteTunnel> tunnelHandler, Consumer<VoiceStream> voiceHandler,
                 InetSocketAddress discoveryServer) throws IOException {
        this(selfPeerId, roomCode, roomMember, tunnelHandler, voiceHandler,
                discoveryServer, 0);
    }

    UdpTransport(String selfPeerId, String roomCode, Predicate<String> roomMember,
                 Consumer<ByteTunnel> tunnelHandler, Consumer<VoiceStream> voiceHandler,
                 InetSocketAddress discoveryServer, int preferredPort) throws IOException {
        this.selfPeerId = selfPeerId;
        this.roomCode = roomCode == null ? "" : roomCode;
        this.roomMember = roomMember;
        this.tunnelHandler = tunnelHandler;
        this.voiceHandler = voiceHandler;
        this.udpToken = newToken();
        this.discoveryServer = discoveryServer;
        DatagramChannel opened = DatagramChannel.open();
        int boundPort;
        try {
            opened.bind(new InetSocketAddress(Math.max(preferredPort, 0)));
            boundPort = ((InetSocketAddress) opened.getLocalAddress()).getPort();
        } catch (IOException preferredFailed) {
            if (preferredPort <= 0) {
                opened.close();
                throw preferredFailed;
            }
            try {
                opened.bind(new InetSocketAddress(0));
                boundPort = ((InetSocketAddress) opened.getLocalAddress()).getPort();
                Log.warn("联机：UDP 端口 " + preferredPort + " 已被占用（"
                        + preferredFailed.getMessage() + "），改用 " + boundPort);
            } catch (IOException fallbackFailed) {
                opened.close();
                fallbackFailed.addSuppressed(preferredFailed);
                throw fallbackFailed;
            }
        }
        this.channel = opened;
        this.channel.configureBlocking(true);
        this.localPort = boundPort;
        this.exec = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "ss-udp");
            thread.setDaemon(true);
            return thread;
        });
        this.reader = new Thread(this::readLoop, "ss-udp-reader");
        this.reader.setDaemon(true);
        this.reader.start();
        exec.scheduleAtFixedRate(this::tick, 10, 10, TimeUnit.MILLISECONDS);
        exec.scheduleAtFixedRate(this::punchTick, 100, 100, TimeUnit.MILLISECONDS);
        startDiscovery();
    }

    int localPort() {
        return localPort;
    }

    String token() {
        return udpToken;
    }

    /** The public endpoint observed by the server, once discovery succeeds. */
    UdpEndpoint publicEndpoint() {
        return discovered.getNow(null);
    }

    CompletableFuture<UdpEndpoint> discovery() {
        return discovered;
    }

    /**
     * The host only learns the room code after the server answers the create
     * request, which happens after the transport is created. The code is part
     * of every KCP hello, so it is installed as soon as it is known.
     */
    void setRoomCode(String code) {
        this.roomCode = code == null ? "" : code;
    }

    /**
     * Installs the room secret. Before this call punch probes and KCP packets
     * cannot be authenticated, so the transport stays idle.
     */
    void setRoomKey(byte[] key) {
        if (closed || key == null || key.length < 16) {
            return;
        }
        roomKey = key.clone();
        punchToken = hmac(roomKey, "sslauncher-punch", PUNCH_TOKEN_LENGTH);
        selfTag = tagFor(selfPeerId);
        tagToPeer.clear();
        pairKeys.clear();
        for (PunchPeer peer : peers.values()) {
            tagToPeer.put(tagFor(peer.peerId), peer.peerId);
        }
        Log.info("联机：UDP 打洞已就绪，本地端口 " + localPort);
    }

    /**
     * Adds a candidate UDP endpoint for a room member. A peer may have several
     * usable endpoints: the IPv4 mapping observed by the server, plus IPv6
     * endpoints derived from its advertised TCP addresses (which use the same
     * numeric port because the client binds UDP and TCP to one port).
     *
     * <p>Probes are sent to every candidate until an authenticated probe
     * arrives; the source address of that probe becomes the primary address.</p>
     */
    void addPeer(String peerId, UdpEndpoint endpoint) {
        if (closed || peerId == null || peerId.isBlank()
                || peerId.equals(selfPeerId) || endpoint == null) {
            return;
        }
        InetSocketAddress address;
        try {
            address = endpoint.toSocketAddress();
        } catch (Exception e) {
            Log.warn("联机：忽略无效的 UDP 地址 " + endpoint.host() + ":" + endpoint.port());
            return;
        }
        PunchPeer peer = peers.computeIfAbsent(peerId, PunchPeer::new);
        if (peer.candidates.size() < MAX_PUNCH_CANDIDATES
                && peer.candidates.add(address) && peer.address == null) {
            peer.address = address;
        }
        if (roomKey != null) {
            tagToPeer.put(tagFor(peerId), peerId);
        }
    }

    void removePeer(String peerId) {
        if (peerId == null) {
            return;
        }
        PunchPeer peer = peers.remove(peerId);
        if (peer != null) {
            peer.ready.completeExceptionally(new IOException("对方已离开房间"));
        }
        Long tag = null;
        for (var entry : tagToPeer.entrySet()) {
            if (entry.getValue().equals(peerId)) {
                tag = entry.getKey();
                break;
            }
        }
        if (tag != null) {
            tagToPeer.remove(tag);
        }
        for (KcpLink link : links.values()) {
            if (peerId.equals(link.peerId)) {
                link.close(true);
            }
        }
    }

    boolean isPeerReady(String peerId) {
        PunchPeer peer = peerId == null ? null : peers.get(peerId);
        return peer != null && peer.reachable;
    }

    /** Completes when a valid punch probe has been seen from that peer. */
    CompletableFuture<Void> punch(String peerId) {
        PunchPeer peer = peerId == null ? null : peers.get(peerId);
        if (peer == null || (peer.candidates.isEmpty() && peer.address == null)) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("对方没有公布 UDP 地址"));
            return failed;
        }
        return peer.ready;
    }

    ByteTunnel openTunnel(String peerId) throws IOException {
        PunchPeer peer = peers.get(peerId);
        if (closed || peer == null || !peer.reachable || peer.address == null) {
            throw new IOException("UDP 打洞尚未成功");
        }
        KcpLink link = createLink(peerId, peer.address, true);
        KcpTunnelSession session = new KcpTunnelSession(this, link, peerId);
        link.tunnel = session;
        try {
            link.sendHello("game");
        } catch (IOException e) {
            link.close(false);
            throw e;
        }
        return session;
    }

    VoiceStream openVoice(String peerId) throws IOException {
        PunchPeer peer = peers.get(peerId);
        if (closed || peer == null || !peer.reachable || peer.address == null) {
            throw new IOException("UDP 打洞尚未成功");
        }
        KcpLink link = createLink(peerId, peer.address, true);
        KcpVoiceSession session = new KcpVoiceSession(this, link, peerId);
        link.voice = session;
        try {
            link.sendHello("voice");
        } catch (IOException e) {
            link.close(false);
            throw e;
        }
        return session;
    }

    void deliverTunnel(ByteTunnel tunnel) {
        try {
            tunnelHandler.accept(tunnel);
        } catch (Throwable t) {
            Log.warn("联机：UDP 隧道处理异常: " + t.getMessage());
            tunnel.close();
        }
    }

    void deliverVoice(VoiceStream stream) {
        try {
            voiceHandler.accept(stream);
        } catch (Throwable t) {
            Log.warn("联机：UDP 语音处理异常: " + t.getMessage());
            stream.close();
        }
    }

    // -------------------------------------------------------------- discovery

    private void startDiscovery() {
        if (discoveryServer == null) {
            discovered.completeExceptionally(new IOException("引导服务器没有 UDP 地址"));
            return;
        }
        try {
            exec.scheduleAtFixedRate(() -> {
                if (closed || discovered.isDone()) {
                    return;
                }
                if (++discoveryAttempts > 60) {
                    discovered.completeExceptionally(new IOException("UDP 端点发现超时"));
                    return;
                }
                sendDiscovery();
            }, 0, 100, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    private void sendDiscovery() {
        byte[] token = udpToken.getBytes(StandardCharsets.US_ASCII);
        byte[] packet = new byte[DISCOVER_MAGIC.length + token.length];
        System.arraycopy(DISCOVER_MAGIC, 0, packet, 0, DISCOVER_MAGIC.length);
        System.arraycopy(token, 0, packet, DISCOVER_MAGIC.length, token.length);
        try {
            sendRaw(packet, discoveryServer);
        } catch (IOException e) {
            // The next attempt may succeed; no need to log every 100 ms.
        }
    }

    private void handleDiscoveryReply(byte[] data, InetSocketAddress source) {
        int prefix = DISCOVER_REPLY.length;
        byte[] token = udpToken.getBytes(StandardCharsets.US_ASCII);
        if (data.length < prefix + token.length + 1 + 4 + 2) {
            return;
        }
        for (int i = 0; i < prefix; i++) {
            if (data[i] != DISCOVER_REPLY[i]) {
                return;
            }
        }
        for (int i = 0; i < token.length; i++) {
            if (data[prefix + i] != token[i]) {
                return;
            }
        }
        int offset = prefix + token.length;
        int family = data[offset++] & 0xFF;
        int addressLength = family == 4 ? 4 : family == 6 ? 16 : 0;
        if (addressLength == 0 || data.length != offset + addressLength + 2) {
            return;
        }
        byte[] addressBytes = new byte[addressLength];
        System.arraycopy(data, offset, addressBytes, 0, addressLength);
        offset += addressLength;
        int port = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        try {
            InetAddress address = InetAddress.getByAddress(addressBytes);
            InetSocketAddress observed = new InetSocketAddress(address, port);
            UdpEndpoint endpoint = UdpEndpoint.of(observed);
            if (endpoint != null && discovered.complete(endpoint)) {
                Log.info("联机：服务器观察到的 UDP 地址是 " + endpoint.host()
                        + ":" + endpoint.port() + "（服务器 " + source + "）");
            }
        } catch (Exception ignored) {
            // A malformed reply is ignored; discovery retries.
        }
    }

    // ------------------------------------------------------------------ socket

    private void readLoop() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        while (!closed) {
            buffer.clear();
            SocketAddress source;
            try {
                source = channel.receive(buffer);
            } catch (ClosedChannelException e) {
                break;
            } catch (IOException e) {
                if (closed) {
                    break;
                }
                Log.warn("联机：UDP 接收失败: " + e.getMessage());
                continue;
            }
            if (source == null || !(source instanceof InetSocketAddress remote)) {
                continue;
            }
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            try {
                handlePacket(data, remote);
            } catch (RuntimeException e) {
                // A single malformed packet must not kill the reader.
                Log.warn("联机：无法处理 UDP 包: " + e.getMessage());
            }
        }
    }

    private void handlePacket(byte[] data, InetSocketAddress source) {
        if (startsWith(data, DISCOVER_REPLY)) {
            handleDiscoveryReply(data, source);
            return;
        }
        if (startsWith(data, PUNCH_MAGIC)) {
            handlePunch(data, source);
            return;
        }
        if (data.length < 24) {
            return;
        }
        int conv = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        if (conv <= 0) {
            return;
        }
        KcpLink link = links.get(conv);
        if (link == null) {
            if (links.size() >= MAX_LINKS) {
                return;
            }
            link = new KcpLink(conv, null, source, false);
            KcpLink existing = links.putIfAbsent(conv, link);
            if (existing != null) {
                link.close(false);
                link = existing;
            } else {
                pendingLinks.incrementAndGet();
            }
        }
        link.input(data, source);
    }

    private void sendRaw(byte[] data, InetSocketAddress target) throws IOException {
        if (closed || target == null) {
            throw new IOException("UDP 已关闭");
        }
        synchronized (sendLock) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.send(buffer, target);
            }
        }
    }

    // ------------------------------------------------------------------ punch

    private void handlePunch(byte[] data, InetSocketAddress source) {
        byte[] token = punchToken;
        if (token == null) {
            return;
        }
        int offset = PUNCH_MAGIC.length;
        if (data.length < offset + PUNCH_TOKEN_LENGTH + 2) {
            return;
        }
        for (int i = 0; i < PUNCH_TOKEN_LENGTH; i++) {
            if (data[offset + i] != token[i]) {
                return;
            }
        }
        offset += PUNCH_TOKEN_LENGTH;
        int fromLength = data[offset++] & 0xFF;
        if (fromLength == 0 || fromLength > MAX_PEER_ID_BYTES
                || data.length < offset + fromLength + 1) {
            return;
        }
        String from = new String(data, offset, fromLength, StandardCharsets.UTF_8);
        offset += fromLength;
        int toLength = data[offset++] & 0xFF;
        if (toLength == 0 || toLength > MAX_PEER_ID_BYTES
                || data.length < offset + toLength + 8 + 1) {
            return;
        }
        String to = new String(data, offset, toLength, StandardCharsets.UTF_8);
        offset += toLength;
        offset += 8; // nonce, currently only used to make each probe unique
        int flags = data[offset] & 0xFF;
        if (!to.equals(selfPeerId) || from.equals(selfPeerId)) {
            return;
        }
        PunchPeer peer = peers.get(from);
        if (peer == null || !roomMember.test(from)) {
            return;
        }
        // The source address of this authenticated probe is the mapping the
        // peer's NAT created towards us. It may differ from the endpoint the
        // server observed (symmetric NAT), so this is the one we should use.
        if (peer.candidates.size() < MAX_PUNCH_CANDIDATES) {
            peer.candidates.add(source);
        }
        peer.address = source;
        peer.lastHeard = System.currentTimeMillis();
        if (!peer.reachable) {
            peer.reachable = true;
            peer.ready.complete(null);
            Log.info("联机：UDP 打洞成功 " + shortId(from) + " -> " + source);
        }
        if (flags == 0) {
            try {
                sendPunch(peer, true, source);
            } catch (IOException ignored) {
                // The peer keeps probing, so a lost reply is harmless.
            }
        }
    }

    private void punchTick() {
        if (closed || punchToken == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (PunchPeer peer : peers.values()) {
            if (peer.reachable) {
                if (peer.address != null && now - peer.lastProbe >= 15_000) {
                    peer.lastProbe = now;
                    try {
                        sendPunch(peer, false, peer.address);
                    } catch (IOException ignored) {
                        // Retried on the next tick.
                    }
                }
                continue;
            }
            long age = now - peer.createdAt;
            long interval = age < 12_000 ? 100 : age < 60_000 ? 500 : 1500;
            if (now - peer.lastProbe < interval) {
                continue;
            }
            peer.lastProbe = now;
            // Until one direction works we do not know which endpoint is
            // reachable (IPv6, UPnP public IPv4, or the server-observed IPv4
            // mapping), so probe all of them. Each probe also opens a pinhole
            // in a stateful firewall/NAT, which is what lets both sides meet.
            List<InetSocketAddress> targets = new ArrayList<>(peer.candidates);
            if (targets.isEmpty() && peer.address != null) {
                targets.add(peer.address);
            }
            for (InetSocketAddress target : targets) {
                try {
                    sendPunch(peer, false, target);
                } catch (IOException ignored) {
                    // The other candidates may still work.
                }
            }
        }
    }

    private void sendPunch(PunchPeer peer, boolean reply, InetSocketAddress target)
            throws IOException {
        byte[] token = punchToken;
        byte[] from = selfPeerId.getBytes(StandardCharsets.UTF_8);
        byte[] to = peer.peerId.getBytes(StandardCharsets.UTF_8);
        if (token == null || from.length > MAX_PEER_ID_BYTES || to.length > MAX_PEER_ID_BYTES) {
            return;
        }
        ByteBuffer packet = ByteBuffer.allocate(PUNCH_MAGIC.length + PUNCH_TOKEN_LENGTH
                + 1 + from.length + 1 + to.length + 8 + 1);
        packet.put(PUNCH_MAGIC);
        packet.put(token);
        packet.put((byte) from.length);
        packet.put(from);
        packet.put((byte) to.length);
        packet.put(to);
        packet.putLong(random.nextLong());
        packet.put((byte) (reply ? 1 : 0));
        sendRaw(packet.array(), target);
    }

    // -------------------------------------------------------------- KCP links

    private KcpLink createLink(String peerId, InetSocketAddress remote, boolean outbound)
            throws IOException {
        if (links.size() >= MAX_LINKS) {
            throw new IOException("UDP 连接数已达到上限");
        }
        int conv;
        do {
            conv = random.nextInt(Integer.MAX_VALUE - 1) + 1;
        } while (links.containsKey(conv));
        KcpLink link = new KcpLink(conv, peerId, remote, outbound);
        KcpLink previous = links.putIfAbsent(conv, link);
        if (previous != null) {
            link.close(false);
            throw new IOException("UDP 连接号冲突");
        }
        return link;
    }

    private void tick() {
        if (closed) {
            return;
        }
        long now = nowMs();
        for (KcpLink link : links.values()) {
            try {
                link.tick(now);
            } catch (RuntimeException e) {
                link.close(false);
            }
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.length() <= 10 ? id
                : id.substring(0, 6) + "…" + id.substring(id.length() - 4);
    }

    // ----------------------------------------------------------------- crypto

    private long tagFor(String peerId) {
        byte[] key = roomKey;
        if (key == null) {
            return 0;
        }
        byte[] hash = sha256(key, "sslauncher-tag", peerId);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (hash[i] & 0xFFL);
        }
        return value;
    }

    private byte[] pairKey(String a, String b) {
        String first = a.compareTo(b) <= 0 ? a : b;
        String second = a.compareTo(b) <= 0 ? b : a;
        return pairKeys.computeIfAbsent(first + "\n" + second,
                ignored -> sha256(roomKey, "sslauncher-kcp", first + "\n" + second));
    }

    private byte[] seal(String peerId, int conv, int type, byte[] payload) throws IOException {
        byte[] key = roomKey;
        if (key == null) {
            throw new IOException("UDP 加密密钥尚未就绪");
        }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] plain = new byte[1 + payload.length];
        plain[0] = (byte) type;
        System.arraycopy(payload, 0, plain, 1, payload.length);
        byte[] aad = aad(selfTag, conv);
        try {
            Cipher cipher = encryptCipher.get();
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(pairKey(selfPeerId, peerId), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plain);
            ByteBuffer envelope = ByteBuffer.allocate(8 + nonce.length + encrypted.length);
            envelope.putLong(selfTag);
            envelope.put(nonce);
            envelope.put(encrypted);
            return envelope.array();
        } catch (Exception e) {
            throw new IOException("无法加密 UDP 数据", e);
        }
    }

    /** Returns null when the packet is not authentic or not from the expected peer. */
    private OpenMessage open(int conv, byte[] envelope, String expectedPeer) {
        byte[] key = roomKey;
        if (key == null || envelope.length < 8 + 12 + 1 + 16) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        long tag = buffer.getLong();
        String sender = tagToPeer.get(tag);
        if (sender == null || (expectedPeer != null && !expectedPeer.equals(sender))) {
            return null;
        }
        byte[] nonce = new byte[12];
        buffer.get(nonce);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        try {
            Cipher cipher = decryptCipher.get();
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(pairKey(selfPeerId, sender), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(tag, conv));
            byte[] plain = cipher.doFinal(encrypted);
            if (plain.length < 2) {
                return null;
            }
            byte[] payload = new byte[plain.length - 1];
            System.arraycopy(plain, 1, payload, 0, payload.length);
            return new OpenMessage(sender, plain[0] & 0xFF, payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] aad(long tag, int conv) {
        ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.putLong(tag);
        buffer.putInt(conv);
        return buffer.array();
    }

    private static byte[] hmac(byte[] key, String label, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(label.getBytes(StandardCharsets.UTF_8));
            byte[] full = mac.doFinal();
            return java.util.Arrays.copyOf(full, length);
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 HMAC", e);
        }
    }

    private static byte[] sha256(byte[] key, String label, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key);
            digest.update((byte) 0);
            digest.update(label.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 SHA-256", e);
        }
    }

    private static Cipher newCipher() {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (Exception e) {
            throw new IllegalStateException("当前 Java 运行时不支持 AES-GCM", e);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private record OpenMessage(String peerId, int type, byte[] payload) {
    }

    private static final class PunchPeer {
        final String peerId;
        final long createdAt = System.currentTimeMillis();
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final Set<InetSocketAddress> candidates = ConcurrentHashMap.newKeySet();
        volatile InetSocketAddress address;
        volatile boolean reachable;
        volatile long lastProbe;
        volatile long lastHeard;

        PunchPeer(String peerId) {
            this.peerId = peerId;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (PunchPeer peer : peers.values()) {
            peer.ready.completeExceptionally(new IOException("UDP 已关闭"));
        }
        peers.clear();
        for (KcpLink link : links.values()) {
            link.close(false);
        }
        links.clear();
        discovered.completeExceptionally(new IOException("UDP 已关闭"));
        try {
            channel.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        reader.interrupt();
        exec.shutdownNow();
    }

    /**
     * One KCP conversation. Each game TCP stream and each voice stream gets
     * its own conv, so independent flows do not block each other.
     */
    final class KcpLink implements KcpOutput {
        final int conv;
        final boolean outbound;
        final Object lock = new Object();
        private final UdpTransport transport;
        private final Ukcp ukcp;
        private final ByteBuf receiveBuffer = Unpooled.buffer(RECEIVE_BUFFER);
        private final long createdAt = nowMs();
        volatile String peerId;
        volatile InetSocketAddress remote;
        volatile KcpTunnelSession tunnel;
        volatile KcpVoiceSession voice;
        volatile boolean helloReceived;
        volatile long lastHeard = nowMs();
        private boolean helloSent;
        private boolean closed;
        private int decryptFailures;
        private long lastPing;

        KcpLink(int conv, String peerId, InetSocketAddress remote, boolean outbound) {
            this.transport = UdpTransport.this;
            this.conv = conv;
            this.peerId = peerId;
            this.remote = remote;
            this.outbound = outbound;
            this.ukcp = new Ukcp(conv, this);
            ukcp.nodelay(true, 10, 2, true);
            ukcp.setMtu(1200);
            ukcp.wndSize(256, 256);
            ukcp.setMinRto(30);
            ukcp.setDeadLink(20);
            ukcp.setStream(false);
            ukcp.update((int) nowMs());
        }

        void sendHello(String kind) throws IOException {
            JsonObject hello = new JsonObject();
            hello.addProperty("v", 1);
            hello.addProperty("kind", kind);
            hello.addProperty("from", selfPeerId);
            hello.addProperty("code", roomCode);
            byte[] payload = hello.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (lock) {
                sendLocked(MSG_HELLO, payload, true);
                helloSent = true;
            }
        }

        void input(byte[] data, InetSocketAddress source) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                lastHeard = nowMs();
                ByteBuf buffer = Unpooled.wrappedBuffer(data);
                try {
                    ukcp.input(buffer);
                } catch (IOException e) {
                    buffer.release();
                    close(false);
                    return;
                }
                buffer.release();
            }
        }

        void tick(long now) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (!outbound && !helloReceived && now - createdAt > 15_000) {
                    close(false);
                    return;
                }
                if (now - lastHeard > 60_000) {
                    close(false);
                    return;
                }
                try {
                    ukcp.update((int) now);
                    drain();
                    if ((helloSent || helloReceived) && now - lastPing > 10_000) {
                        lastPing = now;
                        sendLocked(MSG_PING, new byte[]{0}, false);
                    }
                } catch (IOException | RuntimeException e) {
                    close(false);
                }
            }
        }

        private void drain() throws IOException {
            int budget = 64;
            while (budget-- > 0 && !closed && ukcp.canRecv()) {
                if (tunnel != null && !tunnel.canAccept()) {
                    // Leave the complete message in KCP: its receive window
                    // then backpressures the sender.
                    return;
                }
                receiveBuffer.clear();
                ukcp.receive(receiveBuffer);
                int size = receiveBuffer.readableBytes();
                if (size <= 0) {
                    continue;
                }
                byte[] envelope = new byte[size];
                receiveBuffer.readBytes(envelope);
                OpenMessage message = open(conv, envelope, helloReceived ? peerId : null);
                if (message == null) {
                    if (++decryptFailures >= 8) {
                        close(false);
                        return;
                    }
                    continue;
                }
                decryptFailures = 0;
                if (!outbound && !helloReceived) {
                    if (!acceptHello(message)) {
                        close(false);
                        return;
                    }
                    continue;
                }
                if (peerId != null && !message.peerId.equals(peerId)) {
                    close(false);
                    return;
                }
                switch (message.type) {
                    case MSG_DATA -> {
                        if (tunnel != null) {
                            tunnel.acceptData(message.payload);
                        } else if (voice != null) {
                            voice.acceptFrame(message.payload);
                        }
                    }
                    case MSG_FIN -> {
                        if (tunnel != null) {
                            tunnel.remoteFin();
                        }
                    }
                    case MSG_CLOSE -> {
                        close(false);
                        return;
                    }
                    case MSG_PING -> sendLocked(MSG_PONG, new byte[]{0}, false);
                    case MSG_PONG -> {
                        // Liveness only; lastHeard already updated on input.
                    }
                    default -> {
                        // Unknown message type: ignore for forward compatibility.
                    }
                }
            }
        }

        private boolean acceptHello(OpenMessage message) {
            if (message.type != MSG_HELLO || peerId != null) {
                return false;
            }
            String from;
            String kind;
            String code;
            try {
                JsonObject hello = JsonParser.parseString(
                        new String(message.payload, StandardCharsets.UTF_8)).getAsJsonObject();
                from = hello.get("from").getAsString();
                kind = hello.get("kind").getAsString();
                code = hello.has("code") ? hello.get("code").getAsString() : "";
            } catch (RuntimeException e) {
                return false;
            }
            if (!from.equals(message.peerId) || !roomMember.test(from)) {
                return false;
            }
            if (!roomCode.isEmpty() && !roomCode.equals(code)) {
                return false;
            }
            peerId = from;
            helloReceived = true;
            if (pendingLinks.get() > 0) {
                pendingLinks.decrementAndGet();
            }
            if ("game".equals(kind)) {
                tunnel = new KcpTunnelSession(transport, this, from);
                deliverTunnel(tunnel);
            } else if ("voice".equals(kind)) {
                voice = new KcpVoiceSession(transport, this, from);
                deliverVoice(voice);
            } else {
                return false;
            }
            return true;
        }

        void sendMessage(int type, byte[] payload, boolean block) throws IOException {
            synchronized (lock) {
                sendLocked(type, payload, block);
            }
        }

        private void sendLocked(int type, byte[] payload, boolean block) throws IOException {
            if (closed) {
                throw new IOException("UDP 流已关闭");
            }
            if (payload.length > MAX_MESSAGE) {
                throw new IOException("UDP 消息过大");
            }
            if (peers.get(peerId) == null && !peerId.equals(selfPeerId)) {
                throw new IOException("对方已离开房间");
            }
            if (block) {
                long deadline = System.currentTimeMillis() + 20_000;
                while (!ukcp.canSend(true) && !closed) {
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) {
                        throw new IOException("UDP 发送窗口已满");
                    }
                    try {
                        lock.wait(Math.min(100, wait));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("等待 UDP 发送窗口时被中断", e);
                    }
                }
                if (closed) {
                    throw new IOException("UDP 流已关闭");
                }
            } else if (!ukcp.canSend(false)) {
                return; // Voice and control messages are droppable when congested.
            }
            byte[] envelope = seal(peerId, conv, type, payload);
            ByteBuf buffer = Unpooled.wrappedBuffer(envelope);
            try {
                ukcp.send(buffer);
            } catch (IOException e) {
                throw e;
            } finally {
                buffer.release();
            }
            // Flush immediately instead of waiting for the next 10 ms tick:
            // voice latency matters, and game handshakes benefit too.
            ukcp.update((int) nowMs());
        }

        @Override
        public void out(ByteBuf data, Kcp kcp) {
            try {
                byte[] packet = new byte[data.readableBytes()];
                data.readBytes(packet);
                sendRaw(packet, remote);
            } catch (IOException e) {
                // KCP retransmits; a transient send failure is not fatal.
            } finally {
                data.release();
            }
        }

        void close(boolean notifyPeer) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (notifyPeer && (helloSent || helloReceived)) {
                    try {
                        sendLocked(MSG_CLOSE, new byte[]{0}, false);
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                }
                closed = true;
                links.remove(conv, this);
                pendingLinks.updateAndGet(value -> Math.max(0, value - 1));
                receiveBuffer.release();
                KcpTunnelSession currentTunnel = tunnel;
                if (currentTunnel != null) {
                    currentTunnel.linkClosed();
                }
                KcpVoiceSession currentVoice = voice;
                if (currentVoice != null) {
                    currentVoice.linkClosed();
                }
                lock.notifyAll();
            }
        }
    }
}
