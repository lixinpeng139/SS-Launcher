package com.sslauncher.multiplayer;

import io.libp2p.core.multistream.StrictProtocolBinding;

/** Binds {@link RendezvousProtocol} to the libp2p host. */
final class RendezvousBinding extends StrictProtocolBinding<RendezvousClient> {
    RendezvousBinding(RendezvousProtocol protocol) {
        super(RendezvousProtocol.ID, protocol);
    }
}
