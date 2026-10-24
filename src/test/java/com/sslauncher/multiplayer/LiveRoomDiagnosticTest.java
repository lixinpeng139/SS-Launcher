package com.sslauncher.multiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Temporary live diagnostic. Joins an existing room as another guest and
 * reports the host's advertised addresses, TCP reachability, and whether the
 * host sends any UDP punch probes. Run explicitly:
 *
 * <pre>
 *   gradlew.bat test --tests '*LiveRoomDiagnosticTest' \
 *       -Dss.diag=true -Dss.room=ABCDEF
 * </pre>
 */
@EnabledIfSystemProperty(named = "ss.diag", matches = "true")
class LiveRoomDiagnosticTest {
    @Test
    void diagnoseHostReachability() throws Exception {
        String room = System.getProperty("ss.room", "");
        assertTrue(room.length() >= 4, "pass -Dss.room=<room code>");

        Host host = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/127.0.0.1/tcp/" + freePort())
                .protocol(new RendezvousBinding(new RendezvousProtocol()))
                .build();
        RendezvousClient client = null;
        UdpTransport transport = null;
        try {
            host.start().get(15, TimeUnit.SECONDS);
            String selfId = host.getPeerId().toBase58();
            Multiaddr serverAddress = new Multiaddr(BootstrapServer.address());
            PeerId server = serverAddress.getPeerId();
            List<MultiaddrComponent> components = serverAddress.getComponents().stream()
                    .filter(component -> component.getProtocol() != Protocol.P2P)
                    .toList();
            Connection connection = host.getNetwork()
                    .connect(server, new Multiaddr(components))
                    .get(15, TimeUnit.SECONDS);
            StreamPromise<RendezvousClient> promise = host.newStream(
                    List.of(RendezvousProtocol.ID), connection);
            client = promise.getController().get(15, TimeUnit.SECONDS);

            Map<String, CompletableFuture<JsonObject>> waits = new ConcurrentHashMap<>();
            RendezvousClient attached = client;
            attached.attach(message -> {
                if (message.has("id")) {
                    CompletableFuture<JsonObject> waiting =
                            waits.get(message.get("id").getAsString());
                    if (waiting != null) {
                        waiting.complete(message);
                    }
                }
            }, () -> { });

            JsonObject hello = new JsonObject();
            hello.addProperty("id", "1");
            hello.addProperty("type", "hello");
            hello.addProperty("v", 1);
            hello.addProperty("name", "diag");
            hello.addProperty("listenPort", 0);
            attached.send(hello);

            transport = new UdpTransport(selfId, "",
                    peer -> true, tunnel -> { }, voice -> { });
            CompletableFuture<JsonObject> tokenReply = new CompletableFuture<>();
            waits.put("2", tokenReply);
            JsonObject token = new JsonObject();
            token.addProperty("id", "2");
            token.addProperty("type", "udp-token");
            token.addProperty("token", transport.token());
            attached.send(token);
            JsonObject tokenAck = tokenReply.get(10, TimeUnit.SECONDS);
            assertTrue("udp-token-ok".equals(tokenAck.get("type").getAsString()),
                    "udp-token failed: " + tokenAck);
            UdpEndpoint ownUdp = transport.discovery().get(10, TimeUnit.SECONDS);
            System.out.println("DIAG own UDP endpoint: " + ownUdp.host() + ":" + ownUdp.port());

            CompletableFuture<JsonObject> joinedReply = new CompletableFuture<>();
            waits.put("3", joinedReply);
            JsonObject join = new JsonObject();
            join.addProperty("id", "3");
            join.addProperty("type", "join");
            join.addProperty("code", room);
            join.addProperty("name", "diag");
            join.addProperty("listenPort", 0);
            attached.send(join);
            JsonObject joined = joinedReply.get(15, TimeUnit.SECONDS);
            if ("error".equals(joined.has("type") ? joined.get("type").getAsString() : "")) {
                System.out.println("DIAG join error: " + joined);
                return;
            }

            JsonObject hostInfo = joined.getAsJsonObject("host");
            String hostId = hostInfo.get("peerId").getAsString();
            System.out.println("DIAG host peer: " + hostId);
            JsonArray addresses = hostInfo.getAsJsonArray("addresses");
            List<String> hostAddresses = new ArrayList<>();
            if (addresses != null) {
                for (JsonElement element : addresses) {
                    hostAddresses.add(element.getAsString());
                }
            }
            System.out.println("DIAG host TCP addresses: " + hostAddresses);
            UdpEndpoint hostUdp = hostInfo.has("udp")
                    ? UdpEndpoint.fromJson(hostInfo) : null;
            System.out.println("DIAG host UDP endpoint: " + hostUdp);

            PeerId hostPeer = PeerId.fromBase58(hostId);
            for (String text : hostAddresses) {
                long start = System.nanoTime();
                try {
                    Multiaddr address = withoutPeerId(new Multiaddr(text));
                    Connection dialed = host.getNetwork().connect(hostPeer, address)
                            .get(8, TimeUnit.SECONDS);
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("DIAG TCP OK   " + text + " in " + ms + " ms");
                    dialed.close().get(5, TimeUnit.SECONDS);
                } catch (Exception error) {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("DIAG TCP FAIL " + text + " in " + ms
                            + " ms: " + rootCause(error));
                }
            }

            if (hostUdp != null) {
                transport.setRoomCode(room);
                transport.setRoomKey(Base64.getDecoder()
                        .decode(joined.get("roomKey").getAsString()));
                transport.addPeer(hostId, hostUdp);
                long start = System.nanoTime();
                try {
                    transport.punch(hostId).get(15, TimeUnit.SECONDS);
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("DIAG UDP OK   received host probe in " + ms + " ms");
                } catch (Exception error) {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("DIAG UDP FAIL no host probe in " + ms
                            + " ms: " + rootCause(error));
                }
            } else {
                System.out.println("DIAG UDP FAIL host published no UDP endpoint");
            }

            JsonObject leave = new JsonObject();
            leave.addProperty("id", "4");
            leave.addProperty("type", "leave");
            attached.send(leave);
        } finally {
            if (transport != null) {
                transport.close();
            }
            if (client != null) {
                client.close();
            }
            host.stop().get(10, TimeUnit.SECONDS);
        }
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static Multiaddr withoutPeerId(Multiaddr address) {
        List<MultiaddrComponent> components = address.getComponents().stream()
                .filter(component -> component.getProtocol() != Protocol.P2P)
                .toList();
        return new Multiaddr(components);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
