package com.mahudesh.tvremote.tv

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Opens TCP connections to TVs on the local network, robustly.
 *
 * When this phone *is* the Wi-Fi hotspot (the TV is a hotspot client), Android may send an
 * unbound socket out through mobile data or a VPN instead of the hotspot, and the connection is
 * refused or times out. So we try the default route first, then again with the socket bound to
 * our own address on the interface that shares the TV's subnet, with short retries in between.
 */
object Net {
    /** Every failed route, for the error message: "default: ECONNREFUSED; via 10.196.26.104: …". */
    class RoutesFailed(val tried: List<String>, cause: Throwable?) :
        IOException("No route reached the TV (${tried.joinToString("; ")})", cause)

    fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val target = InetAddress.getByName(host)
        val routes = listOf<InetAddress?>(null) + localAddressesFor(target)
        val tried = mutableListOf<String>()
        var last: Throwable? = null
        repeat(ATTEMPTS) { round ->
            for (source in routes) {
                val socket = Socket()
                try {
                    if (source != null) socket.bind(InetSocketAddress(source, 0))
                    socket.connect(InetSocketAddress(target, port), timeoutMs)
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    if (round > 0 || source != null) {
                        android.util.Log.i("TvRemote", "connected to $host:$port via ${source?.hostAddress ?: "default"} (round ${round + 1})")
                    }
                    return socket
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    last = e
                    val label = "${source?.hostAddress ?: "default"}: ${e.message ?: e.javaClass.simpleName}"
                    if (label !in tried) tried += label
                    android.util.Log.w("TvRemote", "connect $host:$port failed via $label")
                }
            }
            if (round < ATTEMPTS - 1) Thread.sleep(BACKOFF_MS * (round + 1))
        }
        throw RoutesFailed(tried, last)
    }

    /** Our IPv4 addresses on interfaces whose subnet contains [target] (hotspot, Wi-Fi, Ethernet). */
    fun localAddressesFor(target: InetAddress): List<InetAddress> {
        if (target !is Inet4Address) return emptyList()
        val t = target.address.toInt()
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address && it.networkPrefixLength in 8..30 }
                .filter { ia ->
                    val mask = (-1 shl (32 - ia.networkPrefixLength))
                    (ia.address.address.toInt() and mask) == (t and mask)
                }
                .map { it.address }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun ByteArray.toInt() = fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    private const val ATTEMPTS = 3
    private const val BACKOFF_MS = 600L
}
