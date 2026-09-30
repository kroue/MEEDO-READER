package com.waterdistrict.meterreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeInfoTest {

    @Test
    fun `address fits 58 mm paper without cutting a word`() {
        // 32 characters a line on 58 mm paper. The address is 51, and
        // "Bus Terminal Lobby, Brgy. Eastern" alone is 33 — so three lines.
        val lines = OfficeInfo.addressLines(32)
        assertEquals(listOf("Bus Terminal Lobby", "Brgy. Eastern, Wao", "Lanao del Sur"), lines)
        assertTrue(lines.all { it.length <= 32 })
    }

    @Test
    fun `address takes fewer lines on 80 mm paper`() {
        assertEquals(
            listOf("Bus Terminal Lobby, Brgy. Eastern, Wao", "Lanao del Sur"),
            OfficeInfo.addressLines(48)
        )
    }

    @Test
    fun `on screen the address can only wrap between its parts`() {
        val shown = OfficeInfo.addressForDisplay
        // The only ordinary spaces left are the ones after commas.
        assertEquals(3, shown.count { it == ' ' })
        assertTrue(shown.contains("Lanao del Sur"))
        assertEquals(OfficeInfo.ADDRESS, shown.replace(' ', ' '))
    }

    @Test
    fun `keeps the whole address on one line when it fits`() {
        assertEquals(listOf(OfficeInfo.ADDRESS), OfficeInfo.addressLines(80))
    }

    @Test
    fun `leaves a part wider than the line for the printer to wrap`() {
        assertEquals(
            listOf("A very long single part of an address", "Wao"),
            OfficeInfo.addressLines(10, "A very long single part of an address, Wao")
        )
    }
}
