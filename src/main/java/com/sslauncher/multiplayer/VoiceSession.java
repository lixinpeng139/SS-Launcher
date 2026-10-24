package com.sslauncher.multiplayer;

import io.libp2p.core.Stream;
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One reliable voice stream.
 *
 * <p>Each frame is {@code int32 length + Opus packet}. The stream is used in
 * one direction only: the initiator writes microphone frames, the responder
 * reads them. Every pair of room members therefore has two independent
 * streams (A→B and B→A), which avoids any negotiation or stream-dedup logic
 * when both sides dial each other at the same time.</p>
 */
final class VoiceSession implements VoiceStream {
    /** Opus packets are at most 1275 bytes; leave room for future headers. */
    private static final int MAX_FRAME = 4096;

    private final Stream stream;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeHandled = new AtomicBoolean();
    private volatile Consumer<byte[]> frameHandler;
    private volatile Runnable closeHandler;

    VoiceSession(Stream stream) {
        this.stream = stream;
        stream.pushHandler(new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 4, 0, 4));
        stream.pushHandler(new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
                byte[] frame = new byte[buf.readableBytes()];
                buf.readBytes(frame);
                if (frame.length == 0) {
                    return;
                }
                Consumer<byte[]> handler = frameHandler;
                if (handler != null) {
                    try {
                        handler.accept(frame);
                    } catch (Throwable ignored) {
                        // A broken audio sink must not tear down the stream.
                    }
                }
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt == RemoteWriteClosed.INSTANCE) {
                    markClosed();
                    return;
                }
                super.userEventTriggered(ctx, evt);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                markClosed();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                markClosed();
            }
        });
    }

    @Override
    public String remotePeerId() {
        try {
            return stream.remotePeerId().toBase58();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void setFrameHandler(Consumer<byte[]> handler) {
        this.frameHandler = handler;
    }

    /**
     * Registers the close callback. If the stream already closed before the
     * handler was installed, the callback runs immediately so callers cannot
     * miss the event and keep a dead session in their map.
     */
    @Override
    public void setCloseHandler(Runnable handler) {
        this.closeHandler = handler;
        if (handler != null && closed.get()) {
            runCloseHandler(handler);
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /** Sends one Opus packet; the length prefix is added here. */
    @Override
    public void writeFrame(byte[] payload) throws IOException {
        if (closed.get()) {
            throw new IOException("语音流已关闭");
        }
        if (payload == null || payload.length == 0 || payload.length > MAX_FRAME) {
            return;
        }
        byte[] frame = new byte[4 + payload.length];
        ByteBuffer.wrap(frame).putInt(payload.length).put(payload);
        try {
            stream.writeAndFlush(Unpooled.wrappedBuffer(frame));
        } catch (RuntimeException e) {
            markClosed();
            throw new IOException("无法写入语音流: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        markClosed();
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // Already closed.
        }
    }

    private void markClosed() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Runnable handler = closeHandler;
        if (handler != null) {
            runCloseHandler(handler);
        }
    }

    /** Runs the callback at most once across the close/set race. */
    private void runCloseHandler(Runnable handler) {
        if (!closeHandled.compareAndSet(false, true)) {
            return;
        }
        try {
            handler.run();
        } catch (Throwable ignored) {
            // Cleanup must not throw back into Netty.
        }
    }
}
