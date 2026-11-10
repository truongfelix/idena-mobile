package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * The noise of one protected picture, effect by effect (B-215, as the desktop's B-213 NoiseControls): Mirror, Grain
 * and Color shift start at the values the picture was protected with; each change makes the picture's
 * noise again (a slider on release), and the controls wait for it. Regenerate draws new values at random.
 */

/**
 * Picture [number]'s noise: [frame] the protected picture, [effects] its values; [onChange] makes the noise again
 * with new values; [busy] while it does, and the window cannot be closed then (the flip is never sent or protected
 * again with a picture on its way); [error] why the last change failed. Close or back closes it, the last values kept.
 */
@Composable
fun NoiseEditor(
    number: Int,
    frame: ByteArray?,
    effects: NoiseEffects,
    busy: Boolean,
    error: String?,
    onChange: (NoiseEffects) -> Unit,
    onClose: () -> Unit,
) {
    // The sliders' values while dragged; back to the picture's once its noise is made (or failed).
    var shown by remember { mutableStateOf(effects.normalized()) }
    LaunchedEffect(effects, busy) { if (!busy) shown = effects.normalized() }
    // [force]: made again even with the same values (Regenerate draws a new grain too).
    fun commit(next: NoiseEffects, force: Boolean = false) {
        val n = next.normalized()
        shown = n
        if (force || n != effects.normalized()) onChange(n)
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(t(R.string.flip_noise_title, number), style = MaterialTheme.typography.titleMedium)
                Box(contentAlignment = Alignment.Center) {
                    FrameImage(frame, Modifier.fillMaxWidth())
                    if (busy) CircularProgressIndicator()
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(t(R.string.flip_noise_help), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t(R.string.flip_noise_mirror), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = shown.mirror, enabled = !busy, onCheckedChange = { commit(shown.copy(mirror = it)) })
                }
                NoiseSlider(t(R.string.flip_noise_grain, shown.grain), shown.grain, MIN_NOISE_GRAIN..MAX_NOISE_GRAIN, busy,
                    onMove = { shown = shown.copy(grain = it) }, onDone = { commit(shown) })
                NoiseSlider(t(R.string.flip_noise_hue, shown.hue), shown.hue, MIN_NOISE_HUE..MAX_NOISE_HUE, busy,
                    onMove = { shown = shown.copy(hue = it) }, onDone = { commit(shown) })
                // One action per line: French labels are cut side by side (L-65).
                OutlinedButton(onClick = { commit(NoiseEffects.random(), force = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(t(R.string.flip_noise_regenerate))
                }
                Button(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(t(R.string.common_close)) }
            }
        }
    }
}

@Composable
private fun NoiseSlider(label: String, value: Int, range: IntRange, busy: Boolean, onMove: (Int) -> Unit, onDone: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onMove((it + 0.5f).toInt().coerceIn(range)) },
            onValueChangeFinished = onDone,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = !busy,
        )
    }
}
