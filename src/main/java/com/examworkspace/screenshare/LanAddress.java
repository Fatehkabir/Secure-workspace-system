package com.examworkspace.screenshare;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Utility to detect the teacher's LAN IP address so it can be published
 * to students via the database.
 *
 * <p>Prefers non-loopback, non-link-local IPv4 addresses (192.168.x.x, 10.x.x.x, 172.16-31.x.x).
 * Falls back to {@code InetAddress.getLocalHost()} if nothing better is found.
 */
public final class LanAddress {

    private LanAddress() {
    }

    private static final Logger LOG = Logger.getLogger(LanAddress.class.getName());

    /**
     * Returns the best LAN IPv4 address of this machine, or {@code "127.0.0.1"}
     * as a last resort (which students on other PCs cannot use — the teacher
     * should configure PostgreSQL properly first).
     */
    public static String get() {
        // If Database is configured with a PostgreSQL IP/host on this network, prefer that IP
        String dbUrl = com.examworkspace.db.Database.getDbUrl();
        String dbHost = com.examworkspace.db.Database.extractHost(dbUrl);
        if (dbHost != null && !dbHost.isBlank() && !dbHost.equals("127.0.0.1") && !dbHost.equalsIgnoreCase("localhost")) {
            return dbHost;
        }

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface iface = ifaces.nextElement();
                    String name = iface.getName().toLowerCase(java.util.Locale.ROOT);
                    String desc = iface.getDisplayName().toLowerCase(java.util.Locale.ROOT);
                    // Skip virtual adapters (VirtualBox, VMware, WSL, Hyper-V, vEthernet, etc.)
                    if (name.contains("vbox") || name.contains("virtual") || name.contains("vmnet")
                            || desc.contains("virtualbox") || desc.contains("vmware") || desc.contains("hyper-v")
                            || desc.contains("vethernet") || desc.contains("wsl")) {
                        continue;
                    }
                    if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                        continue;
                    }
                    Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address
                                && !addr.isLoopbackAddress()
                                && !addr.isLinkLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            // Fallback
            InetAddress local = InetAddress.getLocalHost();
            return local.getHostAddress();
        } catch (Exception e) {
            LOG.warning("[LanAddress] Could not detect LAN IP: " + e.getMessage());
            return "127.0.0.1";
        }
    }
}
