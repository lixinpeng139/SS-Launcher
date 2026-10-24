package com.sslauncher.multiplayer;

import com.sslauncher.core.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Guest-side listener. Minecraft connects to {@code 127.0.0.1:<port>} and every
 * accepted socket is paired with a fresh libp2p tunnel stream to the host.
 */
final class LocalTunnelProxy implements AutoCloseable {
    interface TunnelFactory {
        ByteTunnel open() throws Exception;
    }

    private static final int MAX_CONNECTIONS = 64;

    private final ServerSocket server;
    private final TunnelFactory factory;
    private final Semaphore permits = new Semaphore(MAX_CONNECTIONS);
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ss-p2p-proxy");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private Thread acceptor;

    LocalTunnelProxy(TunnelFactory factory) throws IOException {
        this.factory = factory;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
    }

    int port() {
        return server.getLocalPort();
    }

    String address() {
        return "127.0.0.1:" + port();
    }

    void start() {
        acceptor = new Thread(this::acceptLoop, "ss-p2p-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                TunnelBridge.setTcpNoDelay(socket);
                if (!permits.tryAcquire()) {
                    Log.warn("联机：同时连接数过多，已拒绝一个本地连接");
                    socket.close();
                    continue;
                }
                workers.execute(() -> handle(socket));
            } catch (SocketException e) {
                if (running) {
                    Log.warn("联机：本地端口监听中断: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running) {
                    Log.warn("联机：接受本地连接失败: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        ByteTunnel session = null;
        try {
            session = factory.open();
            // The permit is held for the whole lifetime of the tunnel, so a
            // misbehaving client cannot exhaust threads by reconnecting.
            TunnelBridge.bridge(socket, session, permits::release);
        } catch (Exception e) {
            Log.warn("联机：无法建立到房主的隧道: " + friendly(e));
            if (session != null) {
                session.close();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            permits.release();
        }
    }

    private static String friendly(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        workers.shutdownNow();
    }
}
