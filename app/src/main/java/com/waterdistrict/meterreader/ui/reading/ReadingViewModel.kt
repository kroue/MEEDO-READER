package com.waterdistrict.meterreader.ui.reading

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.data.local.entity.SyncStatus
import com.waterdistrict.meterreader.data.remote.AuthRepository
import com.waterdistrict.meterreader.domain.billing.BillingMonth
import com.waterdistrict.meterreader.domain.billing.BillingResult
import com.waterdistrict.meterreader.domain.billing.ReadingProblem
import com.waterdistrict.meterreader.domain.billing.WaterBillingCalculator
import com.waterdistrict.meterreader.hardware.bluetooth.BluetoothPrinterManager
import com.waterdistrict.meterreader.hardware.bluetooth.PrinterState
import com.waterdistrict.meterreader.hardware.bluetooth.WaterBillReceiptBuilder
import com.waterdistrict.meterreader.worker.SyncReadingsWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents the complete UI state for the reading-entry screen.
 * The screen is driven entirely by this data class — no mutable state
 * lives in the Composable.
 */
data class ReadingUiState(
    // Consumer
    val consumer: ConsumerEntity? = null,

    // Form input
    val currentReadingInput: String = "",
    val remarks: String = "",

    // Derived billing (recalculated on every input change)
    val billing: BillingResult? = null,
    val inputError: String? = null,     // blocks saving
    val inputWarning: String? = null,   // worth confirming, but not a blocker

    // Save/print flow
    val isSaving: Boolean = false,
    val savedReadingId: Long? = null,
    val saveError: String? = null,

    // Printer
    val printerState: PrinterState = PrinterState.Idle,

    // Pending sync badge
    val pendingSyncCount: Int = 0,

    // The reading already captured for this consumer IN THIS CYCLE, if any —
    // read straight from Room so it survives this screen being recreated
    // (e.g. navigating back to the consumer list and reopening it).
    val existingReading: ReadingEntity? = null,

    // The billing cycle this screen is capturing for, e.g. "AUG 2026".
    val billingMonth: String = "",

    // What to show for "already recorded" — from the local reading if one
    // exists, otherwise from Firestore's billingHistory (via the consumer's
    // already-billed fields) so a bill already on the server still shows
    // even if this device's local copy was lost.
    val existingBillInfo: ExistingBillInfo? = null,
)

/**
 * @property localReadingId Non-null only when there's a local [ReadingEntity]
 *   to open in the Digital Bill screen; null when we only know about the
 *   bill from Firestore's aggregate billingHistory record.
 */
data class ExistingBillInfo(
    val currentReading: Double,
    val totalAmountDue: Double,
    val localReadingId: Long?,
    val orNumber: String,
    /**
     * Set when the server recalculated this bill to a different figure than
     * the one printed in the field — the office took a payment while the
     * reader was out, say. The printed receipt is wrong and must be reissued.
     */
    val serverTotalAmountDue: Double? = null
)

