package com.waterdistrict.meterreader.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RateScheduleTest {

    private fun stored(month: String, rate: Number, threshold: Number = 10L, minimums: Map<String, Number> = mapOf(
        "RESIDENTIAL" to 100L, "GOVERNMENT" to 100L, "COMMERCIAL A" to 125L, "COMMERCIAL B" to 150L,
    )) = mapOf(
        "effectiveMonth" to month,
        "commodityRate" to rate,
        "minChargeThreshold" to threshold,
        "minimumCharges" to minimums,
        "setBy" to "admin@meedo.com",
        "setAt" to "2026-09-20T00:00:00.000Z",
    )

    private val schedule = RateSchedule.fromStored(listOf(stored("2027-01", 13.5), stored("2026-10", 12L)))

    @Test
    fun `bills at the base rates before any change`() {
        assertSame(RateCard.BASE, RateSchedule.EMPTY.cardFor("2026-09"))
        assertEquals(10.80, schedule.cardFor("2026-09").commodityRate, 0.0)
    }

    @Test
    fun `uses a change from its month until the next one, whatever order it was stored in`() {
        assertEquals(12.0, schedule.cardFor("2026-10").commodityRate, 0.0)
        assertEquals(12.0, schedule.cardForMonth("DEC 2026").commodityRate, 0.0)
        assertEquals(13.5, schedule.cardFor("2027-01").commodityRate, 0.0)
    }

    @Test
    fun `works a bill out with the month's rates`() {
        val card = RateSchedule.fromStored(listOf(stored("2026-10", 12L, threshold = 8L, minimums = mapOf(
            "RESIDENTIAL" to 110L, "GOVERNMENT" to 105L, "COMMERCIAL A" to 130L, "COMMERCIAL B" to 160L,
        )))).cardFor("2026-10")
        val bill = WaterBillingCalculator.calculate(
            previousReading = 100.0,
            currentReading = 120.0,
            classification = "COMMERCIAL B",
            config = card.toConfig(),
        )
        // The same case as the console's rates test: 20 m³, 8 covered by ₱160, 12 at ₱12.
        assertEquals(160.0, bill.minimumCharge, 0.0)
        assertEquals(144.0, bill.commodityCharge, 0.0)
        assertEquals(304.0, bill.totalWaterCharge, 0.0)
    }

    @Test
    fun `bills exactly as before under the base rates`() {
        val bill = WaterBillingCalculator.calculate(100.0, 120.0, "RESIDENTIAL", config = RateCard.BASE.toConfig())
        assertEquals(208.0, bill.totalWaterCharge, 0.0)
        assertEquals(125.0, WaterBillingCalculator.minimumChargeFor("commercial a"), 0.0)
        assertEquals(100.0, WaterBillingCalculator.minimumChargeFor("something else"), 0.0)
    }

    @Test
    fun `drops malformed entries instead of failing`() {
        val parsed = RateSchedule.fromStored(listOf(
            stored("2026-11", 13L),
            stored("not a month", 12L),
            stored("2026-10", 0L),
            stored("2026-12", 12.345),
            stored("2027-01", 12L, threshold = 2.5),
            mapOf("effectiveMonth" to "2027-02"),
            "junk",
            null,
        ))
        assertEquals(listOf("2026-11"), parsed.entries.map { it.effectiveMonth })
        assertEquals(0, RateSchedule.fromStored(null).entries.size)
    }
}
