package com.waterdistrict.meterreader.domain.billing

import kotlin.math.max
import kotlin.math.round

// ─────────────────────────────────────────────────────────────────────────────
// Water District — Billing Calculator
// ─────────────────────────────────────────────────────────────────────────────
//
// Rate schedule (per the district's Board-approved rate card, ½" connection):
//
//   Classification            Minimum Charge   Commodity Charge
//                              (covers first    (₱ / m³ beyond the
//                              10 m³)           minimum-charge allowance)
//   ────────────────────────────────────────────────────────────────────
//   RESIDENTIAL / GOV'T.       ₱100.00           ₱10.80
//   COMMERCIAL A                ₱125.00           ₱10.80
//   COMMERCIAL B                ₱150.00           ₱10.80
//
//   Grace period / delinquency policy:
//     Day 0–15 after the account went delinquent : on time, no surcharge.
//     Day 16+                                    : 3% surcharge on the overdue
//                                                   balance, every bill it stays
//                                                   delinquent, plus a flat ₱10.00
//                                                   extension fee charged exactly
//                                                   once per delinquency.
//
//   "When the account went delinquent" means the moment its balance last went
//   from zero to owing — tracked as `delinquentSince` on the Firestore
//   document. It is deliberately NOT the age of the newest bill (which resets
//   every cycle, so nothing would ever reach the 20-day disconnection
//   threshold) and NOT the oldest row still showing an unpaid amount (which a
//   concessionaire could reset by paying a token amount against the oldest
//   cycle, since payments apply oldest-first).
//
//   Disconnection (20+ days) and application-fee forfeiture (6+ months
//   disconnected) are service actions, not billing math — the admin app
//   surfaces them as staff-facing indicators instead.
// ─────────────────────────────────────────────────────────────────────────────

data class WaterRateConfig(
    /** Cubic metres of consumption covered by the minimum charge. */
    val minChargeThreshold: Double = 10.0,
    /** Flat commodity rate (₱ / m³) charged for consumption beyond [minChargeThreshold]. */
    val commodityRate: Double = 10.80,
    /** Surcharge rate applied to the overdue balance once past the grace period (3%). */
    val overdueSurchargeRate: Double = 0.03,
    /** Days after the account went delinquent before a surcharge applies. */
    val gracePeriodDays: Int = 15,
    /** Flat fee for pursuing a delinquent account, applied once it's past the grace period. */
    val extensionFee: Double = 10.00,
    /**
     * Highest reading a meter can show before it rolls back to zero. Mechanical
     * registers on these connections are 5-digit, so 99999 m³ is the wrap point.
     */
    val meterMaxReading: Double = 99_999.0
)

/**
 * Full billing breakdown for one reading.
 * All monetary values are in Philippine Pesos (₱).
 */
data class BillingResult(
    val consumption: Double,        // m³ consumed this period

    val minimumCharge: Double,      // flat minimum charge for the consumer's classification
    val commodityCharge: Double,    // consumption beyond the threshold × commodity rate
    val totalWaterCharge: Double,   // minimumCharge + commodityCharge

    val overdueBalance: Double,     // outstanding balance carried over, if any
    val daysOverdue: Int?,          // days since the account went delinquent, if known
    val pastGracePeriod: Boolean,   // true once daysOverdue > gracePeriodDays
    val overdueSurcharge: Double,   // 3% of overdueBalance, every bill it stays past the grace period
    val extensionFee: Double,       // flat ₱10 delinquency-pursuit fee, charged once per delinquency

    /** Advance payment on the account drawn down against this bill. */
    val creditApplied: Double,
    /** Advance payment left over after this bill. */
    val creditRemaining: Double,

    val totalAmountDue: Double,     // what the concessionaire pays now, never negative

    val dueDateMillis: Long,            // the barangay's due day (see DueDates) — pay on/before this to avoid the surcharge
    val projectedOverdueTotal: Double,  // what totalAmountDue becomes if THIS bill isn't paid by dueDateMillis

    /** True when the meter reading wrapped past [WaterRateConfig.meterMaxReading]. */
    val meterRolledOver: Boolean = false,
) {
    /**
     * What this bill ADDS to the running balance, excluding anything carried
     * in. The upload transaction subtracts this to recover the pre-bill
     * balance when a reading is corrected, so a correction can never
     * compound the previous attempt's charges.
     */
    val chargesAdded: Double get() = totalWaterCharge + overdueSurcharge + extensionFee
}

