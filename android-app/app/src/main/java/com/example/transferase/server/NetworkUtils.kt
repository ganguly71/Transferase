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

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        allIps.add(hostAddress)

                        val name = intf.name.lowercase()
                        // Common hotspot interface names across Android vendors:
                        // ap0, wlan1, rndis0, swlan0, tether0, softap
                        if (name.contains("ap") || name.contains("rndis") || hostAddress.startsWith("192.168.43.") || hostAddress.startsWith("192.168.49.")) {
                            hotspotIp = hostAddress
                            activeInterfaceName = intf.name
                        } else if (name.contains("wlan") || name.contains("wifi")) {
                            if (wifiIp == null) {
                                wifiIp = hostAddress
                                activeInterfaceName = intf.name
                            }
                        } else if (otherIp == null) {
                            otherIp = hostAddress
                            activeInterfaceName = intf.name
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
            else -> Pair("127.0.0.1", "Disconnected (Connect to Wi-Fi or Hotspot)")
        }

        return DeviceNetworkInfo(
            primaryIp = primaryIp,
            connectionType = connectionType,
            interfaceName = activeInterfaceName,
            allIps = allIps
        )
    }
}
