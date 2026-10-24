package com.sslauncher.multiplayer;

import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * libp2p invokes {@code addConnectionHandler} for both inbound and outbound
 * connections. The guest race must only react to host reverse dials, otherwise
 * it can complete and disconnect its own winning link. This guards the port
 * based direction check used by {@link MultiplayerService#onInboundConnection}.
 */
class InboundConnectionDetectionTest {
    @Test
    void outboundDialsAreNotTreatedAsInbound() throws Exception {
        int portA = freePort();
        int portB = freePort();
        List<Boolean> directions = new CopyOnWriteArrayList<>();
        Host a = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/127.0.0.1/tcp/" + portA)
                .build();
        Host b = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/127.0.0.1/tcp/" + portB)
                .build();
        try {
            a.start().get(15, TimeUnit.SECONDS);
            b.start().get(15, TimeUnit.SECONDS);
            a.addConnectionHandler(connection ->
                    directions.add(MultiplayerService.isInboundConnection(connection, portA)));

            Multiaddr targetB = new Multiaddr("/ip4/127.0.0.1/tcp/" + portB
                    + "/p2p/" + b.getPeerId().toBase58());
            Connection outbound = a.getNetwork()
                    .connect(b.getPeerId(), targetB)
                    .get(10, TimeUnit.SECONDS);
            waitFor(() -> directions.size() >= 1);
            assertEquals(List.of(false), directions,
                    "A's own outbound dial must not look inbound");
            outbound.close().get(5, TimeUnit.SECONDS);

            Multiaddr targetA = new Multiaddr("/ip4/127.0.0.1/tcp/" + portA
                    + "/p2p/" + a.getPeerId().toBase58());
            b.getNetwork().connect(a.getPeerId(), targetA).get(10, TimeUnit.SECONDS);
            waitFor(() -> directions.size() >= 2);
            long inbound = directions.stream().filter(Boolean::booleanValue).count();
            long out = directions.stream().filter(value -> !value).count();
            assertEquals(1, inbound, "B's dial must be seen as inbound by A");
            assertEquals(1, out, "A's own dial must stay outbound");
        } finally {
            b.stop().get(10, TimeUnit.SECONDS);
            a.stop().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidInputsAreNotInbound() {
        assertFalse(MultiplayerService.isInboundConnection(null, 4001));
    }

    private static void waitFor(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "connection handler did not fire in time");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
