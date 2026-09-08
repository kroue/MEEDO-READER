package com.waterdistrict.meterreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.data.local.entity.SyncStatus

// ─────────────────────────────────────────────────────────────────────────────
// Type Converters — Room cannot natively store Enums
// ─────────────────────────────────────────────────────────────────────────────

class SyncStatusConverter {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}

// ─────────────────────────────────────────────────────────────────────────────
// Database Declaration
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Singleton Room database for the Water Meter Reader app.
 *
 * ### Migration strategy
 * Destructive fallback is NOT enabled (see [MeterReaderDatabase.MIGRATIONS] and
 * the Hilt module). It used to be — on every build type, despite a comment here
 * claiming otherwise — which meant the next schema change would silently wipe
 * every unsynced reading on every reader's phone on first launch after the
 * update. A reading that hasn't reached Firestore exists in exactly one place;
 * losing it means the concessionaire is not billed for the cycle and nobody
 * finds out until someone notices the gap.
 *
 * Every version bump from here MUST ship a [Migration] in [MIGRATIONS].
 * Schemas are exported to `app/schemas` (see `room.schemaLocation` in
 * build.gradle.kts) so each migration can be written and tested against a real
 * prior schema.
 *
 * ### Instantiation
 * The singleton instance is provided by Hilt's AppModule; never call
 * [androidx.room.Room.databaseBuilder] directly from application code.
 */
@Database(
    entities = [
        ConsumerEntity::class,
        ReadingEntity::class,
    ],
    version = 8,
    exportSchema = true   // keeps a schema JSON for auditing migrations
)
@TypeConverters(SyncStatusConverter::class)
abstract class MeterReaderDatabase : RoomDatabase() {

    abstract fun consumerDao(): ConsumerDao
    abstract fun readingDao(): ReadingDao

