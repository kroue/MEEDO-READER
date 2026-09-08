package com.waterdistrict.meterreader.ui.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterdistrict.meterreader.hardware.bluetooth.PrinterState
import com.waterdistrict.meterreader.hardware.bluetooth.rememberBluetoothConnectAction
import com.waterdistrict.meterreader.ui.theme.WarningAmber
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val pesoFormat = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}
private fun Double.toPeso() = "₱${pesoFormat.format(this)}"
private val billDateFormatShort = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

/**
 * On-screen bill for a saved reading — a digital stand-in for the printed
 * receipt so a bill can be shown to the concessionaire without a printer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalBillScreen(
    onBack: () -> Unit,
    viewModel: DigitalBillViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val triggerPrint = rememberBluetoothConnectAction(onReady = { viewModel.print() })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Digital Bill") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            // Bottom-anchored and full-width — thumb-reachable during one-handed
            // field use, matching ReadingEntryScreen's primary-action placement,
            // rather than a small top-right icon that was this screen's only
            // interactive element yet sat in the least reachable corner.
            if (uiState.reading != null) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = triggerPrint,
                        enabled = uiState.printerState != PrinterState.Printing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (uiState.printerState == PrinterState.Printing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Printing...")
                        } else {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Print Receipt", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        val consumer = uiState.consumer
        val reading = uiState.reading

        if (uiState.isLoading || consumer == null || reading == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) CircularProgressIndicator()
                else Text("Bill not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            (uiState.printerState as? PrinterState.Error)?.let { error ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        error.message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "SOUTH WAO WATER SYSTEM (MEEDO)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Official Water Bill",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    BillRow(
                        "OR Number",
                        reading.orNumber.ifBlank { "Pending sync" },
                        isBold = reading.orNumber.isNotBlank()
                    )
                    BillRow("Account No.", consumer.accountNo)
                    BillRow("Name", consumer.name)
                    BillRow("Address", consumer.address)
                    BillRow("Meter No.", consumer.meterNo)
                    BillRow("Classification", consumer.classification)
                    BillRow("Reading Date", billDateFormatShort.format(Date(reading.readingDate)))
                    BillRow("Billing Date", billDateFormatShort.format(Date(reading.readingDate)))
                    BillRow("Read By", reading.readByUserId)

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    SectionTitle("Meter Reading")
                    BillRow("Previous Reading", "${reading.prevReading} m³")
                    BillRow("Current Reading", "${reading.currentReading} m³")
                    BillRow("Consumption", "${reading.consumption} m³", isBold = true)

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    SectionTitle("Billing Breakdown")
                    BillRow("Minimum Charge (0–10 m³)", reading.minimumCharge.toPeso())
                    val excess = (reading.consumption - 10.0).coerceAtLeast(0.0)
                    BillRow("Commodity Charge (${excess} m³ @ ₱10.80)", reading.commodityCharge.toPeso())
                    BillRow(
                        "Water Charge Subtotal",
                        (reading.minimumCharge + reading.commodityCharge).toPeso(),
                        isBold = true
                    )

                    Spacer(Modifier.height(8.dp))
                    BillRow("Previous Balance", reading.overdueBalance.toPeso())
                    BillRow("Overdue Surcharge (3%)", reading.overdueSurcharge.toPeso())
                    BillRow("Extension Fee", reading.extensionFee.toPeso())

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(thickness = 2.dp)
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TOTAL AMOUNT DUE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            reading.totalAmountDue.toPeso(),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pay on or before ${billDateFormatShort.format(Date(reading.dueDateMillis))} to avoid a surcharge.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("If paid after that date:", fontSize = 11.sp, color = WarningAmber)
                        Text(
                            reading.projectedOverdueTotal.toPeso(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = WarningAmber
                        )
                    }

                    if (reading.remarks.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Remarks: ${reading.remarks}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun BillRow(label: String, value: String, isBold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End
        )
    }
}
