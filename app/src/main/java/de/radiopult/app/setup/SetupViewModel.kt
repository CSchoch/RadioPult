package de.radiopult.app.setup

import android.app.Application
import android.net.Network
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.radiopult.app.fsapi.Discovery
import de.radiopult.app.fsapi.FsApiClient
import de.radiopult.app.fsapi.FsApiException
import de.radiopult.app.fsapi.Nodes
import de.radiopult.app.fsapi.RadioDevice
import de.radiopult.app.fsapi.RadioSetup
import de.radiopult.app.fsapi.WifiNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

enum class SetupStep { INTRO, JOIN, NAME, NETWORKS, PASSWORD, APPLYING, DONE }

data class SetupUi(
    val step: SetupStep = SetupStep.INTRO,
    val busy: Boolean = false,
    val error: String? = null,
    val pin: String = "1234",
    val name: String = "",
    val networks: List<WifiNetwork> = emptyList(),
    val selected: WifiNetwork? = null,
    val status: String = "",
    val found: RadioDevice? = null,
)

/** Assistent für die Ersteinrichtung eines Radios (wie „Audiosystem einrichten“ in UNDOK). */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val wifi = SetupWifi(app)
    private var client: FsApiClient? = null
    private var job: Job? = null

    var active by mutableStateOf(false)
        private set
    var ui by mutableStateOf(SetupUi())
        private set

    val canPickWifi: Boolean get() = wifi.canRequest

    fun start() {
        reset()
        active = true
    }

    fun close() {
        reset()
        active = false
    }

    /** Zurück-Taste: einen Schritt zurück, wo das sinnvoll ist, sonst Assistent schließen. */
    fun back() {
        when (ui.step) {
            SetupStep.PASSWORD -> ui = ui.copy(step = SetupStep.NETWORKS, error = null)
            SetupStep.APPLYING -> Unit
            else -> close()
        }
    }

    private fun reset() {
        job?.cancel()
        job = null
        wifi.release()
        client = null
        ui = SetupUi()
    }

    override fun onCleared() {
        wifi.release()
    }

    fun next() {
        ui = ui.copy(step = SetupStep.JOIN, error = null)
    }

    fun setPin(pin: String) {
        ui = ui.copy(pin = pin.filter(Char::isDigit).take(8))
    }

    fun setName(name: String) {
        ui = ui.copy(name = name.take(32))
    }

    fun joinWithPicker() = launchStep {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@launchStep
        connectRadio(wifi.request())
    }

    fun joinManually() = launchStep {
        val network = wifi.connectedWifi() ?: throw FsApiException(
            "Das Handy ist mit keinem WLAN verbunden. Verbinde es zuerst mit dem WLAN „Setup …“ des Radios.",
        )
        connectRadio(network)
    }

    private suspend fun connectRadio(network: Network) {
        wifi.bind(network)
        for (host in wifi.radioHosts(network)) {
            val c = FsApiClient(host, ui.pin)
            try {
                val name = c.get(Nodes.FRIENDLY_NAME)?.trim().orEmpty()
                client = c
                ui = ui.copy(step = SetupStep.NAME, name = name)
                return
            } catch (e: FsApiException) {
                throw if (e.message == "PIN falsch") FsApiException("Die PIN stimmt nicht. Im Setup-Modus ist sie meist 1234.") else e
            } catch (ignored: IOException) {
                // nächste mögliche Adresse probieren
            }
        }
        throw FsApiException(
            "Im verbundenen WLAN antwortet kein Radio. Ist das Handy mit dem WLAN „Setup …“ des Radios verbunden?",
        )
    }

    fun saveName() = launchStep {
        val c = client ?: return@launchStep
        val name = ui.name.trim()
        if (name.isNotEmpty() && !c.set(Nodes.FRIENDLY_NAME, name)) {
            throw FsApiException("Der Name konnte nicht gespeichert werden.")
        }
        ui = ui.copy(step = SetupStep.NETWORKS)
        scan(c)
    }

    fun rescan() = launchStep {
        client?.let { scan(it) }
    }

    private suspend fun scan(c: FsApiClient) {
        ui = ui.copy(networks = emptyList())
        val networks = RadioSetup(c).scanNetworks()
        ui = ui.copy(
            networks = networks,
            error = if (networks.isEmpty()) "Das Radio hat kein WLAN gefunden. Versuche es noch einmal." else null,
        )
    }

    fun choose(network: WifiNetwork) {
        if (network.secured) {
            ui = ui.copy(step = SetupStep.PASSWORD, selected = network, error = null)
        } else {
            apply(network, "")
        }
    }

    fun chooseHidden(ssid: String, secured: Boolean) {
        if (ssid.isNotBlank()) choose(WifiNetwork(key = null, ssid = ssid.trim(), secured = secured))
    }

    fun connectWith(passphrase: String) {
        ui.selected?.let { apply(it, passphrase) }
    }

    private fun apply(network: WifiNetwork, passphrase: String) = launchStep {
        val c = client ?: return@launchStep
        val previous = ui.step
        ui = ui.copy(step = SetupStep.APPLYING, selected = network, status = "Zugangsdaten werden an das Radio übertragen …")
        try {
            RadioSetup(c).connectTo(network, passphrase)
        } catch (e: IOException) {
            ui = ui.copy(step = previous)
            throw e
        }
        // Das Radio schließt jetzt sein Setup-WLAN; das Handy geht zurück ins Heimnetz
        wifi.release()
        client = null
        ui = ui.copy(status = "Das Radio verbindet sich mit „${network.ssid}“ …")
        ui = ui.copy(step = SetupStep.DONE, found = findRadio(ui.name.trim()))
    }

    private suspend fun findRadio(name: String): RadioDevice? {
        delay(8000)
        repeat(8) {
            val devices = try {
                Discovery.discover(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                emptyList()
            }
            devices.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
            delay(3000)
        }
        return null
    }

    private fun launchStep(block: suspend () -> Unit) {
        if (ui.busy) return
        job = viewModelScope.launch {
            ui = ui.copy(busy = true, error = null)
            try {
                block()
            } catch (e: FsApiException) {
                ui = ui.copy(error = e.message)
            } catch (ignored: IOException) {
                ui = ui.copy(error = "Keine Verbindung zum Radio. Ist das Handy noch mit dem Setup-WLAN verbunden?")
            } finally {
                ui = ui.copy(busy = false)
            }
        }
    }
}
