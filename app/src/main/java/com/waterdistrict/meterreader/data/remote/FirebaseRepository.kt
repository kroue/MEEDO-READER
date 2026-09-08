package com.waterdistrict.meterreader.data.remote

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.domain.billing.BillingMonth
import com.waterdistrict.meterreader.domain.billing.WaterBillingCalculator
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * The Barangays (and billing month) the admin has currently handed to this
 * signed-in field reader via the water-billing-admin Mobile Sync page. An
 * empty list means nothing has been assigned yet. When more than one
 * Barangay is assigned, the reader picks which one to work on first.
 */
data class MobileAssignment(
    val barangays: List<String> = emptyList(),
    val monthStr: String? = null
)

/**
 * A consumer paired with the full breakdown of a bill already recorded for
 * the current cycle (from Firestore), if any — lets the caller hydrate a
 * real local [ReadingEntity] even on a device that never captured it, so
 * "View Bill" / "Print" work the same regardless of which device read it.
 */
data class DownloadedConsumer(
    val consumer: ConsumerEntity,
    val currentMonthReading: ReconciledReading? = null
)

data class ReconciledReading(
    val currentReading: Double,
    val previousReading: Double,
    val minimumCharge: Double,
    val commodityCharge: Double,
    val overdueBalance: Double,
    val overdueSurcharge: Double,
    val extensionFee: Double,
    val creditApplied: Double,
    val totalAmountDue: Double,
    val dueDateMillis: Long,
    val projectedOverdueTotal: Double,
    val billingDateMillis: Long,
    val orNumber: String
)

/**
 * Outcome of pushing one reading to Firestore.
 *
 * @property orNumber           OR number assigned to the bill (existing or newly issued).
 * @property serverTotalAmountDue What the bill actually came to once recalculated against the
 *                              server's live balance.
 * @property differsFromPrinted True when that figure isn't what the reader printed in the field —
 *                              the office needs to reissue the bill.
 */
data class UploadOutcome(
    val orNumber: String,
    val serverTotalAmountDue: Double,
    val differsFromPrinted: Boolean
)

@Singleton
class FirebaseRepository @Inject constructor() {
    private val db = FirebaseFirestore.getInstance()

