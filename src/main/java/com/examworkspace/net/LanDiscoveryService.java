package com.examworkspace.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight UDP broadcast service for LAN discovery.
 * Requires a shared secret for beacon signing; without it, no broadcast or discovery occurs.
 */
public final class LanDiscoveryService {

    public static final int BROADCAST_PORT = 54320;
    private static final String MAGIC_HEADER = "EXAMWORKSPACE_BEACON:";
    private static final AtomicBoolean broadcasting = new AtomicBoolean(false);
    private static Thread beaconThread;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record ServerInfo(String hostName, String ipAddress, int port, String dbName) {
        public String toJdbcUrl() {
            return "jdbc:postgresql://" + hostName + ":" + port + "/" + dbName;
        }

        public String toIpJdbcUrl() {
            return "jdbc:postgresql://" + ipAddress + ":" + port + "/" + dbName;
        }
    }

    private LanDiscoveryService() {}

    public static String getLocalDeviceName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        } catch (Exception ignored) {
        }
        String comp = System.getenv("COMPUTERNAME");
        if (comp != null && !comp.isBlank()) {
            return comp.trim();
        }
        return "localhost";
    }

    public static synchronized void startBeacon(int dbPort, String dbName) {
        if (broadcasting.get()) {
            return;
        }
        String secret = sharedSecret();
        if (secret == null || secret.isBlank()) {
            return;
        }
        broadcasting.set(true);
        String hostName = getLocalDeviceName();

        beaconThread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                while (broadcasting.get()) {
                    String nonce = randomToken();
                    String payload = MAGIC_HEADER + hostName + ":" + dbPort + ":" + dbName + ":" + nonce + ":"
                            + sign(hostName + ":" + dbPort + ":" + dbName + ":" + nonce, secret);
                    byte[] data = payload.getBytes(StandardCharsets.UTF_8);

                    for (InetAddress bcast : getBroadcastAddresses()) {
                        try {
                            DatagramPacket packet = new DatagramPacket(data, data.length, bcast, BROADCAST_PORT);
                            socket.send(packet);
                        } catch (Exception ignored) {
                        }
                    }

                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                broadcasting.set(false);
            }
        }, "ExamWorkspace-LanBeacon");
        beaconThread.setDaemon(true);
        beaconThread.start();
    }

    public static synchronized void stopBeacon() {
        broadcasting.set(false);
        if (beaconThread != null) {
            beaconThread.interrupt();
            beaconThread = null;
        }
    }

    public static boolean isBroadcasting() {
        return broadcasting.get();
    }

    public static ServerInfo discover(int timeoutMillis) {
        String secret = sharedSecret();
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try (DatagramSocket socket = new DatagramSocket(BROADCAST_PORT)) {
            socket.setSoTimeout(Math.max(500, timeoutMillis));
            socket.setBroadcast(true);

            byte[] buf = new byte[512];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (message.startsWith(MAGIC_HEADER)) {
                        String body = message.substring(MAGIC_HEADER.length());
                        String[] parts = body.split(":");
                        if (parts.length >= 5) {
                            String host = parts[0].trim();
                            int port = Integer.parseInt(parts[1].trim());
                            String db = parts[2].trim();
                            String nonce = parts[3].trim();
                            String sig = parts[4].trim();
                            String expected = sign(host + ":" + port + ":" + db + ":" + nonce, secret);
                            if (!expected.equals(sig)) {
                                continue;
                            }
                            String senderIp = packet.getAddress().getHostAddress();
                            return new ServerInfo(host, senderIp, port, db);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    InetAddress bcast = addr.getBroadcast();
                    if (bcast != null && !list.contains(bcast)) {
                        list.add(bcast);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static String sharedSecret() {
        String fromEnv = value(System.getenv("EXAMWORKSPACE_LAN_SECRET"));
        if (fromEnv != null) {
            return fromEnv;
        }
        String fromSys = value(System.getProperty("examworkspace.lan.secret", ""));
        if (fromSys != null) {
            return fromSys;
        }
        Path path = com.examworkspace.db.Database.dataDir().resolve("lan.secret");
        try {
            if (java.nio.file.Files.exists(path)) {
                String existing = java.nio.file.Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
        } catch (IOException ignored) {
        }
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            String generated = randomToken();
            java.nio.file.Files.writeString(path, generated, StandardCharsets.UTF_8);
            return generated;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String value(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return candidate.trim();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign LAN discovery payload", e);
        }
    }
}

