package com.waterdistrict.meterreader.ui.consumers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumerListScreen(
    onBack: () -> Unit,
    onConsumerSelected: (String) -> Unit,
    onViewBill: (Long) -> Unit,
    viewModel: ConsumerListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (uiState.barangay.isNotBlank()) uiState.barangay else "Assigned Concessionaires"
                        )
                        // Progress for THIS cycle. Worth showing now that it
                        // means something: "already read" used to span all
                        // time, so from the second billing month onward every
                        // consumer opened already ticked off and the reader had
                        // no way to see what was left to do.
                        if (uiState.items.isNotEmpty()) {
                            Text(
                                "${uiState.readCount} of ${uiState.items.size} read" +
                                    if (uiState.billingMonth.isNotBlank()) " • ${uiState.billingMonth}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No concessionaires downloaded yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.items, key = { it.consumer.accountNo }) { item ->
                    ConsumerRow(
                        item = item,
                        onClick = { onConsumerSelected(item.consumer.accountNo) },
                        onViewBill = item.localReadingId?.let { id -> { onViewBill(id) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsumerRow(
    item: ConsumerListItem,
    onClick: () -> Unit,
    onViewBill: (() -> Unit)?
) {
    val consumer = item.consumer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isRead) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (item.isRead) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(consumer.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                consumer.address,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Acct: ${consumer.accountNo}  •  Meter: ${consumer.meterNo}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.isRead) {
            if (onViewBill != null) {
                // Jumps straight to the saved bill — no need to open the
                // reading screen or touch "Re-enter" just to look at it.
                IconButton(onClick = onViewBill) {
                    Icon(
                        Icons.Outlined.Receipt,
                        contentDescription = "View Bill",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            } else {
                Text("Read", color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
