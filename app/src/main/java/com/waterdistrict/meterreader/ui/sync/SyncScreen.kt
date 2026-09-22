package com.waterdistrict.meterreader.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterdistrict.meterreader.domain.billing.BillingMonth
import com.waterdistrict.meterreader.domain.billing.DueDates
import com.waterdistrict.meterreader.ui.components.AppTopBar
import com.waterdistrict.meterreader.ui.components.BottomActionBar
import com.waterdistrict.meterreader.ui.components.EmptyState
import com.waterdistrict.meterreader.ui.components.PrimaryButton
import com.waterdistrict.meterreader.ui.components.SecondaryButton
import com.waterdistrict.meterreader.ui.components.SectionCard
import com.waterdistrict.meterreader.ui.components.StatusBanner
import com.waterdistrict.meterreader.ui.components.StatusPill
import com.waterdistrict.meterreader.ui.components.Tone
import com.waterdistrict.meterreader.ui.components.barangayLabel
import com.waterdistrict.meterreader.ui.components.ordinal
import com.waterdistrict.meterreader.ui.theme.BrandBlue800
import com.waterdistrict.meterreader.ui.theme.BrandBlue900

/**
 * Home: which barangay to read, whether this phone and the office agree, and
 * the way into reading.
 */
@Composable
fun SyncScreen(
    userEmail: String = "",
    onOpenSettings: () -> Unit = {},
    onViewConsumers: (barangay: String, billingMonth: String) -> Unit = { _, _ -> },
    viewModel: SyncViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SyncContent(
        state = uiState,
        username = userEmail,
        onOpenSettings = onOpenSettings,
        onSyncNow = viewModel::syncNow,
        onSelectBarangay = viewModel::selectBarangay,
        onSwitchBarangay = viewModel::switchBarangay,
        onStartReading = { barangay ->
            // Carry the cycle forward so meter entry and the reading screen
            // scope their local state to it.
            onViewConsumers(barangay, uiState.assignedMonthStr ?: BillingMonth.current())
        },
    )
}

@Composable
fun SyncContent(
    state: SyncUiState,
    username: String,
    onOpenSettings: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onSelectBarangay: (String) -> Unit = {},
    onSwitchBarangay: () -> Unit = {},
    onStartReading: (String) -> Unit = {},
) {
    val selected = state.selectedBarangay
    val month = state.assignedMonthStr ?: BillingMonth.current()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "MEEDO Field",
                subtitle = if (username.isNotBlank()) "Signed in as $username" else null,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            if (selected != null && !state.isLoading) {
                BottomActionBar {
                    PrimaryButton(
                        text = "Start reading",
                        onClick = { onStartReading(selected) },
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.assignedBarangays.size > 1) {
                        TextButton(
                            onClick = onSwitchBarangay,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Read another barangay", style = MaterialTheme.typography.labelLarge)
                        }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SyncStatusCard(
                month = month,
                pendingUploads = state.pendingUploadCount,
                syncing = state.isLoading,
                onSyncNow = onSyncNow
            )

            when {
                state.assignedBarangays.isEmpty() -> EmptyState(
                    icon = Icons.Default.HourglassEmpty,
                    title = "No barangay assigned yet",
                    body = "The office assigns barangays to you from the Mobile Sync page. " +
                        "This screen updates on its own the moment they do."
                )

                selected == null -> BarangayPicker(
                    barangays = state.assignedBarangays,
                    onSelect = onSelectBarangay
                )

                else -> NowReadingCard(
                    barangay = selected,
                    month = month,
                    loading = state.isLoading,
                    message = state.message,
                    success = state.success
                )
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    month: String,
    pendingUploads: Int,
    syncing: Boolean,
    onSyncNow: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Billing cycle",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(month, style = MaterialTheme.typography.titleLarge)
            }
            if (pendingUploads > 0) {
                StatusPill(
                    text = "$pendingUploads to upload",
                    tone = Tone.Warning,
                    icon = Icons.Default.CloudUpload
                )
            } else {
                StatusPill(text = "All uploaded", tone = Tone.Success, icon = Icons.Default.CloudDone)
            }
        }
        Spacer(Modifier.size(14.dp))
        SecondaryButton(
            text = "Sync now",
            onClick = onSyncNow,
            loading = syncing,
            icon = Icons.Default.Sync,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (pendingUploads > 0) {
                "Readings upload on their own whenever there's a signal. Sync now sends them straight away."
            } else {
                "Sync now fetches any change the office made to your route."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun BarangayPicker(barangays: List<String>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text("Where are you reading today?", style = MaterialTheme.typography.titleMedium)
            Text(
                "${barangays.size} barangay${if (barangays.size == 1) "" else "s"} assigned to you",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        barangays.forEach { barangay ->
            val dueDay = DueDates.dueDayFor(barangay)
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onSelect(barangay) }
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 76.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(barangayLabel(barangay), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (dueDay != null) "Bills due every ${ordinal(dueDay)}"
                            else "Bills due ${DueDates.DEFAULT_DUE_AFTER_DAYS} days after reading",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NowReadingCard(
    barangay: String,
    month: String,
    loading: Boolean,
    message: String?,
    success: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .background(Brush.linearGradient(listOf(BrandBlue900, BrandBlue800)))
                    .padding(20.dp)
            ) {
                Text(
                    "Now reading",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Text(
                    barangayLabel(barangay),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
                Text(
                    month,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f)
                )
                DueDates.dueDayFor(barangay)?.let { day ->
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Bills from this route are due on the ${ordinal(day)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        if (loading) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Downloading your route…", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Keep the app open until it finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        message?.let {
            StatusBanner(
                text = it,
                tone = if (success) Tone.Success else Tone.Error,
                title = if (success) "Route ready" else "Couldn't download the route"
            )
        }

        if (!loading && message == null) {
            Text(
                "Your route is on this phone. Readings work without a signal and upload later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
