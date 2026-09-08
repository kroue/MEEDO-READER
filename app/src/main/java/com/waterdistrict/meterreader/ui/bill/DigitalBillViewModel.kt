package com.waterdistrict.meterreader.ui.bill

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.domain.billing.BillingResult
import com.waterdistrict.meterreader.hardware.bluetooth.BluetoothPrinterManager
import com.waterdistrict.meterreader.hardware.bluetooth.PrinterState
import com.waterdistrict.meterreader.hardware.bluetooth.WaterBillReceiptBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DigitalBillUiState(
    val consumer: ConsumerEntity? = null,
    val reading: ReadingEntity? = null,
    val isLoading: Boolean = true,
    val printerState: PrinterState = PrinterState.Idle
)

/**
 * Loads the saved [ReadingEntity] and its [ConsumerEntity] so the on-screen
 * digital bill can be rendered without needing a physical printer — and lets
 * that same saved bill be reprinted on demand, without re-entering anything.
 *
 * ### Navigation argument
 * The local reading ID is passed via [SavedStateHandle] key `"readingId"`.
 */
@HiltViewModel
class DigitalBillViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val consumerDao: ConsumerDao,
    private val readingDao: ReadingDao,
    private val printerManager: BluetoothPrinterManager
) : ViewModel() {

    private val readingId: Long = savedStateHandle.get<Long>("readingId") ?: 0L

    private val _uiState = MutableStateFlow(DigitalBillUiState())
    val uiState: StateFlow<DigitalBillUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val reading = readingDao.getReadingById(readingId)
            val consumer = reading?.let { consumerDao.getConsumerByAccountNoOnce(it.accountNo) }
            _uiState.value = _uiState.value.copy(
                consumer = consumer,
                reading = reading,
                isLoading = false
            )
        }
        viewModelScope.launch {
            printerManager.printerState.collect { state ->
                _uiState.value = _uiState.value.copy(printerState = state)
            }
        }
    }

    fun print() {
        val consumer = _uiState.value.consumer ?: return
        val reading = _uiState.value.reading ?: return

        viewModelScope.launch {
            printerManager.print {
                WaterBillReceiptBuilder.build(
                    consumer = consumer,
                    billing = reading.toBillingResult(),
                    currentReading = reading.currentReading,
                    readingDate = reading.readingDate,
                    readByName = reading.readByUserId,
                    orNumber = reading.orNumber,
                ).let { bytes -> raw(bytes) }
            }
        }
    }

    /** Reconstructs the billing breakdown straight from what was actually saved/uploaded. */
    private fun ReadingEntity.toBillingResult(): BillingResult = BillingResult(
        consumption = consumption,
        minimumCharge = minimumCharge,
        commodityCharge = commodityCharge,
        totalWaterCharge = minimumCharge + commodityCharge,
        overdueBalance = overdueBalance,
        daysOverdue = null,
        pastGracePeriod = overdueSurcharge > 0 || extensionFee > 0,
        overdueSurcharge = overdueSurcharge,
        extensionFee = extensionFee,
        creditApplied = creditApplied,
        // Not stored per reading — a reprint shows what this bill consumed,
        // not what happens to be left on the account today.
        creditRemaining = 0.0,
        totalAmountDue = totalAmountDue,
        dueDateMillis = dueDateMillis,
        projectedOverdueTotal = projectedOverdueTotal,
        meterRolledOver = currentReading < prevReading,
    )

    override fun onCleared() {
        super.onCleared()
        printerManager.disconnect()
    }
}
