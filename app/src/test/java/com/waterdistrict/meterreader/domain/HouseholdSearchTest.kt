package com.waterdistrict.meterreader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdSearchTest {

    private fun household(name: String, meter: String, account: String = "") =
        HouseholdSearch.Candidate(item = name, name = name, meterNo = meter, accountNo = meter, officeAccountNo = account)

    private val route = listOf(
        household("Maria Santos Dela Cruz", "MTR-710013", "2026-000042"),
        household("Juan Dela Cruz", "MTR-710007", "2026-000017"),
        household("Rodel Peña", "MTR-710113", "2026-000120"),
        household("Abdul Sarip", "MTR-710200", "2026-000130"),
    )

    private fun find(query: String) = HouseholdSearch.search(query, route)

    @Test
    fun `finds by name, in any word order, A to Z`() {
        assertEquals(listOf("Juan Dela Cruz", "Maria Santos Dela Cruz"), find("dela cruz"))
        assertEquals(listOf("Juan Dela Cruz"), find("cruz juan"))
    }

    @Test
    fun `ignores case and accents`() {
        assertEquals(listOf("Rodel Peña"), find("PENA"))
        assertEquals(listOf("Rodel Peña"), find("peña"))
    }

    @Test
    fun `finds by meter number, whole or part`() {
        assertEquals(listOf("Juan Dela Cruz"), find("MTR-710007"))
        // Part of a number finds every meter containing it, A–Z.
        assertEquals(listOf("Juan Dela Cruz", "Maria Santos Dela Cruz"), find("7100"))
    }

    @Test
    fun `finds by the office's account number`() {
        assertEquals(listOf("Maria Santos Dela Cruz"), find("2026-000042"))
    }

    @Test
    fun `puts an exact number first, ahead of A to Z`() {
        // Every meter here contains "MTR-710", but only Zeny's is exactly that:
        // she is the household the reader is standing at, whatever her name.
        val street = listOf(
            household("Ana Lim", "MTR-71001"),
            household("Zeny Uy", "MTR-710"),
            household("Ben Tan", "MTR-71002"),
        )
        assertEquals(listOf("Zeny Uy", "Ana Lim", "Ben Tan"), HouseholdSearch.search("mtr 710", street))
    }

    @Test
    fun `lists nothing until there's something to search on`() {
        assertEquals(emptyList<String>(), find(""))
        assertEquals(emptyList<String>(), find(" j "))
        assertEquals(emptyList<String>(), find("zzz"))
    }
}
