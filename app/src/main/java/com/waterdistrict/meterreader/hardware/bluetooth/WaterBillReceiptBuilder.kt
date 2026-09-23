package com.waterdistrict.meterreader.hardware.bluetooth

import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.data.local.entity.displayAccountNo
import com.waterdistrict.meterreader.domain.billing.BillingResult
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Assembles a formatted ESC/POS water bill receipt.
 *
 * Designed for **58 mm thermal paper** (32-character line width).
 * Change [LINE_WIDTH] to 48 for 80 mm paper.
 *
 * Receipt sections (top to bottom):
 *  1. Water District header (name, address, contact)
 *  2. Separator
 *  3. Consumer information (account, name, address)
 *  4. Separator
 *  5. Reading details (prev, current, consumption)
 *  6. Billing breakdown (base, tiers, VAT, franchise tax)
 *  7. Separator + Total Amount Due (bold, large)
 *  8. Separator
 *  9. Footer (read-by) + paper cut
 */
object WaterBillReceiptBuilder {

    private const val LINE_WIDTH = 32      // characters for 58 mm paper

    private val pesoFormat = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    private val dueDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    /** Format a Double as ₱1,234.56 */
    private fun Double.toPeso(): String = "P${pesoFormat.format(this)}"

    /**
     * Build the complete receipt byte array.
     *
     * @param district       Name of the water district (header)
     * @param districtAddr   Address line for the header
     * @param districtTel    Contact number
     * @param consumer       The consumer whose bill is being printed
     * @param billing        The [BillingResult] from [WaterBillingCalculator]
     * @param currentReading The current meter reading (m3)
     * @param readingDate    Timestamp of the reading (default = now)
     * @param readByName     Name of the field worker (for accountability)
     * @param orNumber       Official Receipt number — assigned server-side on upload, so a
     *                       receipt printed before that completes shows "PENDING SYNC" instead.
     */
    fun build(
        district: String         = "SOUTH WAO WATER SYSTEM (MEEDO)",
        districtAddr: String     = "Wao, Lanao del Sur",
        districtTel: String      = "Tel: 0985 762 5456",
        consumer: ConsumerEntity,
        billing: BillingResult,
        currentReading: Double,
        readingDate: Long        = System.currentTimeMillis(),
        readByName: String       = "Field Reader",
        orNumber: String         = ""
    ): ByteArray {

        // Reading and billing happen in the same field visit in this app's
        // flow, so both dates come from the same timestamp — shown as two
        // labeled rows since a printed bill is expected to state each.
        val dateStr = dueDateFormat.format(Date(readingDate))

        return ReceiptBuilder()
            // ── Initialise printer ────────────────────────────────────────
            .init()

            // ── Header ────────────────────────────────────────────────────
            .alignCenter()
            .fontLarge()
            .boldOn()
            .textLine(district)
            .boldOff()
            .fontNormal()
            .textLine(districtAddr)
            .textLine(districtTel)
            .feed()

            // ── Receipt title ─────────────────────────────────────────────
            .boldOn()
            .textLine("OFFICIAL WATER BILL")
            .boldOff()
            .dividerThick()

            // ── Consumer info ─────────────────────────────────────────────
            .alignLeft()
            // No OR number. This slip is the bill a household is handed at the
            // meter; the official receipt is the one written from the booklet
            // when they pay at the counter, and printing a number here invited
            // it to be mistaken for proof of payment.
            .leftRightText("Account No:", consumer.displayAccountNo, LINE_WIDTH)
            .leftRightText("Meter No:",   consumer.meterNo,   LINE_WIDTH)
            .textLine("Name: ${consumer.name}")
            .textLine("Addr: ${consumer.address}")
            .leftRightText("Reading Date:", dateStr, LINE_WIDTH)
            .leftRightText("Billing Date:", dateStr, LINE_WIDTH)
            .dividerThin()

            // ── Reading details ────────────────────────────────────────────
            .alignCenter()
            .boldOn()
            .textLine("METER READING")
            .boldOff()
            .alignLeft()
            .leftRightText("Previous Reading:", "${consumer.prevReading} m3", LINE_WIDTH)
            .leftRightText("Current Reading:",  "${currentReading} m3",       LINE_WIDTH)
            .boldOn()
            .leftRightText("Consumption:",      "${billing.consumption} m3",  LINE_WIDTH)
            .boldOff()
            .dividerThin()

            // ── Billing breakdown ──────────────────────────────────────────
            .alignCenter()
            .boldOn()
            .textLine("BILLING BREAKDOWN")
            .boldOff()
            .alignLeft()
            // Every line is printed regardless of value \u2014 an official receipt
            // is a complete, auditable statement, not one that hides
            // categories that happen to be \u20B10.00 this cycle.
            .leftRightText("Min Charge (0-10m3):", billing.minimumCharge.toPeso(),   LINE_WIDTH)
            .leftRightText("Commodity Charge:", billing.commodityCharge.toPeso(), LINE_WIDTH)
            .leftRightText("Water Charge:",   billing.totalWaterCharge.toPeso(), LINE_WIDTH)
            .leftRightText("Previous Balance:", billing.overdueBalance.toPeso(), LINE_WIDTH)
            .leftRightText("Overdue Surcharge:", billing.overdueSurcharge.toPeso(), LINE_WIDTH)
            .leftRightText("Extension Fee:", billing.extensionFee.toPeso(), LINE_WIDTH)
            .let { b ->
                // Only printed when there is one — a "Less: Advance Payment
                // 0.00" line on every receipt just invites questions.
                if (billing.creditApplied > 0) {
                    b.leftRightText(
                        "Less: Advance Pymt:",
                        "-" + billing.creditApplied.toPeso(),
                        LINE_WIDTH
                    )
                } else {
                    b
                }
            }
            .dividerThick()

            // ── Total Amount Due ───────────────────────────────────────────
            .alignCenter()
            .fontDoubleHeight()
            .boldOn()
            .textLine("TOTAL AMOUNT DUE")
            .fontLarge()
            .textLine(billing.totalAmountDue.toPeso())
            .fontNormal()
            .boldOff()
            .dividerThin()

            // ── Overdue projection — what this bill becomes if unpaid ──────
            .alignLeft()
            .leftRightText("Pay by:", dueDateFormat.format(Date(billing.dueDateMillis)), LINE_WIDTH)
            .boldOn()
            .leftRightText("If after due date:", billing.projectedOverdueTotal.toPeso(), LINE_WIDTH)
            .boldOff()
            .dividerThin()

            // ── Footer ─────────────────────────────────────────────────────
            .alignLeft()
            .textLine("Read by: $readByName")
            .feed(3)

            // ── Cut paper ──────────────────────────────────────────────────
            .cutPaper()
            .build()
    }
}
