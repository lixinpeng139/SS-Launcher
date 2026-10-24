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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe for the deployed bootstrap server. Run it explicitly:
 *
 * <pre>
 *   gradlew.bat test --tests '*BootstrapUdpProbeTest' -Dss.udpProbe=true
 * </pre>
 *
 * It verifies that the server's UDP listener is reachable from the public
 * internet, returns the endpoint the client's socket really uses, and that the
 * new {@code udp-token} signalling message is understood.
 */
@EnabledIfSystemProperty(named = "ss.udpProbe", matches = "true")
class BootstrapUdpProbeTest {
    @Test
    void serverObservesThePublicUdpEndpoint() throws Exception {
        Multiaddr serverAddress = new Multiaddr(BootstrapServer.address());
        PeerId server = serverAddress.getPeerId();
        assertNotNull(server, "built-in server address has no PeerID");
        List<MultiaddrComponent> components = serverAddress.getComponents().stream()
                .filter(component -> component.getProtocol() != Protocol.P2P)
                .toList();
        Multiaddr dialAddress = new Multiaddr(components);

        Host host = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/127.0.0.1/tcp/" + freePort())
                .protocol(new RendezvousBinding(new RendezvousProtocol()))
                .build();
        UdpTransport transport = null;
        RendezvousClient client = null;
        try {
            host.start().get(15, TimeUnit.SECONDS);
            Connection connection = host.getNetwork().connect(server, dialAddress)
                    .get(15, TimeUnit.SECONDS);
            StreamPromise<RendezvousClient> promise = host.newStream(
                    List.of(RendezvousProtocol.ID), connection);
            client = promise.getController().get(15, TimeUnit.SECONDS);

            Map<String, CompletableFuture<JsonObject>> waits = new ConcurrentHashMap<>();
            RendezvousClient attached = client;
            attached.attach(message -> {
                String id = message.has("id") ? message.get("id").getAsString() : "";
                CompletableFuture<JsonObject> waiting = waits.get(id);
                if (waiting != null) {
                    waiting.complete(message);
                }
            }, () -> { });
            waits.put("2", new CompletableFuture<>());

            JsonObject hello = new JsonObject();
            hello.addProperty("id", "1");
            hello.addProperty("type", "hello");
            hello.addProperty("v", 1);
            hello.addProperty("name", "udp-probe");
            hello.addProperty("listenPort", 0);
            attached.send(hello);

            transport = new UdpTransport("udp-probe-" + System.nanoTime(), "",
                    peer -> false, tunnel -> { }, voice -> { });
            JsonObject token = new JsonObject();
            token.addProperty("id", "2");
            token.addProperty("type", "udp-token");
            token.addProperty("token", transport.token());
            attached.send(token);

            JsonObject tokenReply = waits.get("2").get(10, TimeUnit.SECONDS);
            assertTrue(tokenReply.has("type")
                    && "udp-token-ok".equals(tokenReply.get("type").getAsString()),
                    "server did not acknowledge udp-token: " + tokenReply);

            UdpEndpoint endpoint = transport.discovery().get(10, TimeUnit.SECONDS);
            assertNotNull(endpoint, "server returned no endpoint");
            System.out.println("Observed public UDP endpoint: "
                    + endpoint.host() + ":" + endpoint.port());
            assertTrue(endpoint.port() > 0);
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

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
