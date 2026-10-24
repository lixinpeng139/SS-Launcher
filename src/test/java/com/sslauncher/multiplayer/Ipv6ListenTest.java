package com.sslauncher.multiplayer;

import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The launcher listens on {@code /ip4/0.0.0.0} and relies on the Windows
 * dual-stack wildcard socket to accept IPv6 too. This guards that assumption at
 * the libp2p level, because two players behind carrier NAT can only meet over
 * IPv6.
 */
class Ipv6ListenTest {
    @Test
    void wildcardListenAcceptsIpv6Loopback() throws Exception {
        int port = freePort();
        Host server = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/0.0.0.0/tcp/" + port)
                .build();
        Host client = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/127.0.0.1/tcp/" + freePort())
                .build();
        try {
            server.start().get(15, TimeUnit.SECONDS);
            client.start().get(15, TimeUnit.SECONDS);
            Multiaddr target = new Multiaddr("/ip6/::1/tcp/" + port
                    + "/p2p/" + server.getPeerId().toBase58());
            Connection connection = client.getNetwork()
                    .connect(server.getPeerId(), target)
                    .get(10, TimeUnit.SECONDS);
            assertNotNull(connection);
            System.out.println("IPv6 loopback connection accepted on "
                    + "/ip4/0.0.0.0/tcp/" + port);
            connection.close().get(5, TimeUnit.SECONDS);
        } finally {
            client.stop().get(10, TimeUnit.SECONDS);
            server.stop().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void machineHasUsableGlobalIpv6() throws Exception {
        boolean found = false;
        for (NetworkInterface network :
                Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (var address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet6Address ipv6
                        && !ipv6.isLoopbackAddress()
                        && !ipv6.isLinkLocalAddress()
                        && !ipv6.isMulticastAddress()) {
                    found = true;
                    System.out.println("Global IPv6 on " + network.getName()
                            + ": " + ipv6.getHostAddress());
                }
            }
        }
        System.out.println("global IPv6 present: " + found);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
