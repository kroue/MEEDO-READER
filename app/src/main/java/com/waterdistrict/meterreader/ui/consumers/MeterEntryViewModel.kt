package com.waterdistrict.meterreader.ui.consumers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.domain.billing.BillingMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeterEntryUiState(
    val barangay: String = "",
    val billingMonth: String = "",
    val meterInput: String = "",
    val error: String? = null,
    val isLookingUp: Boolean = false,
    val totalOnRoute: Int = 0,
    val readOnRoute: Int = 0,
    val pendingUploads: Int = 0,
    /** Meter/account number of the household just saved, until the reader starts on the next one. */
    val lastSavedAccount: String? = null,
) {
    val progress: Float get() = if (totalOnRoute > 0) readOnRoute.toFloat() / totalOnRoute else 0f
}

private data class FormState(
    val meterInput: String = "",
    val error: String? = null,
    val isLookingUp: Boolean = false,
)

/**
 * The reading home for one Barangay and billing cycle.
 *
 * Readers open a household by entering its meter number; there is no list of
 * accounts to browse or search. That keeps a reader working from the meter in
 * front of them rather than picking households off a list of names, and it
 * keeps the route's names and addresses off the screen of a phone that goes
 * house to house.
 *
 * The reading screen comes back here after every household, so this is also
 * where the reader sees how much of the route is done.
 */
@HiltViewModel
class MeterEntryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val consumerDao: ConsumerDao,
    readingDao: ReadingDao
) : ViewModel() {

    private val barangay: String = savedStateHandle.get<String>("barangay").orEmpty()

    private val billingMonth: String =
        savedStateHandle.get<String>("billingMonth")?.takeIf { it.isNotBlank() }
            ?: BillingMonth.current()

    private val form = MutableStateFlow(FormState())

    private val _openConsumer = Channel<String>(Channel.BUFFERED)
    /** One-shot: open the reading screen for this account number. */
    val openConsumer: Flow<String> = _openConsumer.receiveAsFlow()

    private val progress = combine(
        consumerDao.countOnRoute(barangay, billingMonth),
        consumerDao.countReadOnRoute(barangay, billingMonth),
        readingDao.getPendingCount()
    ) { total, read, pending -> Triple(total, read, pending) }

    val uiState: StateFlow<MeterEntryUiState> = combine(
        progress,
        form,
        savedStateHandle.getStateFlow<String?>(KEY_LAST_SAVED, null)
    ) { (total, read, pending), f, lastSaved ->
        MeterEntryUiState(
            barangay = barangay,
            billingMonth = billingMonth,
            meterInput = f.meterInput,
            error = f.error,
            isLookingUp = f.isLookingUp,
            totalOnRoute = total,
            readOnRoute = read,
            pendingUploads = pending,
            lastSavedAccount = lastSaved,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeterEntryUiState(barangay = barangay, billingMonth = billingMonth)
    )

    fun onMeterInputChanged(value: String) {
        form.update { it.copy(meterInput = value.uppercase(), error = null) }
        // Starting on the next meter means the "saved" confirmation has done its job.
        if (savedStateHandle.get<String?>(KEY_LAST_SAVED) != null) {
            savedStateHandle.set<String?>(KEY_LAST_SAVED, null)
        }
    }

    fun open() {
        val meter = form.value.meterInput.trim()
        if (meter.isEmpty()) {
            form.update { it.copy(error = "Enter the number printed on the meter.") }
            return
        }
        if (form.value.isLookingUp) return

        viewModelScope.launch {
            form.update { it.copy(isLookingUp = true, error = null) }
            val consumer = consumerDao.findOnRouteByMeter(barangay, billingMonth, meter)
            if (consumer == null) {
                form.update {
                    it.copy(
                        isLookingUp = false,
                        error = "No account with meter number $meter on your $barangay route for " +
                            "$billingMonth. Check the number on the meter."
                    )
                }
            } else {
                // Cleared now, so coming back from the reading screen starts empty.
                form.update { FormState() }
                _openConsumer.send(consumer.accountNo)
            }
        }
    }

    companion object {
        /**
         * Set by the reading screen on its way back here, so this screen can
         * confirm the household was saved. Written to this destination's own
         * SavedStateHandle, which is the one this ViewModel receives.
         */
        const val KEY_LAST_SAVED = "lastSavedAccount"
    }
}
