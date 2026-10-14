package de.radiopult.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.radiopult.app.ui.DevicesScreen
import de.radiopult.app.ui.RadioPultTheme
import de.radiopult.app.ui.RemoteScreen

class MainActivity : ComponentActivity() {

    private val vm: RadioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RadioPultTheme {
                if (vm.current == null) {
                    DevicesScreen(vm)
                } else {
                    BackHandler { vm.disconnect() }
                    RemoteScreen(vm)
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
