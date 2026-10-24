package com.sslauncher.multiplayer;

import io.libp2p.core.Stream;
import io.libp2p.protocol.ProtocolHandler;

import java.util.concurrent.CompletableFuture;

/**
 * Client side of the room-matching protocol. A launcher never acts as a
 * rendezvous server, so inbound streams on this protocol are closed politely.
 */
final class RendezvousProtocol extends ProtocolHandler<RendezvousClient> {
    static final String ID = "/sslauncher/rendezvous/1.0.0";

    RendezvousProtocol() {
        // Traffic quotas are unlimited; the signalling stream carries only JSON.
        super(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Override
    protected CompletableFuture<RendezvousClient> onStartInitiator(Stream stream) {
        return CompletableFuture.completedFuture(new RendezvousClient(stream));
    }

    @Override
    protected CompletableFuture<RendezvousClient> onStartResponder(Stream stream) {
        RendezvousClient client = new RendezvousClient(stream);
        client.close();
        return CompletableFuture.completedFuture(client);
    }
}
