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
//   Late payment:
//     Until the unpaid bill's due date : on time. The due date is the
//                                        barangay's day, or 15 days after
//                                        billing where it has none (DueDates).
//     After it                         : each bill the balance is carried into
//                                        adds a flat ₱10.00 penalty — ₱10 for
//                                        every month it stays unpaid. (This
//                                        replaced a 3% surcharge plus a one-time
//                                        ₱10 extension fee.)
//
//   Whole pesos: the commodity charge is rounded to the nearest peso (half a
//   peso up), so with whole-peso minimum charges and penalty every line on a
//   bill, and its total, is a whole peso. Nothing on a bill says it was
//   rounded. A balance carried in with centavos, from bills issued before
//   this, is rounded off in the total; that difference is kept, unshown, as
//   roundingAdjustment so a correction backs the bill out exactly.
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

/** The minimum charges before any change was made in the console. */
val DEFAULT_MINIMUM_CHARGES: Map<String, Double> = mapOf(
    "RESIDENTIAL" to 100.00,
    "GOVERNMENT" to 100.00,
    "COMMERCIAL A" to 125.00,
    "COMMERCIAL B" to 150.00,
)

data class WaterRateConfig(
    /** Cubic metres of consumption covered by the minimum charge. */
    val minChargeThreshold: Double = 10.0,
    /** Flat commodity rate (₱ / m³) charged for consumption beyond [minChargeThreshold]. */
    val commodityRate: Double = 10.80,
    /**
     * The minimum charge for the first [minChargeThreshold] m³, by
     * classification. With the two above, this is the part of the rate card
     * an admin sets in the console (see [RateSchedule]); the rest are fixed.
     */
    val minimumCharges: Map<String, Double> = DEFAULT_MINIMUM_CHARGES,
    /** Days after billing a bill falls due, for a barangay without a set due day. */
    val gracePeriodDays: Int = 15,
    /** ₱ added to each bill while an unpaid balance is past its due date. */
    val latePenalty: Double = 10.00,
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
    val pastDue: Boolean,           // the carried balance is unpaid past its due date
    val overdueSurcharge: Double,   // no longer charged — always 0; older bills may carry one
    /**
     * The ₱10 late payment penalty. Named for the one-time extension fee it
     * replaced, because that is the field older bills and this phone's
     * database already store it under.
     */
    val extensionFee: Double,

    /** Advance payment on the account drawn down against this bill. */
    val creditApplied: Double,
    /** Advance payment left over after this bill. */
    val creditRemaining: Double,

    /**
     * What rounding the total to a whole peso added or took off — only ever
     * non-zero when a carried balance or credit has centavos. Stored for the
     * books, never shown on a bill.
     */
    val roundingAdjustment: Double = 0.0,
    val totalAmountDue: Double,     // what the concessionaire pays now, in whole pesos, never negative

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
    val chargesAdded: Double get() =
        round((totalWaterCharge + overdueSurcharge + extensionFee + roundingAdjustment) * 100.0) / 100.0
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

    /** Flat minimum charge per the rate card. Unrecognised classifications pay the residential one. */
    fun minimumChargeFor(classification: String, config: WaterRateConfig = WaterRateConfig()): Double =
        config.minimumCharges[classification.trim().uppercase()]
            ?: config.minimumCharges["RESIDENTIAL"]
            ?: DEFAULT_MINIMUM_CHARGES.getValue("RESIDENTIAL")

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
        /** The account's barangay, which fixes its due day — see [DueDates]. */
        barangay: String? = null,
        config: WaterRateConfig = WaterRateConfig()
    ): BillingResult {
        val (consumption, meterRolledOver) = consumptionFor(previousReading, currentReading, config)

        val minimumCharge = minimumChargeFor(classification, config)
        // Billed in whole pesos like every other line: 7.8 m³ at ₱10.80 is ₱84.
        val commodityCharge = Math.round(
            toCentavos(max(0.0, consumption - config.minChargeThreshold) * config.commodityRate)
        ).toDouble()
        val totalWaterCharge = toCentavos(minimumCharge + commodityCharge)

        val carried = max(0.0, overdueBalance)
        val owingSince = delinquentSinceMillis?.takeIf { carried > 0 }

        val daysOverdue = owingSince?.let { startedAt -> ((now - startedAt) / 86_400_000L).toInt() }

        // Past due once today is after the due date the unpaid bill was given.
        val pastDue = owingSince != null &&
            now > DueDates.dueDateFor(barangay, owingSince, config.gracePeriodDays)
        val latePenalty = if (pastDue) config.latePenalty else 0.0

        val grossDue = toCentavos(totalWaterCharge + carried + latePenalty)

        // Credit left on older accounts settles this bill before any cash does.
        val creditApplied = toCentavos(minOf(max(0.0, creditBalance), grossDue))
        val creditRemaining = toCentavos(max(0.0, creditBalance) - creditApplied)
        val exactDue = toCentavos(grossDue - creditApplied)

        // Whole pesos already, unless a balance from before carried centavos in.
        val totalAmountDue = Math.round(exactDue).toDouble()
        val roundingAdjustment = toCentavos(totalAmountDue - exactDue)

        // Unpaid by its due date, the next bill carries this one plus a
        // month's penalty — the figure printed as "if paid after".
        val dueDateMillis = DueDates.dueDateFor(barangay, now, config.gracePeriodDays)
        val projectedOverdueTotal = totalAmountDue + config.latePenalty

        return BillingResult(
            consumption      = consumption,
            minimumCharge    = minimumCharge,
            commodityCharge  = commodityCharge,
            totalWaterCharge = totalWaterCharge,
            overdueBalance   = carried,
            daysOverdue      = daysOverdue,
            pastDue          = pastDue,
            overdueSurcharge = 0.0,
            extensionFee     = latePenalty,
            creditApplied    = creditApplied,
            creditRemaining  = creditRemaining,
            roundingAdjustment = roundingAdjustment,
            totalAmountDue   = totalAmountDue,
            dueDateMillis    = dueDateMillis,
            projectedOverdueTotal = projectedOverdueTotal,
            meterRolledOver  = meterRolledOver,
        )
    }
}
