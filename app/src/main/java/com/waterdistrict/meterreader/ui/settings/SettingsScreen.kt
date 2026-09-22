package com.waterdistrict.meterreader.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterdistrict.meterreader.data.prefs.ThemeChoice
import com.waterdistrict.meterreader.data.remote.MIN_PASSWORD_LENGTH
import com.waterdistrict.meterreader.domain.billing.DueDates
import com.waterdistrict.meterreader.domain.billing.WaterBillingCalculator
import com.waterdistrict.meterreader.domain.billing.WaterRateConfig
import com.waterdistrict.meterreader.ui.components.AppTopBar
import com.waterdistrict.meterreader.ui.components.KeyValueRow
import com.waterdistrict.meterreader.ui.components.ListRow
import com.waterdistrict.meterreader.ui.components.PrimaryButton
import com.waterdistrict.meterreader.ui.components.SecondaryButton
import com.waterdistrict.meterreader.ui.components.SectionCard
import com.waterdistrict.meterreader.ui.components.SectionDivider
import com.waterdistrict.meterreader.ui.components.StatusBanner
import com.waterdistrict.meterreader.ui.components.Tone
import com.waterdistrict.meterreader.ui.components.formatPeso
import com.waterdistrict.meterreader.ui.components.ordinal

/**
 * Settings: the reader's own account, how this phone shows things, the
 * figures the office bills by, and signing out.
 *
 * The same ground as the console's Profile and Settings pages, arranged for
 * a phone. Signing out lives here rather than on the home screen's top bar,
 * where it sat one mis-tap from the button readers use most — and signing
 * back in needs a signal a reader in the field may not have.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onOpenAbout = onOpenAbout,
        onSignOut = onSignOut,
        onStartEditing = viewModel::startEditing,
        onCancelEditing = viewModel::cancelEditing,
        onDraftChanged = viewModel::onDraftChanged,
        onSaveProfile = viewModel::saveProfile,
        onPasswordChanged = viewModel::onPasswordChanged,
        onChangePassword = viewModel::changePassword,
        onThemeChanged = viewModel::setTheme,
        onLargeTextChanged = viewModel::setLargeText,
        onKeepScreenOnChanged = viewModel::setKeepScreenOn,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onStartEditing: () -> Unit = {},
    onCancelEditing: () -> Unit = {},
    onDraftChanged: (ProfileForm) -> Unit = {},
    onSaveProfile: () -> Unit = {},
    onPasswordChanged: (PasswordForm) -> Unit = {},
    onChangePassword: () -> Unit = {},
    onThemeChanged: (ThemeChoice) -> Unit = {},
    onLargeTextChanged: (Boolean) -> Unit = {},
    onKeepScreenOnChanged: (Boolean) -> Unit = {},
) {
    var confirmSignOut by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { AppTopBar(title = "Settings", onBack = onBack) },
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
            AccountHeader(name = state.fullName, username = state.username)

            DetailsCard(state, onStartEditing, onCancelEditing, onDraftChanged, onSaveProfile)

            PasswordCard(state, onPasswordChanged, onChangePassword)

            DisplayCard(state, onThemeChanged, onLargeTextChanged, onKeepScreenOnChanged)

            BillingReferenceCard()

            SectionCard {
                ListRow(
                    title = "About this app",
                    detail = "Developer, terms of use and data privacy",
                    icon = Icons.Default.Info,
                    onClick = onOpenAbout,
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            SecondaryButton(
                text = "Sign out",
                onClick = { confirmSignOut = true },
                icon = Icons.AutoMirrored.Filled.Logout,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            title = { Text("Sign out of this phone?") },
            text = {
                Text(
                    if (state.pendingUploads > 0) {
                        "${state.pendingUploads} reading(s) haven't reached the office yet. They " +
                            "stay on this phone, but they only upload once someone signs back in — " +
                            "and signing in needs a signal."
                    } else {
                        "Signing back in needs a signal. If you're still out on the route, stay " +
                            "signed in."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Stay signed in") }
            }
        )
    }
}

@Composable
private fun AccountHeader(name: String, username: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(60.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Text(
                initialsOf(name.ifBlank { username }),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name.ifBlank { "Field reader" }, style = MaterialTheme.typography.titleLarge)
            Text(
                "@$username  ·  Field reader",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

@Composable
private fun DetailsCard(
    state: SettingsUiState,
    onStartEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    onDraftChanged: (ProfileForm) -> Unit,
    onSaveProfile: () -> Unit,
) {
    val draft = state.draft
    SectionCard(
        title = "Your details",
        icon = Icons.Default.Badge,
        trailing = {
            if (draft == null && state.profileLoaded) {
                TextButton(onClick = onStartEditing) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
            }
        }
    ) {
        if (draft == null) {
            KeyValueRow("Name", state.fullName.ifBlank { "—" })
            KeyValueRow("Phone", state.profile.phoneNumber.ifBlank { "—" })
            KeyValueRow("Username", state.username.ifBlank { "—" })
            Spacer(Modifier.height(4.dp))
            Text(
                "Your name is printed on every receipt you hand out. Your username is set by the office.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.firstName,
                    onValueChange = { onDraftChanged(draft.copy(firstName = it)) },
                    label = { Text("First name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.lastName,
                    onValueChange = { onDraftChanged(draft.copy(lastName = it)) },
                    label = { Text("Last name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.phoneNumber,
                    onValueChange = { onDraftChanged(draft.copy(phoneNumber = it)) },
                    label = { Text("Phone number") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        text = "Cancel",
                        onClick = onCancelEditing,
                        enabled = !state.savingProfile,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = "Save",
                        onClick = onSaveProfile,
                        loading = state.savingProfile,
                        enabled = draft.firstName.isNotBlank() && draft.lastName.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        state.profileMessage?.let {
            Spacer(Modifier.height(10.dp))
            StatusBanner(it.text, if (it.isError) Tone.Error else Tone.Success)
        }
    }
}

@Composable
private fun PasswordCard(
    state: SettingsUiState,
    onPasswordChanged: (PasswordForm) -> Unit,
    onChangePassword: () -> Unit,
) {
    val password = state.password
    SectionCard(title = "Password", icon = Icons.Default.Key) {
        Text(
            "Your current password is asked for first — a phone left unlocked on a route is " +
                "exactly how an account gets taken. Changing it needs a signal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = password.current,
                onValueChange = { onPasswordChanged(password.copy(current = it)) },
                label = { Text("Current password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password.new,
                onValueChange = { onPasswordChanged(password.copy(new = it)) },
                label = { Text("New password") },
                supportingText = { Text("At least $MIN_PASSWORD_LENGTH characters") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password.confirm,
                onValueChange = { onPasswordChanged(password.copy(confirm = it)) },
                label = { Text("Confirm new password") },
                isError = password.mismatch,
                supportingText = if (password.mismatch) {
                    { Text("These don't match.") }
                } else null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(
                text = "Change password",
                onClick = onChangePassword,
                loading = state.changingPassword,
                enabled = password.current.isNotEmpty() &&
                    password.new.length >= MIN_PASSWORD_LENGTH &&
                    password.confirm.isNotEmpty() &&
                    !password.mismatch,
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.passwordMessage?.let {
            Spacer(Modifier.height(10.dp))
            StatusBanner(it.text, if (it.isError) Tone.Error else Tone.Success)
        }
    }
}

@Composable
private fun DisplayCard(
    state: SettingsUiState,
    onThemeChanged: (ThemeChoice) -> Unit,
    onLargeTextChanged: (Boolean) -> Unit,
    onKeepScreenOnChanged: (Boolean) -> Unit,
) {
    val prefs = state.preferences
    SectionCard(title = "Display", icon = Icons.Default.LightMode) {
        Text(
            "Light is easiest to read outdoors. Match phone follows your phone's own setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        val options = listOf(
            ThemeChoice.LIGHT to "Light",
            ThemeChoice.DARK to "Dark",
            ThemeChoice.SYSTEM to "Match phone",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (choice, label) ->
                SegmentedButton(
                    selected = prefs.theme == choice,
                    onClick = { onThemeChanged(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
        SectionDivider(Modifier.padding(top = 6.dp))
        ListRow(
            title = "Larger text",
            detail = "Makes everything easier to read at arm's length",
            icon = Icons.Default.TextFields,
            onClick = { onLargeTextChanged(!prefs.largeText) },
            trailing = { Switch(checked = prefs.largeText, onCheckedChange = onLargeTextChanged) }
        )
        ListRow(
            title = "Keep screen on while reading",
            detail = "The screen won't switch off between households",
            icon = Icons.Default.ScreenLockPortrait,
            onClick = { onKeepScreenOnChanged(!prefs.keepScreenOn) },
            trailing = { Switch(checked = prefs.keepScreenOn, onCheckedChange = onKeepScreenOnChanged) }
        )
    }
}

/**
 * The figures the phone bills by, from the same constants the calculator
 * uses — shown so a reader can answer a household's question on the doorstep,
 * never edited here.
 */
