package com.waterdistrict.meterreader.data.prefs

import android.content.Context
import com.waterdistrict.meterreader.domain.billing.RateSchedule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The water rate schedule the phone last downloaded, kept so a reading taken
 * out of signal is still billed at its month's rates.
 *
 * Replaced whenever the phone downloads a route or uploads readings. Until the
 * first time, it is empty — which bills at the base rates, exactly as every
 * phone did before an admin could change them.
 */
@Singleton
class RateScheduleStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("rate_schedule", Context.MODE_PRIVATE)

    private val _schedule = MutableStateFlow(load())
    val schedule: StateFlow<RateSchedule> = _schedule.asStateFlow()

    fun replace(schedule: RateSchedule) {
        prefs.edit().putString(KEY, encode(schedule)).apply()
        _schedule.value = schedule
    }

    private fun load(): RateSchedule =
        runCatching { decode(prefs.getString(KEY, null)) }.getOrDefault(RateSchedule.EMPTY)

    private companion object {
        const val KEY = "schedule"

        fun encode(schedule: RateSchedule): String = JSONArray(
            schedule.entries.map { entry ->
                JSONObject()
                    .put("effectiveMonth", entry.effectiveMonth)
                    .put("commodityRate", entry.card.commodityRate)
                    .put("minChargeThreshold", entry.card.minChargeThreshold)
                    .put("minimumCharges", JSONObject(entry.card.minimumCharges))
            }
        ).toString()

        /** Back into the stored shape, so it passes the same checks a download does. */
        fun decode(json: String?): RateSchedule {
            if (json.isNullOrBlank()) return RateSchedule.EMPTY
            val array = JSONArray(json)
            val entries = (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                val charges = entry.getJSONObject("minimumCharges")
                mapOf(
                    "effectiveMonth" to entry.getString("effectiveMonth"),
                    "commodityRate" to entry.getDouble("commodityRate"),
                    "minChargeThreshold" to entry.getDouble("minChargeThreshold"),
                    "minimumCharges" to charges.keys().asSequence().associateWith { charges.getDouble(it) },
                )
            }
            return RateSchedule.fromStored(entries)
        }
    }
}
