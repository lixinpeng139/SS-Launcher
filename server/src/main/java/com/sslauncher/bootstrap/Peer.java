package com.sslauncher.bootstrap;

import java.net.InetSocketAddress;
import java.util.List;

/** One connected launcher. Identity always comes from the libp2p stream. */
final class Peer {
    final Session session;
    final String peerId;
    String name = "";
    int listenPort;
    List<String> addresses = List.of();
    /** Public UDP endpoint observed by the server's UDP listener, if any. */
    volatile InetSocketAddress udpEndpoint;
    int gamePort;
    String game = "";
    Room room;
    boolean host;

    Peer(Session session) {
        this.session = session;
        this.peerId = session.peerId();
    }

    String shortId() {
        return peerId.length() <= 10 ? peerId
                : peerId.substring(0, 6) + "…" + peerId.substring(peerId.length() - 4);
    }
}
