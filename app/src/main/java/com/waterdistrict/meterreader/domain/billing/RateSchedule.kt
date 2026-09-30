package com.waterdistrict.meterreader.domain.billing

/**
 * What water costs, and from which billing month — the field app's copy of
 * the schedule an admin keeps in the console's Settings. The rules mirror
 * lib/rates.ts there, so a month is billed at the same rates whichever side
 * issues the bill.
 *
 * The phone downloads the schedule when it syncs and keeps the last copy, so
 * a reader out of signal still bills each month at that month's rates —
 * including a change the admin set ahead of time, as long as the phone synced
 * once after it was set.
 */
data class RateCard(
    /** Cubic metres covered by the minimum charge. */
    val minChargeThreshold: Double,
    /** ₱ per m³ beyond that. */
    val commodityRate: Double,
    /** The minimum charge, by classification. */
    val minimumCharges: Map<String, Double>,
) {
    fun toConfig(base: WaterRateConfig = WaterRateConfig()): WaterRateConfig = base.copy(
        minChargeThreshold = minChargeThreshold,
        commodityRate = commodityRate,
        minimumCharges = minimumCharges,
    )

    fun minimumChargeFor(classification: String): Double =
        WaterBillingCalculator.minimumChargeFor(classification, toConfig())

    companion object {
        /** The rates before any change was made in the console. */
        val BASE = RateCard(
            minChargeThreshold = 10.0,
            commodityRate = 10.80,
            minimumCharges = DEFAULT_MINIMUM_CHARGES,
        )

        val CLASSIFICATIONS = listOf("RESIDENTIAL", "GOVERNMENT", "COMMERCIAL A", "COMMERCIAL B")
    }
}

/** A rate card and the first billing month it applies to, as "2026-10". */
data class ScheduledRateCard(val effectiveMonth: String, val card: RateCard)

class RateSchedule(entries: List<ScheduledRateCard>) {

    /** Only well-formed entries, oldest first. */
    val entries: List<ScheduledRateCard> =
        entries.filter { isMonthKey(it.effectiveMonth) && isUsable(it.card) }.sortedBy { it.effectiveMonth }

    /** The rates a bill for [monthKey] ("2026-10") is worked out with. */
    fun cardFor(monthKey: String): RateCard =
        entries.lastOrNull { it.effectiveMonth <= monthKey }?.card ?: RateCard.BASE

    /** The same, for a month written the usual way ("OCT 2026"). */
    fun cardForMonth(monthStr: String): RateCard = cardFor(BillingMonth.documentKey(monthStr))

    companion object {
        val EMPTY = RateSchedule(emptyList())

        private val MONTH_KEY = Regex("""^\d{4}-(0[1-9]|1[0-2])$""")

        fun isMonthKey(value: String): Boolean = MONTH_KEY.matches(value)

        private fun hasCentavosAtMost(n: Double) = Math.round(n * 100.0) / 100.0 == n

        /** The same checks the console makes before saving a change. */
        fun isUsable(card: RateCard): Boolean =
            card.commodityRate.isFinite() && card.commodityRate > 0 && card.commodityRate <= 1000 &&
                hasCentavosAtMost(card.commodityRate) &&
                card.minChargeThreshold % 1.0 == 0.0 && card.minChargeThreshold in 0.0..100.0 &&
                RateCard.CLASSIFICATIONS.all { c ->
                    val charge = card.minimumCharges[c]
                    charge != null && charge.isFinite() && charge in 0.0..100_000.0 && hasCentavosAtMost(charge)
                }

        /**
         * Reads the schedule as stored: a list of maps. Numbers arrive as
         * Long or Double depending on how they were written. Anything
         * malformed is dropped rather than failing — one bad entry mustn't stop
         * a reader from billing at the others.
         */
        fun fromStored(raw: List<*>?): RateSchedule {
            val entries = raw.orEmpty().mapNotNull { item ->
                val entry = item as? Map<*, *> ?: return@mapNotNull null
                val charges = entry["minimumCharges"] as? Map<*, *> ?: return@mapNotNull null
                ScheduledRateCard(
                    effectiveMonth = entry["effectiveMonth"] as? String ?: return@mapNotNull null,
                    card = RateCard(
                        minChargeThreshold = (entry["minChargeThreshold"] as? Number)?.toDouble() ?: return@mapNotNull null,
                        commodityRate = (entry["commodityRate"] as? Number)?.toDouble() ?: return@mapNotNull null,
                        minimumCharges = RateCard.CLASSIFICATIONS.associateWith { c ->
                            (charges[c] as? Number)?.toDouble() ?: Double.NaN
                        },
                    ),
                )
            }
            return RateSchedule(entries)
        }
    }
}
