package com.waterdistrict.meterreader.ui.consumers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Where a reader opens a household: by typing the number on its meter.
 *
 * Replaces the old browsable, searchable list of every account on the route.
 * The reading screen returns here after each household, with the field already
 * focused, so the rhythm in the field is read the meter, type it, bill it, next.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterEntryScreen(
    onBack: () -> Unit,
    onOpenConsumer: (String) -> Unit,
    viewModel: MeterEntryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(viewModel) {
        viewModel.openConsumer.collect { accountNo -> onOpenConsumer(accountNo) }
    }

    // Ready for the next household the moment this screen is back.
    LaunchedEffect(uiState.lastSavedAccount, uiState.totalOnRoute) {
        if (uiState.totalOnRoute > 0) runCatching { focusRequester.requestFocus() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.barangay.ifBlank { "Reading" })
                        if (uiState.billingMonth.isNotBlank()) {
                            Text(
                                uiState.billingMonth,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.totalOnRoute == 0) {
                Text(
                    text = "Nothing downloaded for this route yet. Go back and tap Sync Now while you have a connection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            // ── Route progress ───────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${uiState.readOnRoute}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        " of ${uiState.totalOnRoute} read",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                if (uiState.pendingUploads > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${uiState.pendingUploads} reading(s) saved on this phone, uploading when there's a connection",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Confirmation for the household just done ─────────────────────
            uiState.lastSavedAccount?.let { saved ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Saved $saved. Ready for the next household.",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // ── Meter number ─────────────────────────────────────────────────
            OutlinedTextField(
                value = uiState.meterInput,
                onValueChange = viewModel::onMeterInputChanged,
                label = { Text("Meter number") },
                placeholder = { Text("As printed on the meter") },
                singleLine = true,
                isError = uiState.error != null,
                supportingText = uiState.error?.let { message -> { Text(message) } },
                leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.open() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )

            Button(
                onClick = viewModel::open,
                enabled = uiState.meterInput.isNotBlank() && !uiState.isLookingUp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isLookingUp) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Open", fontSize = 16.sp)
                }
            }
        }
    }
}
