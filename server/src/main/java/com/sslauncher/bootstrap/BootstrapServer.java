package com.sslauncher.bootstrap;

import io.libp2p.core.Host;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.dsl.HostBuilder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SS Launcher bootstrap server.
 *
 * <p>It only matches room codes and exchanges peer addresses. Once two players
 * know about each other, their game traffic flows directly between them and
 * never through this process.</p>
 */
public final class BootstrapServer {
    private BootstrapServer() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.load(args);
        PrivKey key = ServerIdentity.loadOrCreate(config.home);
        RoomManager manager = new RoomManager(config);

        Host host = new HostBuilder()
                .keyType(KeyType.ED25519)
                .listen("/ip4/0.0.0.0/tcp/" + config.port)
                .protocol(new SignalingProtocol.Binding(new SignalingProtocol(manager)))
                .builderModifier(builder -> builder.getIdentity().setFactory(() -> key))
                .build();

        host.start().get(20, TimeUnit.SECONDS);
        String peerId = host.getPeerId().toBase58();
        // UDP is only used to observe each player's public UDP endpoint so
        // peers can punch through their NATs. It never relays payload data.
        UdpDiscovery udpDiscovery = UdpDiscovery.start(manager, config.udpPort);

        Log.info("=================================================");
        Log.info(" SS Launcher 引导服务器已启动");
        Log.info(" PeerID : " + peerId);
        Log.info(" 监听   : /ip4/0.0.0.0/tcp/" + config.port
                + "，UDP 端点发现 " + (udpDiscovery != null ? config.udpPort : "未启用"));
        if (!config.publicHost.isBlank()) {
            Log.info(" 客户端填写: " + clientAddress(config.publicHost, config.port, peerId));
        } else {
            Log.info(" 客户端填写: /ip4/<服务器公网IP>/tcp/" + config.port + "/p2p/" + peerId);
        }
        Log.info(" 数据目录: " + config.home);
        Log.info(" 房间上限: " + config.maxRooms + "，每房间客人上限: " + config.maxGuests);
        Log.info(" 提示：阿里云安全组和 ufw 都需要放行 TCP " + config.port
                + " 与 UDP " + config.udpPort);
        Log.info("=================================================");

        ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ss-bootstrap-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        keepalive.scheduleAtFixedRate(manager::tick, 10, 10, TimeUnit.SECONDS);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("正在停止引导服务器…");
            keepalive.shutdownNow();
            if (udpDiscovery != null) {
                udpDiscovery.close();
            }
            try {
                host.stop().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.warn("停止 libp2p 节点时出错: " + Session.messageOf(e));
            }
            Log.info("引导服务器已停止");
            stopped.countDown();
        }, "ss-bootstrap-stop"));

        stopped.await();
    }

    private static String clientAddress(String host, int port, String peerId) {
        String value = host.trim();
        if (value.startsWith("/")) {
            return value.endsWith("/p2p/" + peerId) ? value : value + "/p2p/" + peerId;
        }
        return "/ip4/" + value + "/tcp/" + port + "/p2p/" + peerId;
    }
}
