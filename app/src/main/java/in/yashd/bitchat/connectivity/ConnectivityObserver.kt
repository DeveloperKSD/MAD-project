package `in`.yashd.bitchat.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watches the system connectivity service (ConnectivityManager) and reports whether the
 * phone currently has VALIDATED internet access, not just a Wi-Fi or mobile association.
 */
class ConnectivityObserver(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = currentlyOnline() }
        override fun onLost(network: Network) { _online.value = currentlyOnline() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    fun start() {
        _online.value = currentlyOnline()
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun currentlyOnline(): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
