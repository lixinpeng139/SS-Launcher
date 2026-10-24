package com.sslauncher.multiplayer;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * One unidirectional voice stream. Implemented by libp2p (TCP) and by the
 * UDP/KCP transport; the voice mesh does not care which path won the race.
 */
interface VoiceStream {
    String remotePeerId();

    void setFrameHandler(Consumer<byte[]> handler);

    void setCloseHandler(Runnable handler);

    boolean isClosed();

    void writeFrame(byte[] packet) throws IOException;

    void close();
}
