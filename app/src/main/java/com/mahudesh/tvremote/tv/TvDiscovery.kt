package com.mahudesh.tvremote.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

data class FoundTv(val name: String, val host: String) {
    companion object { const val UNNAMED = "Android TV" }
}

/**
 * Finds Android TVs on the local network.
 *
 * 1. mDNS: TVs advertise `_androidtvremote2._tcp` with their friendly name.
 * 2. Fallback sweep of the local /24 on port 6466 — needed when this phone is the
 *    hotspot, since many phones don't route multicast on the hotspot interface.
 */
class TvDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)

    fun mdns(): Flow<FoundTv> = callbackFlow {
        val wifi = app.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("tv-discovery")?.apply { setReferenceCounted(false); acquire() }
        val executor = Executors.newSingleThreadExecutor()

        fun publish(info: NsdServiceInfo) {
            val host = if (Build.VERSION.SDK_INT >= 34) {
                info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
            } else {
                @Suppress("DEPRECATION") info.host?.hostAddress
            }
            if (host != null) trySend(FoundTv(info.serviceName, host))
        }

        fun listener() = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) {
                    nsd.registerServiceInfoCallback(service, executor, object : NsdManager.ServiceInfoCallback {
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            publish(info)
                            runCatching { nsd.unregisterServiceInfoCallback(this) }
                        }
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                        override fun onServiceLost() = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    })
                } else {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) = publish(info)
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        // Newer TVs advertise _androidtvremote2, older Remote Service versions _androidtvremote.
        val listeners = SERVICE_TYPES.map { type -> listener().also { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        awaitClose {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            lock?.release()
            executor.shutdown()
        }
    }

    /** Probes every address on each local IPv4 /24 for the remote port. */
    suspend fun sweep(): List<FoundTv> = withContext(Dispatchers.IO) {
        val candidates = localPrefixes().flatMap { (prefix, own) ->
            (1..254).map { "$prefix.$it" }.filter { it != own }
        }.distinct()
        val gate = Semaphore(48)
        coroutineScope {
            candidates.map { ip ->
                async { gate.withPermit { if (isOpen(ip, RemoteSession.REMOTE_PORT)) ip else null } }
            }.awaitAll().filterNotNull().map { FoundTv(FoundTv.UNNAMED, it) }
        }
    }

    private fun localPrefixes(): List<Pair<String, String>> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            // Skip mobile-data interfaces; Wi-Fi, hotspot (ap/swlan/wlan) and ethernet remain.
            .filterNot { it.name.startsWith("rmnet") || it.name.startsWith("ccmni") || it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { addr ->
                val ip = addr.hostAddress ?: return@mapNotNull null
                ip.substringBeforeLast('.') to ip
            }

    companion object {
        private val SERVICE_TYPES = listOf("_androidtvremote2._tcp.", "_androidtvremote._tcp.")

        /** Default route, then each local address on the TV's subnet (see [Net]). */
        /**
         * Is the TV powered and on the network? Knocks on an unused port so no TV service notices:
         * "refused" (or accepted) means the TV answered; a timeout / no route means it's gone.
         */
        fun isAlive(host: String, timeoutMs: Int = 3000): Boolean {
            val target = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
            return (listOf<java.net.InetAddress?>(null) + Net.localAddressesFor(target)).any { source ->
                try {
                    Socket().use { s ->
                        if (source != null) s.bind(InetSocketAddress(source, 0))
                        s.connect(InetSocketAddress(target, 9), timeoutMs)
                    }
                    true
                } catch (e: java.net.ConnectException) {
                    e.message?.contains("refused", ignoreCase = true) == true
                } catch (e: Exception) {
                    false
                }
            }
        }

        fun isOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean {
            val target = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
            return (listOf<java.net.InetAddress?>(null) + Net.localAddressesFor(target)).any { source ->
                runCatching {
                    Socket().use { s ->
                        if (source != null) s.bind(InetSocketAddress(source, 0))
                        s.connect(InetSocketAddress(target, port), timeoutMs)
                    }
                }.isSuccess
            }
        }
    }
}
