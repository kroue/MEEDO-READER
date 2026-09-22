package com.waterdistrict.meterreader.domain.billing

import java.util.Calendar
import java.util.TimeZone

/**
 * When a bill has to be paid by.
 *
 * Each barangay pays on its own fixed day of the month, so collections are
 * spread across the second half of the month rather than landing on the
 * counter at once. A bill is due on its barangay's next such day: this month's
 * if it hasn't passed when the bill is issued, otherwise next month's. A
 * barangay without a set day keeps fifteen days after billing.
 *
 * This decides only the pay-by date on the bill. The surcharge, the extension
 * fee and disconnection still run on the fifteen-day grace period in
 * [WaterRateConfig.gracePeriodDays], counted from when the balance went from
 * zero to owing.
 *
 * The admin console computes the same thing in lib/dueDates.ts, and the two
 * must agree: a bill issued at the counter and one issued at the meter have to
 * name the same day. DueDatesTest mirrors dueDates.test.ts for that reason.
 */
object DueDates {

    /** The day of the month each barangay's bills fall due. */
    val BARANGAY_DUE_DAYS: Map<String, Int> = mapOf(
        "BO-OT" to 17,
        "CG" to 19,
        "KABATANGAN" to 21,
        "KATUTUNGAN" to 23,
        "PAGALONGAN" to 25,
        "MILAYA" to 26,
    )

    /** For a barangay with no set day: days after billing, as before. */
    const val DEFAULT_DUE_AFTER_DAYS = 15

    private const val MS_PER_DAY = 86_400_000L

    /**
     * The office's calendar. The Philippines is UTC+8 all year, so a fixed zone
     * is exact — and it keeps a phone whose clock is set to another zone from
     * putting a bill due on a different day.
     */
    private val OFFICE_ZONE: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    /** Spellings the same barangay turns up under — the code, or its full name. */
    private val ALIASES: Map<String, String> = mapOf(
        "BOOT" to "BO-OT",
        "CG" to "CG",
        "CEBUANOGROUP" to "CG",
        "KABATANGAN" to "KABATANGAN",
        "KATUTUNGAN" to "KATUTUNGAN",
        "PAGALONGAN" to "PAGALONGAN",
        "MILAYA" to "MILAYA",
    )

    /** The barangay's due day, or null if it has none. */
    fun dueDayFor(barangay: String?): Int? {
        val key = barangay.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        return ALIASES[key]?.let { BARANGAY_DUE_DAYS[it] }
    }

    /**
     * When a bill issued at [billedAtMillis] must be paid by: the last moment of
     * its due day, office time, so "on or before the 17th" holds for all of the
     * 17th. A bill issued on the due day itself is due that same day.
     */
    fun dueDateFor(
        barangay: String?,
        billedAtMillis: Long,
        defaultDays: Int = DEFAULT_DUE_AFTER_DAYS
    ): Long {
        val dueDay = dueDayFor(barangay)
            ?: return billedAtMillis + defaultDays * MS_PER_DAY

        val cal = Calendar.getInstance(OFFICE_ZONE).apply { timeInMillis = billedAtMillis }
        if (cal.get(Calendar.DAY_OF_MONTH) > dueDay) {
            // Go to the first of the month before adding one, so a day like the
            // 31st can't overflow into the month after next.
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, 1)
        }
        // Every due day is under 29 today, but a later one shouldn't overflow a
        // short month into the next.
        cal.set(Calendar.DAY_OF_MONTH, minOf(dueDay, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}
