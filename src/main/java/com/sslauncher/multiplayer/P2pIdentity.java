package com.sslauncher.multiplayer;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Log;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import kotlin.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The launcher's long-lived libp2p identity. Keeping the key on disk means the
 * peer ID — and therefore the /p2p/ address advertised to the rendezvous
 * server — stays stable across restarts.
 */
final class P2pIdentity {
    private P2pIdentity() {
    }

    private static Path file() {
        return AppPaths.ROOT.resolve("p2p").resolve("identity.key");
    }

    static synchronized PrivKey loadOrCreate() throws IOException {
        Path file = file();
        if (Files.exists(file)) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > 0) {
                    return KeyKt.unmarshalPrivateKey(bytes);
                }
            } catch (RuntimeException e) {
                Log.warn("P2P 身份文件损坏，将重新生成: " + e.getMessage());
            } catch (IOException e) {
                throw new IOException("无法读取 P2P 身份文件: " + file, e);
            }
        }
        Pair<PrivKey, ?> pair = KeyKt.generateKeyPair(KeyType.ED25519);
        PrivKey key = pair.getFirst();
        AppPaths.atomicWrite(file, KeyKt.marshalPrivateKey(key));
        Log.info("已生成新的 P2P 身份: " + file);
        return key;
    }
}
