package com.waterdistrict.meterreader.data.local.entity

import androidx.room.ColumnInfo
import com.waterdistrict.meterreader.domain.billing.DEFAULT_MINIMUM_CHARGES
import com.waterdistrict.meterreader.domain.billing.RateCard
import com.waterdistrict.meterreader.domain.billing.WaterRateConfig
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync lifecycle of a reading record.
 *
 * PENDING  → freshly saved on device, not yet uploaded
 * SYNCING  → upload in progress (set by WorkManager before POST)
 * SYNCED   → confirmed by server (response 200)
 * FAILED   → upload failed after max retries
 */
enum class SyncStatus { PENDING, SYNCING, SYNCED, FAILED }

/**
 * A single meter reading captured by the field worker.
 *
 * Foreign-keyed to [ConsumerEntity] so readings are always associated with
 * a valid consumer, even while offline.
 *
 * @property id             Auto-generated local primary key
 * @property accountNo      FK → ConsumerEntity.accountNo
 * @property prevReading    Copy of the previous reading at capture time (m³)
 * @property currentReading Raw reading taken from the meter (m³)
 * @property consumption      Computed: currentReading - prevReading  (m³)
 * @property minimumCharge    Flat minimum charge for the consumer's classification (₱)
 * @property commodityCharge  Consumption beyond the minimum-charge allowance × ₱10.80/m³
 * @property overdueBalance   Outstanding balance carried over from a previous period (₱)
 * @property overdueSurcharge 3% surcharge on [overdueBalance], once past the 15-day grace period
 * @property extensionFee     Flat ₱10 delinquency-pursuit fee, once past the 15-day grace period
 * @property totalAmountDue   Final billable amount (₱)
 * @property dueDateMillis         Pay on/before this date to avoid the overdue surcharge
 * @property projectedOverdueTotal What [totalAmountDue] becomes if this bill isn't paid by [dueDateMillis]
 * @property readingDate    Unix epoch millis when the reading was captured
 * @property syncStatus     Upload state (see [SyncStatus])
 * @property serverId       Server-assigned ID after successful sync (nullable)
 * @property orNumber       Official Receipt number — assigned server-side, atomically, once this
 *                          reading uploads (blank until then; offline-generated numbers could
 *                          collide across devices, so it's never generated on the phone)
 * @property readByUserId   ID of the field worker who took this reading
 * @property remarks        Optional field notes (e.g. "Meter covered by mud")
 */
@Entity(
    tableName = "readings",
    foreignKeys = [
        ForeignKey(
            entity = ConsumerEntity::class,
            parentColumns = ["account_no"],
            childColumns = ["account_no"],
            onDelete = ForeignKey.CASCADE   // removes readings if consumer is purged
        )
    ],
    indices = [
        Index(value = ["account_no"]),
        Index(value = ["sync_status"]),
        // One reading per account per cycle. Corrections replace the row for
        // that month; a new month gets its own. Previously the code reused
        // "the latest reading for this account" regardless of month, so
        // September's save overwrote August's row and the August bill could no
        // longer be viewed or reprinted from the device.
        Index(value = ["account_no", "billing_month"], unique = true)
    ]
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_no")
    val accountNo: String,

    @ColumnInfo(name = "prev_reading")
    val prevReading: Double,

    @ColumnInfo(name = "current_reading")
    val currentReading: Double,

    @ColumnInfo(name = "consumption")
    val consumption: Double,

    // ── Billing components ────────────────────────────────────────────────────
    @ColumnInfo(name = "minimum_charge")
    val minimumCharge: Double,

    @ColumnInfo(name = "commodity_charge")
    val commodityCharge: Double,

    @ColumnInfo(name = "overdue_balance")
    val overdueBalance: Double,

    @ColumnInfo(name = "overdue_surcharge")
    val overdueSurcharge: Double,

    @ColumnInfo(name = "extension_fee")
    val extensionFee: Double = 0.0,

    /** Advance payment on the account applied against this bill. */
    @ColumnInfo(name = "credit_applied")
    val creditApplied: Double = 0.0,

    @ColumnInfo(name = "total_amount_due")
    val totalAmountDue: Double,

    @ColumnInfo(name = "due_date_millis")
    val dueDateMillis: Long,

    @ColumnInfo(name = "projected_overdue_total")
    val projectedOverdueTotal: Double,
    // ─────────────────────────────────────────────────────────────────────────

    @ColumnInfo(name = "reading_date")
    val readingDate: Long = System.currentTimeMillis(),

    /** Billing cycle this reading belongs to, e.g. "AUG 2026". */
    @ColumnInfo(name = "billing_month")
    val billingMonth: String = "",

    /**
     * The authoritative total the server calculated on upload, when it differs
     * from [totalAmountDue] as printed in the field.
     *
     * The phone bills against the balance it downloaded, which can be stale —
     * the office may have taken a payment while the reader was out. The upload
     * transaction recomputes against the live balance, and when the result
     * differs the corrected figure lands here so the office knows to reissue.
     * Null means the printed bill was correct.
     */
    @ColumnInfo(name = "server_total_amount_due")
    val serverTotalAmountDue: Double? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING,

    @ColumnInfo(name = "server_id")
    val serverId: String? = null,

    @ColumnInfo(name = "or_number")
    val orNumber: String = "",

    @ColumnInfo(name = "read_by_user_id")
    val readByUserId: String,

    @ColumnInfo(name = "remarks")
    val remarks: String = "",

    /**
     * The rates this reading was billed at: ₱ per m³, and the m³ the minimum
     * charge covers. Taken from the rate schedule the phone last synced, when
     * the reading is saved, and reused when it uploads — so the office's
     * record matches the bill the household was handed, even if the admin
     * changed the rates in between. Null on readings from before rates could
     * change, which were all billed at the base rates.
     */
    @ColumnInfo(name = "commodity_rate")
    val commodityRate: Double? = null,

    @ColumnInfo(name = "min_charge_threshold")
    val minChargeThreshold: Double? = null
)

/**
 * The rates this reading was billed at, rebuilt from what it recorded — so
 * the upload works the bill out exactly as the phone printed it. A reading
 * from before rates could change recorded none, and was billed at the base
 * rates.
 */
fun ReadingEntity.billedRates(classification: String): WaterRateConfig {
    val rate = commodityRate
    val threshold = minChargeThreshold
    if (rate == null || threshold == null) return RateCard.BASE.toConfig()
    return WaterRateConfig(
        minChargeThreshold = threshold,
        commodityRate = rate,
        // The minimum charge this reading was given is the one its
        // classification had under those rates.
        minimumCharges = DEFAULT_MINIMUM_CHARGES + (classification.trim().uppercase() to minimumCharge),
    )
}

/**
 * What rounding this bill to a whole peso added or took off — whatever the
 * total differs from its itemised lines by. Worked out rather than stored:
 * it is exactly that difference, and a reading from before bills were rounded
 * comes out at zero.
 */
val ReadingEntity.roundingAdjustment: Double
    get() {
        val lines = minimumCharge + commodityCharge + overdueBalance + overdueSurcharge + extensionFee - creditApplied
        return Math.round((totalAmountDue - lines) * 100.0) / 100.0
    }
