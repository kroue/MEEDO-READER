package com.waterdistrict.meterreader.domain.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the billing calculator.
 *
 * These are the numbers a concessionaire actually pays, so the cases below
 * are written as the scenarios the office would recognise rather than as
 * exhaustive branch coverage. Several of them exist because the behaviour was
 * wrong in production: the meter rollover and the delinquency clock each
 * caused a real billing error. Mirrors lib/billingCalculator.test.ts in the
 * console case for case — a bill from either side must come out the same.
 */
class WaterBillingCalculatorTest {

    private val day = 86_400_000L
    private val now = 1_757_000_000_000L // fixed "today" so grace-period maths is deterministic

    private fun bill(
        previous: Double = 100.0,
        current: Double = 110.0,
        classification: String = "RESIDENTIAL",
        overdue: Double = 0.0,
        delinquentSince: Long? = null,
        credit: Double = 0.0,
        barangay: String? = null,
        config: WaterRateConfig = WaterRateConfig(),
    ) = WaterBillingCalculator.calculate(
        previousReading = previous,
        currentReading = current,
        classification = classification,
        overdueBalance = overdue,
        delinquentSinceMillis = delinquentSince,
        creditBalance = credit,
        now = now,
        barangay = barangay,
        config = config,
    )

    // ── Rate card ────────────────────────────────────────────────────────────

