package com.waterdistrict.meterreader.ui.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.domain.billing.WaterRateConfig
import com.waterdistrict.meterreader.hardware.bluetooth.PrinterState
import com.waterdistrict.meterreader.hardware.bluetooth.rememberBluetoothConnectAction
import com.waterdistrict.meterreader.ui.components.AppTopBar
import com.waterdistrict.meterreader.ui.components.BottomActionBar
import com.waterdistrict.meterreader.ui.components.EmptyState
import com.waterdistrict.meterreader.ui.components.KeyValueRow
import com.waterdistrict.meterreader.ui.components.PrimaryButton
import com.waterdistrict.meterreader.ui.components.SectionCard
import com.waterdistrict.meterreader.ui.components.SectionDivider
import com.waterdistrict.meterreader.ui.components.StatusBanner
import com.waterdistrict.meterreader.ui.components.Tone
import com.waterdistrict.meterreader.ui.components.formatDate
import com.waterdistrict.meterreader.ui.components.formatDateLong
import com.waterdistrict.meterreader.ui.components.formatPeso
import com.waterdistrict.meterreader.ui.components.formatVolume
import com.waterdistrict.meterreader.ui.theme.AppTheme

/**
 * On-screen bill for a saved reading — a digital stand-in for the printed
 * receipt, so a bill can be shown to the household without a printer.
 */
@Composable
fun DigitalBillScreen(
    onBack: () -> Unit,
    viewModel: DigitalBillViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val triggerPrint = rememberBluetoothConnectAction(onReady = { viewModel.print() })
    DigitalBillContent(state = uiState, onBack = onBack, onPrint = triggerPrint)
}

@Composable
fun DigitalBillContent(
    state: DigitalBillUiState,
    onBack: () -> Unit = {},
    onPrint: () -> Unit = {},
) {
    val consumer = state.consumer
    val reading = state.reading

    Scaffold(
        topBar = { AppTopBar(title = "Bill", subtitle = consumer?.name, onBack = onBack) },
        bottomBar = {
            // Bottom-anchored and full-width — thumb-reachable one-handed, where
            // every other screen keeps its main action.
            if (reading != null) {
                BottomActionBar {
                    PrimaryButton(
                        text = "Print receipt",
                        onClick = onPrint,
                        loading = state.printerState == PrinterState.Printing,
                        loadingText = "Printing…",
                        icon = Icons.Default.Print,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.isLoading || consumer == null || reading == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator()
                } else {
                    EmptyState(
                        icon = Icons.Default.Print,
                        title = "Bill not found",
                        body = "This phone has no copy of that bill."
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            (state.printerState as? PrinterState.Error)?.let { error ->
                StatusBanner(title = "Didn't print", text = error.message, tone = Tone.Error)
            }
            BillDocument(consumer, reading)
        }
    }
}

@Composable
private fun BillDocument(consumer: ConsumerEntity, reading: ReadingEntity) {
    val rates = WaterRateConfig()
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(vertical = 16.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "SOUTH WAO WATER SYSTEM (MEEDO)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                "Official water bill",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            )
        }

        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Amount due",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatPeso(reading.totalAmountDue),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                "Pay on or before ${formatDateLong(reading.dueDateMillis)}",
                style = MaterialTheme.typography.titleSmall
            )
            Row(Modifier.padding(top = 2.dp)) {
                Text(
                    "If paid after that date",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.status.warning,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatPeso(reading.projectedOverdueTotal),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.status.warning
                )
            }

            SectionDivider(Modifier.padding(top = 8.dp))

            KeyValueRow("OR number", reading.orNumber.ifBlank { "Waiting to upload" }, emphasize = reading.orNumber.isNotBlank())
            KeyValueRow("Account no.", consumer.accountNo)
            KeyValueRow("Name", consumer.name)
            KeyValueRow("Address", consumer.address)
            KeyValueRow("Meter no.", consumer.meterNo)
            KeyValueRow("Classification", consumer.classification)
            KeyValueRow("Billing date", formatDate(reading.readingDate))
            KeyValueRow("Read by", reading.readByUserId)

            SectionDivider()
            Text("Meter reading", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            KeyValueRow("Previous", formatVolume(reading.prevReading))
            KeyValueRow("Current", formatVolume(reading.currentReading))
            KeyValueRow("Consumption", formatVolume(reading.consumption), emphasize = true)

            SectionDivider()
            Text("Charges", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            KeyValueRow(
                "Minimum charge (first ${rates.minChargeThreshold.toInt()} m³)",
                formatPeso(reading.minimumCharge)
            )
            val excess = (reading.consumption - rates.minChargeThreshold).coerceAtLeast(0.0)
            KeyValueRow(
                "Commodity (${formatVolume(excess)} × ${formatPeso(rates.commodityRate)})",
                formatPeso(reading.commodityCharge)
            )
            KeyValueRow(
                "Water charge",
                formatPeso(reading.minimumCharge + reading.commodityCharge),
                emphasize = true
            )
            KeyValueRow("Previous balance", formatPeso(reading.overdueBalance))
            KeyValueRow(
                "Late surcharge (${(rates.overdueSurchargeRate * 100).toInt()}%)",
                formatPeso(reading.overdueSurcharge)
            )
            KeyValueRow("Extension fee", formatPeso(reading.extensionFee))
            if (reading.creditApplied > 0) {
                KeyValueRow(
                    "Less: advance payment",
                    "−" + formatPeso(reading.creditApplied),
                    valueColor = AppTheme.status.success
                )
            }
            SectionDivider()
            KeyValueRow("Total amount due", formatPeso(reading.totalAmountDue), emphasize = true)

            if (reading.remarks.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Remarks: ${reading.remarks}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
