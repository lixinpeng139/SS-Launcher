package com.sslauncher.bootstrap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.libp2p.core.Stream;
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed;
import io.libp2p.transport.implementation.StreamOverNetty;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.MultiaddrComponent;
import io.libp2p.core.multiformats.Protocol;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One signalling connection (one libp2p stream) speaking newline-delimited
 * JSON. Writes are dispatched to the stream's event loop so they can never
 * interleave, and so the multistream-select encoders installed during stream
 * negotiation are gone before the first message is written.
 */
final class Session {
    static final Gson GSON = new Gson();
    static final int MAX_LINE = 256 * 1024;
    private static final int MAX_MESSAGES_PER_MINUTE = 300;

    private final Stream stream;
    private final Channel channel;
    private final RoomManager manager;
    private final String peerId;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    volatile long lastSeen = System.nanoTime();
    volatile long lastPing = System.nanoTime();
    Peer peer;
    /**
     * Random token the client registers over this encrypted signalling stream.
     * The UDP listener uses it to associate an observed public UDP endpoint
     * with this session; the token itself is never sent over UDP in clear
     * outside the discovery packet.
     */
    volatile String udpToken = "";
    volatile java.net.InetSocketAddress udpEndpoint;

    private long windowStart = System.nanoTime();
    private int windowCount;
    private int malformed;

    Session(Stream stream, RoomManager manager) {
        this.stream = stream;
        this.channel = stream instanceof StreamOverNetty over ? over.getNettyChannel() : null;
        this.manager = manager;
        this.peerId = stream.remotePeerId().toBase58();

        stream.pushHandler(new LineBasedFrameDecoder(MAX_LINE));
        stream.pushHandler(new StringDecoder(StandardCharsets.UTF_8));
        stream.pushHandler(new SimpleChannelInboundHandler<String>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, String line) {
                lastSeen = System.nanoTime();
                if (line == null || line.isBlank()) {
                    return;
                }
                if (!allowMessage()) {
                    Log.warn("信令过于频繁，断开 " + shortId());
                    sendError("", "请求过于频繁");
                    close();
                    return;
                }
                JsonObject message;
                try {
                    message = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    if (++malformed >= 5) {
                        Log.warn("无法解析的信令，断开 " + shortId());
                        close();
                    }
                    return;
                }
                manager.handle(Session.this, message);
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt == RemoteWriteClosed.INSTANCE) {
                    close();
                    return;
                }
                super.userEventTriggered(ctx, evt);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                close();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                Log.warn("信令连接异常 " + shortId() + ": " + messageOf(cause));
                close();
            }
        });
    }

    String peerId() {
        return peerId;
    }

    Stream stream() {
        return stream;
    }

    String remoteIp() {
        try {
            Multiaddr remote = stream.getConnection().remoteAddress();
            if (remote == null) {
                return "";
            }
            MultiaddrComponent ip4 = remote.getFirstComponent(Protocol.IP4);
            if (ip4 != null) {
                return ip4.getStringValue();
            }
            MultiaddrComponent ip6 = remote.getFirstComponent(Protocol.IP6);
            if (ip6 != null) {
                return ip6.getStringValue();
            }
        } catch (RuntimeException ignored) {
            // The connection may already be gone.
        }
        return "";
    }

    String shortId() {
        return peerId.length() <= 10 ? peerId
                : peerId.substring(0, 6) + "…" + peerId.substring(peerId.length() - 4);
    }

    void send(JsonObject message) {
        if (closed.get()) {
            return;
        }
        byte[] bytes = (GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8);
        Runnable write = () -> {
            synchronized (writeLock) {
                if (closed.get()) {
                    return;
                }
                try {
                    stream.writeAndFlush(Unpooled.wrappedBuffer(bytes));
                } catch (RuntimeException e) {
                    close();
                }
            }
        };
        if (channel != null) {
            try {
                channel.eventLoop().execute(write);
            } catch (RuntimeException e) {
                close();
            }
        } else {
            write.run();
        }
    }

    void sendError(String id, String message) {
        JsonObject reply = new JsonObject();
        if (id != null && !id.isEmpty()) {
            reply.addProperty("id", id);
        }
        reply.addProperty("type", "error");
        reply.addProperty("message", message);
        send(reply);
    }

    void ping() {
        JsonObject ping = new JsonObject();
        ping.addProperty("type", "ping");
        ping.addProperty("t", System.currentTimeMillis());
        send(ping);
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // Already closed.
        }
        manager.onSessionClosed(this);
    }

    boolean isClosed() {
        return closed.get();
    }

    private boolean allowMessage() {
        long now = System.nanoTime();
        if (now - windowStart > 60_000_000_000L) {
            windowStart = now;
            windowCount = 0;
        }
        return ++windowCount <= MAX_MESSAGES_PER_MINUTE;
    }

    static String messageOf(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null
                && (cause.getMessage() == null || cause.getMessage().isBlank())) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }
}
