package com.sslauncher.multiplayer;

import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceProtocolTest {
    @Test
    void framedAudioReachesTheResponder() throws Exception {
        int responderPort = freePort();
        int initiatorPort = freePort();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> payload = new AtomicReference<>();
        AtomicReference<String> remotePeer = new AtomicReference<>();

        Host responder = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/127.0.0.1/tcp/" + responderPort)
                .protocol(new VoiceBinding(new VoiceProtocol(session -> {
                    remotePeer.set(session.remotePeerId());
                    session.setFrameHandler(frame -> {
                        payload.set(frame);
                        received.countDown();
                    });
                })))
                .build();
        Host initiator = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/127.0.0.1/tcp/" + initiatorPort)
                .protocol(new VoiceBinding(new VoiceProtocol(session -> { })))
                .build();
        try {
            responder.start().get(10, TimeUnit.SECONDS);
            initiator.start().get(10, TimeUnit.SECONDS);

            Connection connection = initiator.getNetwork()
                    .connect(responder.getPeerId(),
                            new Multiaddr("/ip4/127.0.0.1/tcp/" + responderPort))
                    .get(10, TimeUnit.SECONDS);
            StreamPromise<VoiceSession> promise =
                    initiator.newStream(List.of(VoiceProtocol.ID), connection);
            VoiceSession session = promise.getController().get(10, TimeUnit.SECONDS);

            byte[] frame = {0x4F, 0x70, 0x75, 0x73, 0x00, (byte) 0xFF};
            // The responder installs its frame handler during protocol start;
            // real audio repeats every 20 ms, so retry like the capture loop.
            for (int i = 0; i < 40 && received.getCount() > 0; i++) {
                session.writeFrame(frame);
                Thread.sleep(50);
            }
            assertTrue(received.await(2, TimeUnit.SECONDS), "responder received no frame");
            assertArrayEquals(frame, payload.get());
            assertEquals(initiator.getPeerId().toBase58(), remotePeer.get());
        } finally {
            initiator.stop().get(5, TimeUnit.SECONDS);
            responder.stop().get(5, TimeUnit.SECONDS);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
