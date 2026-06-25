package com.andres.musicplayer.ui.eq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.media.audiofx.Equalizer
import com.andres.musicplayer.playback.AudioEffects
import com.andres.musicplayer.ui.common.EmptyState
import kotlinx.coroutines.delay

/** Multi-band equalizer with presets, backed by the system Equalizer effect. */
@Composable
fun EqualizerScreen(modifier: Modifier = Modifier) {
    // The effect only exists once playback has started. Poll until it's ready so
    // the screen fills in automatically instead of showing a dead placeholder.
    var eq by remember { mutableStateOf<Equalizer?>(AudioEffects.equalizer) }
    LaunchedEffect(Unit) {
        while (eq == null) {
            delay(500)
            eq = AudioEffects.equalizer
        }
    }

    val currentEq = eq
    if (currentEq == null) {
        EmptyState(
            title = "Equalizer unavailable",
            body = "Play a song first — the equalizer will load automatically.",
            modifier = modifier,
        )
        return
    }

    var enabled by remember { mutableStateOf(currentEq.enabled) }
    // Bumped to re-read band levels after applying a preset.
    var version by remember { mutableIntStateOf(0) }

    val bandCount = currentEq.numberOfBands.toInt()
    val minLevel = currentEq.bandLevelRange[0].toInt()
    val maxLevel = currentEq.bandLevelRange[1].toInt()
    val presetCount = currentEq.numberOfPresets.toInt()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Equalizer", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    AudioEffects.setEqEnabled(it)
                },
            )
        }

        if (presetCount > 0) {
            Spacer(Modifier.padding(top = 12.dp))
            Text(
                "Presets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (p in 0 until presetCount) {
                    AssistChip(
                        onClick = {
                            AudioEffects.applyEqPreset(p.toShort())
                            if (!enabled) {
                                enabled = true
                                AudioEffects.setEqEnabled(true)
                            }
                            version++
                        },
                        label = { Text(currentEq.getPresetName(p.toShort())) },
                    )
                }
            }
        }

        Spacer(Modifier.padding(top = 16.dp))
        for (band in 0 until bandCount) {
            var level by remember(version, band) {
                mutableStateOf(currentEq.getBandLevel(band.toShort()).toFloat())
            }
            val centerHz = currentEq.getCenterFreq(band.toShort()) / 1000
            Text(
                text = formatFreq(centerHz),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = level,
                valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                onValueChange = {
                    level = it
                    // Always write to the effect; `currentEq.enabled` alone controls
                    // audibility, so the slider and hardware never desync. Goes through
                    // AudioEffects so the level is persisted at the same time.
                    AudioEffects.setEqBandLevel(band.toShort(), it.toInt().toShort())
                },
            )
        }

        AudioEffects.bassBoost?.let { bass ->
            EffectSlider(
                title = "Bass boost",
                initialEnabled = bass.enabled,
                initialStrength = runCatching { bass.roundedStrength.toFloat() }.getOrDefault(0f),
                onEnabledChange = { AudioEffects.setBassEnabled(it) },
                onStrengthChange = { AudioEffects.setBassStrength(it.toInt().toShort()) },
            )
        }
        AudioEffects.virtualizer?.let { virt ->
            EffectSlider(
                title = "Virtualizer (stereo widen)",
                initialEnabled = virt.enabled,
                initialStrength = runCatching { virt.roundedStrength.toFloat() }.getOrDefault(0f),
                onEnabledChange = { AudioEffects.setVirtualizerEnabled(it) },
                onStrengthChange = { AudioEffects.setVirtualizerStrength(it.toInt().toShort()) },
            )
        }
    }
}

@Composable
private fun EffectSlider(
    title: String,
    initialEnabled: Boolean,
    initialStrength: Float,
    onEnabledChange: (Boolean) -> Unit,
    onStrengthChange: (Float) -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var strength by remember { mutableStateOf(initialStrength) }
    Spacer(Modifier.padding(top = 16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                onEnabledChange(it)
            },
        )
    }
    Slider(
        value = strength,
        valueRange = 0f..1000f,
        onValueChange = {
            strength = it
            // Always write the strength; the effect's own enabled flag gates audibility.
            onStrengthChange(it)
        },
    )
}

private fun formatFreq(hz: Int): String =
    if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
