package com.sslauncher.multiplayer;

import com.google.gson.JsonObject;
import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.MultiaddrComponent;
import io.libp2p.core.multiformats.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live end-to-end signalling probe for the room-level UDP endpoint exchange.
 * Run explicitly:
 *
 * <pre>
 *   gradlew.bat test --tests '*BootstrapUdpRoomProbeTest' -Dss.udpProbe=true
 * </pre>
 *
 * It creates a real room with two libp2p peers, registers a UDP discovery
 * token for each, and verifies that the server hands each side the other
 * side's observed public UDP endpoint plus the same room key.
 */
@EnabledIfSystemProperty(named = "ss.udpProbe", matches = "true")
class BootstrapUdpRoomProbeTest {
    @Test
    void roomExchangeCarriesBothUdpEndpointsAndRoomKey() throws Exception {
        Probe host = new Probe("host-probe");
        Probe guest = new Probe("guest-probe");
        try {
            host.start();
            guest.start();

            UdpEndpoint hostUdp = host.registerUdp();
            UdpEndpoint guestUdp = guest.registerUdp();
            assertNotNull(hostUdp);
            assertNotNull(guestUdp);

            JsonObject created = host.request("3", createMessage("host-probe"));
            String code = created.get("code").getAsString();
            assertTrue(code.length() >= 4);
            String hostKey = created.get("roomKey").getAsString();
            assertTrue(hostKey.length() > 20);

            JsonObject joined = guest.request("3", joinMessage("guest-probe", code));
            String guestKey = joined.get("roomKey").getAsString();
            assertEquals(hostKey, guestKey, "both sides must receive the same room key");

            JsonObject hostInfo = joined.getAsJsonObject("host");
            assertNotNull(hostInfo, "joined response has no host");
            assertUdp(hostInfo, hostUdp, "joined.host.udp");

            JsonObject peer = host.peerJoined.get(10, TimeUnit.SECONDS);
            assertNotNull(peer, "host did not get peer-joined");
            assertUdp(peer, guestUdp, "peer-joined.peer.udp");

            System.out.println("host UDP=" + hostUdp.host() + ":" + hostUdp.port()
                    + ", guest UDP=" + guestUdp.host() + ":" + guestUdp.port()
                    + ", room=" + code);
        } finally {
            guest.close();
            host.close();
        }
    }

    private static JsonObject createMessage(String name) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "create");
        message.addProperty("gamePort", 25565);
        message.addProperty("game", "udp-room-probe");
        message.addProperty("name", name);
        message.addProperty("listenPort", 0);
        return message;
    }

    private static JsonObject joinMessage(String name, String code) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "join");
        message.addProperty("code", code);
        message.addProperty("name", name);
        message.addProperty("listenPort", 0);
        return message;
    }

    private static void assertUdp(JsonObject info, UdpEndpoint expected, String path) {
        assertTrue(info.has("udp"), path + " missing: " + info);
        JsonObject udp = info.getAsJsonObject("udp");
        String host = udp.get("host").getAsString();
        int port = udp.get("port").getAsInt();
        assertEquals(expected.host(), host, path + " host mismatch");
        assertEquals(expected.port(), port, path + " port mismatch");
    }

    /** One libp2p host with a rendezvous stream and a UDP discovery transport. */
    private static final class Probe implements AutoCloseable {
        final String name;
        final Map<String, CompletableFuture<JsonObject>> waits = new ConcurrentHashMap<>();
        final CompletableFuture<JsonObject> peerJoined = new CompletableFuture<>();
        Host host;
        RendezvousClient client;
        UdpTransport transport;

        Probe(String name) {
            this.name = name;
        }

        void start() throws Exception {
            Multiaddr serverAddress = new Multiaddr(BootstrapServer.address());
            PeerId server = serverAddress.getPeerId();
            assertNotNull(server, "built-in server address has no PeerID");
            List<MultiaddrComponent> components = serverAddress.getComponents().stream()
                    .filter(component -> component.getProtocol() != Protocol.P2P)
                    .toList();
            Multiaddr dialAddress = new Multiaddr(components);

            host = new HostBuilder()
                    .keyType(KeyType.ED25519)
                    .listen("/ip4/127.0.0.1/tcp/" + freePort())
                    .protocol(new RendezvousBinding(new RendezvousProtocol()))
                    .build();
            host.start().get(15, TimeUnit.SECONDS);
            Connection connection = host.getNetwork().connect(server, dialAddress)
                    .get(15, TimeUnit.SECONDS);
            StreamPromise<RendezvousClient> promise = host.newStream(
                    List.of(RendezvousProtocol.ID), connection);
            client = promise.getController().get(15, TimeUnit.SECONDS);
            client.attach(message -> {
                if (message.has("id")) {
                    CompletableFuture<JsonObject> waiting =
                            waits.get(message.get("id").getAsString());
                    if (waiting != null) {
                        waiting.complete(message);
                    }
                } else if (message.has("type")
                        && "peer-joined".equals(message.get("type").getAsString())
                        && message.has("peer")) {
                    peerJoined.complete(message.getAsJsonObject("peer"));
                }
            }, () -> { });

            JsonObject hello = new JsonObject();
            hello.addProperty("id", "1");
            hello.addProperty("type", "hello");
            hello.addProperty("v", 1);
            hello.addProperty("name", name);
            hello.addProperty("listenPort", 0);
            client.send(hello);

            transport = new UdpTransport(name + "-" + System.nanoTime(), "",
                    peer -> false, tunnel -> { }, voice -> { });
        }

        UdpEndpoint registerUdp() throws Exception {
            CompletableFuture<JsonObject> reply = new CompletableFuture<>();
            waits.put("2", reply);
            JsonObject token = new JsonObject();
            token.addProperty("id", "2");
            token.addProperty("type", "udp-token");
            token.addProperty("token", transport.token());
            client.send(token);
            JsonObject tokenReply = reply.get(10, TimeUnit.SECONDS);
            assertTrue("udp-token-ok".equals(tokenReply.get("type").getAsString()),
                    "server did not acknowledge udp-token: " + tokenReply);
            return transport.discovery().get(10, TimeUnit.SECONDS);
        }

        JsonObject request(String id, JsonObject message) throws Exception {
            CompletableFuture<JsonObject> reply = new CompletableFuture<>();
            waits.put(id, reply);
            message.addProperty("id", id);
            client.send(message);
            JsonObject response = reply.get(15, TimeUnit.SECONDS);
            assertTrue(!response.has("type")
                            || !"error".equals(response.get("type").getAsString()),
                    "server error: " + response);
            return response;
        }

        @Override
        public void close() {
            if (transport != null) {
                transport.close();
            }
            if (client != null) {
                client.close();
            }
            if (host != null) {
                try {
                    host.stop().get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
