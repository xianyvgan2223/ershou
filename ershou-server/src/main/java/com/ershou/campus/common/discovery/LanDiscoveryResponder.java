package com.ershou.campus.common.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网一键发现：手机端向 48899 端口广播 {"app":"ershou-app","probe":"lan"}，
 * 这里原样回一个 {"app":"ershou-server","port":8080,"ips":[...]}，
 * 手机拿到回包地址就是电脑的 IP，不用手输。
 *
 * 纯 UDP，无依赖、无副作用；端口被占用时只打日志，不影响后端启动。
 */
@Component
public class LanDiscoveryResponder {

    private static final Logger log = LoggerFactory.getLogger(LanDiscoveryResponder.class);
    private static final String APP_TAG = "ershou-app";
    private static final String SERVER_TAG = "ershou-server";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int port;
    private final int serverPort;

    private DatagramSocket socket;
    private Thread worker;
    private volatile boolean running;

    public LanDiscoveryResponder(
            @Value("${ershou.discovery.port:48899}") int port,
            @Value("${server.port:8080}") int serverPort) {
        this.port = port;
        this.serverPort = serverPort;
        start();
    }

    private void start() {
        try {
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);
        } catch (Exception e) {
            log.warn("局域网发现端口 {} 不可用（{}），手机端将退化为网段扫描", port, e.getMessage());
            socket = null;
            return;
        }
        running = true;
        worker = new Thread(this::loop, "lan-discovery");
        worker.setDaemon(true);
        worker.start();
        log.info("局域网发现已开启：UDP {} （手机端「一键扫描」会先走这条路）", port);
    }

    private void loop() {
        final byte[] buffer = new byte[512];
        while (running && socket != null && !socket.isClosed()) {
            try {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                final String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                JsonNode node = null;
                try {
                    node = objectMapper.readTree(text);
                } catch (Exception ignored) {
                    // 不是 JSON，忽略
                }
                if (node == null || !APP_TAG.equals(node.path("app").asText())) {
                    continue;
                }
                final byte[] reply = objectMapper.writeValueAsBytes(payload());
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (Exception e) {
                if (running) {
                    log.debug("局域网发现收包异常：{}", e.getMessage());
                }
            }
        }
    }

    private Map<String, Object> payload() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", SERVER_TAG);
        data.put("port", serverPort);
        data.put("name", "校园二手后端");
        data.put("ips", localIps());
        return data;
    }

    /** 本机可用的局域网 IPv4，方便在日志/二维码里直接展示。 */
    public static List<String> localIps() {
        final List<String> ips = new ArrayList<>();
        try {
            final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                final NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                final Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    final InetAddress address = addresses.nextElement();
                    if (address.getAddress().length == 4 && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        ips.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("枚举本机 IP 失败：{}", e.getMessage());
        }
        return ips;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }
}
