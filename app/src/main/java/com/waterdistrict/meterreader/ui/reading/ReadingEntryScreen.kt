package com.waterdistrict.meterreader.ui.reading

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterdistrict.meterreader.hardware.bluetooth.PrinterState
import com.waterdistrict.meterreader.hardware.bluetooth.rememberBluetoothConnectAction
import com.waterdistrict.meterreader.ui.theme.BrandBlue700
import com.waterdistrict.meterreader.ui.theme.BrandBlue900
import com.waterdistrict.meterreader.ui.theme.BrandGreen500
import com.waterdistrict.meterreader.ui.theme.BrandTeal500
import com.waterdistrict.meterreader.ui.theme.ErrorRed
import com.waterdistrict.meterreader.ui.theme.TextPrimaryDark
import com.waterdistrict.meterreader.ui.theme.TextSecondaryDark
import com.waterdistrict.meterreader.ui.theme.WarningAmber
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens — aliased to the shared theme (ui/theme/Color.kt) so this
// screen's palette is the app's actual brand palette, not a coincidentally
// similar copy of it. Kept as top-level constants (rather than switched to
// MaterialTheme.colorScheme.* reads) since dozens of call sites below
// reference these by name; only the values needed to move to one source of
// truth, not every usage.
// ─────────────────────────────────────────────────────────────────────────────
private val PrimaryBlue    = BrandBlue700
private val AccentTeal     = BrandTeal500
private val SurfaceDark    = com.waterdistrict.meterreader.ui.theme.SurfaceDark
private val SurfaceCard    = com.waterdistrict.meterreader.ui.theme.SurfaceCard
private val SurfaceElevated = com.waterdistrict.meterreader.ui.theme.SurfaceElevated
private val TextPrimary    = TextPrimaryDark
private val TextSecondary  = TextSecondaryDark
private val GreenSynced    = BrandGreen500
private val OrangeWarning  = WarningAmber
private val RedError       = ErrorRed

private val GradientBrush = Brush.verticalGradient(
    colors = listOf(BrandBlue900, com.waterdistrict.meterreader.ui.theme.SurfaceDark)
)

private val pesoFormat = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}
private fun Double.toPeso() = "₱${pesoFormat.format(this)}"
private val dueDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

