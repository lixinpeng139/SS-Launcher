package com.sslauncher.multiplayer;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * One reliable byte pipe between the local Minecraft client and the room host.
 * Implemented by the libp2p TCP transport and by the UDP/KCP transport.
 */
interface ByteTunnel {
    InputStream in();

    OutputStream out();

    void closeWrite();

    void close();

    String remotePeerId();
}
