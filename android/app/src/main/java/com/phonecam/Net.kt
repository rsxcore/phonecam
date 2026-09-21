package com.phonecam

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The address the PC should open.
 *
 * A phone on mobile data has an address too, but it is not one the PC can
 * reach, so a Wi-Fi interface is preferred and the rest is only a fallback for
 * the case where the phone is acting as a hotspot.
 */
fun localIpv4(): String? {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (e: Exception) {
        return null
    }

    val usable = interfaces.filter { it.isUp && !it.isLoopback }

    fun addressOf(iface: NetworkInterface): String? =
        iface.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress

    val wifi = usable.firstOrNull { iface ->
        val name = iface.name.lowercase()
        name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("ap")
    }

    return (wifi?.let { addressOf(it) })
        ?: usable.firstNotNullOfOrNull { addressOf(it) }
}
