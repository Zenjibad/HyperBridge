package com.d4viddf.hyperbridge.cooldown.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hypercooldown.actuation.CooldownOptions
import com.d4viddf.hyperbridge.R

/**
 * One app's cooldown overrides, in the host's own "use the global default" shape.
 *
 * The override idiom is copied rather than invented (`ui/components/Dialogs.kt:356-390`,
 * `AppearanceSettingsContent`): a checkbox decides between "this app inherits" and "this app has its
 * own row", and clearing writes the all-null value, which the codec stores as absent keys -- which is
 * what inherit means. Cooldown's sentinel is `enabled` alone, exactly as the island's is `isFloat`
 * alone (`IslandSettingsControl.kt:43`), and `CooldownOptions.isOverridden` states it in one place.
 */
@Composable
fun CooldownAppSettingsContent(
    appCooldown: CooldownOptions,
    globalCooldown: CooldownOptions,
    onUpdate: (CooldownOptions) -> Unit,
    activeDesc: String,
    inactiveDesc: String,
) {
    val isUsingGlobal = appCooldown.enabled == null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    // Inheriting copies the global row in so the user sees the values they are about
                    // to take on, and clearing writes the all-null row. `CooldownOptions()` is the
                    // all-null value: every field is nullable by construction.
                    if (isUsingGlobal) onUpdate(globalCooldown) else onUpdate(CooldownOptions())
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isUsingGlobal, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.cooldown_use_global),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (!isUsingGlobal) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.cooldown_app_override_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            CooldownOptionsEditor(options = appCooldown, onUpdate = onUpdate)
        } else {
            Text(
                text = stringResource(R.string.cooldown_app_defaults_desc),
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp),
            )
        }
    }
}