// ─────────────────────────────────────────────────────────────────────────────
// Root Screen Composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen composable for entering a meter reading, previewing the bill,
 * and printing a receipt via Bluetooth.
 *
 * @param accountNo  Passed from NavHost — used to load the right consumer.
 * @param onBack     Navigation callback for the back arrow.
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

    // Reopening this screen for a consumer that already has a captured
    // reading (from Room, not this ViewModel's in-memory state — which
    // resets every time this screen is recreated) shows a summary instead
    // of a blank form, unless the reader explicitly asks to re-enter it.
    var forceReenter by remember { mutableStateOf(false) }
    val existingBillInfo = uiState.existingBillInfo
    val showExistingReading = existingBillInfo != null && uiState.savedReadingId == null && !forceReenter

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GradientBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 100.dp) // space for the bottom action bar
        ) {
            // Top bar
            ReadingTopBar(
                onBack           = onBack,
                pendingCount     = uiState.pendingSyncCount,
                printerState     = uiState.printerState
            )

            Spacer(Modifier.height(8.dp))

            // Consumer info card
            uiState.consumer?.let { consumer ->
                ConsumerInfoCard(
                    accountNo = consumer.accountNo,
                    name      = consumer.name,
                    address   = consumer.address,
                    meterNo   = consumer.meterNo
                )
            } ?: ConsumerLoadingCard()

            Spacer(Modifier.height(12.dp))

            if (showExistingReading) {
                AlreadyRecordedCard(
                    billInfo = existingBillInfo!!,
                    onViewBill = { existingBillInfo.localReadingId?.let(onViewBill) },
                    onReenter = { forceReenter = true }
                )
            } else {
                // Reading input card
                ReadingInputCard(
                    previousReading     = uiState.consumer?.prevReading ?: 0.0,
                    currentReadingInput = uiState.currentReadingInput,
                    onCurrentReadingChange = { viewModel.onEvent(ReadingUiEvent.CurrentReadingChanged(it)) },
                    remarks             = uiState.remarks,
                    onRemarksChange     = { viewModel.onEvent(ReadingUiEvent.RemarksChanged(it)) },
                    inputError          = uiState.inputError,
                    inputWarning        = uiState.inputWarning
                )

                Spacer(Modifier.height(12.dp))

                // Bill preview — only shown when billing is computed
                AnimatedVisibility(
                    visible = uiState.billing != null,
                    enter   = fadeIn() + expandVertically(),
                    exit    = fadeOut() + shrinkVertically()
                ) {
                    uiState.billing?.let { billing ->
                        BillBreakdownCard(billing = billing)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Printer state card
                PrinterStatusCard(
                    printerState = uiState.printerState,
                    onRetryPrint = triggerRetryPrint,
                    onDisconnect = { viewModel.onEvent(ReadingUiEvent.DisconnectPrinter) }
                )

                // Save result feedback
                uiState.saveError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    ErrorBanner(message = error)
                }

                uiState.savedReadingId?.let { readingId ->
                    Spacer(Modifier.height(8.dp))
                    SuccessBanner(message = "Reading saved — it uploads as soon as there's a connection.")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onViewBill(readingId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal)
                    ) {
                        Icon(Icons.Outlined.Receipt, contentDescription = null, tint = SurfaceDark)
                        Spacer(Modifier.width(8.dp))
                        Text("View Digital Bill", color = SurfaceDark, fontWeight = FontWeight.Bold)
                    }

                    // Only reachable when printing failed after the save — a
                    // clean save returns to meter entry on its own. The bill
                    // can still be reprinted later from the account.
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.onEvent(ReadingUiEvent.NextHousehold) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.6f))
                    ) {
                        Text("Next household", color = AccentTeal, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Floating action buttons
        if (!showExistingReading) {
            ReadingActionButtons(
                modifier      = Modifier.align(Alignment.BottomCenter),
                canSave       = uiState.billing != null && uiState.inputError == null && !uiState.isSaving,
                isSaving      = uiState.isSaving,
                printerReady  = uiState.printerState == PrinterState.Connected,
                onSaveOnly    = { viewModel.onEvent(ReadingUiEvent.SaveOnly) },
                onSaveAndPrint = triggerSaveAndPrint
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadingTopBar(
    onBack: () -> Unit,
    pendingCount: Int,
    printerState: PrinterState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "Meter Reading",
                style = MaterialTheme.typography.titleLarge.copy(
                    color      = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text  = "Water District Field App",
                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
            )
        }

        // Pending sync badge
        if (pendingCount > 0) {
            SyncBadge(count = pendingCount)
            Spacer(Modifier.width(8.dp))
        }

        // Printer indicator
        PrinterIndicatorDot(printerState)
    }
}

@Composable
private fun SyncBadge(count: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(OrangeWarning)
            .size(28.dp)
    ) {
        Text(
            text  = if (count > 99) "99+" else "$count",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PrinterIndicatorDot(state: PrinterState) {
    val color = when (state) {
        PrinterState.Connected  -> GreenSynced
        PrinterState.Printing   -> AccentTeal
        PrinterState.Connecting -> OrangeWarning
        is PrinterState.Error   -> RedError
        else                    -> Color(0xFF546E7A)
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ConsumerInfoCard(
    accountNo: String,
    name: String,
    address: String,
    meterNo: String
) {
    GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(AccentTeal, PrimaryBlue))
                    )
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint   = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(address, color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoChip(label = "Acct", value = accountNo)
                    InfoChip(label = "Meter", value = meterNo)
                }
            }
        }
    }
}

@Composable
private fun AlreadyRecordedCard(
    billInfo: ExistingBillInfo,
    onViewBill: () -> Unit,
    onReenter: () -> Unit
) {
    val hasLocalCopy = billInfo.localReadingId != null

    GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = GreenSynced, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Reading Already Recorded", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Current: ${billInfo.currentReading} m³  •  Due: ${billInfo.totalAmountDue.toPeso()}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    "OR: ${billInfo.orNumber.ifBlank { "Pending sync" }}",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                if (!hasLocalCopy) {
                    Text(
                        "Recorded on the server — no local copy on this device",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                // The office may have taken a payment while the reader was in
                // the field, so the server recalculates every bill against the
                // live balance on upload. When that comes out different from
                // what was printed, the receipt in the concessionaire's hand
                // is wrong and the office has to reissue it.
                billInfo.serverTotalAmountDue?.let { serverTotal ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Office recalculated this bill to ${serverTotal.toPeso()} — the printed " +
                            "receipt is out of date and needs reissuing.",
                        color = OrangeWarning,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onReenter,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.6f))
            ) {
                Text("Re-enter", color = AccentTeal)
            }
            if (hasLocalCopy) {
                Button(
                    onClick = onViewBill,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Icon(Icons.Outlined.Receipt, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View Bill", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ConsumerLoadingCard() {
    GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color    = AccentTeal,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
            Text("Loading consumer data...", color = TextSecondary)
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1565C0).copy(alpha = 0.4f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("$label: ", color = TextSecondary, fontSize = 10.sp)
        Text(value, color = AccentTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReadingInputCard(
    previousReading: Double,
    currentReadingInput: String,
    onCurrentReadingChange: (String) -> Unit,
    remarks: String,
    onRemarksChange: (String) -> Unit,
    inputError: String?,
    inputWarning: String? = null
) {
    GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text  = "📊 Enter Meter Reading",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )

        Spacer(Modifier.height(12.dp))

        // Previous reading (read-only)
        OutlinedTextField(
            value         = "$previousReading",
            onValueChange = {},
            label         = { Text("Previous Reading (m³)") },
            readOnly      = true,
            enabled       = false,
            modifier      = Modifier.fillMaxWidth(),
            colors        = MeterReaderFieldColors(),
            trailingIcon  = { Icon(Icons.Outlined.Lock, null, tint = TextSecondary) }
        )

        Spacer(Modifier.height(10.dp))

        // Current reading input
        OutlinedTextField(
            value         = currentReadingInput,
            onValueChange = onCurrentReadingChange,
            label         = { Text("Current Reading (m³)") },
            placeholder   = { Text("e.g. 148.00", color = TextSecondary) },
            modifier      = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError       = inputError != null,
            supportingText = {
                // A warning is not an error: a meter that rolled past 99,999
                // is a real reading and must still be billable in the field.
                inputError?.let { Text(it, color = RedError, fontSize = 11.sp) }
                    ?: inputWarning?.let { Text(it, color = OrangeWarning, fontSize = 11.sp) }
            },
            colors        = MeterReaderFieldColors(),
            trailingIcon  = {
                Icon(
                    if (inputError != null) Icons.Default.Error else Icons.Default.Speed,
                    contentDescription = null,
                    tint = if (inputError != null) RedError else AccentTeal
                )
            }
        )

        Spacer(Modifier.height(10.dp))

        // Remarks field
        OutlinedTextField(
            value         = remarks,
            onValueChange = onRemarksChange,
            label         = { Text("Remarks (optional)") },
            placeholder   = { Text("e.g. Meter covered by mud", color = TextSecondary) },
            modifier      = Modifier.fillMaxWidth(),
            maxLines      = 2,
            colors        = MeterReaderFieldColors(),
        )
    }
}

@Composable
private fun BillBreakdownCard(billing: com.waterdistrict.meterreader.domain.billing.BillingResult) {
    GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text       = "💧 Bill Breakdown",
            color      = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize   = 15.sp
        )

        Spacer(Modifier.height(10.dp))

        // Consumption summary
        ConsumptionBadge(billing.consumption)

        Spacer(Modifier.height(10.dp))

        DashedDivider()
        Spacer(Modifier.height(8.dp))

        // Line items — always shown in full, even at ₱0.00, so the bill is a
        // complete, auditable statement rather than one that hides categories
        // that happen not to apply this cycle.
        BillLineItem("Minimum Charge (0–10 m³)", billing.minimumCharge, isBold = false)
        val excess = (billing.consumption - 10.0).coerceAtLeast(0.0)
        BillLineItem("Commodity Charge (${excess} m³ @ ₱10.80)", billing.commodityCharge)

        Spacer(Modifier.height(4.dp))
        BillLineItem("Water Charge Subtotal", billing.totalWaterCharge, isBold = true)

        Spacer(Modifier.height(4.dp))
        DashedDivider()
        Spacer(Modifier.height(4.dp))

        val balanceLabel = if (billing.daysOverdue != null)
            "Previous Balance (${billing.daysOverdue}d overdue)"
        else
            "Previous Balance"
        BillLineItem(balanceLabel, billing.overdueBalance)
        BillLineItem("Overdue Surcharge (3%)", billing.overdueSurcharge)
        BillLineItem("Extension Fee", billing.extensionFee)

        Spacer(Modifier.height(8.dp))

        // Total
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(listOf(PrimaryBlue, Color(0xFF006064)))
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TOTAL AMOUNT DUE",
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp
                )
                Text(
                    billing.totalAmountDue.toPeso(),
                    color      = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = 20.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // What happens if this specific bill isn't paid on time — shown up
        // front so the concessionaire knows the cost of going overdue.
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Pay on or before ${dueDateFormat.format(Date(billing.dueDateMillis))} to avoid a surcharge.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("If paid after that date:", color = OrangeWarning, fontSize = 11.sp)
                Text(
                    billing.projectedOverdueTotal.toPeso(),
                    color = OrangeWarning,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ConsumptionBadge(consumption: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AccentTeal.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Waves, null, tint = AccentTeal, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Consumption", color = TextSecondary, fontSize = 11.sp)
            Text(
                "${consumption} m³",
                color = AccentTeal,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp
            )
        }
    }
}

@Composable
private fun BillLineItem(label: String, amount: Double, isBold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color      = if (isBold) TextPrimary else TextSecondary,
            fontSize   = 12.sp,
            fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            amount.toPeso(),
            color      = if (isBold) AccentTeal else TextSecondary,
            fontSize   = 12.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun DashedDivider() {
    HorizontalDivider(
        color     = SurfaceElevated,
        thickness = 1.dp,
        modifier  = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PrinterStatusCard(
    printerState: PrinterState,
    onRetryPrint: () -> Unit,
    onDisconnect: () -> Unit
) {
    val (icon, title, subtitle, color) = when (printerState) {
        PrinterState.Idle         -> Quadruple(Icons.Outlined.Print,        "Printer",        "Not connected", TextSecondary)
        PrinterState.Connecting   -> Quadruple(Icons.Default.BluetoothSearching, "Connecting...", "Please wait",   OrangeWarning)
        PrinterState.Connected    -> Quadruple(Icons.Default.BluetoothConnected, "Printer Ready", "Connected",    GreenSynced)
        PrinterState.Printing     -> Quadruple(Icons.Default.Print,          "Printing...",    "Sending data",  AccentTeal)
        is PrinterState.Error     -> Quadruple(Icons.Default.ErrorOutline,   "Printer Error",  printerState.message, RedError)
        PrinterState.Disconnected -> Quadruple(Icons.Default.BluetoothDisabled, "Disconnected","Tap to reconnect", TextSecondary)
    }

    GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title,    color = TextPrimary,   fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(subtitle, color = TextSecondary, fontSize   = 11.sp)
            }

            if (printerState is PrinterState.Error) {
                TextButton(onClick = onRetryPrint) {
                    Text("Retry", color = AccentTeal)
                }
            }
            if (printerState == PrinterState.Connected) {
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ReadingActionButtons(
    modifier: Modifier = Modifier,
    canSave: Boolean,
    isSaving: Boolean,
    printerReady: Boolean,
    onSaveOnly: () -> Unit,
    onSaveAndPrint: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, SurfaceDark)
                )
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Save Only button
        OutlinedButton(
            onClick  = onSaveOnly,
            enabled  = canSave,
            modifier = Modifier.weight(1f).height(52.dp),
            shape    = RoundedCornerShape(14.dp),
            border   = BorderStroke(1.dp, AccentTeal.copy(alpha = if (canSave) 1f else 0.3f))
        ) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(16.dp), color = AccentTeal, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Save, null, tint = AccentTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save", color = AccentTeal, fontWeight = FontWeight.SemiBold)
            }
        }

        // Save & Print button
        Button(
            onClick  = onSaveAndPrint,
            enabled  = canSave,
            modifier = Modifier.weight(2f).height(52.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = if (printerReady) PrimaryBlue else Color(0xFF1A237E),
                disabledContainerColor = Color(0xFF263238)
            )
        ) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Saving...", color = Color.White)
            } else {
                Icon(Icons.Default.Print, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (printerReady) "Save & Print Receipt" else "Save & Print",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(RedError.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Error, null, tint = RedError, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, color = RedError, fontSize = 12.sp)
    }
}

@Composable
private fun SuccessBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GreenSynced.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = GreenSynced, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, color = GreenSynced, fontSize = 12.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable glassmorphism card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content  = content
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** TextField colours that match the dark theme. */
@Composable
private fun MeterReaderFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor    = AccentTeal,
    unfocusedBorderColor  = Color(0xFF37474F),
    focusedLabelColor     = AccentTeal,
    unfocusedLabelColor   = TextSecondary,
    focusedTextColor      = TextPrimary,
    unfocusedTextColor    = TextPrimary,
    disabledTextColor     = TextSecondary,
    disabledBorderColor   = Color(0xFF263238),
    disabledLabelColor    = Color(0xFF546E7A),
    cursorColor           = AccentTeal,
    errorBorderColor      = RedError,
    errorLabelColor       = RedError,
    errorTextColor        = TextPrimary,
    errorCursorColor      = RedError
)

/** Simple data holder to avoid Pair nesting in when expressions. */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
