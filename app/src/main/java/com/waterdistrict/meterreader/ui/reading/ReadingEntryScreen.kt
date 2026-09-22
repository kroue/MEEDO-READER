package com.waterdistrict.meterreader.ui.reading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.domain.billing.BillingResult
import com.waterdistrict.meterreader.domain.billing.WaterRateConfig
import com.waterdistrict.meterreader.hardware.bluetooth.PrinterState
import com.waterdistrict.meterreader.hardware.bluetooth.rememberBluetoothConnectAction
import com.waterdistrict.meterreader.ui.components.AppTopBar
import com.waterdistrict.meterreader.ui.components.BottomActionBar
import com.waterdistrict.meterreader.ui.components.KeyValueRow
import com.waterdistrict.meterreader.ui.components.PrimaryButton
import com.waterdistrict.meterreader.ui.components.SecondaryButton
import com.waterdistrict.meterreader.ui.components.SectionCard
import com.waterdistrict.meterreader.ui.components.SectionDivider
import com.waterdistrict.meterreader.ui.components.StatusBanner
import com.waterdistrict.meterreader.ui.components.StatusPill
import com.waterdistrict.meterreader.ui.components.Tone
import com.waterdistrict.meterreader.ui.components.formatDateLong
import com.waterdistrict.meterreader.ui.components.formatPeso
import com.waterdistrict.meterreader.ui.components.formatReading
import com.waterdistrict.meterreader.ui.components.formatVolume
import com.waterdistrict.meterreader.ui.theme.AppTheme

/**
 * One household: enter the reading, check the bill, save it and hand over the
 * receipt.
 *
 * Laid out in the order the work is done, top to bottom — who this is, what
 * the meter says, what they owe — with the save buttons pinned at the bottom,
 * above the keyboard, where the thumb that typed the reading already is.
 */
@Composable
fun ReadingEntryScreen(
    accountNo: String,
    onBack: () -> Unit = {},
    onViewBill: (Long) -> Unit = {},
    onReadingComplete: (String) -> Unit = {},
    viewModel: ReadingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Once a household is done — saved, and printed if printing was asked for —
    // go straight back to meter entry for the next one.
    LaunchedEffect(viewModel) {
        viewModel.readingComplete.collect { savedAccountNo -> onReadingComplete(savedAccountNo) }
    }
    val triggerSaveAndPrint = rememberBluetoothConnectAction(
        onReady = { viewModel.onEvent(ReadingUiEvent.SaveAndPrint) }
    )
    val triggerRetryPrint = rememberBluetoothConnectAction(
        onReady = { viewModel.onEvent(ReadingUiEvent.RetryPrint) }
    )

    ReadingEntryContent(
        state = uiState,
        onBack = onBack,
        onViewBill = onViewBill,
        onReadingChanged = { viewModel.onEvent(ReadingUiEvent.CurrentReadingChanged(it)) },
        onRemarksChanged = { viewModel.onEvent(ReadingUiEvent.RemarksChanged(it)) },
        onSaveOnly = { viewModel.onEvent(ReadingUiEvent.SaveOnly) },
        onSaveAndPrint = triggerSaveAndPrint,
        onRetryPrint = triggerRetryPrint,
        onDisconnectPrinter = { viewModel.onEvent(ReadingUiEvent.DisconnectPrinter) },
        onNextHousehold = { viewModel.onEvent(ReadingUiEvent.NextHousehold) },
    )
}

