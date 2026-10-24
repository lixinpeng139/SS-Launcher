package com.sslauncher.multiplayer;

import com.sslauncher.core.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Copies bytes between a local TCP socket and a libp2p tunnel in both
 * directions. Clean EOF is propagated as a half-close so Minecraft's own
 * shutdown handshake still works; a real error tears down both sides.
 */
final class TunnelBridge {
    private TunnelBridge() {
    }

    static void bridge(Socket socket, ByteTunnel session) {
        bridge(socket, session, null);
    }

    /**
     * @param onFinished runs exactly once when the tunnel is fully torn down
     *                   (both directions done or either side failed), so the
     *                   caller can release a connection slot.
     */
    static void bridge(Socket socket, ByteTunnel session, Runnable onFinished) {
        AtomicInteger remaining = new AtomicInteger(2);
        AtomicBoolean finished = new AtomicBoolean();
        Runnable complete = () -> {
            if (onFinished != null && finished.compareAndSet(false, true)) {
                onFinished.run();
            }
        };

        Thread up = new Thread(() -> {
            boolean clean = false;
            try {
                clean = pump(socket.getInputStream(), session.out(), session::closeWrite);
            } catch (IOException e) {
                // The local socket disappeared before the bridge started.
            } finally {
                boolean last = remaining.decrementAndGet() == 0;
                if (!clean || last) {
                    closeBoth(socket, session);
                    complete.run();
                }
            }
        }, "ss-p2p-up");

        Thread down = new Thread(() -> {
            boolean clean = false;
            try {
                clean = pump(session.in(), socket.getOutputStream(), () -> {
                    try {
                        socket.shutdownOutput();
                    } catch (IOException ignored) {
                        // Socket already closed.
                    }
                });
            } catch (IOException e) {
                // The local socket disappeared before the bridge started.
            } finally {
                boolean last = remaining.decrementAndGet() == 0;
                if (!clean || last) {
                    closeBoth(socket, session);
                    complete.run();
                }
            }
        }, "ss-p2p-down");

        up.setDaemon(true);
        down.setDaemon(true);
        up.start();
        down.start();
    }

    /** Returns true when the stream ended with a clean EOF. */
    private static boolean pump(InputStream in, OutputStream out, Runnable onEof) {
        boolean clean = false;
        try {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) {
                    continue;
                }
                out.write(buffer, 0, n);
                out.flush();
            }
            clean = true;
        } catch (IOException e) {
            // A reset is normal when the player disconnects; there is nothing
            // useful to report for a single tunnel.
        } finally {
            if (clean) {
                try {
                    onEof.run();
                } catch (RuntimeException ignored) {
                    // Peer already gone.
                }
            }
        }
        return clean;
    }

    private static void closeBoth(Socket socket, ByteTunnel session) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        session.close();
    }

    static void setTcpNoDelay(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            Log.warn("联机：无法设置 TCP_NODELAY: " + e.getMessage());
        }
    }
}
