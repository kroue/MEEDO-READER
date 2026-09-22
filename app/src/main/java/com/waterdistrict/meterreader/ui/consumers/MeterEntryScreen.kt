package com.waterdistrict.meterreader.ui.consumers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterdistrict.meterreader.ui.components.AppTopBar
import com.waterdistrict.meterreader.ui.components.BottomActionBar
import com.waterdistrict.meterreader.ui.components.EmptyState
import com.waterdistrict.meterreader.ui.components.PrimaryButton
import com.waterdistrict.meterreader.ui.components.SectionCard
import com.waterdistrict.meterreader.ui.components.StatusBanner
import com.waterdistrict.meterreader.ui.components.StatusPill
import com.waterdistrict.meterreader.ui.components.Tone
import com.waterdistrict.meterreader.ui.components.barangayLabel

/**
 * Where a reader opens a household: by typing the number on its meter.
 *
 * Replaces the old browsable, searchable list of every account on the route.
 * The reading screen returns here after each household, with the field already
 * focused, so the rhythm in the field is read the meter, type it, bill it, next.
 * The Open button sits in the bottom bar, directly above the keyboard.
 */
@Composable
fun MeterEntryScreen(
    onBack: () -> Unit,
    onOpenConsumer: (String) -> Unit,
    viewModel: MeterEntryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.openConsumer.collect { accountNo -> onOpenConsumer(accountNo) }
    }

    MeterEntryContent(
        state = uiState,
        onBack = onBack,
        onMeterInputChanged = viewModel::onMeterInputChanged,
        onOpen = viewModel::open,
    )
}

@Composable
fun MeterEntryContent(
    state: MeterEntryUiState,
    onBack: () -> Unit = {},
    onMeterInputChanged: (String) -> Unit = {},
    onOpen: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val hasRoute = state.totalOnRoute > 0

    // Ready for the next household the moment this screen is back.
    LaunchedEffect(state.lastSavedAccount, state.totalOnRoute) {
        if (hasRoute) runCatching { focusRequester.requestFocus() }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = barangayLabel(state.barangay.ifBlank { "Reading" }),
                subtitle = state.billingMonth.ifBlank { null },
                onBack = onBack
            )
        },
        bottomBar = {
            if (hasRoute) {
                BottomActionBar {
                    PrimaryButton(
                        text = "Open household",
                        onClick = onOpen,
                        enabled = state.meterInput.isNotBlank(),
                        loading = state.isLookingUp,
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        modifier = Modifier.fillMaxWidth()
                    )
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
            if (!hasRoute) {
                EmptyState(
                    icon = Icons.Default.CloudOff,
                    title = "Nothing downloaded for this route",
                    body = "Go back and tap Sync now while you have a signal. The route downloads " +
                        "once, then works offline."
                )
                return@Column
            }

            RouteProgress(state)

            state.lastSavedAccount?.let { saved ->
                StatusBanner(
                    title = "Saved $saved",
                    text = "Ready for the next household.",
                    tone = Tone.Success
                )
            }

            Column {
                Text("Meter number", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Type the number printed on the meter in front of you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.meterInput,
                    onValueChange = onMeterInputChanged,
                    placeholder = {
                        Text(
                            "Meter number",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    singleLine = true,
                    isError = state.error != null,
                    supportingText = state.error?.let { message -> { Text(message) } },
                    leadingIcon = {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
                    },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { onOpen() }),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        }
    }
}

@Composable
private fun RouteProgress(state: MeterEntryUiState) {
    val remaining = (state.totalOnRoute - state.readOnRoute).coerceAtLeast(0)
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${state.readOnRoute}",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "  of ${state.totalOnRoute} read",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 6.dp)
            )
            if (state.pendingUploads > 0) {
                StatusPill(
                    text = "${state.pendingUploads} to upload",
                    tone = Tone.Warning,
                    icon = Icons.Default.CloudUpload,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (remaining == 0) "Every household on this route is done."
            else "$remaining household${if (remaining == 1) "" else "s"} left",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
