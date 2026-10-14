package de.radiopult.app

import android.os.Bundle
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

    override fun onStop() {
        vm.onHidden()
        super.onStop()
    }
}