@Composable
private fun BillingReferenceCard() {
    val rates = WaterRateConfig()
    SectionCard(title = "What the office bills by", icon = Icons.Default.Receipt) {
        KeyValueRow("Residential / Government", formatPeso(WaterBillingCalculator.minimumChargeFor("RESIDENTIAL")))
        KeyValueRow("Commercial A", formatPeso(WaterBillingCalculator.minimumChargeFor("COMMERCIAL A")))
        KeyValueRow("Commercial B", formatPeso(WaterBillingCalculator.minimumChargeFor("COMMERCIAL B")))
        Text(
            "Minimum charge, covering the first ${rates.minChargeThreshold.toInt()} m³. " +
                "Each m³ after that is ${formatPeso(rates.commodityRate)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SectionDivider()
        KeyValueRow(
            "Late surcharge",
            "${(rates.overdueSurchargeRate * 100).toInt()}% after ${rates.gracePeriodDays} days"
        )
        KeyValueRow("Extension fee", "${formatPeso(rates.extensionFee)}, once per unpaid run")
        SectionDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Due dates", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(4.dp))
        DUE_DATE_ROWS.forEach { (label, code) ->
            val day = DueDates.dueDayFor(code)
            KeyValueRow(
                label,
                if (day != null) "Every ${ordinal(day)}" else "${DueDates.DEFAULT_DUE_AFTER_DAYS} days after billing"
            )
        }
        Text(
            "A bill issued after this month's date is due on next month's.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Every barangay the office serves, in the console's order. */
private val DUE_DATE_ROWS = listOf(
    "Bo-ot" to "BO-OT",
    "Cebuano Group" to "CG",
    "Kabatangan" to "KABATANGAN",
    "Salvacion" to "SALVACION",
    "Amoyong" to "AMOYONG",
    "Katutungan" to "KATUTUNGAN",
    "Pagalongan" to "PAGALONGAN",
    "Milaya" to "MILAYA",
    "Diomil" to "DIOMIL",
)
