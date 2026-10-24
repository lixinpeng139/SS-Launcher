package com.sslauncher.multiplayer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Log;
import io.libp2p.core.Stream;
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed;
import io.libp2p.transport.implementation.StreamOverNetty;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Client side of the rendezvous protocol: one libp2p stream, newline-delimited
 * JSON in both directions. Messages received before {@link #attach} are queued
 * so a fast server cannot race the handshake.
 */
final class RendezvousClient {
    static final Gson GSON = new Gson();
    static final int MAX_LINE = 256 * 1024;

    private final Stream stream;
    private final Channel channel;
    private final Queue<JsonObject> pending = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final Object writeLock = new Object();

    private Consumer<JsonObject> listener;
    private Runnable closeHandler;
    private volatile boolean closed;
    private Throwable closeError;

    RendezvousClient(Stream stream) {
        this.stream = stream;
        this.channel = stream instanceof StreamOverNetty over ? over.getNettyChannel() : null;
        stream.pushHandler(new LineBasedFrameDecoder(MAX_LINE));
        stream.pushHandler(new StringDecoder(StandardCharsets.UTF_8));
        stream.pushHandler(new SimpleChannelInboundHandler<String>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, String line) {
                if (line == null || line.isBlank()) {
                    return;
                }
                try {
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    dispatch(message);
                } catch (RuntimeException e) {
                    Log.warn("联机：引导服务器发来了无法解析的消息: " + e.getMessage());
                }
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt == RemoteWriteClosed.INSTANCE) {
                    markClosed(null);
                    return;
                }
                super.userEventTriggered(ctx, evt);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                markClosed(null);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                markClosed(cause);
            }
        });
    }

    void attach(Consumer<JsonObject> listener, Runnable closeHandler) {
        List<JsonObject> backlog = new ArrayList<>();
        boolean alreadyClosed;
        synchronized (lock) {
            this.listener = listener;
            this.closeHandler = closeHandler;
            while (!pending.isEmpty()) {
                backlog.add(pending.poll());
            }
            alreadyClosed = closed;
        }
        for (JsonObject message : backlog) {
            deliver(listener, message);
        }
        if (alreadyClosed && closeHandler != null) {
            closeHandler.run();
        }
    }

    void send(JsonObject message) throws IOException {
        if (closed) {
            throw new IOException("引导服务器连接已关闭");
        }
        byte[] bytes = (GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8);
        // A stream that was just opened still carries the multistream-select
        // encoders for one event-loop turn. Writing inline from the completion
        // callback of newStream() would then prefix the JSON with a varint
        // length. Deferring to the event loop makes the pipeline clean first.
        if (channel != null) {
            try {
                channel.eventLoop().execute(() -> write(bytes));
            } catch (RuntimeException e) {
                throw new IOException("无法发送信令消息: " + e.getMessage(), e);
            }
        } else {
            write(bytes);
        }
    }

    private void write(byte[] bytes) {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            try {
                stream.writeAndFlush(Unpooled.wrappedBuffer(bytes));
            } catch (RuntimeException e) {
                markClosed(e);
            }
        }
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    Throwable closeError() {
        synchronized (lock) {
            return closeError;
        }
    }

    void close() {
        markClosed(null);
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // Already closed.
        }
    }

    private void dispatch(JsonObject message) {
        Consumer<JsonObject> target;
        synchronized (lock) {
            target = listener;
            if (target == null) {
                pending.add(message);
                return;
            }
        }
        deliver(target, message);
    }

    private static void deliver(Consumer<JsonObject> target, JsonObject message) {
        try {
            target.accept(message);
        } catch (Throwable t) {
            Log.warn("联机：处理引导服务器消息失败: " + t.getMessage());
        }
    }

    private void markClosed(Throwable error) {
        Runnable handler;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            closeError = error;
            handler = closeHandler;
        }
        if (handler != null) {
            handler.run();
        }
    }
}