/** Why a reading can't be billed as entered. */
sealed class ReadingProblem {
    /** Below the previous reading and too far below to be a plausible meter wrap. */
    data class BelowPrevious(val previousReading: Double) : ReadingProblem()
    data class NotANumber(val raw: String) : ReadingProblem()
    data class ImplausiblyHigh(val consumption: Double) : ReadingProblem()
}

/**
 * Pure, stateless billing calculator.
 *
 * Usage:
 * ```kotlin
 * val result = WaterBillingCalculator.calculate(
 *     previousReading = 120.0,
 *     currentReading  = 148.0,
 *     classification  = "COMMERCIAL A",
 *     overdueBalance  = 250.0,
 *     delinquentSinceMillis = someEpochMillis
 * )
 * println("Total Due: ₱${result.totalAmountDue}")
 * ```
 */
object WaterBillingCalculator {

    /**
     * Consumption above this in a single cycle is treated as a data-entry
     * mistake rather than a real reading — roughly 20× a heavy household month.
     * The reader is asked to confirm rather than silently issuing a bill for
     * tens of thousands of pesos off a mistyped digit.
     */
    const val IMPLAUSIBLE_CONSUMPTION_M3 = 1_000.0

    /** Flat minimum charge per the rate card, keyed by classification. */
    fun minimumChargeFor(classification: String): Double =
        when (classification.trim().uppercase()) {
            "COMMERCIAL A" -> 125.00
            "COMMERCIAL B" -> 150.00
            else           -> 100.00 // RESIDENTIAL / GOVERNMENT
        }

    /** Rounds to centavos so float noise never reaches Firestore or a receipt. */
    private fun toCentavos(value: Double): Double = round(value * 100.0) / 100.0

    /**
     * Consumption for a cycle, accounting for a meter that wrapped past its
     * maximum back to zero.
     *
     * A 5-digit mechanical register reading 99,890 last month and 120 this
     * month consumed 230 m³, not "-99,770". Previously any reading below the
     * previous one threw, so a rolled-over meter — and a meter replaced
     * mid-cycle, which also starts low — simply could not be billed in the
     * field: no override, no "meter changed" path, the account just got
     * skipped for the cycle.
     */
    fun consumptionFor(
        previousReading: Double,
        currentReading: Double,
        config: WaterRateConfig = WaterRateConfig()
    ): Pair<Double, Boolean> {
        if (currentReading >= previousReading) {
            return (currentReading - previousReading) to false
        }
        // Wrapped: what was left to the top of the dial, plus what's on it now.
        val wrapped = (config.meterMaxReading - previousReading) + currentReading + 1.0
        return wrapped to true
    }

    /**
     * Validates a reading before it can be billed, so the UI can explain the
     * problem rather than the calculator throwing.
     *
     * A reading below the previous one is accepted as a meter rollover only
     * when the resulting consumption is plausible; a small backwards step is
     * far more likely to be a typo than a wrap of a 5-digit register.
     */
    fun validate(
        previousReading: Double,
        currentReading: Double,
        config: WaterRateConfig = WaterRateConfig()
    ): ReadingProblem? {
        val (consumption, rolledOver) = consumptionFor(previousReading, currentReading, config)
        if (rolledOver && consumption > IMPLAUSIBLE_CONSUMPTION_M3) {
            return ReadingProblem.BelowPrevious(previousReading)
        }
        if (consumption > IMPLAUSIBLE_CONSUMPTION_M3) {
            return ReadingProblem.ImplausiblyHigh(consumption)
        }
        return null
    }

