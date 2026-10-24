package com.sslauncher.multiplayer;

import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.MultiaddrComponent;
import io.libp2p.core.multiformats.Protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The built-in rendezvous server.
 *
 * <p>本仓库为公开分发，不包含真实服务器地址。请把 {@link #ADDRESS} 改成你自己的
 * 引导服务器多地址，例如
 * {@code "/ip4/203.0.113.9/tcp/4001/p2p/12D3KooW..."}。
 * 占位值 {@code example.invalid} 属于保留 TLD，永远不会解析，因此在填入真实地址
 * 之前，联机打洞会直接失败并提示。</p>
 */
public final class BootstrapServer {
    private static final String ADDRESS = "/dns4/example.invalid/tcp/4001";

    private BootstrapServer() {
    }

    /** The bootstrap multiaddr. */
    public static String address() {
        return ADDRESS;
    }

    /**
     * The UDP endpoint-discovery address. The server listens on the same port
     * number over UDP as it does over TCP unless configured otherwise; clients
     * only have one built-in server address to remember.
     *
     * @return null when the address has no IP/DNS/TCP components.
     */
    public static InetSocketAddress udpAddress() {
        try {
            Multiaddr address = new Multiaddr(address());
            MultiaddrComponent tcp = address.getFirstComponent(Protocol.TCP);
            if (tcp == null) {
                return null;
            }
            int port = Integer.parseInt(tcp.getStringValue());
            String host = null;
            for (Protocol protocol : new Protocol[]{Protocol.IP4, Protocol.IP6,
                    Protocol.DNS, Protocol.DNS4, Protocol.DNS6}) {
                MultiaddrComponent component = address.getFirstComponent(protocol);
                if (component != null) {
                    host = component.getStringValue();
                    break;
                }
            }
            if (host == null || host.isBlank()) {
                return null;
            }
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (RuntimeException | UnknownHostException e) {
            return null;
        }
    }
}
