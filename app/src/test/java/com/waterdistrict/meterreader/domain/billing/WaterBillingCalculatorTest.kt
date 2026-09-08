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
 * wrong in production: the meter rollover, the delinquency clock, and the
 * one-time extension fee each caused a real billing error.
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
        feeAlreadyCharged: Boolean = false,
    ) = WaterBillingCalculator.calculate(
        previousReading = previous,
        currentReading = current,
        classification = classification,
        overdueBalance = overdue,
        delinquentSinceMillis = delinquentSince,
        creditBalance = credit,
        now = now,
        extensionFeeAlreadyCharged = feeAlreadyCharged,
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

    // ── Grace period, surcharge, extension fee ───────────────────────────────

    @Test
    fun `no surcharge inside the fifteen day grace period`() {
        val result = bill(overdue = 500.0, delinquentSince = now - 15 * day)
        assertFalse(result.pastGracePeriod)
        assertEquals(0.0, result.overdueSurcharge, 0.001)
        assertEquals(0.0, result.extensionFee, 0.001)
        assertEquals(600.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `surcharge and extension fee apply from day sixteen`() {
        val result = bill(overdue = 500.0, delinquentSince = now - 16 * day)
        assertTrue(result.pastGracePeriod)
        assertEquals(15.0, result.overdueSurcharge, 0.001)   // 3% of 500
        assertEquals(10.0, result.extensionFee, 0.001)
        assertEquals(625.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `extension fee is charged once per delinquency, not once per bill`() {
        val result = bill(
            overdue = 500.0,
            delinquentSince = now - 60 * day,
            feeAlreadyCharged = true,
        )
        assertEquals(0.0, result.extensionFee, 0.001)
        // Surcharge still applies every cycle the debt stays overdue.
        assertEquals(15.0, result.overdueSurcharge, 0.001)
    }

    @Test
    fun `no surcharge when the delinquency start date is unknown`() {
        // Legacy documents with a balance but no delinquentSince: we can't tell
        // how long it has been owed, so we don't invent a penalty.
        val result = bill(overdue = 500.0, delinquentSince = null)
        assertNull(result.daysOverdue)
        assertFalse(result.pastGracePeriod)
        assertEquals(0.0, result.overdueSurcharge, 0.001)
        assertEquals(600.0, result.totalAmountDue, 0.001)
    }

    @Test
    fun `a long-standing debt keeps ageing rather than resetting each cycle`() {
        // The bug this guards: ageing the newest bill instead of the delinquency
        // start made a two-year debt read as days old, so it never reached the
        // 20-day disconnection threshold.
        val result = bill(overdue = 2_400.0, delinquentSince = now - 400 * day)
        assertEquals(400, result.daysOverdue)
        assertTrue(result.pastGracePeriod)
    }

    @Test
    fun `no surcharge when nothing is owed even if a delinquency date lingers`() {
        val result = bill(overdue = 0.0, delinquentSince = now - 90 * day)
        assertNull(result.daysOverdue)
        assertEquals(0.0, result.overdueSurcharge, 0.001)
        assertEquals(0.0, result.extensionFee, 0.001)
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
        // 100 now; unpaid past the due date it becomes 100 + 3% + ₱10.
        assertEquals(113.0, result.projectedOverdueTotal, 0.001)
        assertEquals(now + 15 * day, result.dueDateMillis)
    }

    @Test
    fun `projection does not charge the extension fee twice`() {
        val result = bill(overdue = 500.0, delinquentSince = now - 16 * day)
        // The fee was already applied on this bill, so the projection adds only
        // the surcharge.
        assertEquals(625.0 * 1.03, result.projectedOverdueTotal, 0.01)
    }

    // ── Correction safety ────────────────────────────────────────────────────

    @Test
    fun `chargesAdded excludes the balance carried in`() {
        // uploadReading subtracts this to recover the pre-bill balance when a
        // reading is corrected. If it included the carried balance, correcting
        // a reading would wipe the concessionaire's existing debt.
        val result = bill(previous = 100.0, current = 125.0, overdue = 500.0,
                          delinquentSince = now - 16 * day)
        assertEquals(262.0 + 15.0 + 10.0, result.chargesAdded, 0.001)
    }

    @Test
    fun `money values are rounded to centavos`() {
        // 13 m³ → 3 m³ above the allowance × 10.80 = 32.4, no float dust.
        val result = bill(previous = 0.0, current = 13.0)
        assertEquals(32.4, result.commodityCharge, 0.0001)
        assertEquals(132.4, result.totalAmountDue, 0.0001)
    }
}
