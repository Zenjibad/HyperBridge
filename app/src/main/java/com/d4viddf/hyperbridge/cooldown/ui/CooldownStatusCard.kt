package com.d4viddf.hyperbridge.cooldown.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.cooldown.act.CooldownRuntime
import com.d4viddf.hyperbridge.cooldown.sensor.SecondWriterProbe
import com.d4viddf.hyperbridge.ui.components.ListOptionCard
import com.d4viddf.hyperbridge.ui.components.WarningCard
import com.d4viddf.hyperbridge.ui.screens.theme.ShapeStyle
import com.d4viddf.hyperbridge.ui.screens.theme.getExpressiveShape

/**
 * What a user can act on: whether a second writer is armed, which channels this app is currently
 * damping, and the lock residue the restores cannot clear (spec §1.1, §5.7).
 *
 * Two of the three are deliberately hedged in words rather than presented as measurements:
 *
 * - the second-writer verdict comes from the platform's `enabled_notification_listeners` mirror,
 *   which lags the bind by about 3 s on this device, so it is a reading and not a guarantee;
 * - the residue names the mask in **hexadecimal** and says **at least**, because the platform never
 *   hands the mask back to a plain listener on this ROM and `PreferencesHelper` begins from whatever
 *   lock bits the channel already carried. Never "exactly", never "pristine", never "clean".
 */
@Composable
fun CooldownStatusCard(runtime: CooldownRuntime, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Probed once per composition of this screen, not per row: the probe reads a settings string and
    // the package manager, and the verdict only has to be as fresh as the screen the user is looking
    // at. The *write* path probes on every attempt, which is where freshness matters.
    val armed = remember { SecondWriterProbe.armed(context) }
    val rows = remember { runtime.statusRows() }

    Column(modifier = modifier.fillMaxWidth()) {
        WarningCard(
            title = stringResource(R.string.cooldown_second_writer_title),
            description = when (armed) {
                true -> stringResource(R.string.cooldown_second_writer_armed_message)
                false -> stringResource(R.string.cooldown_second_writer_clear_message)
                null -> stringResource(R.string.cooldown_second_writer_unknown_message)
            },
            color = if (armed == true) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            onClick = {}
        )

        Spacer(Modifier.height(8.dp))

        ListOptionCard(
            title = stringResource(R.string.cooldown_status_title),
            subtitle = runtime.transportNote(),
            icon = Icons.Filled.Notifications,
            shape = getExpressiveShape(rows.size + 2, 0, ShapeStyle.Large),
            onClick = {}
        )

        rows.forEachIndexed { index, row ->
            Spacer(Modifier.height(2.dp))
            ListOptionCard(
                title = row.packageName,
                subtitle = stringResource(
                    R.string.cooldown_row_subtitle,
                    row.installed?.name ?: stringResource(R.string.cooldown_row_no_engine),
                    row.written.toString()
                ),
                icon = Icons.Filled.Notifications,
                shape = getExpressiveShape(rows.size + 2, index + 1, ShapeStyle.Large),
                onClick = {}
            )
        }

        Spacer(Modifier.height(2.dp))

        ListOptionCard(
            title = stringResource(R.string.cooldown_residue_title),
            // `restoreVerdict` is `RestoreGuard.verdict()`'s own sentence: derived, a lower bound, and
            // it names the mask in hexadecimal. It is shown verbatim rather than summarised here, so
            // the screen and the log cannot disagree about how sure this app is.
            subtitle = runtime.restoreVerdict,
            icon = Icons.Filled.Notifications,
            shape = getExpressiveShape(rows.size + 2, rows.size + 1, ShapeStyle.Large),
            onClick = {}
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.cooldown_status_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
