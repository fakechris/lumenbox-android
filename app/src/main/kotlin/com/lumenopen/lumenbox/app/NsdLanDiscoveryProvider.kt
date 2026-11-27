package com.lumenopen.lumenbox.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.lumenopen.lumenbox.relay.DiscoveredLanEndpoint
import com.lumenopen.lumenbox.relay.LanDiscoveryProvider
import kotlinx.coroutines.delay
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android `NsdManager` implementation of [LanDiscoveryProvider] discovering `_lumenbox._tcp.`
 * personal Mac endpoints on the local Wi-Fi network (`docs/110 §3.1`, `INV-2251`).
 */
class NsdLanDiscoveryProvider(
    context: Context,
) : LanDiscoveryProvider {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override suspend fun discoverDirectEndpoints(
        routeId: String?,
        timeoutMillis: Long,
    ): List<DiscoveredLanEndpoint> {
        val found = CopyOnWriteArrayList<DiscoveredLanEndpoint>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val hostAddr = resolved.host?.hostAddress ?: return
                            val port = resolved.port
                            if (port <= 0) return
                            val txtRoute = resolved.attributes["route"]?.toString(StandardCharsets.UTF_8)
                            if (routeId != null && txtRoute != null && txtRoute != routeId) return
                            val formattedHost = if (hostAddr.contains(':')) "[$hostAddr]" else hostAddr
                            found.add(
                                DiscoveredLanEndpoint(
                                    serviceName = resolved.serviceName,
                                    routeId = txtRoute,
                                    wsUrl = "ws://$formattedHost:$port/ws",
                                )
                            )
                        }
                    }
                )
            }
        }

        return try {
            nsdManager.discoverServices(
                LanDiscoveryProvider.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
            delay(timeoutMillis.coerceAtLeast(150L))
            found.distinctBy { it.wsUrl }
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }
}
