package com.sslauncher.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.MultiaddrComponent;
import io.libp2p.core.multiformats.Protocol;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Room state machine. All mutations are serialized on this object's monitor;
 * network writes only enqueue work on Netty event loops, so holding the lock
 * never blocks on I/O.
 */
final class RoomManager {
    private static final char[] CODE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final int MAX_ADDRESSES = 8;

    private final ServerConfig config;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Room> rooms = new HashMap<>();
    /** UDP discovery token -> signalling session. */
    private final Map<String, Session> udpTokens = new HashMap<>();

    RoomManager(ServerConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------- lifecycle

    synchronized void onSessionOpened(Session session) {
        Session previous = sessions.put(session.peerId(), session);
        if (previous != null && previous != session) {
            // A reconnecting client replaces its old half-open session, but the
            // old room must be closed explicitly before the new session joins.
            removePeer(previous, "客户端已重新连接");
            previous.close();
        }
        Log.info("+ 会话 peer=" + session.shortId()
                + (session.remoteIp().isEmpty() ? "" : " ip=" + session.remoteIp()));
    }

    synchronized void onSessionClosed(Session session) {
        dropUdpToken(session);
        if (sessions.get(session.peerId()) != session) {
            return;
        }
        sessions.remove(session.peerId(), session);
        removePeer(session, "连接已断开");
        Log.info("- 会话 peer=" + session.shortId());
    }

    private void dropUdpToken(Session session) {
        String token = session.udpToken;
        if (!token.isEmpty()) {
            udpTokens.remove(token, session);
            session.udpToken = "";
        }
    }

    synchronized void tick() {
        long now = System.nanoTime();
        long idleNanos = config.idleSeconds * 1_000_000_000L;
        long pingNanos = config.pingSeconds * 1_000_000_000L;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.isClosed()) {
                continue;
            }
            if (now - session.lastSeen > idleNanos) {
                Log.info("会话超时，断开 peer=" + session.shortId());
                session.close();
                continue;
            }
            if (now - session.lastPing > pingNanos) {
                session.lastPing = now;
                session.ping();
            }
        }
    }

    // --------------------------------------------------------------- messages

    synchronized void handle(Session session, JsonObject message) {
        if (session.isClosed()) {
            return;
        }
        String type = string(message, "type");
        String id = string(message, "id");
        switch (type) {
            case "hello" -> hello(session, message, id);
            case "udp-token" -> udpToken(session, message, id);
            case "create" -> create(session, message, id);
            case "join" -> join(session, message, id);
            case "leave" -> {
                Peer peer = session.peer;
                if (peer != null) {
                    session.peer = null;
                    leaveRoom(peer, "玩家退出房间");
                }
            }
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                if (message.has("t")) {
                    pong.add("t", message.get("t"));
                }
                session.send(pong);
            }
            case "pong" -> {
                // lastSeen was already refreshed by the read handler.
            }
            default -> {
                if (!id.isEmpty()) {
                    session.sendError(id, "未知请求类型");
                }
            }
        }
    }

    private void hello(Session session, JsonObject message, String id) {
        Peer peer = ensurePeer(session);
        String name = cleanName(string(message, "name"));
        if (!name.isEmpty()) {
            peer.name = name;
        } else if (peer.name.isEmpty()) {
            peer.name = fallbackName(session.peerId());
        }
        int listenPort = intValue(message, "listenPort", 0);
        if (listenPort > 0 && listenPort <= 65535) {
            peer.listenPort = listenPort;
        }

        JsonObject reply = new JsonObject();
        reply.addProperty("id", id);
        reply.addProperty("type", "welcome");
        reply.addProperty("publicIp", session.remoteIp());
        session.send(reply);
    }

    /**
     * Associates a client-generated token with this signalling session. The
     * client then sends a UDP discovery packet containing the token; the UDP
     * listener records the source address it observes. Only the resulting
     * {IP, port} pair is shared with room members; no data is relayed.
     */
    private void udpToken(Session session, JsonObject message, String id) {
        String token = string(message, "token").trim();
        if (token.length() < 16 || token.length() > 128
                || !token.matches("[A-Za-z0-9_+/=-]+")) {
            session.sendError(id, "UDP 令牌格式不正确");
            return;
        }
        dropUdpToken(session);
        session.udpToken = token;
        udpTokens.put(token, session);
        JsonObject reply = new JsonObject();
        reply.addProperty("id", id);
        reply.addProperty("type", "udp-token-ok");
        session.send(reply);
    }

    /**
     * Called from the UDP listener thread. Returns the observed endpoint when
     * the token is valid, or null when the packet should be ignored.
     */
    synchronized InetSocketAddress onUdpProbe(String token, InetSocketAddress source) {
        Session session = udpTokens.get(token);
        if (session == null || session.isClosed() || source == null
                || source.getAddress() == null || source.getPort() <= 0) {
            return null;
        }
        InetSocketAddress previous = session.udpEndpoint;
        session.udpEndpoint = source;
        if (previous == null || !previous.equals(source)) {
            Log.info("UDP 端点已登记 peer=" + session.shortId() + " udp="
                    + source.getAddress().getHostAddress() + ":" + source.getPort());
        }
        return source;
    }

    private void create(Session session, JsonObject message, String id) {
        int gamePort = intValue(message, "gamePort", 0);
        if (gamePort < 1 || gamePort > 65535) {
            session.sendError(id, "游戏端口无效");
            return;
        }
        Peer peer = ensurePeer(session);
        updateProfile(peer, message);
        leaveRoom(peer, "创建了新房间");
        if (rooms.size() >= config.maxRooms) {
            session.sendError(id, "服务器房间已满，请稍后再试");
            return;
        }

        String code = newCode();
        peer.gamePort = gamePort;
        peer.game = cleanGame(string(message, "game"));
        peer.host = true;
        peer.udpEndpoint = session.udpEndpoint;
        byte[] keyBytes = new byte[32];
        random.nextBytes(keyBytes);
        Room room = new Room(code, peer, Base64.getEncoder().encodeToString(keyBytes));
        peer.room = room;
        rooms.put(code, room);

        JsonObject reply = new JsonObject();
        reply.addProperty("id", id);
        reply.addProperty("type", "created");
        reply.addProperty("code", code);
        reply.addProperty("roomKey", room.key);
        session.send(reply);
        Log.info("房间已创建 code=" + code + " host=" + peer.shortId()
                + " gamePort=" + gamePort + " 地址数=" + peer.addresses.size());
    }

    private void join(Session session, JsonObject message, String id) {
        String code = normalizeCode(string(message, "code"));
        if (code.length() < 4) {
            session.sendError(id, "房间号格式不正确");
            return;
        }
        Room room = rooms.get(code);
        if (room == null) {
            session.sendError(id, "房间不存在或已关闭");
            return;
        }
        Peer peer = ensurePeer(session);
        if (peer.room == room) {
            // Re-joining the same room: refresh the observed endpoint so a
            // restarted client is not stuck advertising its old UDP port.
            peer.udpEndpoint = session.udpEndpoint;
            sendJoined(session, room, id);
            return;
        }
        if (room.guests.size() >= config.maxGuests) {
            session.sendError(id, "房间已满（最多 " + config.maxGuests + " 位客人）");
            return;
        }

        updateProfile(peer, message);
        peer.udpEndpoint = session.udpEndpoint;
        leaveRoom(peer, "加入了其他房间");
        peer.host = false;
        peer.room = room;
        room.guests.put(peer.peerId, peer);

        sendJoined(session, room, id);
        notifyPeerJoined(room, peer);
        Log.info("玩家加入 code=" + code + " peer=" + peer.shortId()
                + " 客人总数=" + room.guests.size());
    }

    private void sendJoined(Session session, Room room, String id) {
        JsonObject reply = new JsonObject();
        reply.addProperty("id", id);
        reply.addProperty("type", "joined");
        reply.addProperty("code", room.code);
        reply.addProperty("roomKey", room.key);
        reply.add("host", peerInfo(room.host));
        // Existing guests so the new player's roster is complete immediately.
        // Guests receive each other's listen addresses because voice chat is a
        // full P2P mesh: every pair of room members needs a direct stream, and
        // the server never relays audio. Addresses are only shared with peers
        // that are already in the same room.
        JsonArray guests = new JsonArray();
        for (Peer guest : room.guests.values()) {
            if (!guest.peerId.equals(session.peerId())) {
                guests.add(memberInfo(guest));
            }
        }
        reply.add("guests", guests);
        session.send(reply);
    }

    private void notifyPeerJoined(Room room, Peer guest) {
        JsonObject toHost = new JsonObject();
        toHost.addProperty("type", "peer-joined");
        toHost.add("peer", peerInfo(guest));
        room.host.session.send(toHost);

        JsonObject toGuests = new JsonObject();
        toGuests.addProperty("type", "peer-joined");
        toGuests.add("peer", memberInfo(guest));
        for (Peer other : room.guests.values()) {
            if (other != guest && !other.session.isClosed()) {
                other.session.send(toGuests);
            }
        }
    }

    // ------------------------------------------------------------------ rooms

    private void leaveRoom(Peer peer, String reason) {
        Room room = peer.room;
        if (room == null) {
            return;
        }
        peer.room = null;
        if (peer.host) {
            closeRoom(room, reason);
            return;
        }
        peer.host = false;
        room.guests.remove(peer.peerId);
        JsonObject notify = new JsonObject();
        notify.addProperty("type", "peer-left");
        notify.addProperty("peerId", peer.peerId);
        if (!room.host.session.isClosed()) {
            room.host.session.send(notify);
        }
        for (Peer other : room.guests.values()) {
            if (!other.session.isClosed()) {
                other.session.send(notify);
            }
        }
        Log.info("玩家离开 code=" + room.code + " peer=" + peer.shortId());
    }

    private void closeRoom(Room room, String reason) {
        rooms.remove(room.code, room);
        room.host.room = null;
        room.host.host = false;
        for (Peer guest : new ArrayList<>(room.guests.values())) {
            guest.room = null;
            guest.host = false;
            JsonObject closed = new JsonObject();
            closed.addProperty("type", "room-closed");
            closed.addProperty("reason", reason);
            guest.session.send(closed);
        }
        room.guests.clear();
        Log.info("房间已关闭 code=" + room.code + " 原因=" + reason);
    }

    private void removePeer(Session session, String reason) {
        Peer peer = session.peer;
        if (peer == null) {
            return;
        }
        session.peer = null;
        leaveRoom(peer, reason);
    }

    // --------------------------------------------------------------- profiles

    private Peer ensurePeer(Session session) {
        if (session.peer == null) {
            session.peer = new Peer(session);
        }
        return session.peer;
    }

    private void updateProfile(Peer peer, JsonObject message) {
        String name = cleanName(string(message, "name"));
        if (!name.isEmpty()) {
            peer.name = name;
        } else if (peer.name.isEmpty()) {
            peer.name = fallbackName(peer.peerId);
        }
        int listenPort = intValue(message, "listenPort", 0);
        if (listenPort > 0 && listenPort <= 65535) {
            peer.listenPort = listenPort;
        }
        if (message.has("addresses") && message.get("addresses").isJsonArray()) {
            peer.addresses = sanitizeAddresses(message.getAsJsonArray("addresses"));
        }
    }

    /**
     * Roster entry for guests: enough to open a direct voice stream, but no
     * game port or game name. The host still gets the full {@link #peerInfo}.
     */
    private static JsonObject memberInfo(Peer peer) {
        JsonObject info = new JsonObject();
        info.addProperty("peerId", peer.peerId);
        info.addProperty("name", peer.name);
        info.addProperty("listenPort", peer.listenPort);
        JsonArray addresses = new JsonArray();
        for (String address : peer.addresses) {
            addresses.add(address);
        }
        info.add("addresses", addresses);
        addUdp(info, peer);
        return info;
    }

    private static JsonObject peerInfo(Peer peer) {
        JsonObject info = new JsonObject();
        info.addProperty("peerId", peer.peerId);
        info.addProperty("name", peer.name);
        info.addProperty("listenPort", peer.listenPort);
        info.addProperty("gamePort", peer.gamePort);
        info.addProperty("game", peer.game);
        JsonArray addresses = new JsonArray();
        for (String address : peer.addresses) {
            addresses.add(address);
        }
        info.add("addresses", addresses);
        addUdp(info, peer);
        return info;
    }

    /** The public UDP endpoint observed by the discovery listener, if any. */
    private static void addUdp(JsonObject info, Peer peer) {
        InetSocketAddress endpoint = peer.udpEndpoint;
        if (endpoint == null || endpoint.getAddress() == null) {
            return;
        }
        JsonObject udp = new JsonObject();
        udp.addProperty("host", endpoint.getAddress().getHostAddress());
        udp.addProperty("port", endpoint.getPort());
        info.add("udp", udp);
    }

    // ---------------------------------------------------------------- helpers

    private String newCode() {
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String code = builder.toString();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("无法生成不重复的房间号");
    }

    /** Mirrors the client's normalization: uppercase A–Z / 0–9, max 10. */
    static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        String value = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return value.length() > 10 ? value.substring(0, 10) : value;
    }

    private static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        String value = name.replaceAll("[\\p{Cntrl}]", "").trim();
        return value.length() > 24 ? value.substring(0, 24) : value;
    }

    private static String fallbackName(String peerId) {
        return "玩家" + (peerId.length() > 4 ? peerId.substring(peerId.length() - 4) : "");
    }

    private static String cleanGame(String game) {
        if (game == null) {
            return "";
        }
        String value = game.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static List<String> sanitizeAddresses(JsonArray input) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : input) {
            if (result.size() >= MAX_ADDRESSES) {
                break;
            }
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            String text;
            try {
                text = element.getAsString().trim();
            } catch (RuntimeException e) {
                continue;
            }
            if (text.isEmpty() || text.length() > 128) {
                continue;
            }
            try {
                Multiaddr address = new Multiaddr(text);
                if (!address.has(Protocol.TCP)
                        || address.has(Protocol.P2PCIRCUIT)
                        || !address.hasAny(Protocol.IP4, Protocol.IP6,
                        Protocol.DNS, Protocol.DNS4, Protocol.DNS6)) {
                    continue;
                }
                List<MultiaddrComponent> components = address.getComponents().stream()
                        .filter(component -> component.getProtocol() != Protocol.P2P)
                        .toList();
                result.add(new Multiaddr(components).toString());
            } catch (RuntimeException ignored) {
                // Ignore malformed addresses instead of failing the request.
            }
        }
        return List.copyOf(result);
    }

    private static String string(JsonObject source, String key) {
        if (source == null || !source.has(key)) {
            return "";
        }
        JsonElement value = source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "";
        }
        try {
            return value.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int intValue(JsonObject source, String key, int fallback) {
        if (source == null || !source.has(key)) {
            return fallback;
        }
        try {
            return source.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
