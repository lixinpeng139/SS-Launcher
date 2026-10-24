package com.sslauncher.bootstrap;

import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import kotlin.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persists the server's libp2p identity. The peer ID is part of the address
 * every client configures, so it must not change across restarts.
 */
final class ServerIdentity {
    private ServerIdentity() {
    }

    static synchronized PrivKey loadOrCreate(Path home) throws IOException {
        Path file = home.resolve("identity.key");
        Files.createDirectories(home);
        if (Files.exists(file)) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > 0) {
                    return KeyKt.unmarshalPrivateKey(bytes);
                }
            } catch (RuntimeException e) {
                Log.warn("身份文件损坏，将重新生成: " + e.getMessage());
            }
        }
        Pair<PrivKey, ?> pair = KeyKt.generateKeyPair(KeyType.ED25519);
        PrivKey key = pair.getFirst();
        Path temp = file.resolveSibling("identity.key.tmp");
        Files.write(temp, KeyKt.marshalPrivateKey(key));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Log.info("已生成新的服务器身份: " + file);
        return key;
    }
}
