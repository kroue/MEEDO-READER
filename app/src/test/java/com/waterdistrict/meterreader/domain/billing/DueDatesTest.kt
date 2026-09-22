package com.waterdistrict.meterreader.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Mirrors lib/dueDates.test.ts in the admin console case for case. A bill from
 * the counter and a bill from the meter have to fall due on the same day.
 */
class DueDatesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** An instant given as Philippine wall-clock time. */
    private fun ph(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute)
            add(Calendar.HOUR_OF_DAY, -8)
        }.timeInMillis

    /** The end of a day, Philippine time. */
    private fun endOfDayPh(year: Int, month: Int, day: Int): Long =
        ph(year, month, day, 23, 59) + 59_999L

    @Test
    fun `knows each barangay's day`() {
        assertEquals(17, DueDates.dueDayFor("BO-OT"))
        assertEquals(19, DueDates.dueDayFor("CG"))
        assertEquals(21, DueDates.dueDayFor("KABATANGAN"))
        assertEquals(23, DueDates.dueDayFor("KATUTUNGAN"))
        assertEquals(25, DueDates.dueDayFor("PAGALONGAN"))
        assertEquals(26, DueDates.dueDayFor("MILAYA"))
    }

    @Test
    fun `accepts the full name and loose spellings`() {
        assertEquals(19, DueDates.dueDayFor("Cebuano Group"))
        assertEquals(17, DueDates.dueDayFor("Bo-ot"))
        assertEquals(17, DueDates.dueDayFor(" boot "))
        assertEquals(26, DueDates.dueDayFor("milaya"))
    }

    @Test
    fun `has nothing for a barangay without a set day`() {
        assertNull(DueDates.dueDayFor("SALVACION"))
        assertNull(DueDates.dueDayFor("AMOYONG"))
        assertNull(DueDates.dueDayFor("DIOMIL"))
        assertNull(DueDates.dueDayFor(""))
        assertNull(DueDates.dueDayFor(null))
    }

    @Test
    fun `is this month's day when the bill is issued before it`() {
        assertEquals(endOfDayPh(2026, 9, 17), DueDates.dueDateFor("BO-OT", ph(2026, 9, 5)))
        assertEquals(endOfDayPh(2026, 9, 26), DueDates.dueDateFor("MILAYA", ph(2026, 9, 1)))
    }

    @Test
    fun `is still this month's day when the bill is issued on the day itself`() {
        assertEquals(endOfDayPh(2026, 9, 17), DueDates.dueDateFor("BO-OT", ph(2026, 9, 17, 16)))
    }

    @Test
    fun `rolls to next month's day once this month's has passed`() {
        assertEquals(endOfDayPh(2026, 10, 17), DueDates.dueDateFor("BO-OT", ph(2026, 9, 18)))
        assertEquals(endOfDayPh(2026, 10, 19), DueDates.dueDateFor("CG", ph(2026, 9, 21)))
    }

    @Test
    fun `rolls over the end of the year`() {
        assertEquals(endOfDayPh(2027, 1, 26), DueDates.dueDateFor("MILAYA", ph(2026, 12, 27)))
    }

    @Test
    fun `goes by the office's calendar, not the phone's clock`() {
        // 04:00 on 18 September in the Philippines is still the 17th in UTC —
        // but the office's 17th has passed, so the bill is due in October.
        val earlyMorning = ph(2026, 9, 18, 4)
        assertEquals(
            17,
            Calendar.getInstance(utc).apply { timeInMillis = earlyMorning }.get(Calendar.DAY_OF_MONTH)
        )
        assertEquals(endOfDayPh(2026, 10, 17), DueDates.dueDateFor("BO-OT", earlyMorning))
    }

    @Test
    fun `keeps fifteen days after billing for a barangay without a set day`() {
        val billed = ph(2026, 9, 5)
        assertEquals(billed + 15 * 86_400_000L, DueDates.dueDateFor("SALVACION", billed))
        assertEquals(billed + 15 * 86_400_000L, DueDates.dueDateFor(null, billed))
    }

    @Test
    fun `matches the console on the calculator's own test instant`() {
        // lib/billingCalculator.test.ts: NOW = 1_757_000_000_000 is 4 September
        // 2025, 23:33 in the Philippines, and a Bo-ot bill then is due at
        // Date.UTC(2025, 8, 17, 15, 59, 59, 999).
        assertEquals(1_758_124_799_999L, DueDates.dueDateFor("BO-OT", 1_757_000_000_000L))
    }
}
