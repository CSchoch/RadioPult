package de.radiopult.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.radiopult.app.fsapi.FsApiClient
import de.radiopult.app.fsapi.FsApiException
import de.radiopult.app.fsapi.Nodes
import de.radiopult.app.fsapi.WrongPinException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Ein Radio aus der Multiroom-Liste (netRemote.multiroom.device.listAll).
 * [role]: 0 allein, 1 Mitglied, 2 Server der Gruppe; [stereoRole]: 0 allein, 1 Haupt-, 2 Zweitlautsprecher.
 */
data class MrDevice(
    val udn: String,
    val name: String,
    val ip: String,
    val groupId: String,
    val groupName: String,
    val role: Int,
    val clientNumber: Int,
    val stereoRole: Int,
    val stereoName: String,
    val current: Boolean,
) {
    val grouped get() = groupId.isNotEmpty() && role != 0
    val server get() = role == 2
}

data class MrGroup(
    val id: String,
    val name: String,
    val server: MrDevice,
    val clients: List<MrDevice>,
    val masterVolume: Int? = null,
    /** Lautstärke je Radio (udn); der Server über sys.audio.volume, Mitglieder über client.volumeN. */
    val volumes: Map<String, Int> = emptyMap(),
) {
    val members get() = listOf(server) + clients
}

data class MultiroomUi(
    val loading: Boolean = false,
    /** null = noch nicht geprüft; false = Radio kann kein Multiroom. */
    val supported: Boolean? = null,
    val groups: List<MrGroup> = emptyList(),
    val ungrouped: List<MrDevice> = emptyList(),
    /** Mitglieder je Gruppe außer dem Server (netRemote.multiroom.caps.maxClients). */
    val maxClients: Int = 4,
    /** Radios, die ein Stereopaar bilden können (haben netRemote.multichannel.*). */
    val stereoCapable: Set<String> = emptySet(),
    val busy: Boolean = false,
    val volumeMax: Int = 32,
)

/**
 * Multiroom-Gruppen und Stereopaare, so wie UNDOK sie anlegt: Gruppen entstehen auf dem Radio,
 * das die Musik liefert (Server); Mitglieder kommen per UDN dazu. Für andere Radios gilt deren
 * gespeicherte PIN (sonst 1234).
 */
