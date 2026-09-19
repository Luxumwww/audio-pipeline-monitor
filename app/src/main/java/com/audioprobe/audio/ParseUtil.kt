package com.audioprobe.audio

/**
 * dumpsys prints the same numeric column in several shapes depending on the service and
 * the Android release: `0x3`, `00000003`, plain `3`, and with a `0x` prefix inside a
 * captured group.
 *
 * Kotlin's [String.toIntOrNull] with a radix **rejects** the `0x` prefix, so
 * `"0x3".toIntOrNull(16)` returns null. Falling back to a default there silently turns a
 * channel mask into 0, which is worse than failing, so every hex column goes through
 * this helper.
 */
internal fun parseHexInt(raw: String?): Int? {
    val text = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
    if (text.isEmpty()) return null
    return text.toIntOrNull(16)
}

internal fun parseHexLong(raw: String?): Long? {
    val text = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
    if (text.isEmpty()) return null
    return text.toLongOrNull(16)
}

/** Decimal columns (`SRate`, `pid`, frame counts) - never ambiguous. */
internal fun parseDecInt(raw: String?): Int? = raw?.trim()?.toIntOrNull()
