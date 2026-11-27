package com.lumenopen.lumenbox.relay

/**
 * Discovers personal LumenBox hosts advertising `_lumenbox._tcp` on the local network (`docs/110 §3.1`).
 * Implemented in `:app` using Android's `NsdManager`, and injectable in `:core` tests.
 */
data class DiscoveredLanEndpoint(
    val serviceName: String,
    val routeId: String?,
    val wsUrl: String,
)

fun interface LanDiscoveryProvider {
    suspend fun discoverDirectEndpoints(routeId: String?, timeoutMillis: Long): List<DiscoveredLanEndpoint>

    companion object {
        const val SERVICE_TYPE = "_lumenbox._tcp."
        val NONE = LanDiscoveryProvider { _, _ -> emptyList() }
    }
}
