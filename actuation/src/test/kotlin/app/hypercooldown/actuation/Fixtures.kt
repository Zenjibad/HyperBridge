package app.hypercooldown.actuation

/**
 * Builders shared by the actuation tests.
 *
 * The defaults describe a chatty chat app's channel: `IMPORTANCE_HIGH`, audible, with an explicit
 * vibration pattern of the legacy `[timing, amplitude, ...]` shape and full (255) amplitudes.
 * `audioUsage = 5` is `AudioAttributes.USAGE_NOTIFICATION` and `audioContentType = 4` is
 * `CONTENT_TYPE_SONIFICATION`.
 */
fun channelFields(
    channelId: String = "messages",
    importance: Int = 4,
    soundUri: String? = "content://settings/system/notification_sound",
    audioUsage: Int = 5,
    audioContentType: Int = 4,
    audioFlags: Int = 0,
    vibrationPattern: List<Long>? = listOf(0L, 255L, 200L, 255L),
    vibrationEffectBytes: String? = null,
    vibrationEnabled: Boolean = true,
): ChannelFields = ChannelFields(
    channelId = channelId,
    importance = importance,
    soundUri = soundUri,
    audioUsage = audioUsage,
    audioContentType = audioContentType,
    audioFlags = audioFlags,
    vibrationPattern = vibrationPattern,
    vibrationEffectBytes = vibrationEffectBytes,
    vibrationEnabled = vibrationEnabled,
)

fun snapshot(
    fields: ChannelFields = channelFields(),
    channelBytes: String? = "AQ==",
): ChannelSnapshot = ChannelSnapshot(fields = fields, channelBytes = channelBytes)

fun target(
    key: String = "0:com.example.chat",
    packageName: String = "com.example.chat",
    uid: Int = 10123,
    userId: Int = 0,
): ChannelTarget = ChannelTarget(key, packageName, uid, userId)
