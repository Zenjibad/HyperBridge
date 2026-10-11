package com.d4viddf.hyperbridge.cooldown.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.hypercooldown.actuation.CooldownOptionBounds
import app.hypercooldown.actuation.CooldownOptions
import app.hypercooldown.actuation.HeadsUpOption
import app.hypercooldown.actuation.IslandOption
import app.hypercooldown.actuation.SoundOption
import app.hypercooldown.actuation.VibrationOption
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.cooldown.act.CooldownRuntime
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.ui.components.EngineOptionCard
import com.d4viddf.hyperbridge.ui.components.ExpandableSettingCard
import com.d4viddf.hyperbridge.ui.components.SettingsToggleCard
import com.d4viddf.hyperbridge.ui.components.WarningCard
import com.d4viddf.hyperbridge.ui.screens.theme.ShapeStyle
import com.d4viddf.hyperbridge.ui.screens.theme.getExpressiveShape
import kotlinx.coroutines.launch

/**
 * The cooldown's settings screen, in the host's own shape: `remember { AppPreferences(context) }`,
 * a flow through `collectAsState`, and the host's `SettingsToggleCard` / `EngineOptionCard` /
 * `ExpandableSettingCard` / `WarningCard` components. No ViewModel -- no settings screen in this host
 * has one (`AppListViewModel` and `ThemeViewModel` are the only two, and neither is a settings
 * screen) -- and no DI container, because the host has none.
 *
 * **The screen's job is to say what the feature does, in the user's words.** The phases were named
 * after AOSP's internals (`POLITE`, `MUTED`, "cooldown") and the numbers were offered as three
 * radio buttons; both are fixed here. Three rules, and they are the whole of Part B:
 *
 *  1. every control carries one plain sentence about **what the user will experience**, not what the
 *     implementation does;
 *  2. the sequence is stated once, in order, before any switch -- and the one line that depends on the
 *     current numbers is *derived from them* (`CooldownOptionBounds.describeSequence`), so it cannot
 *     describe a window the settings do not;
 *  3. the values are editable and validated, with AOSP's own value shown as a described preset: a
 *     value the user typed is never silently clamped, and an entry that is out of range says so.
 *
 * The honest costs are on the rows they belong to and not in a footnote: the island's option is free
 * of channel writes and says so, and the heads-up option's demotion is stated where the switch is,
 * with no acknowledgement gate in front of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CooldownSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val global by preferences.cooldownGlobalFlow.collectAsState(initial = CooldownOptions())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cooldown_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))

            CooldownStatusCard(runtime = CooldownRuntime.get(context))

            CooldownOptionsEditor(
                options = global,
                onUpdate = { updated -> scope.launch { preferences.updateCooldownGlobal(updated) } }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The whole option surface, as rows. Shared with the per-app sheet, where the same options are one
 * app's overrides rather than the global row -- which is the host's own idiom, not a cooldown
 * invention (`AppearanceSettingsContent` shows the island's options in both places).
 */
