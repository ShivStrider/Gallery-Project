package com.facealbum.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Formats an epoch-millis timestamp into a short, humane label the user can
 * skim ("Today", "Yesterday", "Aug 12", "Jun '23"). Locale-aware.
 */
fun formatFriendlyDate(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return "Unknown"

    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val thenCal = Calendar.getInstance().apply { timeInMillis = epochMillis }

    val sameYear = nowCal.get(Calendar.YEAR) == thenCal.get(Calendar.YEAR)
    val sameDay = sameYear &&
        nowCal.get(Calendar.DAY_OF_YEAR) == thenCal.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"

    nowCal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = nowCal.get(Calendar.YEAR) == thenCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == thenCal.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Yesterday"

    val pattern = if (sameYear) "MMM d" else "MMM ''yy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
}
