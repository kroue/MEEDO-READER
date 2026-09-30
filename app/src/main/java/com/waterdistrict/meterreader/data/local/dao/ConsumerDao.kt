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
     * Saves consumers downloaded from the server — inserting new ones and
     * updating existing ones IN PLACE.
     *
     * This must be an upsert, not `@Insert(onConflict = REPLACE)`. SQLite's
     * REPLACE resolves a primary-key conflict by DELETING the existing row and
     * inserting a fresh one, and `readings.account_no` references consumers
     * with ON DELETE CASCADE — so every route re-download silently deleted
     * every reading on that route that hadn't synced yet. Online that stayed
     * hidden, because readings upload within seconds of being saved. Offline,
     * where they wait for a connection, the next download (served from
     * Firestore's local cache) wiped them: the reading vanished, the household
     * showed as unread again, and nothing ever uploaded.
     */
    @Upsert
    suspend fun upsertAll(consumers: List<ConsumerEntity>)

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

    /**
     * The consumer on this route and cycle whose meter number, or account
     * number, is exactly [meterNo] (case and surrounding whitespace aside) — so
     * a reader who types the full number can open the household straight away
     * rather than picking it from the search results.
     */
    @Query(
        """
        SELECT * FROM consumers
        WHERE  route_id = :routeId
          AND  billing_month = :billingMonth
          AND  (UPPER(TRIM(meter_no)) = UPPER(TRIM(:meterNo))
                OR UPPER(TRIM(account_no)) = UPPER(TRIM(:meterNo))
                OR (office_account_no <> ''
                    AND UPPER(TRIM(office_account_no)) = UPPER(TRIM(:meterNo))))
        LIMIT  1
        """
    )
    suspend fun findOnRouteByMeter(routeId: String, billingMonth: String, meterNo: String): ConsumerEntity?

    /**
     * Every household on this route and cycle, with whether it has been read —
     * what the reader's search filters as they type (see HouseholdSearch).
     * Read on this device, or already billed on the server by another device,
     * counts as read, as it does in [countReadOnRoute].
     */
    @Query(
        """
        SELECT c.*,
               (c.already_billed_this_month = 1
                OR c.account_no IN (SELECT account_no FROM readings WHERE billing_month = :billingMonth)) AS isRead
        FROM   consumers c
        WHERE  c.route_id = :routeId
          AND  c.billing_month = :billingMonth
        """
    )
    fun householdsOnRoute(routeId: String, billingMonth: String): Flow<List<RouteHousehold>>

    /** How many accounts are on this route this cycle. */
    @Query("SELECT COUNT(*) FROM consumers WHERE route_id = :routeId AND billing_month = :billingMonth")
    fun countOnRoute(routeId: String, billingMonth: String): Flow<Int>

    /**
     * How many of them are done this cycle: read on this device, or already
     * billed on the server by another device.
     */
    @Query(
        """
        SELECT COUNT(*) FROM consumers
        WHERE  route_id = :routeId
          AND  billing_month = :billingMonth
          AND  (already_billed_this_month = 1
                OR account_no IN (SELECT account_no FROM readings WHERE billing_month = :billingMonth))
        """
    )
    fun countReadOnRoute(routeId: String, billingMonth: String): Flow<Int>

    /** Observe a single consumer record (useful for detail screens). */
    @Query("SELECT * FROM consumers WHERE account_no = :accountNo LIMIT 1")
    fun getConsumerByAccountNo(accountNo: String): Flow<ConsumerEntity?>

    /** One-shot fetch (not a Flow) — useful for WorkManager background tasks. */
    @Query("SELECT * FROM consumers WHERE account_no = :accountNo LIMIT 1")
    suspend fun getConsumerByAccountNoOnce(accountNo: String): ConsumerEntity?

    /** Count of consumers per route — handy for route-download progress UI. */
    @Query("SELECT COUNT(*) FROM consumers WHERE route_id = :routeId")
    suspend fun countByRoute(routeId: String): Int
}

/** A household on the route, and whether it has been read this cycle. */
data class RouteHousehold(
    @Embedded val consumer: ConsumerEntity,
    @ColumnInfo(name = "isRead") val isRead: Boolean,
)
