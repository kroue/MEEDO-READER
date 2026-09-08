package com.waterdistrict.meterreader.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.data.local.entity.SyncStatus
import com.waterdistrict.meterreader.data.remote.FirebaseRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * # SyncReadingsWorker
 *
 * A [CoroutineWorker] that uploads all PENDING meter readings to Firestore
 * whenever the device has network connectivity — each reading is appended
 * to its concessionaire's `billingHistory` array.
 *
 * ### Scheduling policy
 * - Triggered by [WorkManager] with [NetworkType.CONNECTED] constraint.
 * - Uses **exponential back-off** (starting at 30 s, capped at 5 min) so
 *   partial failures do not hammer a flaky connection.
 * - Injected by Hilt via [@HiltWorker] / [@AssistedInject].
 *
 * ### Failure handling
 * | Scenario                                | Result              |
 * |-----------------------------------------|---------------------|
 * | Network error / Firestore write failure | [Result.retry]      |
 * | No matching consumer for a reading      | marked FAILED       |
 * | All readings uploaded cleanly           | [Result.success]    |
 * | No PENDING readings found               | [Result.success]    |
 *
 * Only genuinely transient failures ask for a retry. A reading marked FAILED
 * for a missing `firebaseId` is a data problem the worker has already decided
 * will never succeed; counting it toward the retry decision kept the worker
 * rescheduling with exponential backoff forever, burning battery for nothing.
 */
@HiltWorker
class SyncReadingsWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val readingDao: ReadingDao,
    private val consumerDao: ConsumerDao,
    private val firebaseRepository: FirebaseRepository,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SyncReadingsWorker"

        // Keys for WorkInfo progress / output Data
        const val KEY_UPLOADED_COUNT   = "uploaded_count"
        const val KEY_FAILED_COUNT     = "failed_count"
        const val KEY_TOTAL_COUNT      = "total_count"
        const val KEY_REPRICED_COUNT   = "repriced_count"

        // WorkManager unique work name — ensures only one sync runs at a time
        const val UNIQUE_WORK_NAME = "sync_readings_work"

        /**
         * Build the [OneTimeWorkRequest] with network constraint and
         * exponential back-off, ready to be enqueued.
         */
        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncReadingsWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS   // initial delay; doubles each retry, max ~5 min
                )
                .addTag(UNIQUE_WORK_NAME)
                .build()

        /**
         * Enqueue the sync worker as [ExistingWorkPolicy.KEEP] — if a sync is
         * already queued/running, the new request is silently discarded.
         * Call this from the Repository whenever a new reading is saved.
         */
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,    // don't cancel an in-progress upload
                buildRequest()
            )
        }

        /**
         * Enqueue with [ExistingWorkPolicy.REPLACE] — forces a fresh sync,
         * e.g., when the user taps "Sync Now" in the UI.
         */
        fun forceEnqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildRequest()
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Worker entry point
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Sync worker started. Attempt #${runAttemptCount + 1}")

        // 0. Recover anything a previous run left mid-flight.
        //
        //    Rows are flipped to SYNCING before upload as an optimistic lock,
        //    and nothing used to move them back if the process died in between
        //    — Android's ten-minute worker limit, doze, low memory, or the
        //    reader force-stopping the app. Those readings became invisible to
        //    both the pending query and the pending badge: a day of field work
        //    would sit unsynced while the app showed everything as uploaded.
        //
        //    Safe to do unconditionally because only one sync runs at a time
        //    (unique work name + ExistingWorkPolicy), so any SYNCING row here
        //    is by definition abandoned.
        val requeued = readingDao.requeueInterruptedUploads()
        if (requeued > 0) {
            Log.w(TAG, "Requeued $requeued reading(s) left mid-upload by a previous run.")
        }

        // 1. Fetch all readings that need to be uploaded
        val pendingReadings = readingDao.getPendingReadingsOnce()
        if (pendingReadings.isEmpty()) {
            Log.i(TAG, "No pending readings found. Worker exiting cleanly.")
            return@withContext Result.success()
        }

        Log.i(TAG, "Found ${pendingReadings.size} pending reading(s).")

        // 2. Report initial progress so the UI can show a spinner
        setProgress(
            workDataOf(
                KEY_TOTAL_COUNT    to pendingReadings.size,
                KEY_UPLOADED_COUNT to 0,
                KEY_FAILED_COUNT   to 0
            )
        )

        // 3. Mark all as SYNCING (optimistic lock — prevents duplicate uploads
        //    if the worker is retried while a batch is in-flight)
        val idsToUpload = pendingReadings.map { it.id }
        readingDao.updateSyncStatusForIds(idsToUpload, SyncStatus.SYNCING)

        // 4. Upload each reading to Firestore
        var uploadedCount    = 0
        var permanentFailures = 0
        var transientFailures = 0
        var repricedCount    = 0

        for (reading in pendingReadings) {
            val consumer = consumerDao.getConsumerByAccountNoOnce(reading.accountNo)

            if (consumer == null || consumer.firebaseId.isBlank()) {
                // No matching Firestore document to append to — a data problem,
                // not a connectivity one, so don't keep retrying forever.
                Log.e(TAG, "No consumer/firebaseId found for reading ${reading.id} (${reading.accountNo})")
                readingDao.updateSyncStatus(reading.id, SyncStatus.FAILED)
                permanentFailures++
                continue
            }

            val result = firebaseRepository.uploadReading(reading, consumer)
            val outcome = result.getOrNull()
            if (outcome != null) {
                readingDao.updateSyncStatus(
                    id = reading.id,
                    status = SyncStatus.SYNCED,
                    serverId = consumer.firebaseId,
                    orNumber = outcome.orNumber
                )
                // The server recalculated this bill against the live balance,
                // which may have moved since the reader downloaded it (a payment
                // taken at the office, say). When the result isn't what was
                // printed in the field, record the authoritative figure so the
                // reader and the office can both see the bill needs reissuing.
                readingDao.setServerTotal(
                    reading.id,
                    outcome.serverTotalAmountDue.takeIf { outcome.differsFromPrinted }
                )
                if (outcome.differsFromPrinted) {
                    repricedCount++
                    Log.w(
                        TAG,
                        "Reading ${reading.id} repriced on upload: printed=${reading.totalAmountDue}, " +
                            "server=${outcome.serverTotalAmountDue}"
                    )
                }
                uploadedCount++
            } else {
                Log.w(TAG, "Failed to upload reading ${reading.id}: ${result.exceptionOrNull()?.message}")
                // Likely transient (network/Firestore) — leave PENDING for retry.
                readingDao.updateSyncStatus(reading.id, SyncStatus.PENDING)
                transientFailures++
            }
        }

        Log.i(
            TAG,
            "Sync complete. Uploaded=$uploadedCount, Repriced=$repricedCount, " +
                "Transient=$transientFailures, Permanent=$permanentFailures"
        )

        val outputData = workDataOf(
            KEY_TOTAL_COUNT    to pendingReadings.size,
            KEY_UPLOADED_COUNT to uploadedCount,
            KEY_FAILED_COUNT   to (transientFailures + permanentFailures),
            KEY_REPRICED_COUNT to repricedCount
        )

        // Retry only for failures another attempt could actually fix.
        if (transientFailures > 0) Result.retry() else Result.success(outputData)
    }
}
