package de.radiopult.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.radiopult.app.setup.SetupViewModel
import de.radiopult.app.ui.DevicesScreen
import de.radiopult.app.ui.RadioPultTheme
import de.radiopult.app.ui.RemoteScreen
import de.radiopult.app.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val vm: RadioViewModel by viewModels()
    private val setupVm: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RadioPultTheme {
                when {
                    setupVm.active -> {
                        BackHandler { setupVm.back() }
                        SetupScreen(setupVm, onFinished = { device ->
                            device?.let(vm::addDevice)
                            vm.scan()
                        })
                    }

                    vm.current == null -> DevicesScreen(vm, onSetup = setupVm::start)

                    else -> {
                        BackHandler { vm.disconnect() }
                        RemoteScreen(vm)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onVisible()
    }

    /** Lautstärketasten des Handys steuern das Radio, solange die Fernbedienung offen ist. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (!radioVolumeKeys()) return super.onKeyDown(keyCode, event)
        vm.volumeStep(delta)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        return (volumeKey && radioVolumeKeys()) || super.onKeyUp(keyCode, event)
    }

    private fun radioVolumeKeys() = !setupVm.active && vm.current != null && vm.ui.connected && vm.ui.power

    override fun onStop() {
        vm.onHidden()
        super.onStop()
    }
}
