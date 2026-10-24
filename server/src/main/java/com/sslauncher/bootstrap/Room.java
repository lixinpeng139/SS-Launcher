package com.sslauncher.bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;

/** A room is host plus guests; it lives only as long as the host is online. */
final class Room {
    final String code;
    final Peer host;
    /**
     * Shared secret handed to every room member over the encrypted signalling
     * stream. It authenticates UDP punch probes and encrypts KCP payloads, so
     * only room members can join a punched path. The server never uses it to
     * relay or decrypt game/voice traffic.
     */
    final String key;
    final Map<String, Peer> guests = new LinkedHashMap<>();
    final long createdAt = System.currentTimeMillis();

    Room(String code, Peer host, String key) {
        this.code = code;
        this.host = host;
        this.key = key;
    }
}
