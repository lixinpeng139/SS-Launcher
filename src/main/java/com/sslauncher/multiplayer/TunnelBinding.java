package com.sslauncher.multiplayer;

import io.libp2p.core.multistream.StrictProtocolBinding;

/** Binds {@link TunnelProtocol} to the libp2p host. */
final class TunnelBinding extends StrictProtocolBinding<TunnelSession> {
    TunnelBinding(TunnelProtocol protocol) {
        super(TunnelProtocol.ID, protocol);
    }
}
