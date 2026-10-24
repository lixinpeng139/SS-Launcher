package com.sslauncher.multiplayer;

import com.sslauncher.core.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal UPnP IGD client used to make the libp2p listener reachable from the
 * internet on a home router.
 *
 * <p>Both the TCP and UDP ports are mapped with the same external port as the
 * internal port. The TCP mapping lets peers dial the advertised public
 * address; the UDP mapping lets the same port carry hole-punch probes and the
 * KCP tunnel. UPnP is best-effort: CGNAT, double NAT or routers with UPnP
 * disabled still require a manual port forward (see the UI).</p>
 */
final class PortMapper implements AutoCloseable {
    private static final Pattern ERROR_CODE = Pattern.compile("<errorCode>\\s*(\\d+)\\s*</errorCode>");
    private static final String DESCRIPTION = "SS Launcher P2P";
    private static final long LEASE_SECONDS = 3600L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Service service;
    private String localIp;
    private int port;
    private String externalIp = "";
    private boolean tcpMapped;
    private boolean udpMapped;

    static final class Result {
        /** True when the TCP mapping was installed. */
        final boolean mapped;
        /** True when the UDP mapping was installed. */
        final boolean udpMapped;
        final String externalIp;
        final String detail;

        Result(boolean mapped, boolean udpMapped, String externalIp, String detail) {
            this.mapped = mapped;
            this.udpMapped = udpMapped;
            this.externalIp = externalIp == null ? "" : externalIp;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final class Service {
        final String controlUrl;
        final String type;

        Service(String controlUrl, String type) {
            this.controlUrl = controlUrl;
            this.type = type;
        }
    }

    private static final class SoapException extends IOException {
        final String code;

        SoapException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Discovers the gateway, maps {@code port}/tcp and {@code port}/udp, and
     * returns the external IP. The caller should run this on a background
     * thread: discovery can take a few seconds when the router does not answer.
     */
    synchronized Result open(int port) {
        this.port = port;
        this.tcpMapped = false;
        this.udpMapped = false;
        String ip = localIpv4();
        if (ip == null) {
            return new Result(false, false, "", "没有找到局域网 IPv4 地址");
        }
        this.localIp = ip;
        try {
            String location = discover();
            service = resolveService(location);
            externalIp = getExternalIp(service);
            String tcpError = "";
            String udpError = "";
            try {
                addMapping(service, port, localIp, LEASE_SECONDS, "TCP");
                tcpMapped = true;
            } catch (IOException e) {
                tcpError = message(e);
            }
            try {
                addMapping(service, port, localIp, LEASE_SECONDS, "UDP");
                udpMapped = true;
            } catch (IOException e) {
                udpError = message(e);
            }
            if (tcpMapped || udpMapped) {
                String protocols = (tcpMapped ? "TCP " : "") + (udpMapped ? "UDP " : "");
                Log.info("联机：UPnP 端口映射成功 " + port + "/" + protocols.trim()
                        + " -> " + ip + "，公网 IP " + externalIp);
                return new Result(tcpMapped, udpMapped, externalIp,
                        "已映射 " + port + "/" + protocols.trim());
            }
            service = null;
            String detail = "TCP: " + tcpError + "；UDP: " + udpError;
            Log.warn("联机：UPnP 端口映射失败: " + detail);
            return new Result(false, false, "", detail);
        } catch (Exception e) {
            service = null;
            String detail = message(e);
            Log.warn("联机：UPnP 端口映射失败: " + detail);
            return new Result(false, false, "", detail);
        }
    }

    /** Refreshes the lease; safe to call from a scheduled task. */
    synchronized void renew() {
        if (service == null || localIp == null) {
            return;
        }
        if (tcpMapped) {
            renew("TCP");
        }
        if (udpMapped) {
            renew("UDP");
        }
    }

    private void renew(String protocol) {
        try {
            addMapping(service, port, localIp, LEASE_SECONDS, protocol);
        } catch (IOException e) {
            Log.warn("联机：UPnP " + protocol + " 映射续期失败: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (service == null) {
            return;
        }
        if (tcpMapped) {
            deleteMapping("TCP");
        }
        if (udpMapped) {
            deleteMapping("UDP");
        }
        tcpMapped = false;
        udpMapped = false;
        service = null;
    }

    private void deleteMapping(String protocol) {
        String inner = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>" + protocol + "</NewProtocol>";
        try {
            soap(service, "DeletePortMapping", inner);
        } catch (IOException e) {
            Log.warn("联机：删除 UPnP " + protocol + " 映射失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- discovery

    private String discover() throws IOException {
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
        byte[] data = request.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(600);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket.send(new DatagramPacket(data, data.length, group, 1900));
            long deadline = System.currentTimeMillis() + 2600;
            byte[] buffer = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String text = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.US_ASCII);
                String location = header(text, "LOCATION");
                if (location != null && !location.isBlank()) {
                    return location.trim();
                }
            }
        }
        throw new IOException("未发现支持 UPnP 的路由器");
    }

    private Service resolveService(String location) throws IOException {
        String xml = httpGet(location);
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            try {
                factory.setExpandEntityReferences(false);
            } catch (RuntimeException ignored) {
                // Not supported by every parser.
            }
            doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("无法解析路由器描述文件: " + e.getMessage(), e);
        }

        String base = location;
        NodeList baseNodes = doc.getElementsByTagNameNS("*", "URLBase");
        if (baseNodes.getLength() > 0) {
            String value = baseNodes.item(0).getTextContent();
            if (value != null && !value.isBlank()) {
                base = value.trim();
            }
        }

        NodeList services = doc.getElementsByTagNameNS("*", "service");
        if (services.getLength() == 0) {
            services = doc.getElementsByTagName("service");
        }
        for (int i = 0; i < services.getLength(); i++) {
            Node node = services.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String type = childText(element, "serviceType");
            if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) {
                continue;
            }
            String control = childText(element, "controlURL");
            if (control.isBlank()) {
                continue;
            }
            String controlUrl = resolve(base, control);
            if (controlUrl != null) {
                return new Service(controlUrl, type);
            }
        }
        throw new IOException("路由器没有提供 WAN 端口映射服务");
    }

    // ------------------------------------------------------------------ SOAP

    private String getExternalIp(Service service) throws IOException {
        String body = soap(service, "GetExternalIPAddress", "");
        Matcher matcher = Pattern.compile("<NewExternalIPAddress>\\s*([^<]*)\\s*</NewExternalIPAddress>")
                .matcher(body);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private void addMapping(Service service, int port, String localIp, long lease, String protocol)
            throws IOException {
        String inner = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>" + protocol + "</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + escapeXml(localIp) + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + DESCRIPTION + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>";
        try {
            soap(service, "AddPortMapping", inner);
        } catch (SoapException e) {
            if ("718".equals(e.code)) {
                // ConflictInMappingEntry: usually a stale mapping from a
                // previous launcher run on the same port. Treat it as ours.
                Log.info("联机：端口 " + port + "/" + protocol + " 已有 UPnP 映射，继续使用");
                return;
            }
            if ("724".equals(e.code) && lease > 0) {
                // OnlyPermanentLeasesSupported.
                addMapping(service, port, localIp, 0, protocol);
                return;
            }
            throw e;
        }
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String soap(Service service, String action, String inner) throws IOException {
        String envelope = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + service.type + "\">"
                + inner
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpRequest request = HttpRequest.newBuilder(URI.create(service.controlUrl))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPACTION", "\"" + service.type + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("UPnP 请求被中断", e);
        } catch (IOException e) {
            throw new IOException("无法连接路由器: " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 == 2 && !response.body().contains("<errorCode>")) {
            return response.body();
        }
        Matcher matcher = ERROR_CODE.matcher(response.body());
        String code = matcher.find() ? matcher.group(1) : "";
        String message = soapErrorMessage(code, action);
        throw new SoapException(code, message);
    }

    private static String soapErrorMessage(String code, String action) {
        String hint = switch (code) {
            case "401", "606", "714" -> "路由器拒绝了端口映射（权限不足）";
            case "718" -> "端口已被其他程序映射";
            case "724" -> "路由器只支持永久端口映射";
            case "725" -> "路由器只支持永久端口映射";
            case "726" -> "路由器不支持远程主机参数";
            default -> "路由器返回错误" + (code.isEmpty() ? "" : " " + code);
        };
        return hint + " (" + action + ")";
    }

    // --------------------------------------------------------------- helpers

    private String httpGet(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("读取路由器描述失败: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("UPnP 请求被中断", e);
        }
    }

    private static String localIpv4() {
        try (DatagramSocket socket = new DatagramSocket()) {
            // UDP connect does not send a packet; it only selects a route and
            // tells us which local address the gateway will see.
            socket.connect(InetAddress.getByName("223.5.5.5"), 53);
            InetAddress address = socket.getLocalAddress();
            if (address != null && !address.isAnyLocalAddress() && !address.isLoopbackAddress()) {
                return address.getHostAddress();
            }
        } catch (Exception ignored) {
            // Fall through to interface enumeration.
        }
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (var address : java.util.Collections.list(network.getInetAddresses())) {
                    if (address instanceof java.net.Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // No usable address.
        }
        return null;
    }

    private static String resolve(String base, String reference) {
        try {
            return URI.create(base).resolve(reference).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String childText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String local = node.getNodeName();
            int colon = local.indexOf(':');
            if (colon >= 0) {
                local = local.substring(colon + 1);
            }
            if (local.equalsIgnoreCase(name)) {
                return node.getTextContent() == null ? "" : node.getTextContent().trim();
            }
        }
        return "";
    }

    private static String header(String response, String name) {
        for (String line : response.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Optional hardening; some parsers do not know the feature.
        }
    }
}