// Events that the UI sends up to the ViewModel (UDF pattern)
sealed class ReadingUiEvent {
    data class CurrentReadingChanged(val value: String) : ReadingUiEvent()
    data class RemarksChanged(val value: String) : ReadingUiEvent()
    object SaveAndPrint : ReadingUiEvent()
    object SaveOnly : ReadingUiEvent()
    object RetryPrint : ReadingUiEvent()
    object DisconnectPrinter : ReadingUiEvent()
    /** Move on without a receipt — used when printing failed after a save. */
    object NextHousehold : ReadingUiEvent()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the meter reading entry screen.
 *
 * ### Responsibilities
 * - Load the [ConsumerEntity] for the selected account.
 * - Reactively recalculate the bill whenever [currentReadingInput] changes.
 * - Save the [ReadingEntity] to Room and trigger WorkManager sync.
 * - Delegate Bluetooth print jobs to [BluetoothPrinterManager].
 *
 * ### Navigation argument
 * The account number is passed via [SavedStateHandle] key `"accountNo"`.
 */
@HiltViewModel
class ReadingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val consumerDao: ConsumerDao,
    private val readingDao: ReadingDao,
    private val printerManager: BluetoothPrinterManager,
    private val workManager: WorkManager,
    private val authRepository: AuthRepository,
) : ViewModel() {

    // Account number from NavGraph argument (fallback for testing without NavHost)
    private val accountNo: String = savedStateHandle.get<String>("accountNo") ?: "00123-456"

    // The billing cycle being captured. Everything local is scoped to it: a
    // correction replaces this month's row, while a new month gets its own,
    // so last month's bill stays viewable and printable on the device.
    private val billingMonth: String =
        savedStateHandle.get<String>("billingMonth")?.takeIf { it.isNotBlank() }
            ?: BillingMonth.current()

    // Editable form fields (internal MutableStateFlow)
    private val _currentReadingInput = MutableStateFlow("")
    private val _remarks             = MutableStateFlow("")

    // Internal action triggers
    private val _saveAndPrintTrigger = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val _savedReadingId      = MutableStateFlow<Long?>(null)
    private val _isSaving            = MutableStateFlow(false)
    private val _saveError           = MutableStateFlow<String?>(null)

    // One-shot: this household is finished. The screen answers it by going
    // straight back to meter entry, ready for the next household.
    private val _readingComplete = Channel<String>(Channel.BUFFERED)
    val readingComplete: Flow<String> = _readingComplete.receiveAsFlow()

    /** Exposed to the UI — single source of truth. */
    val uiState: StateFlow<ReadingUiState> = combine(
        consumerDao.getConsumerByAccountNo(accountNo),
        _currentReadingInput,
        _remarks,
        printerManager.printerState,
        readingDao.getPendingCount(),
        _isSaving,
        _savedReadingId,
        _saveError,
        readingDao.getReadingForAccountInMonth(accountNo, billingMonth)
    ) { args ->
        // combine<*> passes Array<Any?> when combining >6 flows
        @Suppress("UNCHECKED_CAST")
        val consumer          = args[0] as ConsumerEntity?
        val currentInput      = args[1] as String
        val remarks           = args[2] as String
        val printerState      = args[3] as PrinterState
        val pendingCount      = args[4] as Int
        val isSaving          = args[5] as Boolean
        val savedId           = args[6] as Long?
        val saveError         = args[7] as String?
        val existingReading   = args[8] as ReadingEntity?

        val computed = computeBilling(consumer, currentInput)

        val existingBillInfo = when {
            existingReading != null -> ExistingBillInfo(
                currentReading = existingReading.currentReading,
                totalAmountDue = existingReading.totalAmountDue,
                localReadingId = existingReading.id,
                orNumber = existingReading.orNumber,
                serverTotalAmountDue = existingReading.serverTotalAmountDue
            )
            consumer?.alreadyBilledThisMonth == true -> ExistingBillInfo(
                currentReading = consumer.billedReadingThisMonth,
                totalAmountDue = consumer.billedAmountThisMonth,
                localReadingId = null,
                orNumber = ""
            )
            else -> null
        }

        ReadingUiState(
            consumer          = consumer,
            currentReadingInput = currentInput,
            remarks           = remarks,
            billing           = computed.billing,
            inputError        = computed.error,
            inputWarning      = computed.warning,
            billingMonth      = billingMonth,
            isSaving          = isSaving,
            savedReadingId    = savedId,
            saveError         = saveError,
            printerState      = printerState,
            pendingSyncCount  = pendingCount,
            existingReading   = existingReading,
            existingBillInfo  = existingBillInfo
        )
    }.stateIn(
        scope            = viewModelScope,
        started          = SharingStarted.WhileSubscribed(5_000),
        initialValue     = ReadingUiState()
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Event handler (called by the UI)
    // ─────────────────────────────────────────────────────────────────────────

    fun onEvent(event: ReadingUiEvent) {
        when (event) {
            is ReadingUiEvent.CurrentReadingChanged -> {
                _currentReadingInput.value = event.value
                _saveError.value = null       // clear any previous save error
            }
            is ReadingUiEvent.RemarksChanged -> _remarks.value = event.value
            ReadingUiEvent.SaveAndPrint      -> saveReading(andPrint = true)
            ReadingUiEvent.SaveOnly          -> saveReading(andPrint = false)
            ReadingUiEvent.RetryPrint        -> retryPrint()
            ReadingUiEvent.DisconnectPrinter -> printerManager.disconnect()
            ReadingUiEvent.NextHousehold     -> viewModelScope.launch { _readingComplete.send(accountNo) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveReading(andPrint: Boolean) {
        val state = uiState.value

        // Guard: validation
        if (state.inputError != null || state.billing == null || state.consumer == null) return

        viewModelScope.launch {
            _isSaving.value = true
            _saveError.value = null

            try {
                val currentReading = state.currentReadingInput.toDouble()

                // Never allowed to hold up or prevent the save — see
                // resolveReaderName. This lookup used to throw offline before the
                // reading reached Room, so the reading was never recorded.
                val readerName = resolveReaderName()

                // 1. Persist to Room — reuse the existing row's id (if any) so a
                //    correction replaces the prior reading instead of creating
                //    a duplicate local row (and, on upload, a duplicate billing entry).
                val entity = ReadingEntity(
                    id               = state.existingReading?.id ?: 0,
                    accountNo        = accountNo,
                    billingMonth     = billingMonth,
                    prevReading      = state.consumer.prevReading,
                    currentReading   = currentReading,
                    consumption      = state.billing.consumption,
                    minimumCharge    = state.billing.minimumCharge,
                    commodityCharge  = state.billing.commodityCharge,
                    overdueBalance   = state.billing.overdueBalance,
                    overdueSurcharge = state.billing.overdueSurcharge,
                    extensionFee     = state.billing.extensionFee,
                    creditApplied    = state.billing.creditApplied,
                    totalAmountDue   = state.billing.totalAmountDue,
                    dueDateMillis    = state.billing.dueDateMillis,
                    projectedOverdueTotal = state.billing.projectedOverdueTotal,
                    syncStatus       = SyncStatus.PENDING,
                    // A correction re-enters the upload queue and clears any
                    // stale "server repriced this" note from the last attempt.
                    serverTotalAmountDue = null,
                    orNumber         = state.existingReading?.orNumber ?: "",
                    readByUserId     = readerName,
                    remarks          = state.remarks
                )

                val localId = readingDao.insert(entity)
                _savedReadingId.value = localId

                // 2. Kick off background sync (no-op if already queued)
                SyncReadingsWorker.enqueue(workManager)

                // 3. Print if requested
                val printed = if (andPrint) {
                    printReceipt(state.consumer, state.billing, currentReading, readerName)
                } else {
                    true
                }

                // 4. Straight back to meter entry for the next household — but
                //    only once the receipt is actually out. Leaving this screen
                //    clears the ViewModel, which would cancel a print mid-job,
                //    and if printing failed the reader needs to stay to retry.
                if (printed) _readingComplete.send(accountNo)

            } catch (e: Exception) {
                _saveError.value = "Failed to save reading: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun retryPrint() {
        val state = uiState.value
        val consumer = state.consumer ?: return
        val billing  = state.billing  ?: return
        val current  = state.currentReadingInput.toDoubleOrNull() ?: return

        viewModelScope.launch {
            val printed = printReceipt(consumer, billing, current, resolveReaderName())
            // A retry that works after a save finishes the household too.
            if (printed && uiState.value.savedReadingId != null) _readingComplete.send(accountNo)
        }
    }

    /**
     * The signed-in reader's name for the receipt.
     *
     * Read fresh when the network allows, so an admin renaming a reader
     * mid-session shows on the very next bill — but capped, and with a fallback,
     * so it can never block the save. On a weak signal an unbounded lookup could
     * stall saving for a full network timeout.
     */
    private suspend fun resolveReaderName(): String {
        val profile = withTimeoutOrNull(READER_NAME_TIMEOUT_MS) {
            authRepository.getCurrentReaderProfile()
        } ?: authRepository.lastKnownProfile
        return profile?.fullName?.ifBlank { null } ?: "Field Reader"
    }

    /** @return true if the receipt printed. */
    private suspend fun printReceipt(
        consumer: ConsumerEntity,
        billing: BillingResult,
        currentReading: Double,
        readByName: String
    ): Boolean {
        return printerManager.print {
            WaterBillReceiptBuilder.build(
                consumer       = consumer,
                billing        = billing,
                currentReading = currentReading,
                readByName     = readByName,
            ).let { bytes -> raw(bytes) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Billing derivation (pure, no side effects)
    // ─────────────────────────────────────────────────────────────────────────

    private data class ComputedBilling(
        val billing: BillingResult? = null,
        val error: String? = null,
        val warning: String? = null
    )

    /**
     * Derives the bill from the entered reading.
     *
     * A reading below the previous one is no longer rejected outright. A
     * 5-digit mechanical register that passes 99,999 wraps back to zero, and a
     * meter replaced mid-cycle also starts low — both used to be unbillable in
     * the field with no override, so the account was simply skipped for the
     * cycle and the reader had to call the office. Now the calculator handles
     * the wrap and the screen says so; only an implausible result is blocked.
     */
    private fun computeBilling(
        consumer: ConsumerEntity?,
        input: String
    ): ComputedBilling {
        if (consumer == null || input.isBlank()) return ComputedBilling()

        val currentReading = input.toDoubleOrNull()
            ?: return ComputedBilling(error = "Please enter a valid number.")
        if (currentReading < 0) {
            return ComputedBilling(error = "A meter reading can't be negative.")
        }

        when (val problem = WaterBillingCalculator.validate(consumer.prevReading, currentReading)) {
            is ReadingProblem.BelowPrevious -> return ComputedBilling(
                error = "That's below the previous reading of ${problem.previousReading} m³. " +
                    "Check the digits — if the meter was replaced, tell the office before billing."
            )
            is ReadingProblem.ImplausiblyHigh -> return ComputedBilling(
                error = "That would be ${problem.consumption.toInt()} m³ this month, which looks " +
                    "like a typo. Check the reading."
            )
            is ReadingProblem.NotANumber -> return ComputedBilling(error = "Please enter a valid number.")
            null -> Unit
        }

        return try {
            val result = WaterBillingCalculator.calculate(
                previousReading = consumer.prevReading,
                currentReading  = currentReading,
                classification  = consumer.classification,
                overdueBalance  = consumer.overdueBalance,
                delinquentSinceMillis = consumer.delinquentSinceMillis,
                creditBalance   = consumer.creditBalance,
                extensionFeeAlreadyCharged = consumer.extensionFeeAlreadyCharged
            )
            ComputedBilling(
                billing = result,
                warning = if (result.meterRolledOver) {
                    "Meter appears to have rolled over past 99,999 — billing " +
                        "${result.consumption.toInt()} m³ for this cycle."
                } else {
                    null
                }
            )
        } catch (e: Exception) {
            ComputedBilling(error = e.message)
        }
    }

    // The printer is deliberately NOT disconnected when this screen closes.
    // Readers now return to meter entry after every household, so dropping the
    // Bluetooth link here would mean reconnecting to the printer at every
    // single house. The printer manager is an app-wide singleton; the reader
    // can still disconnect explicitly from the printer card.

    private companion object {
        const val READER_NAME_TIMEOUT_MS = 2_000L
    }
}
