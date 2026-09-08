// ─────────────────────────────────────────────────────────────────────────────
// PHASE 1 — ROOM DATABASE
// Entities, DAO, Database class & Tiered Billing Calculation Logic
// ─────────────────────────────────────────────────────────────────────────────

// ───────────────────────────────────────────────
// 1-A  data/local/entity/ConsumerEntity.kt
// ───────────────────────────────────────────────
package com.waterdistrict.meterreader.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a water utility customer assigned to the meter reader's route.
 * Downloaded from the server before field work begins and stored locally.
 *
 * @property accountNo   Utility-wide unique identifier (e.g. "2024-001234")
 * @property name        Full customer name
 * @property address     Service address (barangay, municipality)
 * @property meterNo     Physical meter serial number
 * @property prevReading Last recorded reading (in cubic metres, m³)
 * @property routeId     Groups consumers for batch download / sync
 */
@Entity(tableName = "consumers")
data class ConsumerEntity(
    @PrimaryKey
    @ColumnInfo(name = "account_no")
    val accountNo: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "address")
    val address: String,

    @ColumnInfo(name = "meter_no")
    val meterNo: String,

    @ColumnInfo(name = "prev_reading")
    val prevReading: Double,

    @ColumnInfo(name = "route_id")
    val routeId: String,

    @ColumnInfo(name = "firebase_id")
    val firebaseId: String = "",

    @ColumnInfo(name = "prev_reading_month")
    val prevReadingMonth: String = "",

    @ColumnInfo(name = "classification")
    val classification: String = "RESIDENTIAL",

    @ColumnInfo(name = "overdue_balance")
    val overdueBalance: Double = 0.0,

    // Epoch millis of when this account's balance last went from zero to
    // owing. Drives the 15-day grace period / 3% surcharge / extension fee —
    // the aggregate overdueBalance alone doesn't say how long it's been owed.
    //
    // Deliberately NOT the oldest still-unpaid bill's date: payments apply
    // oldest-cycle-first, so clearing just that one row would move the start
    // forward and let a multi-year debt reset its own delinquency clock for
    // the price of one month's bill.
    @ColumnInfo(name = "delinquent_since_millis")
    val delinquentSinceMillis: Long? = null,

    // Advance payment held on the account, drawn down against the next bill.
    @ColumnInfo(name = "credit_balance")
    val creditBalance: Double = 0.0,

    // Whether the ₱10 extension fee has already been charged for the current
    // unpaid streak — it's a one-time fee per delinquency, not per bill.
    @ColumnInfo(name = "extension_fee_already_charged")
    val extensionFeeAlreadyCharged: Boolean = false,

    // Firestore's billingHistory is the source of truth for "already billed
    // this cycle" — a local reading row alone isn't reliable, since local
    // data can be lost (app reinstall, device swap) independently of what
    // has actually been recorded on the server.
    @ColumnInfo(name = "already_billed_this_month")
    val alreadyBilledThisMonth: Boolean = false,

    // The billing cycle this row was downloaded for, e.g. "AUG 2026". Local
    // state is scoped to it so a new cycle starts clean: without it, every
    // consumer read in any previous month still counted as read.
    @ColumnInfo(name = "billing_month")
    val billingMonth: String = "",

    @ColumnInfo(name = "billed_reading_this_month")
    val billedReadingThisMonth: Double = 0.0,

    @ColumnInfo(name = "billed_amount_this_month")
    val billedAmountThisMonth: Double = 0.0,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "PENDING",

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long = System.currentTimeMillis()
)