@Composable
fun ReadingEntryContent(
    state: ReadingUiState,
    onBack: () -> Unit = {},
    onViewBill: (Long) -> Unit = {},
    onReadingChanged: (String) -> Unit = {},
    onRemarksChanged: (String) -> Unit = {},
    onSaveOnly: () -> Unit = {},
    onSaveAndPrint: () -> Unit = {},
    onRetryPrint: () -> Unit = {},
    onDisconnectPrinter: () -> Unit = {},
    onNextHousehold: () -> Unit = {},
) {
    // Reopening this screen for a consumer that already has a captured
    // reading (from Room, not the ViewModel's in-memory state — which resets
    // every time this screen is recreated) shows a summary instead of a blank
    // form, unless the reader explicitly asks to re-enter it.
    var forceReenter by remember { mutableStateOf(false) }
    val existing = state.existingBillInfo
    val showExisting = existing != null && state.savedReadingId == null && !forceReenter
    val savedId = state.savedReadingId
    val canSave = state.billing != null && state.inputError == null && !state.isSaving

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Meter reading",
                subtitle = state.billingMonth.ifBlank { null },
                onBack = onBack,
                actions = {
                    PrinterPill(state.printerState)
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            BottomActionBar {
                when {
                    showExisting -> {
                        val localId = existing!!.localReadingId
                        if (localId != null) {
                            PrimaryButton(
                                text = "View bill",
                                onClick = { onViewBill(localId) },
                                icon = Icons.Outlined.Receipt,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        SecondaryButton(
                            text = "Re-enter reading",
                            onClick = { forceReenter = true },
                            icon = Icons.Default.Edit,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Saved, but the receipt didn't print — a clean save goes back
                    // to meter entry on its own. Retry is on the printer card.
                    savedId != null -> {
                        PrimaryButton(
                            text = "Next household",
                            onClick = onNextHousehold,
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SecondaryButton(
                            text = "View bill",
                            onClick = { onViewBill(savedId) },
                            icon = Icons.Outlined.Receipt,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(
                            text = "Save",
                            onClick = onSaveOnly,
                            enabled = canSave,
                            icon = Icons.Default.Save,
                            modifier = Modifier.weight(1f)
                        )
                        PrimaryButton(
                            text = "Save & print",
                            onClick = onSaveAndPrint,
                            enabled = canSave,
                            loading = state.isSaving,
                            loadingText = "Saving…",
                            icon = Icons.Default.Print,
                            modifier = Modifier.weight(1.7f)
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val consumer = state.consumer
            if (consumer == null) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading the household…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@Column
            }

            HouseholdCard(consumer)

            if (showExisting) {
                AlreadyRecordedCard(existing!!)
                return@Column
            }

            ReadingCard(
                previousReading = consumer.prevReading,
                input = state.currentReadingInput,
                onInputChanged = onReadingChanged,
                remarks = state.remarks,
                onRemarksChanged = onRemarksChanged,
                error = state.inputError,
                warning = state.inputWarning,
                consumption = state.billing?.consumption,
                readOnly = savedId != null,
            )

            AnimatedVisibility(
                visible = state.billing != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                state.billing?.let { BillCard(it) }
            }

            PrinterCard(
                printerState = state.printerState,
                onRetry = onRetryPrint,
                onDisconnect = onDisconnectPrinter
            )

            state.saveError?.let { StatusBanner(text = it, tone = Tone.Error, title = "Not saved") }

            if (savedId != null) {
                StatusBanner(
                    title = "Reading saved",
                    text = "It uploads on its own as soon as there's a signal.",
                    tone = Tone.Success
                )
            }
        }
    }
}

// ── Household ────────────────────────────────────────────────────────────────

@Composable
private fun HouseholdCard(consumer: ConsumerEntity) {
    SectionCard {
        Text(consumer.name, style = MaterialTheme.typography.titleLarge)
        val classification = consumer.classification.lowercase().replaceFirstChar { it.uppercase() }
        Text(
            listOf(consumer.address, classification).filter { it.isNotBlank() }.joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IdBlock("Account no.", consumer.accountNo, Modifier.weight(1f))
            IdBlock("Meter no.", consumer.meterNo, Modifier.weight(1f))
        }
    }
}

@Composable
private fun IdBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1
        )
    }
}

@Composable
private fun AlreadyRecordedCard(info: ExistingBillInfo) {
    SectionCard(title = "Already read this cycle", icon = Icons.Default.CheckCircle) {
        KeyValueRow("Reading", formatVolume(info.currentReading))
        KeyValueRow("Amount billed", formatPeso(info.totalAmountDue), emphasize = true)
        KeyValueRow("OR number", info.orNumber.ifBlank { "Waiting to upload" })
        if (info.localReadingId == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Recorded at the office — this phone has no copy of the bill to show or print.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The office may have taken a payment while the reader was in the
        // field, so the server recalculates every bill against the live
        // balance on upload. When that comes out different from what was
        // printed, the receipt in the household's hand is wrong.
        info.serverTotalAmountDue?.let { serverTotal ->
            Spacer(Modifier.height(10.dp))
            StatusBanner(
                title = "Receipt needs reissuing",
                text = "The office recalculated this bill to ${formatPeso(serverTotal)}. " +
                    "The printed receipt is out of date.",
                tone = Tone.Warning
            )
        }
    }
}

// ── Reading ──────────────────────────────────────────────────────────────────

@Composable
private fun ReadingCard(
    previousReading: Double,
    input: String,
    onInputChanged: (String) -> Unit,
    remarks: String,
    onRemarksChanged: (String) -> Unit,
    error: String?,
    warning: String?,
    consumption: Double?,
    readOnly: Boolean,
) {
    SectionCard(title = "Meter reading", icon = Icons.Default.WaterDrop) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Previous",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatReading(previousReading),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            if (consumption != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Used this cycle",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatVolume(consumption),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChanged,
            label = { Text("Current reading (m³)") },
            singleLine = true,
            readOnly = readOnly,
            isError = error != null,
            supportingText = {
                // A warning is not an error: a meter that rolled past 99,999 is
                // a real reading and must still be billable in the field.
                when {
                    error != null -> Text(error)
                    warning != null -> Text(warning, color = AppTheme.status.warning)
                    else -> Text("As shown on the dial, including decimals")
                }
            },
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next
            ),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = remarks,
            onValueChange = onRemarksChanged,
            label = { Text("Remarks (optional)") },
            placeholder = { Text("e.g. meter covered by mud") },
            readOnly = readOnly,
            maxLines = 3,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Bill ─────────────────────────────────────────────────────────────────────

@Composable
private fun BillCard(billing: BillingResult) {
    val rates = WaterRateConfig()
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        // What matters most, first: how much, and by when.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(16.dp)
        ) {
            Text(
                "Amount due",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                formatPeso(billing.totalAmountDue),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "Pay on or before ${formatDateLong(billing.dueDateMillis)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Every line, even at ₱0.00, so the bill is a complete, auditable
            // statement rather than one that hides what didn't apply this time.
            KeyValueRow(
                "Minimum charge (first ${rates.minChargeThreshold.toInt()} m³)",
                formatPeso(billing.minimumCharge)
            )
            val excess = (billing.consumption - rates.minChargeThreshold).coerceAtLeast(0.0)
            KeyValueRow(
                "Commodity (${formatVolume(excess)} × ${formatPeso(rates.commodityRate)})",
                formatPeso(billing.commodityCharge)
            )
            KeyValueRow("Water charge", formatPeso(billing.totalWaterCharge), emphasize = true)
            SectionDivider()
            KeyValueRow(
                if (billing.daysOverdue != null) "Previous balance (${billing.daysOverdue} days)"
                else "Previous balance",
                formatPeso(billing.overdueBalance)
            )
            KeyValueRow(
                "Late surcharge (${(rates.overdueSurchargeRate * 100).toInt()}%)",
                formatPeso(billing.overdueSurcharge)
            )
            KeyValueRow("Extension fee", formatPeso(billing.extensionFee))
            if (billing.creditApplied > 0) {
                KeyValueRow(
                    "Less: advance payment",
                    "−" + formatPeso(billing.creditApplied),
                    valueColor = AppTheme.status.success
                )
            }
            SectionDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "If paid after the due date",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.status.warning,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatPeso(billing.projectedOverdueTotal),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.status.warning
                )
            }
        }
    }
}

// ── Printer ──────────────────────────────────────────────────────────────────

private data class PrinterLook(val icon: ImageVector, val title: String, val detail: String, val tone: Tone?)

private fun lookOf(state: PrinterState): PrinterLook = when (state) {
    PrinterState.Idle -> PrinterLook(Icons.Default.Print, "Printer", "Connects when you tap Save & print", null)
    PrinterState.Connecting -> PrinterLook(Icons.Default.BluetoothSearching, "Connecting…", "Finding the printer", Tone.Warning)
    PrinterState.Connected -> PrinterLook(Icons.Default.BluetoothConnected, "Printer ready", "Connected", Tone.Success)
    PrinterState.Printing -> PrinterLook(Icons.Default.Print, "Printing…", "Sending the receipt", Tone.Info)
    is PrinterState.Error -> PrinterLook(Icons.Default.ErrorOutline, "Printer problem", state.message, Tone.Error)
    PrinterState.Disconnected -> PrinterLook(Icons.Default.BluetoothDisabled, "Printer disconnected", "Reconnects when you print", null)
}

@Composable
private fun PrinterPill(state: PrinterState) {
    val look = lookOf(state)
    val short = when (state) {
        PrinterState.Connected -> "Printer ready"
        PrinterState.Connecting -> "Connecting"
        PrinterState.Printing -> "Printing"
        is PrinterState.Error -> "Printer error"
        else -> "No printer"
    }
    StatusPill(text = short, tone = look.tone ?: Tone.Info, icon = look.icon)
}

@Composable
private fun PrinterCard(
    printerState: PrinterState,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val look = lookOf(printerState)
    val tint = when (look.tone) {
        Tone.Success -> AppTheme.status.success
        Tone.Warning -> AppTheme.status.warning
        Tone.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                if (printerState == PrinterState.Printing || printerState == PrinterState.Connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = tint)
                } else {
                    Icon(look.icon, contentDescription = null, tint = tint)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(look.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    look.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (printerState is PrinterState.Error) {
                TextButton(onClick = onRetry) {
                    Text("Retry print", fontWeight = FontWeight.Bold)
                }
            }
            if (printerState == PrinterState.Connected) {
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}