    /**
     * Listens to this signed-in reader's users/{uid} document in real time,
     * so the device reacts as soon as the admin assigns or recalls a
     * Barangay for them — no manual sync step needed to see the change.
     */
    fun listenForUserAssignment(uid: String): Flow<MobileAssignment> = callbackFlow {
        val registration = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirebaseRepository", "Error listening for user assignment", error)
                    return@addSnapshotListener
                }
                @Suppress("UNCHECKED_CAST")
                val barangays = (snapshot?.get("assignedBarangays") as? List<String>) ?: emptyList()
                trySend(
                    MobileAssignment(
                        barangays = barangays,
                        monthStr = snapshot?.getString("assignedMonthStr")?.takeIf { it.isNotBlank() }
                    )
                )
            }
        awaitClose { registration.remove() }
    }

    suspend fun fetchAssignedConcessionaires(
        barangay: String,
        monthStr: String
    ): Result<List<DownloadedConsumer>> {
        return try {
            val querySnapshot = db.collection("concessionaires")
                .whereEqualTo("barangay", barangay)
                .whereEqualTo("status", "CONNECTED")
                .whereEqualTo("assignedForReading", monthStr)
                .get()
                .await()

            val consumers = querySnapshot.documents.mapNotNull { doc ->
                try {
                    val id = doc.id
                    val accountNo = doc.getString("meterNumber") ?: id // Fallback to id if no meter number
                    val firstName = doc.getString("firstName") ?: ""
                    val lastName = doc.getString("lastName") ?: ""
                    val name = "$firstName $lastName".trim()
                    val address = doc.getString("purok")?.let { "Purok $it, $barangay" } ?: barangay
                    val meterNo = doc.getString("meterNumber") ?: "N/A"
                    val classification = doc.getString("classification") ?: "RESIDENTIAL"

                    @Suppress("UNCHECKED_CAST")
                    val billingHistory = doc.get("billingHistory") as? List<Map<String, Any>>
                    // Ordered by month, not by position. Firestore preserves array
                    // order, but that order is chronological only by accident: a
                    // corrected reading is appended at the end, and the admin's XLSX
                    // importer writes rows in whatever order the sheet had. Trusting
                    // position put the wrong month's reading in "previous reading",
                    // and every consumption from then on was wrong.
                    val orderedHistory = billingHistory
                        ?.sortedBy { BillingMonth.sortKey(it["month"] as? String) }
                        .orEmpty()

                    val currentMonthRecord = orderedHistory.lastOrNull { (it["month"] as? String) == monthStr }
                    val lastPriorRecord = orderedHistory.lastOrNull { (it["month"] as? String) != monthStr }

                    val prevReading = (lastPriorRecord?.get("reading") as? Number)?.toDouble() ?: 0.0
                    val prevReadingMonth = lastPriorRecord?.get("month") as? String ?: ""

                    // The balance this consumer carried in BEFORE the current cycle's
                    // bill. If they've already been billed this month (a correction, or
                    // a re-download after syncing), billingBalance now includes this
                    // month's own water charge — using it as the overdue base would bill
                    // the month a second time on top of itself.
                    val storedBalance = doc.getDouble("billingBalance") ?: 0.0
                    val priorBalance = max(0.0, storedBalance - chargesAddedBy(currentMonthRecord))

                    val creditBalance = doc.getDouble("creditBalance") ?: 0.0

                    // When the balance last went from zero to owing — the reference
                    // point for the 15-day grace period, the 3% surcharge and the ₱10
                    // extension fee. Falls back to the oldest unpaid bill for documents
                    // written before the field existed.
                    val delinquentSinceMillis = parseIsoMillis(doc.getString("delinquentSince"))
                        ?: orderedHistory
                            .filter { record ->
                                val peso = (record["pesoAmount"] as? Number)?.toDouble() ?: 0.0
                                val paid = (record["amountPaid"] as? Number)?.toDouble() ?: 0.0
                                paid < peso
                            }
                            .mapNotNull { record -> parseIsoMillis(record["billingDate"] as? String) }
                            .minOrNull()

                    // The ₱10 extension fee is charged once per delinquency, not once per
                    // bill — derived (not separately tracked) by checking whether any bill
                    // issued since this debt began already carried the fee.
                    val extensionFeeAlreadyCharged = delinquentSinceMillis?.let { streakStart ->
                        orderedHistory.any { record ->
                            val recordMillis = parseIsoMillis(record["billingDate"] as? String)
                            val feeCharged = record["extensionFeeCharged"] as? Boolean ?: false
                            recordMillis != null && recordMillis >= streakStart && feeCharged
                        }
                    } ?: false

                    val consumerEntity = ConsumerEntity(
                        accountNo = accountNo,
                        name = name,
                        address = address,
                        meterNo = meterNo,
                        prevReading = prevReading,
                        routeId = barangay,
                        firebaseId = id,
                        prevReadingMonth = prevReadingMonth,
                        classification = classification,
                        overdueBalance = priorBalance,
                        delinquentSinceMillis = delinquentSinceMillis,
                        creditBalance = creditBalance,
                        extensionFeeAlreadyCharged = extensionFeeAlreadyCharged,
                        alreadyBilledThisMonth = currentMonthRecord != null,
                        billedReadingThisMonth = (currentMonthRecord?.get("reading") as? Number)?.toDouble() ?: 0.0,
                        billedAmountThisMonth = (currentMonthRecord?.get("pesoAmount") as? Number)?.toDouble() ?: 0.0,
                        billingMonth = monthStr,
                        syncStatus = "PENDING"
                    )

                    val reconciledReading = currentMonthRecord?.let { record ->
                        val billingDateMillis = parseIsoMillis(record["billingDate"] as? String)
                        val dueDateMillis = (record["dueDateMillis"] as? Number)?.toLong()
                        // Only reconstructable if the itemized fields were actually written
                        // (records created before this was tracked won't have them).
                        if (billingDateMillis == null || dueDateMillis == null) {
                            null
                        } else {
                            ReconciledReading(
                                currentReading = (record["reading"] as? Number)?.toDouble() ?: 0.0,
                                previousReading = (record["previousReading"] as? Number)?.toDouble() ?: 0.0,
                                minimumCharge = (record["minimumCharge"] as? Number)?.toDouble() ?: 0.0,
                                commodityCharge = (record["commodityCharge"] as? Number)?.toDouble() ?: 0.0,
                                overdueBalance = (record["overdueBalance"] as? Number)?.toDouble() ?: 0.0,
                                overdueSurcharge = (record["overdueSurcharge"] as? Number)?.toDouble() ?: 0.0,
                                extensionFee = (record["extensionFee"] as? Number)?.toDouble() ?: 0.0,
                                creditApplied = (record["creditApplied"] as? Number)?.toDouble() ?: 0.0,
                                totalAmountDue = (record["pesoAmount"] as? Number)?.toDouble() ?: 0.0,
                                dueDateMillis = dueDateMillis,
                                projectedOverdueTotal = (record["projectedOverdueTotal"] as? Number)?.toDouble() ?: 0.0,
                                billingDateMillis = billingDateMillis,
                                orNumber = (record["orNumber"] as? String) ?: ""
                            )
                        }
                    }

                    DownloadedConsumer(consumerEntity, reconciledReading)
                } catch (e: Exception) {
                    Log.e("FirebaseRepository", "Error parsing doc ${doc.id}", e)
                    null
                }
            }

            Result.success(consumers)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error fetching concessionaires", e)
            Result.failure(e)
        }
    }

    /**
     * Writes a captured reading into the concessionaire's `billingHistory` in
     * Firestore — the same array the admin billing page reads — and assigns it
     * an Official Receipt number atomically via a shared counter
     * (settings/orCounter). The number is only ever generated here, inside the
     * transaction: generating it on the phone would risk two offline devices
     * producing the same number.
     *
     * ### Why the bill is recalculated here rather than trusted
     *
     * The phone bills against the balance it downloaded, which may be hours or
     * days old. It previously wrote `billingBalance = reading.totalAmountDue`
     * — an absolute value computed from that stale snapshot — so a payment
     * taken at the office while the reader was in the field was simply
     * overwritten: the receipt stayed in `payments[]`, the balance went back
     * up, and the concessionaire was billed again for money they had already
     * paid, with a receipt in hand to prove it.
     *
     * So the only thing the field visit actually determines is the meter
     * reading. Everything else — the carried balance, the surcharge, the
     * extension fee, any advance credit — is server state, and is read fresh
     * inside this transaction and recalculated against it. When the result
     * differs from what was printed in the field, [UploadOutcome.differsFromPrinted]
     * says so, and the office reissues that bill.
     *
     * Re-uploading a corrected reading replaces the entry for the same month
     * rather than appending, reuses its OR number rather than burning a new
     * one, and backs that entry's own charges out of the balance first — so a
     * correction can never compound the previous attempt.
     */
    suspend fun uploadReading(reading: ReadingEntity, consumer: ConsumerEntity): Result<UploadOutcome> {
        return try {
            val monthStr = reading.billingMonth.ifBlank { BillingMonth.of(reading.readingDate) }
            val billingYear = monthStr.substringAfterLast(' ').toIntOrNull()
                ?: Instant.ofEpochMilli(reading.readingDate).atZone(java.time.ZoneId.systemDefault()).year

            val docRef = db.collection("concessionaires").document(consumer.firebaseId)
            val counterRef = db.collection("settings").document("orCounter")

            val outcome = db.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                @Suppress("UNCHECKED_CAST")
                val existingHistory = snapshot.get("billingHistory") as? List<Map<String, Any>> ?: emptyList()
                val existingRecordForMonth = existingHistory.firstOrNull { it["month"] == monthStr }

                // ── Recalculate against the LIVE balance ─────────────────────
                val storedBalance = snapshot.getDouble("billingBalance") ?: 0.0
                val storedCredit = snapshot.getDouble("creditBalance") ?: 0.0
                val waterMeterBalance = snapshot.getDouble("waterMeterBalance") ?: 0.0

                // Back this month's own charges out, so correcting a reading
                // recomputes from the pre-bill position instead of stacking on
                // top of the previous attempt. A payment made since the bill
                // was issued is preserved: it reduced storedBalance, and so it
                // reduces the recovered prior balance too.
                val priorBalance = max(
                    0.0,
                    storedBalance - chargesAddedBy(existingRecordForMonth)
                )
                val creditAvailable = storedCredit + creditAppliedBy(existingRecordForMonth)

                val delinquentSince = parseIsoMillis(snapshot.getString("delinquentSince"))
                val extensionFeeAlreadyCharged = delinquentSince?.let { streakStart ->
                    existingHistory.any { record ->
                        if (record["month"] == monthStr) return@any false
                        val recordMillis = parseIsoMillis(record["billingDate"] as? String)
                        val feeCharged = record["extensionFeeCharged"] as? Boolean ?: false
                        recordMillis != null && recordMillis >= streakStart && feeCharged
                    }
                } ?: false

                val billing = WaterBillingCalculator.calculate(
                    previousReading = reading.prevReading,
                    currentReading = reading.currentReading,
                    classification = consumer.classification,
                    overdueBalance = priorBalance,
                    delinquentSinceMillis = delinquentSince,
                    creditBalance = creditAvailable,
                    now = reading.readingDate,
                    extensionFeeAlreadyCharged = extensionFeeAlreadyCharged
                )

                // ── OR number ────────────────────────────────────────────────
                val reusableOrNumber = (existingRecordForMonth?.get("orNumber") as? String)
                    ?.takeIf { it.isNotBlank() }

                val orNumber = reusableOrNumber ?: run {
                    val counterSnapshot = transaction.get(counterRef)
                    val counterYear = counterSnapshot.getLong("year")?.toInt()
                    val lastNumber = counterSnapshot.getLong("lastNumber") ?: 0L
                    val nextNumber = if (counterYear == billingYear) lastNumber + 1 else 1L

                    transaction.set(counterRef, mapOf("year" to billingYear, "lastNumber" to nextNumber))
                    "OR-$billingYear-" + nextNumber.toString().padStart(6, '0')
                }

                val record = mapOf(
                    "month" to monthStr,
                    "reading" to reading.currentReading,
                    "previousReading" to reading.prevReading,
                    "pesoAmount" to billing.totalAmountDue,
                    "orNumber" to orNumber,
                    // Preserve whatever the office has already collected against
                    // this cycle; a corrected reading must not wipe a payment.
                    "amountPaid" to ((existingRecordForMonth?.get("amountPaid") as? Number)?.toDouble() ?: 0.0),
                    "billingDate" to Instant.ofEpochMilli(reading.readingDate).toString(),
                    "extensionFeeCharged" to (billing.extensionFee > 0),
                    // Full itemized breakdown — not just the aggregate — so any
                    // device (not only the one that captured it) can reconstruct
                    // and view/print this exact bill, and so a later correction
                    // can back these exact charges out again.
                    "minimumCharge" to billing.minimumCharge,
                    "commodityCharge" to billing.commodityCharge,
                    "overdueBalance" to billing.overdueBalance,
                    "overdueSurcharge" to billing.overdueSurcharge,
                    "extensionFee" to billing.extensionFee,
                    "creditApplied" to billing.creditApplied,
                    "meterRolledOver" to billing.meterRolledOver,
                    "dueDateMillis" to billing.dueDateMillis,
                    "projectedOverdueTotal" to billing.projectedOverdueTotal
                )

                val updatedHistory = existingHistory.filter { it["month"] != monthStr } + record

                val newBillingBalance = billing.totalAmountDue
                val newCreditBalance = billing.creditRemaining

                // The delinquency clock starts when a balance appears on a
                // cleared account and stops when it's paid off — it is NOT
                // restarted by each new bill, which is what let a years-old
                // debt read as three days overdue.
                val delinquencyUpdate: Any? = when {
                    newBillingBalance <= 0 -> FieldValue.delete()
                    delinquentSince == null -> Instant.ofEpochMilli(reading.readingDate).toString()
                    else -> null // already delinquent; leave the original date alone
                }

                val updates = mutableMapOf<String, Any>(
                    "billingHistory" to updatedHistory,
                    "billingBalance" to newBillingBalance,
                    "creditBalance" to newCreditBalance,
                    "totalBalance" to round((newBillingBalance + waterMeterBalance) * 100.0) / 100.0,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                delinquencyUpdate?.let { updates["delinquentSince"] = it }

                transaction.update(docRef, updates)

                UploadOutcome(
                    orNumber = orNumber,
                    serverTotalAmountDue = billing.totalAmountDue,
                    // Half a centavo of float drift isn't a reissue.
                    differsFromPrinted = abs(billing.totalAmountDue - reading.totalAmountDue) >= 0.01
                )
            }.await()

            Result.success(outcome)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error uploading reading for ${consumer.accountNo}", e)
            Result.failure(e)
        }
    }

    /**
     * What a billing record added to the running balance on top of whatever it
     * carried in — the water charge plus surcharge plus extension fee, less any
     * advance credit it consumed. Subtracting this from the current balance
     * recovers the position before that bill was issued.
     */
    private fun chargesAddedBy(record: Map<String, Any>?): Double {
        if (record == null) return 0.0
        val minimum = (record["minimumCharge"] as? Number)?.toDouble()
        val commodity = (record["commodityCharge"] as? Number)?.toDouble()
        val surcharge = (record["overdueSurcharge"] as? Number)?.toDouble() ?: 0.0
        val extension = (record["extensionFee"] as? Number)?.toDouble() ?: 0.0
        val credit = (record["creditApplied"] as? Number)?.toDouble() ?: 0.0

        // Records written before the itemized breakdown existed: fall back to
        // the difference between the total and what it carried in.
        if (minimum == null && commodity == null) {
            val peso = (record["pesoAmount"] as? Number)?.toDouble() ?: 0.0
            val carried = (record["overdueBalance"] as? Number)?.toDouble() ?: 0.0
            return max(0.0, peso - carried)
        }
        return (minimum ?: 0.0) + (commodity ?: 0.0) + surcharge + extension - credit
    }

    /** Advance credit a billing record consumed, so a correction can hand it back. */
    private fun creditAppliedBy(record: Map<String, Any>?): Double =
        (record?.get("creditApplied") as? Number)?.toDouble() ?: 0.0

    private fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
