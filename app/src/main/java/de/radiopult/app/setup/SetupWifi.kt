package de.radiopult.app.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.annotation.RequiresApi
import de.radiopult.app.fsapi.FsApiException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Verbindung zum WLAN, das ein Radio im Setup-Modus aufmacht („Setup <Marke> <Modell> …“).
 * Das Netz hat kein Internet, deshalb bindet die App ihren Verkehr ausdrücklich daran –
 * sonst würde Android die Anfragen über mobile Daten schicken.
 */
class SetupWifi(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Ab Android 10 kann die App das Setup-WLAN über einen System-Dialog selbst verbinden. */
    val canRequest: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun request(): Network = suspendCancellableCoroutine { cont ->
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(".*[Ss][Ee][Tt][Uu][Pp].*", PatternMatcher.PATTERN_ADVANCED_GLOB))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) {
                    cont.resumeWithException(
                        FsApiException("Kein Setup-WLAN verbunden. Ist das Radio im Setup-Modus?"),
                    )
                }
            }
        }
        callback = cb
        cm.requestNetwork(request, cb)
        cont.invokeOnCancellation { release() }
    }

    /** Für ältere Android-Versionen: das WLAN, mit dem sich der Nutzer selbst verbunden hat. */
    @Suppress("DEPRECATION")
    fun connectedWifi(): Network? = cm.allNetworks.firstOrNull { network ->
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun bind(network: Network) {
        cm.bindProcessToNetwork(network)
    }

    /** Mögliche Adressen des Radios: Es ist im eigenen WLAN zugleich Router und DHCP-Server. */
    fun radioHosts(network: Network): List<String> {
        val lp = cm.getLinkProperties(network)
        val hosts = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp?.dhcpServerAddress?.hostAddress?.let { hosts += it }
        }
        lp?.routes.orEmpty()
            .filter { it.isDefaultRoute }
            .mapNotNullTo(hosts) { it.gateway?.hostAddress }
        hosts += "192.168.1.1"
        return hosts.filter { ':' !in it && it != "0.0.0.0" }
    }

    /** Setup-WLAN freigeben – Android kehrt dann ins gewohnte WLAN zurück. */
    fun release() {
        cm.bindProcessToNetwork(null)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
