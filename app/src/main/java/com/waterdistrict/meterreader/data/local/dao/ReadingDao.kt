package com.waterdistrict.meterreader.data.local.dao

import androidx.room.*
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

/** Row projection for [ReadingDao.getReadingIdsByAccount]. */
data class AccountReadingId(
    @ColumnInfo(name = "account_no") val accountNo: String,
    @ColumnInfo(name = "id") val id: Long
)

/**
 * Data Access Object for [ReadingEntity].
 *
 * Reading inserts happen offline; sync queries are invoked by WorkManager.
 */
@Dao
interface ReadingDao {

    // ─────────────────────────────────────────────────────────────────────────
    // Writes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persist a new reading. Returns the auto-generated local ID so the UI
     * can immediately reference the record (e.g., for printing).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: ReadingEntity): Long

    @Update
    suspend fun update(reading: ReadingEntity)

    @Delete
    suspend fun delete(reading: ReadingEntity)

    /**
     * Targeted status update — avoids loading the full entity into memory
     * just to flip a single field. Used heavily by WorkManager.
     *
     * @param id        The local primary key
     * @param status    The new [SyncStatus]
     * @param serverId  Server-assigned ID returned after a successful POST
     * @param orNumber  The OR number assigned atomically server-side on upload, if any —
     *                  left null (leaving the column unchanged) for non-upload status flips.
     */
    @Query(
        """
        UPDATE readings
        SET    sync_status = :status,
               server_id   = :serverId,
               or_number   = COALESCE(:orNumber, or_number)
        WHERE  id = :id
        """
    )
    suspend fun updateSyncStatus(id: Long, status: SyncStatus, serverId: String? = null, orNumber: String? = null)

    /**
     * Bulk status flip for multiple IDs (e.g., optimistic batch update
     * before a bulk upload API call).
     */
    @Query("UPDATE readings SET sync_status = :status WHERE id IN (:ids)")
    suspend fun updateSyncStatusForIds(ids: List<Long>, status: SyncStatus)

    /**
     * Returns any reading left mid-upload to the PENDING queue.
     *
     * The sync worker flips rows to SYNCING before uploading them as an
     * optimistic lock. If the process dies in between — Android's ten-minute
     * worker limit, doze, low memory, or the reader force-stopping the app —
     * nothing ever moved them back, so they became invisible to both
     * getPendingReadingsOnce() and the pending badge. A day of field work
     * would sit unsynced while the app reported everything as uploaded.
     *
     * Called at the start of every worker run; safe because only one sync runs
     * at a time (ExistingWorkPolicy on a unique work name).
     */
    @Query("UPDATE readings SET sync_status = 'PENDING' WHERE sync_status = 'SYNCING'")
    suspend fun requeueInterruptedUploads(): Int

    /** Records the authoritative total the server calculated, when it differs from the printed one. */
    @Query("UPDATE readings SET server_total_amount_due = :serverTotal WHERE id = :id")
    suspend fun setServerTotal(id: Long, serverTotal: Double?)

    // ─────────────────────────────────────────────────────────────────────────
    // Reads — Flow
    // ─────────────────────────────────────────────────────────────────────────

    /** Observe all readings for an account, newest first. */
    @Query(
        """
        SELECT * FROM readings
        WHERE  account_no = :accountNo
        ORDER  BY reading_date DESC
        """
    )
    fun getReadingsForAccount(accountNo: String): Flow<List<ReadingEntity>>

    /**
     * The reading captured for an account IN A GIVEN BILLING CYCLE.
     *
     * Scoped to the month on purpose. The old "latest reading for this
     * account, any month" query meant September's screen opened showing
     * August's bill as "already recorded", and saving September's reading
     * reused August's row id — replacing it, so the August bill could no
     * longer be viewed or reprinted from the device.
     */
    @Query(
        """
        SELECT * FROM readings
        WHERE  account_no = :accountNo AND billing_month = :billingMonth
        LIMIT  1
        """
    )
    fun getReadingForAccountInMonth(accountNo: String, billingMonth: String): Flow<ReadingEntity?>

    /** Observe all PENDING readings — drives the sync badge in the UI. */
    @Query("SELECT * FROM readings WHERE sync_status = 'PENDING' ORDER BY reading_date ASC")
    fun getPendingReadings(): Flow<List<ReadingEntity>>

    /**
     * Accounts already read IN THE GIVEN CYCLE.
     *
     * Previously `SELECT DISTINCT account_no FROM readings` with no month
     * filter, so from the second billing month onward every consumer read in
     * any earlier month still showed as done — the route list opened fully
     * green and the reader had no way to see what was left.
     */
    @Query("SELECT DISTINCT account_no FROM readings WHERE billing_month = :billingMonth")
    fun getReadAccountNumbers(billingMonth: String): Flow<List<String>>

    /** This cycle's local reading id per account — lets the consumer list jump straight to its bill. */
    @Query(
        """
        SELECT account_no, id FROM readings
        WHERE  billing_month = :billingMonth
        """
    )
    fun getReadingIdsByAccount(billingMonth: String): Flow<List<AccountReadingId>>

    // ─────────────────────────────────────────────────────────────────────────
    // Reads — suspend (one-shot, for WorkManager / background tasks)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * One-shot PENDING list used by the sync worker.
     * Sorted ascending so older readings are uploaded first.
     */
    @Query("SELECT * FROM readings WHERE sync_status = 'PENDING' ORDER BY reading_date ASC")
    suspend fun getPendingReadingsOnce(): List<ReadingEntity>

    /** One-shot fetch by local ID — used after insertion to retrieve full record. */
    @Query("SELECT * FROM readings WHERE id = :id LIMIT 1")
    suspend fun getReadingById(id: Long): ReadingEntity?

    /** One-shot fetch of this cycle's reading for an account — used to decide whether
     *  a bill Firestore already knows about needs to be hydrated into a local row. */
    @Query(
        """
        SELECT * FROM readings
        WHERE  account_no = :accountNo AND billing_month = :billingMonth
        LIMIT  1
        """
    )
    suspend fun getReadingForAccountInMonthOnce(accountNo: String, billingMonth: String): ReadingEntity?

    // ─────────────────────────────────────────────────────────────────────────
    // Aggregates — for dashboard / summary screens
    // ─────────────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM readings WHERE sync_status = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM readings WHERE sync_status = 'SYNCED'")
    fun getSyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM readings")
    fun getTotalReadingCount(): Flow<Int>
}