    companion object {
        const val DATABASE_NAME = "meter_reader.db"

        /**
         * 7 → 8: scope local state to a billing cycle, and carry the extra
         * balance fields the corrected billing model needs.
         *
         * Existing rows are backfilled onto the cycle they were captured in —
         * derived from `reading_date` for readings, and from the newest
         * reading for consumers — so a device upgrading mid-cycle keeps
         * showing the work it has already done rather than appearing to start
         * over.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── consumers ────────────────────────────────────────────────
                //
                // Rebuilt rather than ALTERed, because this version DROPS
                // `overdue_billing_date_millis` (replaced by
                // `delinquent_since_millis`). SQLite only gained
                // ALTER TABLE ... DROP COLUMN in 3.35, and minSdk here is 26 —
                // on older devices that statement doesn't exist. Leaving the
                // column in place isn't an option either: Room compares the
                // live table against the entity on every open and aborts with
                // "Migration didn't properly handle consumers" if the database
                // carries a column the entity doesn't declare.
                //
                // The DDL below is copied verbatim from the generated schema
                // (app/schemas/…/8.json) so the rebuilt table matches what Room
                // expects exactly.
                db.execSQL(
                    """
                    CREATE TABLE consumers_new (
                      `account_no` TEXT NOT NULL,
                      `name` TEXT NOT NULL,
                      `address` TEXT NOT NULL,
                      `meter_no` TEXT NOT NULL,
                      `prev_reading` REAL NOT NULL,
                      `route_id` TEXT NOT NULL,
                      `firebase_id` TEXT NOT NULL,
                      `prev_reading_month` TEXT NOT NULL,
                      `classification` TEXT NOT NULL,
                      `overdue_balance` REAL NOT NULL,
                      `delinquent_since_millis` INTEGER,
                      `credit_balance` REAL NOT NULL,
                      `extension_fee_already_charged` INTEGER NOT NULL,
                      `already_billed_this_month` INTEGER NOT NULL,
                      `billing_month` TEXT NOT NULL,
                      `billed_reading_this_month` REAL NOT NULL,
                      `billed_amount_this_month` REAL NOT NULL,
                      `sync_status` TEXT NOT NULL,
                      `downloaded_at` INTEGER NOT NULL,
                      PRIMARY KEY(`account_no`)
                    )
                    """.trimIndent()
                )

                // `overdue_billing_date_millis` held the oldest unpaid bill's
                // date, which is close enough to a delinquency start for
                // existing rows; the next download replaces it with the
                // authoritative value from Firestore. `billing_month` is filled
                // in below, once readings have their own cycle backfilled.
                db.execSQL(
                    """
                    INSERT INTO consumers_new (
                      account_no, name, address, meter_no, prev_reading, route_id,
                      firebase_id, prev_reading_month, classification, overdue_balance,
                      delinquent_since_millis, credit_balance, extension_fee_already_charged,
                      already_billed_this_month, billing_month, billed_reading_this_month,
                      billed_amount_this_month, sync_status, downloaded_at
                    )
                    SELECT
                      account_no, name, address, meter_no, prev_reading, route_id,
                      firebase_id, prev_reading_month, classification, overdue_balance,
                      overdue_billing_date_millis, 0.0, extension_fee_already_charged,
                      already_billed_this_month, '', billed_reading_this_month,
                      billed_amount_this_month, sync_status, downloaded_at
                    FROM consumers
                    """.trimIndent()
                )

                // Foreign keys are off during a Room migration, so dropping the
                // parent here does not cascade the child readings away.
                db.execSQL("DROP TABLE consumers")
                db.execSQL("ALTER TABLE consumers_new RENAME TO consumers")

                // ── readings ─────────────────────────────────────────────────
                db.execSQL("ALTER TABLE readings ADD COLUMN credit_applied REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE readings ADD COLUMN billing_month TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE readings ADD COLUMN server_total_amount_due REAL")

                // Backfill each reading's cycle from the date it was captured.
                // SQLite has no month-name formatter, so map the numeric month
                // through a CASE — the same fixed table BillingMonth uses.
                db.execSQL(
                    """
                    UPDATE readings SET billing_month =
                      CASE strftime('%m', reading_date / 1000, 'unixepoch', 'localtime')
                        WHEN '01' THEN 'JAN' WHEN '02' THEN 'FEB' WHEN '03' THEN 'MAR'
                        WHEN '04' THEN 'APR' WHEN '05' THEN 'MAY' WHEN '06' THEN 'JUN'
                        WHEN '07' THEN 'JUL' WHEN '08' THEN 'AUG' WHEN '09' THEN 'SEP'
                        WHEN '10' THEN 'OCT' WHEN '11' THEN 'NOV' ELSE 'DEC'
                      END
                      || ' ' || strftime('%Y', reading_date / 1000, 'unixepoch', 'localtime')
                    """.trimIndent()
                )

                // A consumer belongs to the cycle of its most recent reading;
                // failing that, to the current one, so nothing is orphaned by
                // the stale-cycle cleanup on first sync.
                db.execSQL(
                    """
                    UPDATE consumers SET billing_month = COALESCE(
                      (SELECT r.billing_month FROM readings r
                        WHERE r.account_no = consumers.account_no
                        ORDER BY r.reading_date DESC LIMIT 1),
                      CASE strftime('%m', 'now', 'localtime')
                        WHEN '01' THEN 'JAN' WHEN '02' THEN 'FEB' WHEN '03' THEN 'MAR'
                        WHEN '04' THEN 'APR' WHEN '05' THEN 'MAY' WHEN '06' THEN 'JUN'
                        WHEN '07' THEN 'JUL' WHEN '08' THEN 'AUG' WHEN '09' THEN 'SEP'
                        WHEN '10' THEN 'OCT' WHEN '11' THEN 'NOV' ELSE 'DEC'
                      END
                      || ' ' || strftime('%Y', 'now', 'localtime')
                    )
                    """.trimIndent()
                )

                // Older builds could leave two readings for one account in the
                // same cycle. Keep the newest before the unique index goes on,
                // or its creation fails and the migration takes the app down.
                db.execSQL(
                    """
                    DELETE FROM readings WHERE id NOT IN (
                      SELECT MAX(id) FROM readings GROUP BY account_no, billing_month
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_readings_account_no_billing_month " +
                        "ON readings (account_no, billing_month)"
                )

                // Anything caught mid-upload by the old build is requeued
                // rather than left in a state nothing recovers from.
                db.execSQL("UPDATE readings SET sync_status = 'PENDING' WHERE sync_status = 'SYNCING'")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_7_8)
    }
}
