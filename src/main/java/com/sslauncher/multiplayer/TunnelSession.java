package com.sslauncher.multiplayer;

import io.libp2p.core.Stream;
import io.libp2p.etc.util.netty.mux.RemoteWriteClosed;
import io.libp2p.transport.implementation.StreamOverNetty;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * One reliable byte pipe carried by a libp2p stream.
 *
 * <p>Inbound bytes are copied out of Netty's {@link ByteBuf} on the event loop
 * and handed to the TCP bridge through a bounded queue. When the consumer is
 * slower than the network the stream's auto-read flag is turned off, which
 * propagates backpressure through the muxer instead of buffering without
 * limit.</p>
 */
final class TunnelSession implements ByteTunnel {
    private static final int HIGH_WATER = 4 * 1024 * 1024;
    private static final int LOW_WATER = 1024 * 1024;

    private final Stream stream;
    private final Channel channel;
    private final Deque<byte[]> queue = new ArrayDeque<>();
    private long queued;
    private boolean paused;
    private boolean remoteClosed;
    private boolean closed;
    private Throwable error;

    private final InputStream input = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return readQueued(b, off, len);
        }

        @Override
        public int available() {
            synchronized (TunnelSession.this) {
                return (int) Math.min(Integer.MAX_VALUE, queued);
            }
        }
    };

    private final OutputStream output = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return;
            }
            if (closed) {
                throw new IOException("联机隧道已关闭");
            }
            byte[] copy = Arrays.copyOfRange(b, off, off + len);
            try {
                stream.writeAndFlush(Unpooled.wrappedBuffer(copy));
            } catch (RuntimeException e) {
                throw new IOException("无法写入联机隧道: " + e.getMessage(), e);
            }
        }

        @Override
        public void flush() {
            // writeAndFlush already flushed the muxer frame.
        }
    };

    TunnelSession(Stream stream) {
        this.stream = stream;
        this.channel = stream instanceof StreamOverNetty over ? over.getNettyChannel() : null;
        stream.pushHandler(new Inbound());
    }

    @Override
    public InputStream in() {
        return input;
    }

    @Override
    public OutputStream out() {
        return output;
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
    public void closeWrite() {
        try {
            stream.closeWrite();
        } catch (RuntimeException ignored) {
            // The stream may have been closed by the peer already.
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            remoteClosed = true;
            queue.clear();
            queued = 0;
            notifyAll();
        }
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // Already closed.
        }
    }

    private int readQueued(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        synchronized (this) {
            while (true) {
                if (!queue.isEmpty()) {
                    byte[] chunk = queue.peekFirst();
                    int n = Math.min(len, chunk.length);
                    System.arraycopy(chunk, 0, b, off, n);
                    queued -= n;
                    queue.removeFirst();
                    if (n < chunk.length) {
                        queue.addFirst(Arrays.copyOfRange(chunk, n, chunk.length));
                    }
                    if (paused && queued <= LOW_WATER) {
                        paused = false;
                        resumeReading();
                    }
                    notifyAll();
                    return n;
                }
                if (closed || remoteClosed) {
                    return -1;
                }
                if (error != null) {
                    throw new IOException("联机隧道已断开: " + messageOf(error), error);
                }
                try {
                    wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取联机隧道时被中断", e);
                }
            }
        }
    }

    private void resumeReading() {
        if (channel != null) {
            channel.config().setAutoRead(true);
            channel.read();
        }
    }

    private void pauseReading() {
        if (channel != null) {
            channel.config().setAutoRead(false);
        }
    }

    private void markRemoteClosed() {
        synchronized (this) {
            remoteClosed = true;
            notifyAll();
        }
    }

    private void markError(Throwable t) {
        synchronized (this) {
            if (error == null) {
                error = t;
            }
            notifyAll();
        }
    }

    private static String messageOf(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private final class Inbound extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) {
                ReferenceCountUtil.release(msg);
                return;
            }
            try {
                int size = buf.readableBytes();
                if (size <= 0) {
                    return;
                }
                byte[] data = new byte[size];
                buf.readBytes(data);
                synchronized (TunnelSession.this) {
                    queue.addLast(data);
                    queued += size;
                    if (!paused && queued >= HIGH_WATER) {
                        paused = true;
                        pauseReading();
                    }
                    TunnelSession.this.notifyAll();
                }
            } finally {
                ReferenceCountUtil.release(buf);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt == RemoteWriteClosed.INSTANCE) {
                markRemoteClosed();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            markRemoteClosed();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            markError(cause);
            markRemoteClosed();
        }
    }
}
