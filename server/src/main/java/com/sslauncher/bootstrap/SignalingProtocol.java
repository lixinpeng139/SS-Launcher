package com.sslauncher.bootstrap;

import io.libp2p.core.Stream;
import io.libp2p.core.multistream.StrictProtocolBinding;
import io.libp2p.protocol.ProtocolHandler;

import java.util.concurrent.CompletableFuture;

/**
 * The only protocol the bootstrap server speaks. It exchanges room and address
 * information; it never accepts the tunnel protocol, so game bytes cannot be
 * relayed through this process.
 */
final class SignalingProtocol extends ProtocolHandler<Session> {
    static final String ID = "/sslauncher/rendezvous/1.0.0";

    private final RoomManager manager;

    SignalingProtocol(RoomManager manager) {
        // These are inbound traffic quotas, not message size limits.
        super(Long.MAX_VALUE, Long.MAX_VALUE);
        this.manager = manager;
    }

    @Override
    protected CompletableFuture<Session> onStartResponder(Stream stream) {
        Session session = new Session(stream, manager);
        manager.onSessionOpened(session);
        return CompletableFuture.completedFuture(session);
    }

    @Override
    protected CompletableFuture<Session> onStartInitiator(Stream stream) {
        CompletableFuture<Session> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("bootstrap server is respond-only"));
        return future;
    }

    static final class Binding extends StrictProtocolBinding<Session> {
        Binding(SignalingProtocol protocol) {
            super(ID, protocol);
        }
    }
}
