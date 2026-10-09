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
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * Every row offers the AOSP treatment first, so parity is the baseline and a deviation is a
 * deliberate act (spec 4.2). The two risky rows are separate rows and are never one switch with two
 * names, and the heads-up rows cannot be turned on until the warning beside them is acknowledged:
 * `CooldownPolicy.presentation` refuses an unacknowledged `SUPPRESS` and reports a note, and the rows
 * here refuse to let the user set one in the first place.
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
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

            Spacer(Modifier.height(16.dp))

            CooldownOptionsEditor(
                options = global,
                onUpdate = { updated -> scope.launch { preferences.updateCooldownGlobal(updated) } }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The whole option surface of Task 4, as rows. Shared with the per-app sheet, where the same options
 * are one app's overrides rather than the global row -- which is the host's own idiom, not a
 * cooldown invention (`AppearanceSettingsContent` shows the island's options in both places).
 */
@Composable
fun CooldownOptionsEditor(
    options: CooldownOptions,
    onUpdate: (CooldownOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The acknowledgement is read from the stored option rather than held in local state: the policy
    // layer is what refuses an unacknowledged suppress, so a screen that shadowed the option in
    // `remember` could show "acknowledged" while the writer still refused.
    val acknowledged = options.headsUpSuppressionAcknowledged == true
    val context = LocalContext.current
    val runtime = remember { CooldownRuntime.get(context.applicationContext) }

    Column(modifier = modifier.fillMaxWidth()) {
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

        // ---- Sound and vibration: one row per effect per state, never one row for two states ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_sound),
            icon = Icons.Outlined.VolumeOff
        ) {
            Column {
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_sound_polite_title),
                    subtitle = stringResource(R.string.cooldown_sound_polite_subtitle),
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
                    title = stringResource(R.string.cooldown_sound_muted_title),
                    subtitle = stringResource(R.string.cooldown_sound_muted_subtitle),
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
                    title = stringResource(R.string.cooldown_vibration_polite_title),
                    subtitle = stringResource(R.string.cooldown_vibration_polite_subtitle),
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
                    title = stringResource(R.string.cooldown_vibration_muted_title),
                    subtitle = stringResource(R.string.cooldown_vibration_muted_subtitle),
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

        // ---- The attenuation factor: a channel's sound is replaced by a scaled copy of itself ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_factor_title),
            icon = Icons.Outlined.Memory,
            subtitle = stringResource(R.string.cooldown_factor_subtitle)
        ) {
            ChoiceGroup(
                title = stringResource(R.string.cooldown_factor_choice_title),
                subtitle = stringResource(R.string.cooldown_factor_choice_subtitle),
                current = options.attenuationFactor,
                choices = listOf(
                    Choice(null, stringResource(R.string.cooldown_factor_aosp), stringResource(R.string.cooldown_factor_aosp_desc)),
                    Choice(0.5f, "50 %", stringResource(R.string.cooldown_factor_50_desc)),
                    Choice(0.1f, "10 %", stringResource(R.string.cooldown_factor_10_desc))
                ),
                onSelect = { onUpdate(options.copy(attenuationFactor = it)) }
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- Timing. AOSP's values are the defaults and are labelled as such ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_timing),
            icon = Icons.Outlined.Timer
        ) {
            Column {
                ChoiceGroup(
                    title = stringResource(R.string.cooldown_timing_polite_title),
                    subtitle = stringResource(R.string.cooldown_timing_polite_subtitle),
                    current = options.timeoutPoliteMs,
                    choices = listOf(
                        Choice(null, stringResource(R.string.cooldown_timing_aosp_60), stringResource(R.string.cooldown_timing_polite_60_desc)),
                        Choice(30_000L, "30 s", stringResource(R.string.cooldown_timing_polite_30_desc)),
                        Choice(120_000L, "120 s", stringResource(R.string.cooldown_timing_polite_120_desc))
                    ),
                    onSelect = { onUpdate(options.copy(timeoutPoliteMs = it)) }
                )
                Spacer(Modifier.height(12.dp))
                ChoiceGroup(
                    title = stringResource(R.string.cooldown_timing_muted_title),
                    subtitle = stringResource(R.string.cooldown_timing_muted_subtitle),
                    current = options.timeoutMutedMs,
                    choices = listOf(
                        Choice(null, stringResource(R.string.cooldown_timing_aosp_10), stringResource(R.string.cooldown_timing_muted_10_desc)),
                        Choice(5_000L, "5 s", stringResource(R.string.cooldown_timing_muted_5_desc)),
                        Choice(30_000L, "30 s", stringResource(R.string.cooldown_timing_muted_30_desc))
                    ),
                    onSelect = { onUpdate(options.copy(timeoutMutedMs = it)) }
                )
                Spacer(Modifier.height(12.dp))
                ChoiceGroup(
                    title = stringResource(R.string.cooldown_counter_title),
                    subtitle = stringResource(R.string.cooldown_counter_subtitle),
                    current = options.counterReset,
                    choices = listOf(
                        Choice(null, stringResource(R.string.cooldown_counter_aosp), stringResource(R.string.cooldown_counter_aosp_desc)),
                        Choice(5, "5", stringResource(R.string.cooldown_counter_5_desc)),
                        Choice(20, "20", stringResource(R.string.cooldown_counter_20_desc))
                    ),
                    onSelect = { onUpdate(options.copy(counterReset = it)) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- The island: free, so it is honoured without a gate and needs no warning ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_island),
            icon = Icons.Outlined.NotificationsActive,
            subtitle = stringResource(R.string.cooldown_island_free_note)
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

        // ---- Heads-up: a permanent, user-locked write, so the warning comes first ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_heads_up),
            icon = Icons.Filled.Warning
        ) {
            Column {
                WarningCard(
                    title = stringResource(R.string.cooldown_heads_up_warning_title),
                    description = stringResource(R.string.cooldown_heads_up_warning_message),
                    color = MaterialTheme.colorScheme.errorContainer,
                    onClick = {}
                )
                Spacer(Modifier.height(8.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_heads_up_ack_title),
                    subtitle = stringResource(R.string.cooldown_heads_up_ack_subtitle),
                    icon = Icons.Filled.Warning,
                    checked = acknowledged,
                    shape = getExpressiveShape(3, 0, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(options.copy(headsUpSuppressionAcknowledged = it))
                    }
                )
                Spacer(Modifier.height(2.dp))
                SettingsToggleCard(
                    title = stringResource(R.string.cooldown_heads_up_polite_title),
                    subtitle = stringResource(R.string.cooldown_heads_up_polite_subtitle),
                    icon = Icons.Filled.Warning,
                    checked = options.headsUpPolite == HeadsUpOption.SUPPRESS,
                    // Disabled until acknowledged, which is the same gate the policy enforces -- shown
                    // here rather than only enforced there, so the user is told why.
                    enabled = acknowledged,
                    shape = getExpressiveShape(3, 1, ShapeStyle.Large),
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
                    title = stringResource(R.string.cooldown_heads_up_muted_title),
                    subtitle = stringResource(R.string.cooldown_heads_up_muted_subtitle),
                    icon = Icons.Filled.Warning,
                    checked = options.headsUpMuted == HeadsUpOption.SUPPRESS,
                    enabled = acknowledged,
                    shape = getExpressiveShape(3, 2, ShapeStyle.Large),
                    onCheckedChange = {
                        onUpdate(
                            options.copy(
                                headsUpMuted =
                                    if (it) HeadsUpOption.SUPPRESS else HeadsUpOption.INHERIT
                            )
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Visibility: stated, not offered. See the sentence. ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_visibility),
            icon = Icons.Outlined.Visibility
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Exemptions: four categories, always on, and the per-app switch that is the fifth ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_section_exemptions),
            icon = Icons.Outlined.DoNotDisturbOn
        ) {
            Text(
                text = stringResource(R.string.cooldown_exemptions_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- The two-writer choice, which is a promise rather than a switch (spec 1.1) ----
        ExpandableSettingCard(
            title = stringResource(R.string.cooldown_second_writer_title),
            icon = Icons.Filled.Notifications
        ) {
            Column {
                Text(
                    text = stringResource(R.string.cooldown_second_writer_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cooldown_second_writer_option_a),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cooldown_second_writer_option_b),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class Choice<T>(val value: T, val title: String, val description: String)

/**
 * A radio group out of the host's own `EngineOptionCard`, which is what it already uses for
 * "exactly one of these" (`EngineSettingsScreen`). `null` is always an option where the option set
 * is nullable, and it always means the same thing: use the AOSP value.
 */
@Composable
private fun <T> ChoiceGroup(
    title: String,
    subtitle: String,
    current: T,
    choices: List<Choice<T>>,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        choices.forEachIndexed { index, choice ->
            EngineOptionCard(
                title = choice.title,
                description = choice.description,
                isSelected = choice.value == current,
                onClick = { onSelect(choice.value) }
            )
            if (index != choices.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}
