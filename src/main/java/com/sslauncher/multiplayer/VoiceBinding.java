package com.sslauncher.multiplayer;

import io.libp2p.core.multistream.StrictProtocolBinding;

/** Binds {@link VoiceProtocol} to the libp2p host. */
final class VoiceBinding extends StrictProtocolBinding<VoiceSession> {
    VoiceBinding(VoiceProtocol protocol) {
        super(VoiceProtocol.ID, protocol);
    }
}
