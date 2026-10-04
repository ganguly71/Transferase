package com.example.transferase.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class DeviceNetworkInfo(
    val primaryIp: String,
    val connectionType: String, // "Mobile Hotspot", "Wi-Fi", "Local Network", "Disconnected"
    val interfaceName: String,
    val allIps: List<String>
)

object NetworkUtils {

    fun getNetworkInfo(context: Context): DeviceNetworkInfo {
        val allIps = mutableListOf<String>()
        var hotspotIp: String? = null
        var wifiIp: String? = null
        var otherIp: String? = null
        var activeInterfaceName = "unknown"

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue

                val name = intf.name.lowercase()

                // Exclude cellular data and VPN interfaces from being picked as local transfer IPs
                val isCellular = name.startsWith("rmnet") || name.startsWith("ccmni") ||
                        name.startsWith("pdp") || name.startsWith("wwan") ||
                        name.startsWith("radio") || name.contains("dummy") ||
                        name.startsWith("v4-rmnet")
                val isVpn = name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp")

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue

                        // Skip loopback or link-local auto-ip
                        if (hostAddress.startsWith("127.") || hostAddress.startsWith("169.254.")) continue

                        if (!isCellular && !isVpn) {
                            allIps.add(hostAddress)

                            // Common hotspot interface names across Android vendors:
                            // ap0, wlan1, rndis0, swlan0, tether0, softap, 192.168.43.x, 192.168.49.x
                            if (name.contains("ap") || name.contains("rndis") || name.contains("tether") ||
                                hostAddress.startsWith("192.168.43.") || hostAddress.startsWith("192.168.49.") || hostAddress.startsWith("192.168.50.")
                            ) {
                                if (hotspotIp == null) {
                                    hotspotIp = hostAddress
                                    activeInterfaceName = intf.name
                                }
                            } else if (name.contains("wlan") || name.contains("wifi") || name.contains("eth")) {
                                if (wifiIp == null) {
                                    wifiIp = hostAddress
                                    activeInterfaceName = intf.name
                                }
                            } else if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.") || hostAddress.startsWith("172.")) {
                                if (otherIp == null) {
                                    otherIp = hostAddress
                                    activeInterfaceName = intf.name
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Determine priority: Hotspot > Wi-Fi > Other LAN > Fallback
        val (primaryIp, connectionType) = when {
            hotspotIp != null -> Pair(hotspotIp, "Mobile Hotspot")
            wifiIp != null -> Pair(wifiIp, "Wi-Fi Network")
            otherIp != null -> Pair(otherIp, "Local Network")
            allIps.isNotEmpty() -> Pair(allIps.first(), "Local Network")
            else -> Pair("127.0.0.1", "Disconnected (Turn on Hotspot or Wi-Fi)")
        }

        return DeviceNetworkInfo(
            primaryIp = primaryIp,
            connectionType = connectionType,
            interfaceName = activeInterfaceName,
            allIps = allIps
        )
    }
}
