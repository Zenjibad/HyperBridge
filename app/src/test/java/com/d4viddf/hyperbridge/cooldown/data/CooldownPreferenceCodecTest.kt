package com.d4viddf.hyperbridge.cooldown.data

import app.hypercooldown.actuation.CooldownOptions
import app.hypercooldown.actuation.HeadsUpOption
import app.hypercooldown.actuation.IslandOption
import app.hypercooldown.actuation.SoundOption
import app.hypercooldown.actuation.VisibilityOption
import com.d4viddf.hyperbridge.data.db.SettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownPreferenceCodecTest {

    @Test
    fun `app keys are raw literals in the host's own shape`() {
        // The host writes every per-app key as a raw literal outside SettingsKeys
        // (AppPreferences.kt:268-282), and the cooldown group copies that, deliberately.
        assertEquals("cooldown_com.example.chat_enabled", CooldownPreferenceKeys.app("com.example.chat", "enabled"))
        assertEquals(
            "cooldown_com.example.chat_timeout_polite_ms",
            CooldownPreferenceKeys.app("com.example.chat", "timeout_polite_ms"),
        )
    }

    @Test
    fun `global keys are constants, exactly as the island group does it`() {
        assertEquals("cooldown_global_enabled", SettingsKeys.COOLDOWN_GLOBAL_ENABLED)
        assertEquals("cooldown_global_island_muted", SettingsKeys.COOLDOWN_GLOBAL_ISLAND_MUTED)
        assertEquals("cooldown_global_owned_packages", SettingsKeys.COOLDOWN_GLOBAL_OWNED_PACKAGES)
    }

    @Test
    fun `only the fields that are set are encoded, so absence means inherit`() {
        val encoded = CooldownPreferenceCodec.encode(
            CooldownOptions(enabled = true, soundMuted = SoundOption.SILENT, timeoutPoliteMs = 30_000L),
        )
        assertEquals("true", encoded["enabled"])
        assertEquals("SILENT", encoded["sound_muted"])
        assertEquals("30000", encoded["timeout_polite_ms"])
        assertFalse(encoded.containsKey("sound_polite"))
        assertFalse(encoded.containsKey("island_muted"))
        assertFalse(encoded.containsKey("heads_up_suppression_acknowledged"))
    }

    @Test
    fun `a write of null means "remove the key", which is the host's inherit semantics`() {
        // updateAppIslandConfig is `if (x != null) save(...) else remove(...)` (AppPreferences.kt:276-282).
        // A codec that stored the string "null" would break inherit silently.
        val before = CooldownPreferenceCodec.encode(CooldownOptions(soundMuted = SoundOption.SILENT))
        val after = CooldownPreferenceCodec.encode(CooldownOptions())
        assertTrue(before.containsKey("sound_muted"))
        assertTrue(after.isEmpty())
    }

    @Test
    fun `decode reads back exactly what encode wrote`() {
        val options = CooldownOptions(
            enabled = true,
            soundPolite = SoundOption.SILENT,
            headsUpMuted = HeadsUpOption.SUPPRESS,
            headsUpSuppressionAcknowledged = true,
            islandPolite = IslandOption.SUPPRESS,
            mutedVisibility = VisibilityOption.SHOWN,
            timeoutMutedMs = 12_000L,
            counterReset = 7,
            attenuationFactor = 0.25f,
            ownedPackages = setOf("com.example.chat", "com.example.mail"),
        )
        assertEquals(options, CooldownPreferenceCodec.decode(CooldownPreferenceCodec.encode(options)))
    }

    @Test
    fun `a corrupt or unparsable value reads as absent rather than as a wrong value`() {
        val decoded = CooldownPreferenceCodec.decode(
            mapOf(
                "enabled" to "yes",
                "sound_muted" to "LOUDER",
                "timeout_polite_ms" to "soon",
                "attenuation_factor" to "0,3",
            ),
        )
        assertEquals(null, decoded.enabled)
        assertEquals(null, decoded.soundMuted)
        assertEquals(null, decoded.timeoutPoliteMs)
        assertEquals(null, decoded.attenuationFactor)
    }

    @Test
    fun `the acknowledgement flag survives as its own key, so it is never inferred`() {
        val decoded = CooldownPreferenceCodec.decode(mapOf("heads_up_suppression_acknowledged" to "true"))
        assertEquals(true, decoded.headsUpSuppressionAcknowledged)
        val default = CooldownPreferenceCodec.decode(mapOf("heads_up_muted" to "SUPPRESS"))
        assertEquals(null, default.headsUpSuppressionAcknowledged)
    }

    @Test
    fun `every field of CooldownOptions has a key, so a new option cannot silently fail to persist`() {
        // The data class is the source of truth: a field added by a later task and forgotten here would
        // save nothing and read back as null — an option that looks wired in the UI and is not.
        val declared = CooldownOptions::class.java.declaredFields
            .map { it.name }
            .map { camel -> camel.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase() }
            .toSet()
        assertEquals(declared, CooldownPreferenceCodec.FIELDS.toSet())
    }

    @Test
    fun `every global key the codec builds is a SettingsKeys constant`() {
        // The constants exist to be greppable, and a per-app key built by string interpolation cannot
        // drift from a constant the way `COOLDOWN_GLOBAL_RELAY_DURING_COOLDOWN` once did (the plan's
        // own Step 5 block omitted it). Values, not names, because both spellings are in use.
        val constants = SettingsKeys::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(null) as? String }
            .toSet()
        CooldownPreferenceCodec.FIELDS.forEach { field ->
            val global = CooldownPreferenceKeys.global(field)
            assertNotNull("no SettingsKeys constant for $global", constants.firstOrNull { it == global })
        }
    }
}
