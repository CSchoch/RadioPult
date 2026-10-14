package de.radiopult.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.radiopult.app.EqBand
import de.radiopult.app.EqPreset
import de.radiopult.app.EqualizerUi
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(
    eq: EqualizerUi,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPreset: (EqPreset) -> Unit,
    onBand: (Int, Int) -> Unit,
    onLoudness: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Equalizer", style = MaterialTheme.typography.titleLarge)

            when {
                !eq.loaded && eq.loading -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                !eq.loaded -> Column {
                    Hint("Der Equalizer konnte nicht geladen werden.")
                    TextButton(onClick = onRetry) { Text("Erneut versuchen") }
                }

                !eq.available -> Hint("Dieses Radio bietet keinen Equalizer an.")

                else -> {
                    if (eq.presets.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            eq.presets.forEach { preset ->
                                FilterChip(
                                    selected = preset.key == eq.presetKey,
                                    onClick = { onPreset(preset) },
                                    label = { Text(preset.label) },
                                )
                            }
                        }
                    }
                    if (eq.customActive) {
                        eq.bands.forEach { band -> BandSlider(band, onBand) }
                        eq.loudness?.let { on ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Loudness", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Switch(checked = on, onCheckedChange = { onLoudness() })
                            }
                        }
                    } else if (eq.bands.isNotEmpty() || eq.loudness != null) {
                        val custom = eq.presets.firstOrNull { it.key == eq.customKey }?.label.orEmpty()
                        Hint("Eigene Klangregler gibt es im Preset „$custom“.")
                    }
                }
            }
        }
    }
}

@Composable
private fun BandSlider(band: EqBand, onChange: (Int, Int) -> Unit) {
    var dragging by remember(band.index) { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: band.value.toFloat()).coerceIn(band.min.toFloat(), band.max.toFloat())
    val range = band.max - band.min
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(band.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(signed(shown.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onChange(band.index, it.roundToInt()) }
                dragging = null
            },
            valueRange = band.min.toFloat()..band.max.toFloat(),
            steps = if (range <= 40) range - 1 else 0,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()
