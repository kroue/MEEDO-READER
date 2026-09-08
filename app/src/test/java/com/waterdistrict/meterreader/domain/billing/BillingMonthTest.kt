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
    fun `document key is zero-padded and sorts chronologically as a string`() {
        // The bill's Firestore document ID. Sortable so the sub-collection's own
        // key order is chronological, and stable so a corrected reading
        // overwrites that month rather than appending a second bill.
        assertEquals("2026-08", BillingMonth.documentKey("AUG 2026"))
        assertEquals("2026-01", BillingMonth.documentKey("JAN 2026"))
        assertEquals("2025-12", BillingMonth.documentKey("DEC 2025"))
        assertTrue(BillingMonth.documentKey("DEC 2025") < BillingMonth.documentKey("JAN 2026"))
        assertTrue(BillingMonth.documentKey("SEP 2026") < BillingMonth.documentKey("OCT 2026"))
    }

    @Test
    fun `document key is derived from the same month string the admin writes`() {
        assertEquals(
            BillingMonth.documentKey(BillingMonth.of(millisFor(2026, 9, 15), manila)),
            "2026-09"
        )
    }

    @Test
    fun `unparseable months get a distinct document key rather than colliding`() {
        // Mapping every malformed month onto one ID would have them silently
        // overwrite each other.
        val a = BillingMonth.documentKey("not a month")
        val b = BillingMonth.documentKey("also bad")
        assertTrue(a.startsWith("invalid-"))
        assertTrue(b.startsWith("invalid-"))
        assertTrue(a != b)
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
