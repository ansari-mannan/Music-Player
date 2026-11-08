package com.andres.musicplayer.ui.eq

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.playback.AudioEffects
import com.andres.musicplayer.playback.PlaybackPrefs
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Sound settings. Every effect is the app's own, so it sounds the same on every phone and
 * works before anything has played. Everything is saved and re-applied automatically.
 */
@Composable
fun EqualizerScreen(modifier: Modifier = Modifier) {
    val mono by PlaybackPrefs.mono.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SwitchRow(
            title = "Mono audio",
            body = "Left and right combined the way Android's own Mono audio does it, after " +
                "all the effects below. Only affects this app.",
            checked = mono,
            onChange = { PlaybackPrefs.setMono(it) },
        )

        SectionDivider()
        EqualizerSection()

        SectionDivider()
        StrengthEffect(
            title = "Bass boost",
            body = "Up to +15 dB around 90 Hz.",
            initialEnabled = AudioEffects.bassEnabled,
            initialStrength = AudioEffects.bassStrength.toFloat(),
            onEnabledChange = { AudioEffects.setBassEnabled(it) },
            onStrengthChange = { AudioEffects.setBassStrength(it.toInt().toShort()) },
        )

        SectionDivider()
        StrengthEffect(
            title = "Virtualizer",
            body = "Widens the stereo image, best on headphones.",
            initialEnabled = AudioEffects.virtualizerEnabled,
            initialStrength = AudioEffects.virtualizerStrength.toFloat(),
            onEnabledChange = { AudioEffects.setVirtualizerEnabled(it) },
            onStrengthChange = { AudioEffects.setVirtualizerStrength(it.toInt().toShort()) },
        )

        SectionDivider()
        ReverbSection()

        SectionDivider()
        LoudnessSection()

        run {
                // Volume leveller.
                SectionDivider()
                var leveler by remember { mutableStateOf(AudioEffects.levelerEnabled) }
                SwitchRow(
                    title = "Volume leveller",
                    body = "Brings quiet parts up and holds loud ones back — handy for live " +
                        "recordings where the band and the crowd are far apart in volume.",
                    checked = leveler,
                    onChange = {
                        leveler = it
                        AudioEffects.setLevelerEnabled(it)
                    },
                )
        }

        SpatialAudioNote()
    }
}

