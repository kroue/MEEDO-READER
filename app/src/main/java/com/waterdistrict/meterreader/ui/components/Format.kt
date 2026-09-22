package com.waterdistrict.meterreader.ui.components

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How money, volumes and dates read on screen — one way, everywhere.
 *
 * Each screen used to format these itself, so the same bill could show
 * "Sep 17, 2026" on one screen and "17 Sep 2026" on the next.
 */

private val symbols = DecimalFormatSymbols(Locale.US)
private val pesoFormat = DecimalFormat("#,##0.00", symbols)
private val volumeFormat = DecimalFormat("#,##0.##", symbols)

/** ₱1,234.50 */
fun formatPeso(amount: Double): String = "₱" + pesoFormat.format(amount)

/** 148.5 m³ */
fun formatVolume(cubicMetres: Double): String = volumeFormat.format(cubicMetres) + " m³"

/** A meter reading without its unit, e.g. 1,482.5 */
fun formatReading(value: Double): String = volumeFormat.format(value)

/**
 * The Philippines is UTC+8 all year. Dates are shown on the office's calendar
 * so a due date reads the same on every phone, whatever its clock is set to —
 * the same rule DueDates uses to work them out.
 */
private val officeZone: TimeZone = TimeZone.getTimeZone("GMT+08:00")

/** 17 Oct 2026 */
fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = officeZone }.format(Date(millis))

/** 17 October 2026 */
fun formatDateLong(millis: Long): String =
    SimpleDateFormat("d MMMM yyyy", Locale.US).apply { timeZone = officeZone }.format(Date(millis))

/** 17th, 21st, 23rd */
fun ordinal(n: Int): String {
    val teen = n % 100 in 11..13
    val suffix = if (teen) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/** Barangay codes as the office writes them in full: "CG" → "Cebuano Group". */
private val BARANGAY_NAMES = mapOf(
    "BO-OT" to "Bo-ot",
    "CG" to "Cebuano Group",
    "KABATANGAN" to "Kabatangan",
    "SALVACION" to "Salvacion",
    "AMOYONG" to "Amoyong",
    "KATUTUNGAN" to "Katutungan",
    "PAGALONGAN" to "Pagalongan",
    "MILAYA" to "Milaya",
    "DIOMIL" to "Diomil",
)

fun barangayLabel(code: String): String = BARANGAY_NAMES[code.trim().uppercase()] ?: code
