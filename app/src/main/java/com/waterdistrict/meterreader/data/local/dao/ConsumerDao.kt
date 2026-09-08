package com.waterdistrict.meterreader.data.local.dao

import androidx.room.*
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ConsumerEntity].
 *
 * All query results are exposed as [Flow] so that the UI layer automatically
 * receives updated data whenever the underlying table changes — no manual
 * refresh calls required.
 */
@Dao
interface ConsumerDao {

    // ─────────────────────────────────────────────────────────────────────────
    // Writes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bulk-insert consumers downloaded from the server.
     * REPLACE strategy handles re-downloads: if the server sends an updated
     * record for an existing accountNo, the local row is overwritten.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(consumers: List<ConsumerEntity>)

    /** Single insert, e.g. for manual additions. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(consumer: ConsumerEntity)

    /** Full object update; Room matches on primary key. */
    @Update
    suspend fun update(consumer: ConsumerEntity)

    /** Remove a single consumer and cascade-delete its readings. */
    @Delete
    suspend fun delete(consumer: ConsumerEntity)

    /** Purge all consumers for a given route (e.g., before re-download). */
    @Query("DELETE FROM consumers WHERE route_id = :routeId")
    suspend fun deleteByRoute(routeId: String)

    /**
     * Drops consumers left over from earlier billing cycles.
     *
     * Local rows were never cleared, so a route reassigned to another reader —
     * or an account that moved to DISCONNECTED — lingered on the device
     * indefinitely, still listed and still showing whatever balance it had
     * months ago.
     *
     * Any consumer still holding an unsynced reading is kept, whatever cycle
     * it belongs to: the foreign key cascades readings away with their
     * consumer, and a reading that has not reached Firestore exists nowhere
     * else. Readings that HAVE synced do go with the pruned consumer — they
     * are safely on the server, with the full itemised breakdown, and come
     * back via hydrateReconciledReadings if that account is ever reassigned.
     */
    @Query(
        """
        DELETE FROM consumers
        WHERE  billing_month != :billingMonth
          AND  account_no NOT IN (
                 SELECT account_no FROM readings WHERE sync_status != 'SYNCED'
               )
        """
    )
    suspend fun deleteStaleCycles(billingMonth: String): Int

    // ─────────────────────────────────────────────────────────────────────────
    // Reads — Flow-based (auto-updates UI on table change)
    // ─────────────────────────────────────────────────────────────────────────

    /** Observe all consumers, ordered alphabetically by name. */
    @Query("SELECT * FROM consumers ORDER BY name ASC")
    fun getAllConsumers(): Flow<List<ConsumerEntity>>

    /** Observe consumers belonging to a specific route in a specific billing cycle. */
    @Query(
        """
        SELECT * FROM consumers
        WHERE  route_id = :routeId AND billing_month = :billingMonth
        ORDER  BY name ASC
        """
    )
    fun getConsumersByRoute(routeId: String, billingMonth: String): Flow<List<ConsumerEntity>>

    /** Observe a single consumer record (useful for detail screens). */
    @Query("SELECT * FROM consumers WHERE account_no = :accountNo LIMIT 1")
    fun getConsumerByAccountNo(accountNo: String): Flow<ConsumerEntity?>

    /** One-shot fetch (not a Flow) — useful for WorkManager background tasks. */
    @Query("SELECT * FROM consumers WHERE account_no = :accountNo LIMIT 1")
    suspend fun getConsumerByAccountNoOnce(accountNo: String): ConsumerEntity?

    /** Full-text search on name or address (for the search bar). */
    @Query(
        """
        SELECT * FROM consumers
        WHERE name     LIKE '%' || :query || '%'
           OR address  LIKE '%' || :query || '%'
           OR account_no LIKE '%' || :query || '%'
        ORDER BY name ASC
        """
    )
    fun searchConsumers(query: String): Flow<List<ConsumerEntity>>

    /** Count of consumers per route — handy for route-download progress UI. */
    @Query("SELECT COUNT(*) FROM consumers WHERE route_id = :routeId")
    suspend fun countByRoute(routeId: String): Int
}
