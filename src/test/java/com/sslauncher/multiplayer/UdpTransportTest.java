package com.sslauncher.multiplayer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the UDP path: two transports on loopback exchange
 * authenticated punch probes, then run a KCP tunnel and a voice frame over the
 * punched socket without any server in between.
 */
class UdpTransportTest {
    private static final byte[] ROOM_KEY = new byte[32];

    static {
        for (int i = 0; i < ROOM_KEY.length; i++) {
            ROOM_KEY[i] = (byte) (i * 7 + 3);
        }
    }

    @Test
    void punchedPeersExchangeTunnelAndVoice() throws Exception {
        CountDownLatch tunnelArrived = new CountDownLatch(1);
        AtomicReference<ByteTunnel> inboundTunnel = new AtomicReference<>();
        CountDownLatch voiceArrived = new CountDownLatch(1);
        AtomicReference<byte[]> receivedFrame = new AtomicReference<>();
        CountDownLatch frameArrived = new CountDownLatch(1);

        UdpTransport host = transport("peer-host-a", tunnel -> {
            inboundTunnel.set(tunnel);
            tunnelArrived.countDown();
            Thread echo = new Thread(() -> {
                try {
                    InputStream in = tunnel.in();
                    byte[] buffer = new byte[64];
                    int n = in.read(buffer);
                    if (n > 0) {
                        tunnel.out().write(Arrays.copyOf(buffer, n));
                        tunnel.out().flush();
                    }
                } catch (IOException ignored) {
                    // The test will fail on the missing echo.
                }
            }, "udp-test-echo");
            echo.setDaemon(true);
            echo.start();
        }, stream -> {
            stream.setFrameHandler(frame -> {
                receivedFrame.set(frame);
                frameArrived.countDown();
            });
            voiceArrived.countDown();
        });
        UdpTransport guest = transport("peer-guest-b", tunnel -> { }, stream -> { });
        try {
            host.setRoomKey(ROOM_KEY);
            guest.setRoomKey(ROOM_KEY);
            host.addPeer("peer-guest-b", new UdpEndpoint("127.0.0.1", guest.localPort()));
            guest.addPeer("peer-host-a", new UdpEndpoint("127.0.0.1", host.localPort()));

            host.punch("peer-guest-b").get(10, TimeUnit.SECONDS);
            guest.punch("peer-host-a").get(10, TimeUnit.SECONDS);
            assertTrue(host.isPeerReady("peer-guest-b"));
            assertTrue(guest.isPeerReady("peer-host-a"));

            ByteTunnel outbound = guest.openTunnel("peer-host-a");
            byte[] payload = "hello-over-kcp".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            outbound.out().write(payload);
            outbound.out().flush();

            assertTrue(tunnelArrived.await(10, TimeUnit.SECONDS), "no inbound tunnel");
            assertNotNull(inboundTunnel.get());
            assertEquals("peer-guest-b", inboundTunnel.get().remotePeerId());
            assertEquals("peer-host-a", outbound.remotePeerId());

            CompletableFuture<byte[]> echoed = new CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    byte[] buffer = new byte[64];
                    int n = outbound.in().read(buffer);
                    echoed.complete(n <= 0 ? new byte[0] : Arrays.copyOf(buffer, n));
                } catch (IOException e) {
                    echoed.completeExceptionally(e);
                }
            }, "udp-test-reader");
            reader.setDaemon(true);
            reader.start();
            assertArrayEquals(payload, echoed.get(10, TimeUnit.SECONDS));

            VoiceStream voice = guest.openVoice("peer-host-a");
            voice.writeFrame(new byte[]{1, 2, 3, 4, 5});
            assertTrue(voiceArrived.await(10, TimeUnit.SECONDS), "no inbound voice stream");
            assertTrue(frameArrived.await(10, TimeUnit.SECONDS), "voice frame was not delivered");
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, receivedFrame.get());

            outbound.close();
            voice.close();
        } finally {
            guest.close();
            host.close();
        }
    }

    @Test
    void discoveryParsesTheObservedEndpoint() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        AtomicReference<Throwable> responderError = new AtomicReference<>();
        try (java.net.DatagramSocket discovery = new java.net.DatagramSocket(
                new InetSocketAddress("127.0.0.1", 0))) {
            discovery.setSoTimeout(10_000);
            Thread responder = new Thread(() -> {
                try {
                    byte[] buffer = new byte[512];
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                    discovery.receive(packet);
                    requestSeen.countDown();
                    byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                    byte[] token = Arrays.copyOfRange(request, 6, request.length);
                    java.io.ByteArrayOutputStream reply = new java.io.ByteArrayOutputStream();
                    reply.write("SSUDP2".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    reply.write(token);
                    reply.write(4);
                    reply.write(new byte[]{127, 0, 0, 1});
                    reply.write((54321 >>> 8) & 0xFF);
                    reply.write(54321 & 0xFF);
                    byte[] bytes = reply.toByteArray();
                    discovery.send(new java.net.DatagramPacket(
                            bytes, bytes.length, packet.getSocketAddress()));
                } catch (Throwable e) {
                    responderError.set(e);
                }
            }, "udp-test-discovery");
            responder.setDaemon(true);
            responder.start();

            InetSocketAddress server = new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), discovery.getLocalPort());
            try (UdpTransport transport = new UdpTransport("peer-probe", "",
                    peer -> false, tunnel -> { }, voice -> { }, server)) {
                UdpEndpoint endpoint;
                try {
                    endpoint = transport.discovery().get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    assertTrue(requestSeen.await(2, TimeUnit.SECONDS),
                            "server never received a discovery request; "
                                    + "responder error: " + responderError.get());
                    throw e;
                }
                assertEquals("127.0.0.1", endpoint.host());
                assertEquals(54321, endpoint.port());
            }
        }
    }

    @Test
    void punchesOverIpv6WithCandidateEndpoints() throws Exception {
        Assumptions.assumeTrue(ipv6LoopbackAvailable(), "IPv6 loopback is not available");
        UdpTransport host = transport("peer-host-v6", tunnel -> { }, voice -> { });
        UdpTransport guest = transport("peer-guest-v6", tunnel -> { }, voice -> { });
        try {
            host.setRoomKey(ROOM_KEY);
            guest.setRoomKey(ROOM_KEY);
            // A dead candidate must not stop probes to the reachable one.
            host.addPeer("peer-guest-v6", new UdpEndpoint("127.0.0.1", 1));
            guest.addPeer("peer-host-v6", new UdpEndpoint("127.0.0.1", 1));
            // Exactly what the peers derive from each other's advertised
            // /ip6/.../tcp/<listenPort> multiaddrs.
            host.addPeer("peer-guest-v6", new UdpEndpoint("::1", guest.localPort()));
            guest.addPeer("peer-host-v6", new UdpEndpoint("::1", host.localPort()));

            host.punch("peer-guest-v6").get(10, TimeUnit.SECONDS);
            guest.punch("peer-host-v6").get(10, TimeUnit.SECONDS);
            assertTrue(host.isPeerReady("peer-guest-v6"));
            assertTrue(guest.isPeerReady("peer-host-v6"));
        } finally {
            guest.close();
            host.close();
        }
    }

    @Test
    void bindsPreferredUdpPortWhenAvailable() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        try (UdpTransport transport = new UdpTransport("peer-port", "ROOM01",
                peer -> true, tunnel -> { }, voice -> { },
                (InetSocketAddress) null, port)) {
            assertEquals(port, transport.localPort());
        }
    }

    @Test
    void fallsBackWhenPreferredUdpPortIsOccupied() throws Exception {
        try (DatagramSocket blocker = new DatagramSocket(0)) {
            int blocked = blocker.getLocalPort();
            try (UdpTransport transport = new UdpTransport("peer-port", "ROOM01",
                    peer -> true, tunnel -> { }, voice -> { },
                    (InetSocketAddress) null, blocked)) {
                assertNotEquals(blocked, transport.localPort());
                assertTrue(transport.localPort() > 0);
            }
        }
    }

    @Test
    void derivesUdpCandidatesFromTcpAddresses() {
        List<UdpEndpoint> endpoints = MultiplayerService.udpCandidates(List.of(
                "/ip6/240e:441:2512:246d::1/tcp/4001",
                "/ip6/240e:441:2512:246d::1/tcp/4001/p2p/12D3KooWExample",
                "/ip6/fd00::1/tcp/4001",
                "/ip6/fe80::1/tcp/4001",
                "/ip6/2002:c0a8:0101::1/tcp/4001",
                "/ip4/106.119.59.56/tcp/4001",
                "/ip4/192.168.1.8/tcp/4002",
                "/dns4/example.invalid/tcp/4003",
                "not-a-multiaddr"));
        assertEquals(List.of(
                new UdpEndpoint("240e:441:2512:246d::1", 4001),
                new UdpEndpoint("106.119.59.56", 4001)), endpoints);
    }

    private static boolean ipv6LoopbackAvailable() {
        try (java.nio.channels.DatagramChannel channel = java.nio.channels.DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static UdpTransport transport(String peerId,
                                          java.util.function.Consumer<ByteTunnel> tunnel,
                                          java.util.function.Consumer<VoiceStream> voice)
            throws IOException {
        return new UdpTransport(peerId, "ROOM01", peer -> true, tunnel, voice,
                (InetSocketAddress) null);
    }
}