    @Test
    fun `consumption within the minimum charge allowance costs only the minimum`() {
        val result = bill(previous = 100.0, current = 108.0)
        assertEquals(8.0, result.consumption, 0.001)
        assertEquals(100.0, result.minimumCharge, 0.001)
        assertEquals(0.0, result.commodityCharge, 0.001)
        assertEquals(100.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `consumption beyond ten cubic metres adds commodity charge`() {
        val result = bill(previous = 100.0, current = 125.0)
        // 25 m³ used, 15 of them above the 10 m³ allowance at ₱10.80
        assertEquals(162.0, result.commodityCharge, 0.001)
        assertEquals(262.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `each classification uses its own minimum charge`() {
        assertEquals(100.0, WaterBillingCalculator.minimumChargeFor("RESIDENTIAL"), 0.001)
        assertEquals(100.0, WaterBillingCalculator.minimumChargeFor("GOVERNMENT"), 0.001)
        assertEquals(125.0, WaterBillingCalculator.minimumChargeFor("COMMERCIAL A"), 0.001)
        assertEquals(150.0, WaterBillingCalculator.minimumChargeFor("COMMERCIAL B"), 0.001)
        // Whitespace and casing are normalised before matching.
        assertEquals(125.0, WaterBillingCalculator.minimumChargeFor("  commercial a  "), 0.001)
        assertEquals(150.0, WaterBillingCalculator.minimumChargeFor(" Commercial B "), 0.001)
        // Anything unrecognised falls back to the residential rate rather than
        // failing — imported data has carried odd classification strings before.
        assertEquals(100.0, WaterBillingCalculator.minimumChargeFor("INDUSTRIAL"), 0.001)
        assertEquals(100.0, WaterBillingCalculator.minimumChargeFor(""), 0.001)
    }

    // ── Late payment penalty ─────────────────────────────────────────────────

    @Test
    fun `nothing extra before the unpaid bill's due date`() {
        // No set due day, so the bill fell due fifteen days after billing.
        val result = bill(overdue = 500.0, delinquentSince = now - 15 * day)
        assertFalse(result.pastDue)
        assertEquals(0.0, result.extensionFee, 0.001)
        assertEquals(0.0, result.overdueSurcharge, 0.001)
        assertEquals(600.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `ten pesos once the carried balance is past its due date, and no 3 percent`() {
        val result = bill(overdue = 500.0, delinquentSince = now - 16 * day)
        assertTrue(result.pastDue)
        assertEquals(10.0, result.extensionFee, 0.001)
        assertEquals(0.0, result.overdueSurcharge, 0.001)
        assertEquals(610.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `ten pesos on every month's bill it stays unpaid, not just once`() {
        // Two months on, this bill still carries its own ₱10; last month's ₱10
        // is already inside the carried balance.
        val result = bill(overdue = 610.0, delinquentSince = now - 60 * day)
        assertEquals(10.0, result.extensionFee, 0.001)
        assertEquals(720.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `counts from the barangay's own due day`() {
        // "now" is 4 September 2025 in the Philippines. Billed 5 August, a
        // Bo-ot bill fell due on the 17th — past due. Billed 25 August, it falls
        // due on 17 September — not yet.
        val augustFifth = 1_754_359_200_000L        // 5 Aug 2025, 02:00 UTC
        val augustTwentyFifth = 1_756_087_200_000L  // 25 Aug 2025, 02:00 UTC
        assertEquals(10.0, bill(barangay = "BO-OT", overdue = 500.0, delinquentSince = augustFifth).extensionFee, 0.001)
        assertEquals(0.0, bill(barangay = "BO-OT", overdue = 500.0, delinquentSince = augustTwentyFifth).extensionFee, 0.001)
    }

    @Test
    fun `no penalty when the delinquency start date is unknown`() {
        // Legacy documents with a balance but no delinquentSince: we can't tell
        // how long it has been owed, so we don't invent a penalty.
        val result = bill(overdue = 500.0, delinquentSince = null)
        assertNull(result.daysOverdue)
        assertFalse(result.pastDue)
        assertEquals(0.0, result.extensionFee, 0.001)
        assertEquals(600.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `a long-standing debt keeps ageing rather than resetting each cycle`() {
        // The bug this guards: ageing the newest bill instead of the delinquency
        // start made a two-year debt read as days old, so it never reached the
        // 20-day disconnection threshold.
        val result = bill(overdue = 2_400.0, delinquentSince = now - 400 * day)
        assertEquals(400, result.daysOverdue)
        assertTrue(result.pastDue)
    }

    @Test
    fun `no penalty when nothing is owed even if a delinquency date lingers`() {
        val result = bill(overdue = 0.0, delinquentSince = now - 90 * day)
        assertNull(result.daysOverdue)
        assertEquals(0.0, result.extensionFee, 0.001)
    }

    // ── Whole pesos ──────────────────────────────────────────────────────────

    @Test
    fun `the water is billed in whole pesos`() {
        // 3 m³ at ₱10.80 is ₱32.40, billed as ₱32.
        val result = bill(previous = 0.0, current = 13.0)
        assertEquals(32.0, result.commodityCharge, 0.0)
        assertEquals(132.0, result.totalWaterCharge, 0.0)
        assertEquals(132.0, result.totalAmountDue, 0.0)
        assertEquals(0.0, result.roundingAdjustment, 0.0)
    }

    @Test
    fun `half a peso rounds up`() {
        val result = bill(previous = 0.0, current = 11.0, config = WaterRateConfig(commodityRate = 10.5))
        assertEquals(11.0, result.commodityCharge, 0.0)
        assertEquals(111.0, result.totalAmountDue, 0.0)
    }

    @Test
    fun `every line is a whole peso, adding up to the amount due`() {
        // 7.3 m³ at ₱10.80 is ₱78.84, billed as ₱79.
        val result = bill(previous = 100.0, current = 117.3, overdue = 431.0, delinquentSince = now - 40 * day)
        assertEquals(
            listOf(100.0, 79.0, 431.0, 10.0),
            listOf(result.minimumCharge, result.commodityCharge, result.overdueBalance, result.extensionFee)
        )
        assertEquals(620.0, result.totalAmountDue, 0.0)
        assertEquals(0.0, result.roundingAdjustment, 0.0)
    }

    @Test
    fun `centavos a balance from before carries in are rounded off`() {
        val result = bill(overdue = 50.4)
        assertEquals(150.0, result.totalAmountDue, 0.0)
        assertEquals(-0.4, result.roundingAdjustment, 0.0001)
    }

    // ── Advance credit ───────────────────────────────────────────────────────

    @Test
    fun `advance credit is drawn down against the bill`() {
        val result = bill(previous = 100.0, current = 110.0, credit = 40.0)
        assertEquals(40.0, result.creditApplied, 0.001)
        assertEquals(0.0, result.creditRemaining, 0.001)
        assertEquals(60.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `credit larger than the bill leaves the remainder on account and never goes negative`() {
        val result = bill(previous = 100.0, current = 110.0, credit = 250.0)
        assertEquals(100.0, result.creditApplied, 0.001)
        assertEquals(150.0, result.creditRemaining, 0.001)
        assertEquals(0.0, result.totalAmountDue, 0.001)
    }

    // ── Meter rollover ───────────────────────────────────────────────────────

    @Test
    fun `a meter that wraps past its maximum bills the real consumption`() {
        // 5-digit register: 99,890 last month, 120 this month is 231 m³ used,
        // not a negative reading. This used to throw, so the account simply
        // couldn't be billed in the field.
        val result = bill(previous = 99_890.0, current = 120.0)
        assertTrue(result.meterRolledOver)
        assertEquals(230.0, result.consumption, 0.001)
        assertEquals(100.0 + 220.0 * 10.80, result.totalAmountDue, 0.01)
    }

    @Test
    fun `a small backwards step is rejected as a typo, not treated as a rollover`() {
        val problem = WaterBillingCalculator.validate(previousReading = 500.0, currentReading = 480.0)
        assertTrue(problem is ReadingProblem.BelowPrevious)
    }

    @Test
    fun `an implausibly large consumption is rejected`() {
        val problem = WaterBillingCalculator.validate(previousReading = 100.0, currentReading = 90_000.0)
        assertTrue(problem is ReadingProblem.ImplausiblyHigh)
    }

    @Test
    fun `an ordinary reading passes validation`() {
        assertNull(WaterBillingCalculator.validate(previousReading = 100.0, currentReading = 132.0))
    }

    // ── Projection printed on the receipt ────────────────────────────────────

    @Test
    fun `projected total matches what the next bill would actually carry forward`() {
        val result = bill(previous = 100.0, current = 110.0)
        // ₱100 now; unpaid past the due date the next bill adds a month's ₱10.
        assertEquals(110.0, result.projectedOverdueTotal, 0.001)
        assertEquals(now + 15 * day, result.dueDateMillis)
    }

    @Test
    fun `projection adds one more month's penalty to a bill already carrying one`() {
        val result = bill(overdue = 500.0, delinquentSince = now - 16 * day)
        assertEquals(620.0, result.projectedOverdueTotal, 0.001)
    }

    // ── Correction safety ────────────────────────────────────────────────────

    @Test
    fun `chargesAdded excludes the balance carried in`() {
        // uploadReading subtracts this to recover the pre-bill balance when a
        // reading is corrected. If it included the carried balance, correcting
        // a reading would wipe the concessionaire's existing debt.
        val result = bill(previous = 100.0, current = 125.0, overdue = 500.0,
                          delinquentSince = now - 16 * day)
        assertEquals(262.0 + 10.0, result.chargesAdded, 0.001)
    }

    @Test
    fun `chargesAdded includes the rounding, so a correction backs it out exactly`() {
        val result = bill(overdue = 50.4)
        assertEquals(150.0, result.totalAmountDue, 0.0) // 100 + 50.40, rounded
        assertEquals(50.4, result.totalAmountDue - result.chargesAdded, 0.001)
    }
}
