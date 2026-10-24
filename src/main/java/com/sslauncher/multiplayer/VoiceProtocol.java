package com.sslauncher.multiplayer;

import io.libp2p.core.Stream;
import io.libp2p.protocol.ProtocolHandler;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Voice stream protocol. The initiator only writes; the responder registers
 * the session with {@link VoiceChatService}, which reads frames from it.
 */
final class VoiceProtocol extends ProtocolHandler<VoiceSession> {
    static final String ID = "/sslauncher/voice/1.0.0";

    private final Consumer<VoiceSession> responder;

    VoiceProtocol(Consumer<VoiceSession> responder) {
        // These arguments are inbound traffic quotas, not frame limits.
        super(Long.MAX_VALUE, Long.MAX_VALUE);
        this.responder = responder;
    }

    @Override
    protected CompletableFuture<VoiceSession> onStartInitiator(Stream stream) {
        return CompletableFuture.completedFuture(new VoiceSession(stream));
    }

    @Override
    protected CompletableFuture<VoiceSession> onStartResponder(Stream stream) {
        VoiceSession session = new VoiceSession(stream);
        try {
            responder.accept(session);
        } catch (Throwable ignored) {
            session.close();
        }
        return CompletableFuture.completedFuture(session);
    }
}