@Composable
private fun EqualizerSection() {
    var enabled by remember { mutableStateOf(AudioEffects.eqEnabled) }
    var preset by remember { mutableIntStateOf(AudioEffects.eqPreset) }
    val gains = remember { mutableStateListOf<Float>().apply { addAll(AudioEffects.eqGainsDb) } }
    val maxDb = AudioEffects.eqMaxDb.toFloat()

    SwitchRow(
        title = "Equalizer",
        body = null,
        checked = enabled,
        onChange = {
            enabled = it
            AudioEffects.setEqEnabled(it)
            if (!it) {
                // Off means flat: the sliders drop back to 0 dB.
                for (i in gains.indices) gains[i] = 0f
                preset = AudioEffects.FLAT_PRESET
            }
        },
    )

    Text(
        "Presets",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AudioEffects.eqPresetNames.forEachIndexed { index, name ->
            FilterChip(
                selected = preset == index,
                onClick = {
                    AudioEffects.applyEqPreset(index)
                    preset = index
                    enabled = true
                    gains.clear()
                    gains.addAll(AudioEffects.eqGainsDb)
                },
                label = { Text(name) },
            )
        }
        FilterChip(selected = preset == -1, onClick = {}, label = { Text("Custom") }, enabled = false)
    }

    // Vertical sliders, one per band, like a hardware graphic equalizer.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        AudioEffects.eqBandFrequencies.forEachIndexed { band, hz ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(Locale.US, "%+d", gains.getOrElse(band) { 0f }.roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VerticalSlider(
                    value = gains.getOrElse(band) { 0f },
                    range = -maxDb..maxDb,
                    onChange = { db ->
                        // Whole dB steps, as Android's equalizer presents them.
                        val snapped = db.roundToInt().toFloat()
                        if (band < gains.size) gains[band] = snapped
                        preset = -1
                        enabled = true
                        AudioEffects.setEqBandDb(band, snapped)
                    },
                    modifier = Modifier
                        .height(200.dp)
                        .width(48.dp),
                )
                Text(formatFreq(hz), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Text(
        "±${maxDb.roundToInt()} dB",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A Material slider turned on its side: up is more. */
@Composable
private fun VerticalSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Slider(
            value = value,
            valueRange = range,
            onValueChange = onChange,
            modifier = Modifier
                .graphicsLayer {
                    rotationZ = 270f
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .layout { measurable, constraints ->
                    // Measure with width and height swapped, then place so the rotated
                    // slider fills the box top to bottom.
                    val placeable = measurable.measure(
                        Constraints(
                            minWidth = constraints.minHeight,
                            maxWidth = constraints.maxHeight,
                            minHeight = constraints.minWidth,
                            maxHeight = constraints.maxWidth,
                        ),
                    )
                    layout(placeable.height, placeable.width) {
                        placeable.place(-placeable.width, 0)
                    }
                },
        )
    }
}

@Composable
private fun ReverbSection() {
    var selected by remember { mutableIntStateOf(AudioEffects.reverbPreset) }
    Text("Reverb", style = MaterialTheme.typography.titleMedium)
    Text(
        "Adds the sound of a room or hall (Android's seven presets).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AudioEffects.reverbPresetNames.forEachIndexed { index, label ->
            FilterChip(
                selected = selected == index,
                onClick = {
                    selected = index
                    AudioEffects.setReverbPreset(index)
                },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun LoudnessSection() {
    var enabled by remember { mutableStateOf(AudioEffects.loudnessEnabled) }
    var gain by remember { mutableFloatStateOf(AudioEffects.loudnessGainMb.toFloat()) }
    SwitchRow(
        title = "Loudness boost",
        body = "Makes quiet recordings louder without distorting. +${String.format(Locale.US, "%.1f", gain / 100f)} dB",
        checked = enabled,
        onChange = {
            enabled = it
            AudioEffects.setLoudnessEnabled(it)
            if (!it) gain = 0f // off brings the slider back to zero
        },
    )
    Slider(
        value = gain,
        valueRange = 0f..1500f,
        onValueChange = {
            gain = it
            AudioEffects.setLoudnessGain(it.toInt())
            // Dragging the slider up switches the boost on.
            if (!enabled && it > 0f) {
                enabled = true
                AudioEffects.setLoudnessEnabled(true)
            }
        },
    )
}

/**
 * Android's Spatializer isn't an effect an app can attach: when the phone and headphones
 * support it, Android applies it itself, controlled from the system Sound settings.
 */
@Composable
private fun SpatialAudioNote() {
    val context = LocalContext.current
    val available = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching {
                manager.spatializer.immersiveAudioLevel != android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
            }.getOrDefault(false)
        } else {
            false
        }
    }
    SectionDivider()
    Text("Spatial audio", style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (available) {
            "This phone supports Android's spatial audio. It's switched on and off in the " +
                "system Sound settings (and for supported headphones), and applies to this app " +
                "automatically."
        } else {
            "This phone doesn't offer Android's spatial audio. The Virtualizer above is the " +
                "closest equivalent."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StrengthEffect(
    title: String,
    body: String?,
    initialEnabled: Boolean,
    initialStrength: Float,
    onEnabledChange: (Boolean) -> Unit,
    onStrengthChange: (Float) -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var strength by remember { mutableFloatStateOf(initialStrength) }
    SwitchRow(
        title = title,
        body = body,
        checked = enabled,
        onChange = {
            enabled = it
            onEnabledChange(it)
            if (!it) strength = 0f // off brings the slider back to zero
        },
    )
    Slider(
        value = strength,
        valueRange = 0f..1000f,
        onValueChange = {
            strength = it
            onStrengthChange(it)
            // Dragging the slider up switches the effect on.
            if (!enabled && it > 0f) {
                enabled = true
                onEnabledChange(true)
            }
        },
    )
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
}

private fun formatFreq(hz: Double): String =
    if (hz >= 1000) {
        val k = hz / 1000
        if (k == k.toInt().toDouble()) "${k.toInt()} kHz" else String.format(Locale.US, "%.1f kHz", k)
    } else {
        "${hz.toInt()} Hz"
    }
