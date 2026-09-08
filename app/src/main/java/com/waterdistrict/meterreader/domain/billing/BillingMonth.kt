package com.waterdistrict.meterreader.domain.billing

import java.time.Instant
import java.time.ZoneId

/**
 * The "MMM yyyy" month strings used as keys throughout the system —
 * `billingHistory[].month` and `assignedForReading` on the Firestore
 * document, and `readings.billing_month` locally.
 *
 * Built from a fixed table rather than a locale-aware formatter. The admin
 * console does the same (see `currentMonthStr` in lib/billing.ts): a
 * locale-dependent abbreviation would silently disagree between the two —
 * several locales render September as "Sept" rather than "SEP", so an
 * assignment written by a browser in one locale would never match the query
 * the phone runs, and the reader would simply download an empty route.
 */
object BillingMonth {

    private val MONTHS = arrayOf(
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    )

    /** e.g. "AUG 2026" for the given instant, in the device's own time zone. */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return "${MONTHS[date.monthValue - 1]} ${date.year}"
    }

    /** e.g. "AUG 2026" for right now. */
    fun current(zone: ZoneId = ZoneId.systemDefault()): String = of(System.currentTimeMillis(), zone)

    /**
     * Firestore document ID for a month's bill: "AUG 2026" -> "2026-08".
     *
     * Sortable, so the sub-collection's own key order is chronological and the
     * two most recent bills can be read without an index. Also stable, so a
     * corrected reading overwrites that month's bill rather than appending a
     * second one.
     */
    fun documentKey(monthStr: String): String {
        val parts = monthStr.trim().split(" ")
        val monthIndex = if (parts.size == 2) MONTHS.indexOf(parts[0].uppercase()) else -1
        val year = parts.getOrNull(1)?.toIntOrNull()
        if (monthIndex < 0 || year == null) {
            // Unparseable months would otherwise all collide on one document ID
            // and silently overwrite each other.
            return "invalid-" + monthStr.replace(Regex("[^A-Za-z0-9]"), "-")
        }
        return "%04d-%02d".format(year, monthIndex + 1)
    }

    /**
     * Sortable key for a month string, so history can be ordered by month
     * rather than by position in the array. Firestore preserves array order,
     * but that order is only chronological by accident: a corrected reading is
     * appended at the end, and the admin's XLSX importer writes rows in
     * whatever order the sheet had. Returns -1 for anything unparseable so
     * malformed entries sort last rather than silently posing as January.
     */
    fun sortKey(monthStr: String?): Int {
        val parts = monthStr?.trim()?.split(" ") ?: return -1
        if (parts.size != 2) return -1
        val monthIndex = MONTHS.indexOf(parts[0].uppercase())
        val year = parts[1].toIntOrNull() ?: return -1
        if (monthIndex < 0) return -1
        return year * 12 + monthIndex
    }
}
