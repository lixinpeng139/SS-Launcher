package com.sslauncher.multiplayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * The UDP/KCP implementation of a game tunnel. It mirrors the byte-stream and
 * backpressure semantics of the libp2p TCP tunnel so {@link TunnelBridge} and
 * Minecraft cannot tell which transport won the race.
 */
final class KcpTunnelSession implements ByteTunnel {
    private static final int HIGH_WATER = 4 * 1024 * 1024;
    private static final int MAX_WRITE = 32 * 1024;

    private final UdpTransport transport;
    private final UdpTransport.KcpLink link;
    private final String peerId;
    private final Deque<byte[]> queue = new ArrayDeque<>();
    private long queued;
    private boolean remoteClosed;
    private boolean outputClosed;
    private boolean closed;

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
            synchronized (KcpTunnelSession.this) {
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
            synchronized (KcpTunnelSession.this) {
                if (closed || outputClosed) {
                    throw new IOException("联机隧道已关闭");
                }
            }
            int offset = off;
            int remaining = len;
            while (remaining > 0) {
                int chunk = Math.min(remaining, MAX_WRITE);
                byte[] copy = Arrays.copyOfRange(b, offset, offset + chunk);
                link.sendMessage(UdpTransport.MSG_DATA, copy, true);
                offset += chunk;
                remaining -= chunk;
            }
        }

        @Override
        public void flush() {
            // KCP flushes from its update loop; no per-write action needed.
        }
    };

    KcpTunnelSession(UdpTransport transport, UdpTransport.KcpLink link, String peerId) {
        this.transport = transport;
        this.link = link;
        this.peerId = peerId;
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
        return peerId;
    }

    @Override
    public void closeWrite() {
        synchronized (this) {
            if (closed || outputClosed) {
                return;
            }
            outputClosed = true;
        }
        try {
            link.sendMessage(UdpTransport.MSG_FIN, new byte[]{0}, false);
        } catch (IOException ignored) {
            // The peer may already be gone.
        }
    }

    @Override
    public void close() {
        link.close(true);
        linkClosed();
    }

    /** Called by the link while it holds its own lock. */
    void linkClosed() {
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
    }

    void acceptData(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            queue.addLast(data);
            queued += data.length;
            notifyAll();
        }
    }

    void remoteFin() {
        synchronized (this) {
            remoteClosed = true;
            notifyAll();
        }
    }

    /** False when the consumer is behind; KCP then stops draining messages. */
    boolean canAccept() {
        synchronized (this) {
            return !closed && queued < HIGH_WATER;
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
                    notifyAll();
                    return n;
                }
                if (closed || remoteClosed) {
                    return -1;
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
}
