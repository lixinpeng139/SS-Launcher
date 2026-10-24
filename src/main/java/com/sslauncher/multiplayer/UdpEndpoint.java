package com.sslauncher.multiplayer;

import com.google.gson.JsonObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * A public UDP endpoint observed by the bootstrap server.
 *
 * <p>Only IP literals are expected; host names would require a DNS lookup on
 * every probe and are not produced by the server. The endpoint is public
 * information shared with the other members of the same room.</p>
 */
public record UdpEndpoint(String host, int port) {
    public UdpEndpoint {
        host = host == null ? "" : host.trim();
        if (host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("无效的 UDP 地址");
        }
    }

    /** Returns null when the object has no usable {@code udp} entry. */
    static UdpEndpoint fromJson(JsonObject source) {
        if (source == null || !source.has("udp") || !source.get("udp").isJsonObject()) {
            return null;
        }
        JsonObject udp = source.getAsJsonObject("udp");
        try {
            String host = udp.get("host").getAsString();
            int port = udp.get("port").getAsInt();
            return new UdpEndpoint(host, port);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static UdpEndpoint of(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return new UdpEndpoint(address.getAddress().getHostAddress(), address.getPort());
    }

    InetSocketAddress toSocketAddress() throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }
}
