package com.SkrinVex.OfoxMessenger.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateUtils {
    /**
     * Formats epoch millis into a classic, readable date-time string in the user's
     * current locale and device timezone.
     *
     * Example (ru_RU): "19 янв. 2026, 14:05"
     */
    fun formatTimestamp(
        epochMillis: Long,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (epochMillis <= 0L) return ""
        val formatter = DateTimeFormatter
            .ofPattern("d MMM yyyy, HH:mm", locale)
            .withZone(zoneId)
        return formatter.format(Instant.ofEpochMilli(epochMillis))
    }
}