    /**
     * Calculate the full water bill for a single meter reading.
     *
     * @param previousReading       Prior period's meter reading (m³)
     * @param currentReading        This period's meter reading  (m³)
     * @param classification        Consumer classification, e.g. "RESIDENTIAL", "COMMERCIAL A"
     * @param overdueBalance        Outstanding balance carried in from previous periods (₱).
     *                              Must NOT include anything this bill itself adds — see
     *                              [BillingResult.chargesAdded] and FirebaseRepository.uploadReading.
     * @param delinquentSinceMillis When the account's balance last went from zero to owing
     *                              (epoch millis); null if unknown, in which case no surcharge
     *                              or extension fee applies even if a balance is owed, since we
     *                              can't tell how long it has been outstanding.
     * @param creditBalance         Advance payment held on the account, drawn down against this bill.
     * @param now                   Current time (epoch millis) — overridable for testing.
     * @param extensionFeeAlreadyCharged True if the ₱10 extension fee was already charged within
     *                              this same unpaid streak — it's one-time per delinquency.
     * @param config                Rate schedule for the district
     */
    fun calculate(
        previousReading: Double,
        currentReading: Double,
        classification: String,
        overdueBalance: Double = 0.0,
        delinquentSinceMillis: Long? = null,
        creditBalance: Double = 0.0,
        now: Long = System.currentTimeMillis(),
        extensionFeeAlreadyCharged: Boolean = false,
        /** The account's barangay, which fixes its due day — see [DueDates]. */
        barangay: String? = null,
        config: WaterRateConfig = WaterRateConfig()
    ): BillingResult {
        val (consumption, meterRolledOver) = consumptionFor(previousReading, currentReading, config)

        val minimumCharge = minimumChargeFor(classification)
        val commodityCharge = toCentavos(
            max(0.0, consumption - config.minChargeThreshold) * config.commodityRate
        )
        val totalWaterCharge = toCentavos(minimumCharge + commodityCharge)

        val carried = max(0.0, overdueBalance)

        val daysOverdue = delinquentSinceMillis
            ?.takeIf { carried > 0 }
            ?.let { startedAt -> ((now - startedAt) / 86_400_000L).toInt() }

        val pastGracePeriod = daysOverdue != null && daysOverdue > config.gracePeriodDays

        val overdueSurcharge =
            if (pastGracePeriod) toCentavos(carried * config.overdueSurchargeRate) else 0.0
        val extensionFee =
            if (pastGracePeriod && !extensionFeeAlreadyCharged) config.extensionFee else 0.0

        val grossDue = toCentavos(totalWaterCharge + carried + overdueSurcharge + extensionFee)

        // Advance payments on the account settle this bill before any cash does.
        val creditApplied = toCentavos(minOf(max(0.0, creditBalance), grossDue))
        val creditRemaining = toCentavos(max(0.0, creditBalance) - creditApplied)
        val totalAmountDue = toCentavos(grossDue - creditApplied)

        // What the concessionaire would owe if THIS bill goes unpaid past its
        // own due date — mirrors exactly how the next bill would treat it as the
        // new overdue balance, so the printed figure matches what actually happens.
        val dueDateMillis = DueDates.dueDateFor(barangay, now, config.gracePeriodDays)
        val extensionFeeUsedForThisDebt = extensionFeeAlreadyCharged || extensionFee > 0
        val projectedSurcharge = toCentavos(totalAmountDue * config.overdueSurchargeRate)
        val projectedExtensionFee = if (extensionFeeUsedForThisDebt) 0.0 else config.extensionFee
        val projectedOverdueTotal =
            toCentavos(totalAmountDue + projectedSurcharge + projectedExtensionFee)

        return BillingResult(
            consumption      = consumption,
            minimumCharge    = minimumCharge,
            commodityCharge  = commodityCharge,
            totalWaterCharge = totalWaterCharge,
            overdueBalance   = carried,
            daysOverdue      = daysOverdue,
            pastGracePeriod  = pastGracePeriod,
            overdueSurcharge = overdueSurcharge,
            extensionFee     = extensionFee,
            creditApplied    = creditApplied,
            creditRemaining  = creditRemaining,
            totalAmountDue   = totalAmountDue,
            dueDateMillis    = dueDateMillis,
            projectedOverdueTotal = projectedOverdueTotal,
            meterRolledOver  = meterRolledOver,
        )
    }
}
