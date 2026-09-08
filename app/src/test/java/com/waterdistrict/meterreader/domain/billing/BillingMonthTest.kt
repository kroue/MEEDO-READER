package com.waterdistrict.meterreader.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The month string is a join key: the phone queries
 * `assignedForReading == "SEP 2026"` against whatever the admin console wrote.
 * A mismatch produces no error on either side — the reader just downloads an
 * empty route — so the format is pinned here.
 */
class BillingMonthTest {

    private val manila = ZoneId.of("Asia/Manila")

    private fun millisFor(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, manila).toInstant().toEpochMilli()

    @Test
    fun `formats as three-letter uppercase month and four-digit year`() {
        assertEquals("AUG 2026", BillingMonth.of(millisFor(2026, 8, 15), manila))
        assertEquals("JAN 2026", BillingMonth.of(millisFor(2026, 1, 1), manila))
        assertEquals("DEC 2025", BillingMonth.of(millisFor(2025, 12, 31), manila))
    }

    @Test
    fun `september is SEP, never SEPT`() {
        // The specific bug this guards: several locales' ICU data abbreviate
        // September as "Sept". A browser-locale-dependent month string in the
        // admin console would silently stop matching the phone's Locale.US
        // format for one month of the year.
        assertEquals("SEP 2026", BillingMonth.of(millisFor(2026, 9, 1), manila))
    }

    @Test
    fun `sort key orders months chronologically across year boundaries`() {
        val dec2025 = BillingMonth.sortKey("DEC 2025")
        val jan2026 = BillingMonth.sortKey("JAN 2026")
        val feb2026 = BillingMonth.sortKey("FEB 2026")
        assertTrue(dec2025 < jan2026)
        assertTrue(jan2026 < feb2026)
    }

    @Test
    fun `sort key is case insensitive and tolerates surrounding whitespace`() {
        assertEquals(BillingMonth.sortKey("AUG 2026"), BillingMonth.sortKey(" aug 2026 "))
    }

    @Test
    fun `unparseable months sort last rather than posing as January`() {
        // Returning 0 for junk would have sorted a malformed entry ahead of
        // every real month, making it the "previous reading".
        assertEquals(-1, BillingMonth.sortKey("not a month"))
        assertEquals(-1, BillingMonth.sortKey(""))
        assertEquals(-1, BillingMonth.sortKey(null))
        assertEquals(-1, BillingMonth.sortKey("XXX 2026"))
        assertTrue(BillingMonth.sortKey("not a month") < BillingMonth.sortKey("JAN 1970"))
    }
}
