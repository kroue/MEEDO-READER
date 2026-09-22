package com.waterdistrict.meterreader.design

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.data.prefs.ReaderPreferences
import com.waterdistrict.meterreader.domain.billing.WaterBillingCalculator
import com.waterdistrict.meterreader.hardware.bluetooth.PrinterState
import com.waterdistrict.meterreader.ui.about.AboutScreen
import com.waterdistrict.meterreader.ui.auth.LoginContent
import com.waterdistrict.meterreader.ui.auth.LoginUiState
import com.waterdistrict.meterreader.ui.bill.DigitalBillContent
import com.waterdistrict.meterreader.ui.bill.DigitalBillUiState
import com.waterdistrict.meterreader.ui.consumers.MeterEntryContent
import com.waterdistrict.meterreader.ui.consumers.MeterEntryUiState
import com.waterdistrict.meterreader.ui.reading.ExistingBillInfo
import com.waterdistrict.meterreader.ui.reading.ReadingEntryContent
import com.waterdistrict.meterreader.ui.reading.ReadingUiState
import com.waterdistrict.meterreader.ui.settings.ProfileForm
import com.waterdistrict.meterreader.ui.settings.SettingsContent
import com.waterdistrict.meterreader.ui.settings.SettingsUiState
import com.waterdistrict.meterreader.ui.sync.SyncContent
import com.waterdistrict.meterreader.ui.sync.SyncUiState
import com.waterdistrict.meterreader.ui.theme.MeterReaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen, drawn to a PNG in app/build/design/, with made-up data — for
 * reviewing the layout without a phone or a signed-in account.
 *
 *     gradlew testDebugUnitTest -Psnapshots
 *
 * Excluded from ordinary test runs (see testOptions in app/build.gradle.kts):
 * these check nothing, they only draw.
 *
 * A plain Application, not MeterReaderApplication, so nothing tries to reach
 * the backend while drawing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi", application = Application::class)