class MultiroomController(
    private val scope: CoroutineScope,
    private val clientFor: (String) -> FsApiClient,
    private val text: (Int, Array<out Any>) -> String,
    private val onNotice: (String) -> Unit,
) {
    var ui by mutableStateOf(MultiroomUi())
        private set

    private var host: String? = null
    private var job: Job? = null

    private fun str(id: Int, vararg args: Any) = text(id, args)

    fun reset() {
        job?.cancel()
        host = null
        ui = MultiroomUi()
    }

    fun load(currentHost: String, volumeMax: Int) {
        host = currentHost
        ui = ui.copy(volumeMax = volumeMax)
        task(showBusy = false) { read() }
    }

    private suspend fun read() {
        val h = host ?: return
        val c = clientFor(h)
        ui = ui.copy(loading = true)
        try {
            if (c.get(Nodes.MR_PROTOCOL) == null) {
                ui = ui.copy(loading = false, supported = false)
                return
            }
            val maxClients = c.get(Nodes.MR_MAX_CLIENTS)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 4
            val devices = c.list(Nodes.MR_DEVICES).mapNotNull { item ->
                val udn = item.text("udn")
                if (udn.isEmpty()) return@mapNotNull null
                val ip = item.text("ipaddress")
                MrDevice(
                    udn = udn,
                    name = item.text("friendlyname").ifEmpty { ip },
                    ip = ip,
                    groupId = item.text("groupid"),
                    groupName = item.text("groupname"),
                    role = item.fields["grouprole"]?.trim()?.toIntOrNull() ?: 0,
                    clientNumber = item.fields["clientnumber"]?.trim()?.toIntOrNull() ?: 255,
                    stereoRole = item.fields["systemrole"]?.trim()?.toIntOrNull() ?: 0,
                    stereoName = item.text("systemname"),
                    current = ip == h,
                )
            }
            // Stereo-Zweitlautsprecher tauchen nicht einzeln auf, sie gehören zum Hauptlautsprecher
            val visible = devices.filter { it.stereoRole != 2 }
            val groups = visible.filter { it.grouped }.groupBy { it.groupId }.mapNotNull { (id, members) ->
                val server = members.firstOrNull { it.server } ?: return@mapNotNull null
                readVolumes(MrGroup(id, server.groupName.ifEmpty { members.first().groupName }, server, members.filter { !it.server }))
            }
            val inGroups = groups.flatMap { g -> g.members.map { it.udn } }.toSet()
            val stereo = visible.filter { d ->
                runCatching { clientFor(d.ip).get(Nodes.MC_STATE) != null }.getOrDefault(false)
            }.map { it.udn }.toSet()
            ui = ui.copy(
                loading = false,
                supported = true,
                maxClients = maxClients,
                groups = groups,
                ungrouped = visible.filter { it.udn !in inGroups },
                stereoCapable = stereo,
            )
        } catch (e: IOException) {
            ui = ui.copy(loading = false)
            throw e
        }
    }

    private suspend fun readVolumes(group: MrGroup): MrGroup {
        val s = clientFor(group.server.ip)
        return try {
            val nodes = listOf(Nodes.MR_MASTER_VOLUME, Nodes.VOLUME) + group.clients.map { Nodes.mrClientVolume(it.clientNumber) }
            val v = s.getAll(nodes)
            val volumes = buildMap {
                v[Nodes.VOLUME]?.trim()?.toIntOrNull()?.let { put(group.server.udn, it) }
                group.clients.forEach { d -> v[Nodes.mrClientVolume(d.clientNumber)]?.trim()?.toIntOrNull()?.let { put(d.udn, it) } }
            }
            group.copy(masterVolume = v[Nodes.MR_MASTER_VOLUME]?.trim()?.toIntOrNull(), volumes = volumes)
        } catch (ignored: IOException) {
            group
        }
    }

    /** Neue Gruppe: [server] liefert die Musik (seine Quelle muss Multiroom können), die anderen hören mit. */
    fun createGroup(name: String, server: MrDevice, members: List<MrDevice>) = task {
        val s = clientFor(server.ip)
        if (s.get(Nodes.MR_GROUP_STREAMABLE)?.trim() == "0") throw FsApiException(R.string.error_mr_not_streamable, server.name)
        if (!s.set(Nodes.MR_GROUP_CREATE, name.trim())) throw FsApiException(R.string.error_mr_create)
        delay(500)
        members.filter { it.udn != server.udn }.forEach { addClient(s, it) }
        onNotice(str(R.string.notice_mr_created, name.trim()))
    }

    fun addToGroup(group: MrGroup, device: MrDevice) = task {
        if (group.clients.size >= ui.maxClients) throw FsApiException(R.string.error_mr_full)
        addClient(clientFor(group.server.ip), device)
    }

    private suspend fun addClient(server: FsApiClient, device: MrDevice) {
        if (!server.set(Nodes.MR_GROUP_ADD, device.udn)) throw FsApiException(R.string.error_mr_add, device.name)
        delay(1000) // das Radio braucht einen Moment, bis es in der Gruppe ist
    }

    /**
     * Radio aus der Gruppe nehmen. Ist es der Server und hat die Gruppe noch Mitglieder, wird
     * erst ein Mitglied zum Server gemacht (wie bei UNDOK), sonst die Gruppe aufgelöst.
     */
    fun removeFromGroup(group: MrGroup, device: MrDevice) = task {
        val server = clientFor(group.server.ip)
        if (!device.server) {
            if (!server.set(Nodes.MR_GROUP_REMOVE, device.udn)) throw FsApiException(R.string.error_mr_remove, device.name)
            return@task
        }
        val attached = server.get(Nodes.MR_ATTACHED)?.trim()?.toIntOrNull() ?: group.clients.size
        val heir = group.clients.firstOrNull()
        if (attached <= 0 || heir == null) {
            if (!server.set(Nodes.MR_GROUP_DESTROY, "1")) throw FsApiException(R.string.error_mr_delete)
            return@task
        }
        val next = clientFor(heir.ip)
        if (!next.set(Nodes.MR_GROUP_STATE, "2")) throw FsApiException(R.string.error_mr_remove, device.name)
        delay(4000)
        if (!next.set(Nodes.MR_GROUP_REMOVE, device.udn)) throw FsApiException(R.string.error_mr_remove, device.name)
    }

    fun deleteGroup(group: MrGroup) = task {
        if (!clientFor(group.server.ip).set(Nodes.MR_GROUP_DESTROY, "1")) throw FsApiException(R.string.error_mr_delete)
        delay(500)
        onNotice(str(R.string.notice_mr_deleted, group.name))
    }

    fun setMasterVolume(group: MrGroup, value: Int) {
        updateGroup(group.copy(masterVolume = value))
        task(showBusy = false, reload = false) { clientFor(group.server.ip).set(Nodes.MR_MASTER_VOLUME, value.toString()) }
    }

    fun setMemberVolume(group: MrGroup, device: MrDevice, value: Int) {
        updateGroup(group.copy(volumes = group.volumes + (device.udn to value)))
        task(showBusy = false, reload = false) {
            val s = clientFor(group.server.ip)
            if (device.server) s.set(Nodes.VOLUME, value.toString())
            else s.set(Nodes.mrClientVolume(device.clientNumber), value.toString())
        }
    }

    private fun updateGroup(group: MrGroup) {
        ui = ui.copy(groups = ui.groups.map { if (it.id == group.id) group else it })
    }

    // ---- Stereopaar ----

    /**
     * Zwei Radios zu einem Stereopaar machen: [primary] spielt [primaryLeft] ? links : rechts,
     * [secondary] die andere Seite. Ablauf wie bei UNDOK, mit Warten auf die Bestätigung des Radios.
     */
    fun pairStereo(name: String, primary: MrDevice, secondary: MrDevice, primaryLeft: Boolean) = task {
        val p = clientFor(primary.ip)
        if (!p.set(Nodes.MC_CREATE, name.trim())) throw FsApiException(R.string.error_stereo_pair)
        for (i in 0 until 20) {
            if (!p.get(Nodes.MC_NAME).isNullOrBlank()) break
            delay(500)
        }
        val left = "1"
        val right = "2"
        val ok = p.set(Nodes.MC_ADD_SECONDARY, secondary.udn) &&
            p.set(Nodes.MC_PRIMARY_MASK, if (primaryLeft) left else right)
        if (!ok) throw FsApiException(R.string.error_stereo_pair)
        for (i in 0 until 20) {
            if (p.get(Nodes.MC_SECONDARY_STATUS)?.trim() == "1") break // 1 = bereit
            delay(500)
        }
        if (!p.set(Nodes.MC_SECONDARY_MASK, if (primaryLeft) right else left)) throw FsApiException(R.string.error_stereo_pair)
        onNotice(str(R.string.notice_stereo_paired, name.trim()))
    }

    /** Links und rechts tauschen. */
    fun swapStereo(primary: MrDevice) = task {
        val p = clientFor(primary.ip)
        val leftNow = p.get(Nodes.MC_PRIMARY_MASK)?.trim() == "1"
        val ok = p.set(Nodes.MC_PRIMARY_MASK, if (leftNow) "2" else "1") &&
            p.set(Nodes.MC_SECONDARY_MASK, if (leftNow) "1" else "2")
        if (!ok) throw FsApiException(R.string.error_stereo_pair)
    }

    fun unpairStereo(primary: MrDevice) = task {
        if (!clientFor(primary.ip).set(Nodes.MC_UNPAIR, "1")) throw FsApiException(R.string.error_stereo_unpair)
        delay(1000)
        onNotice(str(R.string.notice_stereo_unpaired, primary.stereoName.ifEmpty { primary.name }))
    }

    private fun task(showBusy: Boolean = true, reload: Boolean = true, block: suspend () -> Unit) {
        if (showBusy && ui.busy) return
        job = scope.launch {
            if (showBusy) ui = ui.copy(busy = true)
            try {
                block()
                if (reload) read()
            } catch (e: WrongPinException) {
                onNotice(str(R.string.error_mr_pin))
            } catch (e: FsApiException) {
                onNotice(text(e.text, e.args))
            } catch (ignored: IOException) {
                onNotice(str(R.string.error_mr_unreachable))
            } finally {
                if (showBusy) ui = ui.copy(busy = false)
            }
        }
    }
}
