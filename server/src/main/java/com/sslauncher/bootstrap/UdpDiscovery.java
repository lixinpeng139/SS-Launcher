package com.sslauncher.bootstrap;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * STUN-like endpoint discovery for UDP hole punching.
 *
 * <p>This listener only observes which public {IP, port} a client's UDP socket
 * maps to and returns it to that client. The client registers a random token
 * over the encrypted signalling stream first, so arbitrary internet hosts
 * cannot query another player's mapping. No game or voice data is ever sent
 * to this listener, and the server never forwards packets between players.</p>
 */
final class UdpDiscovery implements AutoCloseable {
    private static final byte[] REQUEST_MAGIC = "SSUDP1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REPLY_MAGIC = "SSUDP2".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PACKET = 512;

    private final RoomManager manager;
    private final DatagramSocket socket;
    private final Thread thread;
    private volatile boolean closed;

    private UdpDiscovery(RoomManager manager, DatagramSocket socket) {
        this.manager = manager;
        this.socket = socket;
        this.thread = new Thread(this::run, "ss-udp-discovery");
        this.thread.setDaemon(true);
    }

    /** Returns null when the UDP port could not be bound; TCP mode keeps working. */
    static UdpDiscovery start(RoomManager manager, int port) {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            UdpDiscovery discovery = new UdpDiscovery(manager, socket);
            discovery.thread.start();
            Log.info("UDP 端点发现已监听 /ip4/0.0.0.0/udp/" + port);
            return discovery;
        } catch (SocketException e) {
            Log.warn("无法监听 UDP " + port + "，UDP 打洞将不可用: " + e.getMessage());
            return null;
        }
    }

    private void run() {
        byte[] buffer = new byte[MAX_PACKET];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (closed) {
                    break;
                }
                Log.warn("UDP 发现监听异常: " + e.getMessage());
                continue;
            }
            try {
                handle(packet);
            } catch (RuntimeException e) {
                // A malformed packet must never take the listener down.
            }
        }
    }

    private void handle(DatagramPacket packet) {
        int length = packet.getLength();
        if (length <= REQUEST_MAGIC.length || length > MAX_PACKET) {
            return;
        }
        byte[] data = packet.getData();
        for (int i = 0; i < REQUEST_MAGIC.length; i++) {
            if (data[i] != REQUEST_MAGIC[i]) {
                return;
            }
        }
        String token = new String(data, REQUEST_MAGIC.length,
                length - REQUEST_MAGIC.length, StandardCharsets.US_ASCII).trim();
        if (token.isEmpty()) {
            return;
        }
        if (!(packet.getSocketAddress() instanceof InetSocketAddress source)
                || source.getAddress() == null) {
            return;
        }
        InetSocketAddress observed = manager.onUdpProbe(token, source);
        if (observed == null) {
            return;
        }
        byte[] reply = reply(token, observed);
        try {
            socket.send(new DatagramPacket(reply, reply.length,
                    packet.getSocketAddress()));
        } catch (IOException e) {
            Log.warn("无法回复 UDP 发现请求: " + e.getMessage());
        }
    }

    private static byte[] reply(String token, InetSocketAddress observed) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        InetAddress address = observed.getAddress();
        byte[] addressBytes = address.getAddress();
        // magic + token + family byte + address + 2-byte port
        byte[] reply = new byte[REPLY_MAGIC.length + tokenBytes.length + 1
                + addressBytes.length + 2];
        int offset = 0;
        System.arraycopy(REPLY_MAGIC, 0, reply, offset, REPLY_MAGIC.length);
        offset += REPLY_MAGIC.length;
        System.arraycopy(tokenBytes, 0, reply, offset, tokenBytes.length);
        offset += tokenBytes.length;
        reply[offset++] = (byte) (address instanceof Inet4Address ? 4
                : address instanceof Inet6Address ? 6 : 0);
        System.arraycopy(addressBytes, 0, reply, offset, addressBytes.length);
        offset += addressBytes.length;
        reply[offset++] = (byte) ((observed.getPort() >>> 8) & 0xFF);
        reply[offset] = (byte) (observed.getPort() & 0xFF);
        return reply;
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
        thread.interrupt();
    }
}