class DesignSnapshots {

    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            MeterReaderTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/design/$name.png")
    }

    // ── Sample data ──────────────────────────────────────────────────────────

    /** 5 September 2026, 09:00 in the Philippines. */
    private val billedAt = 1_788_570_000_000L

    private val household = ConsumerEntity(
        accountNo = "048213",
        name = "Maria Santos Dela Cruz",
        address = "Purok 3, BO-OT",
        meterNo = "048213",
        prevReading = 1_234.5,
        routeId = "BO-OT",
        classification = "RESIDENTIAL",
        overdueBalance = 180.0,
        delinquentSinceMillis = billedAt - 20L * 86_400_000L,
        billingMonth = "SEP 2026",
    )

    private val billing = WaterBillingCalculator.calculate(
        previousReading = household.prevReading,
        currentReading = 1_252.0,
        classification = household.classification,
        overdueBalance = household.overdueBalance,
        delinquentSinceMillis = household.delinquentSinceMillis,
        now = billedAt,
        barangay = household.routeId,
    )

    private val reading = ReadingEntity(
        id = 7,
        accountNo = household.accountNo,
        prevReading = household.prevReading,
        currentReading = 1_252.0,
        consumption = billing.consumption,
        minimumCharge = billing.minimumCharge,
        commodityCharge = billing.commodityCharge,
        overdueBalance = billing.overdueBalance,
        overdueSurcharge = billing.overdueSurcharge,
        extensionFee = billing.extensionFee,
        creditApplied = billing.creditApplied,
        totalAmountDue = billing.totalAmountDue,
        dueDateMillis = billing.dueDateMillis,
        projectedOverdueTotal = billing.projectedOverdueTotal,
        readingDate = billedAt,
        billingMonth = "SEP 2026",
        orNumber = "0012847",
        readByUserId = "Juan Reyes",
    )

    private val readingState = ReadingUiState(
        consumer = household,
        currentReadingInput = "1252",
        billing = billing,
        printerState = PrinterState.Connected,
        billingMonth = "SEP 2026",
    )

    private val settingsState = SettingsUiState(
        username = "jreyes",
        profile = ProfileForm("Juan", "Reyes", "0917 555 0142"),
        profileLoaded = true,
        preferences = ReaderPreferences(),
        pendingUploads = 2,
    )

    // ── Screens ──────────────────────────────────────────────────────────────

    @Test
    fun login() = snap("01-login") {
        LoginContent(LoginUiState(username = "jreyes"))
    }

    @Test
    fun homePickBarangay() = snap("02-home-pick-barangay") {
        SyncContent(
            SyncUiState(
                assignedBarangays = listOf("BO-OT", "CG", "SALVACION"),
                assignedMonthStr = "SEP 2026",
                pendingUploadCount = 0,
            ),
            username = "jreyes"
        )
    }

    @Test
    fun homeRouteReady() = snap("03-home-route-ready") {
        SyncContent(
            SyncUiState(
                assignedBarangays = listOf("BO-OT", "CG"),
                assignedMonthStr = "SEP 2026",
                selectedBarangay = "BO-OT",
                message = "Synced 142 concessionaires for BO-OT.",
                success = true,
                pendingUploadCount = 3,
            ),
            username = "jreyes"
        )
    }

    @Test
    fun homeNothingAssigned() = snap("04-home-nothing-assigned") {
        SyncContent(SyncUiState(), username = "jreyes")
    }

    @Test
    fun meterEntry() = snap("05-meter-entry") {
        MeterEntryContent(
            MeterEntryUiState(
                barangay = "BO-OT",
                billingMonth = "SEP 2026",
                meterInput = "048214",
                totalOnRoute = 142,
                readOnRoute = 57,
                pendingUploads = 2,
                lastSavedAccount = "048213",
            )
        )
    }

    @Test
    fun readingEntry() = snap("06-reading") {
        ReadingEntryContent(readingState)
    }

    @Test
    @Config(qualifiers = "w393dp-h1500dp-xhdpi")
    fun readingEntryFull() = snap("07-reading-full") {
        ReadingEntryContent(readingState)
    }

    @Test
    fun readingEntryDark() = snap("08-reading-dark", dark = true) {
        ReadingEntryContent(readingState)
    }

    @Test
    fun readingPrinterFailed() = snap("09-reading-print-failed") {
        ReadingEntryContent(
            readingState.copy(
                savedReadingId = 7,
                printerState = PrinterState.Error("Printer out of paper or switched off."),
            )
        )
    }

    @Test
    fun readingAlreadyRecorded() = snap("10-reading-already-recorded") {
        ReadingEntryContent(
            readingState.copy(
                existingBillInfo = ExistingBillInfo(
                    currentReading = 1_252.0,
                    totalAmountDue = billing.totalAmountDue,
                    localReadingId = 7,
                    orNumber = "0012847",
                )
            )
        )
    }

    @Test
    @Config(qualifiers = "w393dp-h1500dp-xhdpi")
    fun bill() = snap("11-bill") {
        DigitalBillContent(
            DigitalBillUiState(consumer = household, reading = reading, isLoading = false)
        )
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun settings() = snap("12-settings") {
        SettingsContent(settingsState)
    }

    @Test
    @Config(qualifiers = "w393dp-h2200dp-xhdpi")
    fun about() = snap("13-about") {
        AboutScreen()
    }

    // ── A short budget phone: 376 × 670 dp, like the R330S readers carry ─────

    @Test
    @Config(qualifiers = "w376dp-h670dp-hdpi")
    fun smallLogin() = snap("20-small-login") {
        LoginContent(LoginUiState(username = "jreyes"))
    }

    @Test
    @Config(qualifiers = "w376dp-h670dp-hdpi")
    fun smallHome() = snap("21-small-home") {
        SyncContent(
            SyncUiState(
                assignedBarangays = listOf("BO-OT", "CG"),
                assignedMonthStr = "SEP 2026",
                selectedBarangay = "BO-OT",
                message = "Synced 142 concessionaires for BO-OT.",
                success = true,
            ),
            username = "jreyes"
        )
    }

    @Test
    @Config(qualifiers = "w376dp-h670dp-hdpi")
    fun smallMeterEntry() = snap("22-small-meter-entry") {
        MeterEntryContent(
            MeterEntryUiState(
                barangay = "BO-OT",
                billingMonth = "SEP 2026",
                meterInput = "048214",
                totalOnRoute = 142,
                readOnRoute = 57,
                pendingUploads = 2,
                lastSavedAccount = "048213",
            )
        )
    }

    @Test
    @Config(qualifiers = "w376dp-h670dp-hdpi")
    fun smallReading() = snap("23-small-reading") {
        ReadingEntryContent(readingState)
    }

    @Test
    fun meterEntryDark() = snap("14-meter-entry-dark", dark = true) {
        MeterEntryContent(
            MeterEntryUiState(
                barangay = "CG",
                billingMonth = "SEP 2026",
                totalOnRoute = 88,
                readOnRoute = 12,
            )
        )
    }
}
