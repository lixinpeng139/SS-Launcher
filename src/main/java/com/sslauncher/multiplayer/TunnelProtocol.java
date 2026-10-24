package com.sslauncher.multiplayer;

import io.libp2p.core.Stream;
import io.libp2p.protocol.ProtocolHandler;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Raw byte tunnel used for Minecraft connections.
 *
 * <p>The guest opens one stream per local TCP connection; the host accepts the
 * stream and bridges it to the game port listening on 127.0.0.1.</p>
 */
final class TunnelProtocol extends ProtocolHandler<TunnelSession> {
    static final String ID = "/sslauncher/tunnel/1.0.0";

    private final Consumer<TunnelSession> responder;

    TunnelProtocol(Consumer<TunnelSession> responder) {
        // 0 = no per-stream traffic limit; backpressure is applied at the
        // TCP bridge through TunnelSession's bounded queue.
        // The arguments are inbound traffic quotas, not size limits: 0 would
        // reject the first byte of every stream (ProtocolViolationException).
        super(Long.MAX_VALUE, Long.MAX_VALUE);
        this.responder = responder;
    }

    @Override
    protected CompletableFuture<TunnelSession> onStartInitiator(Stream stream) {
        return CompletableFuture.completedFuture(new TunnelSession(stream));
    }

    @Override
    protected CompletableFuture<TunnelSession> onStartResponder(Stream stream) {
        TunnelSession session = new TunnelSession(stream);
        try {
            responder.accept(session);
        } catch (Throwable t) {
            session.close();
        }
        return CompletableFuture.completedFuture(session);
    }
}