@Composable
fun CooldownOptionsEditor(
    options: CooldownOptions,
    onUpdate: (CooldownOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read from the stored option, not from local state: this is one preference shared by the global
    // row and every per-app row, and a `remember`ed copy would let one screen hide the note while the
    // other still showed it.
    val noteRead = options.headsUpSuppressionAcknowledged == true
    val context = LocalContext.current
    val runtime = remember { CooldownRuntime.get(context.applicationContext) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.cooldown_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        SettingsToggleCard(
            title = stringResource(R.string.cooldown_enable_title),
            subtitle = stringResource(R.string.cooldown_enable_subtitle),
            icon = Icons.Filled.Notifications,
            // `!= false`, not `== true`: the option is nullable and AOSP's own
            // `notification_cooldown_enabled` defaults to 1, so "nobody has ever touched this" reads
            // as on. The runtime's enable gate is `enabled != false` for the same reason, and the two
            // must not disagree or the screen would display "off" while the feature ran.
            checked = options.enabled != false,
            shape = getExpressiveShape(1, 0, ShapeStyle.Large),
            onCheckedChange = { onUpdate(options.copy(enabled = it)) }
        )

        Spacer(Modifier.height(16.dp))

        // ---- The sequence, once, in order, before any switch. The one line that depends on the
        // numbers is derived from them. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_sequence_title),
            icon = Icons.Outlined.Speed,
        ) {
            Column {
                SequenceRow(stringResource(R.string.cooldown_sequence_first))
                SequenceRow(stringResource(R.string.cooldown_sequence_quieter))
                SequenceRow(stringResource(R.string.cooldown_sequence_silent))
                SequenceRow(stringResource(R.string.cooldown_sequence_recover))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.cooldown_value_is,
                        CooldownOptionBounds.describeSequence(
                            timeoutPoliteMs = options.timeoutPoliteMs
                                ?: CooldownNumberText.defaultTimeoutPoliteMs,
                            timeoutMutedMs = options.timeoutMutedMs
                                ?: CooldownNumberText.defaultTimeoutMutedMs,
                            factor = options.attenuationFactor
                                ?.takeIf { it in CooldownOptionBounds.ATTENUATION_RANGE }
                                ?: CooldownNumberText.defaultFactor,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- What each phase does. One row per effect per phase, never one row for two phases. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_alert),
            icon = Icons.Outlined.VolumeOff,
        ) {
            Column {
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_quieter_sound_title),
                    subtitle = stringResource(R.string.cooldown_quieter_sound_subtitle),
                    icon = Icons.Outlined.VolumeOff,
                    checked = options.soundPolite == SoundOption.SILENT,
                    shape = getExpressiveShape(4, 0, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                soundPolite = if (it) SoundOption.SILENT else SoundOption.INHERIT
                            )
                        )
                    }
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_silent_sound_title),
                    subtitle = stringResource(R.string.cooldown_silent_sound_subtitle),
                    icon = Icons.Outlined.VolumeOff,
                    checked = options.soundMuted == SoundOption.SILENT,
                    shape = getExpressiveShape(4, 1, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                soundMuted = if (it) SoundOption.SILENT else SoundOption.INHERIT
                            )
                        )
                    }
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_quieter_vibration_title),
                    subtitle = stringResource(R.string.cooldown_quieter_vibration_subtitle),
                    icon = Icons.Outlined.DoNotDisturbOn,
                    checked = options.vibrationPolite == VibrationOption.OFF,
                    shape = getExpressiveShape(4, 2, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                vibrationPolite =
                                    if (it) VibrationOption.OFF else VibrationOption.INHERIT
                            )
                        )
                    }
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_silent_vibration_title),
                    subtitle = stringResource(R.string.cooldown_silent_vibration_subtitle),
                    icon = Icons.Outlined.DoNotDisturbOn,
                    checked = options.vibrationMuted == VibrationOption.OFF,
                    shape = getExpressiveShape(4, 3, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                vibrationMuted =
                                    if (it) VibrationOption.OFF else VibrationOption.INHERIT
                            )
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- How much quieter: an editable number, with Android's value as a described preset. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_volume_title),
            icon = Icons.Outlined.Speed,
            subtitle = stringResource(R.string.cooldown_volume_subtitle),
        ) {
            Column {
                NumberField(
                    label = stringResource(R.string.cooldown_volume_label),
                    option = options.attenuationFactor?.let { CooldownNumberText.percent(it) },
                    fallback = CooldownNumberText.percent(CooldownNumberText.defaultFactor),
                    parse = { text -> CooldownNumberText.toFactor(text) },
                    describe = { factor -> CooldownOptionBounds.describeFactor(factor) },
                    problemText = stringResource(R.string.cooldown_error_percent),
                    onValue = { factor -> onUpdate(options.copy(attenuationFactor = factor)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_volume_preset_aosp),
                    description = stringResource(R.string.cooldown_volume_preset_aosp_desc),
                    selected = options.attenuationFactor == null,
                    onClick = { onUpdate(options.copy(attenuationFactor = null)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_volume_preset_50),
                    description = stringResource(R.string.cooldown_volume_preset_50_desc),
                    selected = options.attenuationFactor == 0.5f,
                    onClick = { onUpdate(options.copy(attenuationFactor = 0.5f)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Timing: seconds, with the seconds spelled out beside the field. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_timing),
            icon = Icons.Outlined.Timer,
        ) {
            Column {
                SettingsLabel(
                    title = stringResource(R.string.cooldown_timing_quieter_title),
                    subtitle = stringResource(R.string.cooldown_timing_quieter_subtitle),
                )
                NumberField(
                    label = stringResource(R.string.cooldown_timing_seconds_label),
                    option = options.timeoutPoliteMs?.let { CooldownNumberText.seconds(it) },
                    fallback = CooldownNumberText.seconds(CooldownNumberText.defaultTimeoutPoliteMs),
                    parse = { text -> CooldownNumberText.toMillis(text) },
                    describe = { ms -> CooldownOptionBounds.describeMs(ms) },
                    problemText = stringResource(R.string.cooldown_error_seconds),
                    onValue = { ms -> onUpdate(options.copy(timeoutPoliteMs = ms)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_aosp_quieter),
                    description = stringResource(R.string.cooldown_timing_preset_aosp_quieter_desc),
                    selected = options.timeoutPoliteMs == null,
                    onClick = { onUpdate(options.copy(timeoutPoliteMs = null)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_30s),
                    description = stringResource(R.string.cooldown_timing_preset_30s_desc),
                    selected = options.timeoutPoliteMs == 30_000L,
                    onClick = { onUpdate(options.copy(timeoutPoliteMs = 30_000L)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_120s),
                    description = stringResource(R.string.cooldown_timing_preset_120s_desc),
                    selected = options.timeoutPoliteMs == 120_000L,
                    onClick = { onUpdate(options.copy(timeoutPoliteMs = 120_000L)) },
                )

                Spacer(Modifier.height(16.dp))

                SettingsLabel(
                    title = stringResource(R.string.cooldown_timing_silent_title),
                    subtitle = stringResource(R.string.cooldown_timing_silent_subtitle),
                )
                NumberField(
                    label = stringResource(R.string.cooldown_timing_seconds_label),
                    option = options.timeoutMutedMs?.let { CooldownNumberText.seconds(it) },
                    fallback = CooldownNumberText.seconds(CooldownNumberText.defaultTimeoutMutedMs),
                    parse = { text -> CooldownNumberText.toMillis(text) },
                    describe = { ms -> CooldownOptionBounds.describeMs(ms) },
                    problemText = stringResource(R.string.cooldown_error_seconds),
                    onValue = { ms -> onUpdate(options.copy(timeoutMutedMs = ms)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_aosp_silent),
                    description = stringResource(R.string.cooldown_timing_preset_aosp_silent_desc),
                    selected = options.timeoutMutedMs == null,
                    onClick = { onUpdate(options.copy(timeoutMutedMs = null)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_5s),
                    description = stringResource(R.string.cooldown_timing_preset_5s_desc),
                    selected = options.timeoutMutedMs == 5_000L,
                    onClick = { onUpdate(options.copy(timeoutMutedMs = 5_000L)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_30s_silent),
                    description = stringResource(R.string.cooldown_timing_preset_30s_silent_desc),
                    selected = options.timeoutMutedMs == 30_000L,
                    onClick = { onUpdate(options.copy(timeoutMutedMs = 30_000L)) },
                )

                Spacer(Modifier.height(16.dp))

                SettingsLabel(
                    title = stringResource(R.string.cooldown_timing_reset_title),
                    subtitle = stringResource(R.string.cooldown_timing_reset_subtitle),
                )
                NumberField(
                    label = stringResource(R.string.cooldown_timing_count_label),
                    option = options.counterReset?.let { CooldownNumberText.count(it) },
                    fallback = CooldownNumberText.count(CooldownNumberText.defaultCounterReset),
                    parse = { text -> CooldownNumberText.toCount(text) },
                    describe = { count -> count.toString() },
                    problemText = stringResource(R.string.cooldown_error_count),
                    onValue = { count -> onUpdate(options.copy(counterReset = count)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_aosp_reset),
                    description = stringResource(R.string.cooldown_timing_preset_aosp_reset_desc),
                    selected = options.counterReset == null,
                    onClick = { onUpdate(options.copy(counterReset = null)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_5),
                    description = stringResource(R.string.cooldown_timing_preset_5_desc),
                    selected = options.counterReset == 5,
                    onClick = { onUpdate(options.copy(counterReset = 5)) },
                )
                Spacer(Modifier.height(8.dp))
                PresetCard(
                    title = stringResource(R.string.cooldown_timing_preset_20),
                    description = stringResource(R.string.cooldown_timing_preset_20_desc),
                    selected = options.counterReset == 20,
                    onClick = { onUpdate(options.copy(counterReset = 20)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- The island: no channel write, so nothing gates it -- and its realisation is visible, so
        // the copy says what disappears. The cost is asymmetric with heads-up (nothing is written,
        // nothing is locked), which is why this section carries no warning card. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_island),
            icon = Icons.Outlined.NotificationsActive,
            subtitle = stringResource(R.string.cooldown_island_free_note),
        ) {
            Column {
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_island_polite_title),
                    subtitle = stringResource(R.string.cooldown_island_polite_subtitle),
                    icon = Icons.Outlined.NotificationsActive,
                    checked = options.islandPolite == IslandOption.SUPPRESS,
                    shape = getExpressiveShape(2, 0, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                islandPolite =
                                    if (it) IslandOption.SUPPRESS else IslandOption.INHERIT
                            )
                        )
                    }
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_island_muted_title),
                    subtitle = stringResource(R.string.cooldown_island_muted_subtitle),
                    icon = Icons.Outlined.NotificationsActive,
                    checked = options.islandMuted == IslandOption.SUPPRESS,
                    shape = getExpressiveShape(2, 1, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                islandMuted =
                                    if (it) IslandOption.SUPPRESS else IslandOption.INHERIT
                            )
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Heads-up: the honest cost first, then a normal toggle. No gate: the write is
        // recoverable (transaction 43 releases the lock, and the restore sends it), so a separate
        // acknowledgement that blocks the row would be a trap rather than a safeguard. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_heads_up),
            icon = Icons.Filled.Warning,
        ) {
            Column {
                if (!noteRead) {
                    WarningCard(
                        title = stringResource(R.string.cooldown_heads_up_cost_title),
                        description = stringResource(R.string.cooldown_heads_up_cost_message),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = {},
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_heads_up_quieter_title),
                    subtitle = stringResource(R.string.cooldown_heads_up_quieter_subtitle),
                    icon = Icons.Filled.Warning,
                    checked = options.headsUpPolite == HeadsUpOption.SUPPRESS,
                    shape = getExpressiveShape(3, 0, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                headsUpPolite =
                                    if (it) HeadsUpOption.SUPPRESS else HeadsUpOption.INHERIT
                            )
                        )
                    }
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_heads_up_silent_title),
                    subtitle = stringResource(R.string.cooldown_heads_up_silent_subtitle),
                    icon = Icons.Filled.Warning,
                    checked = options.headsUpMuted == HeadsUpOption.SUPPRESS,
                    shape = getExpressiveShape(3, 1, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                headsUpMuted =
                                    if (it) HeadsUpOption.SUPPRESS else HeadsUpOption.INHERIT
                            )
                        )
                    }
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_heads_up_note_read_title),
                    subtitle = stringResource(R.string.cooldown_heads_up_note_read_subtitle),
                    icon = Icons.Filled.Warning,
                    checked = noteRead,
                    shape = getExpressiveShape(3, 2, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(options.copy(headsUpSuppressionAcknowledged = it))
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Visibility: stated, not offered. See the sentence. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_visibility),
            icon = Icons.Outlined.Visibility,
        ) {
            Column {
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_visibility_keep_title),
                    subtitle = stringResource(R.string.cooldown_visibility_keep_subtitle),
                    icon = Icons.Outlined.Visibility,
                    checked = true,
                    enabled = false,
                    shape = getExpressiveShape(2, 0, ShapeStyle.Large),
                    onCheckedChange = {}
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_visibility_hide_title),
                    subtitle = runtime.visibilityUnsupportedNote(),
                    icon = Icons.Outlined.Visibility,
                    checked = false,
                    enabled = false,
                    shape = getExpressiveShape(2, 1, ShapeStyle.Large),
                    onCheckedChange = {}
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cooldown_visibility_deferred_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Exemptions: four categories, always on, and the per-app switch that is the fifth ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_exempt),
            icon = Icons.Outlined.DoNotDisturbOn,
        ) {
            Text(
                text = stringResource(R.string.cooldown_exempt_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- The two-writer choice, which is a promise rather than a switch (spec 1.1) ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_second_writer_title),
            icon = Icons.Filled.Notifications,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.cooldown_second_writer_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cooldown_second_writer_option_a),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cooldown_second_writer_option_b),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A row of the stated sequence: what happens, in one line, with nothing to configure. */
@Composable
private fun SequenceRow(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** A label above a field, for the sub-options that are not cards of their own. */
@Composable
private fun SettingsLabel(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A shortcut for a described value, in the host's own "one of these" card. */
@Composable
private fun PresetCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    EngineOptionCard(
        title = title,
        description = description,
        isSelected = selected,
        onClick = onClick,
    )
}

/**
 * A number the user can actually change.
 *
 * The text is held locally so that a half-typed entry is not thrown away, and the option is written
 * only when the entry parses **and** is in range: [parse] answers null otherwise, the screen shows
 * [problemText] and stores nothing rather than storing a clamped value the user did not choose.
 * [fallback] is what an unset option means, so a field never hides a value that is in force.
 */
@Composable
private fun <T> NumberField(
    label: String,
    option: String?,
    fallback: String,
    parse: (String) -> T?,
    describe: (T) -> String,
    problemText: String,
    onValue: (T) -> Unit,
) {
    val shown = option ?: fallback
    var text by remember(shown) { mutableStateOf(shown) }
    val parsed = remember(text) { parse(text) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = getExpressiveShape(1, 0, ShapeStyle.Large),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { entered ->
                    text = entered
                    // Only a value that parses and is in range is stored. The bounds live in
                    // `CooldownOptionBounds`, shared with the engine's own sanitiser, so the screen
                    // cannot accept something the write path would refuse.
                    parse(entered)?.let(onValue)
                },
                label = { Text(label) },
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        text = parsed?.let { stringResource(R.string.cooldown_value_is, describe(it)) }
                            ?: problemText,
                        color = if (parsed == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
