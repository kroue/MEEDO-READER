package com.waterdistrict.meterreader.ui.consumers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.domain.HouseholdSearch
import com.waterdistrict.meterreader.domain.billing.BillingMonth
import com.waterdistrict.meterreader.data.local.entity.displayAccountNo
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

/** One household in the search results. */
data class HouseholdMatch(
    /** The key the reading screen opens it by. */
    val accountNo: String,
    val name: String,
    val meterNo: String,
    /** The office's account number, or the meter number where it has none. */
    val displayAccountNo: String,
    val address: String,
    val isRead: Boolean,
)

data class MeterEntryUiState(
    val barangay: String = "",
    val billingMonth: String = "",
    /** What the reader has typed: a name, an account number or a meter number. */
    val meterInput: String = "",
    /** Households matching it — an exact number first, then A–Z by name. */
    val matches: List<HouseholdMatch> = emptyList(),
    /** How many more matched than are listed. */
    val moreMatches: Int = 0,
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
 * Readers find a household by searching its name, account number or meter
 * number (see HouseholdSearch). Nothing is listed until they type — the route's
 * names stay off the screen of a phone that goes house to house until the
 * reader is looking for someone — and typing a full meter or account number
 * still opens that household directly.
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
        savedStateHandle.getStateFlow<String?>(KEY_LAST_SAVED, null),
        consumerDao.householdsOnRoute(barangay, billingMonth),
    ) { (total, read, pending), f, lastSaved, households ->
        val found = HouseholdSearch.search(
            f.meterInput,
            households.map { h ->
                HouseholdSearch.Candidate(
                    item = h,
                    name = h.consumer.name,
                    meterNo = h.consumer.meterNo,
                    accountNo = h.consumer.accountNo,
                    officeAccountNo = h.consumer.officeAccountNo,
                )
            }
        )
        MeterEntryUiState(
            barangay = barangay,
            billingMonth = billingMonth,
            meterInput = f.meterInput,
            matches = found.take(MAX_LISTED).map { h ->
                HouseholdMatch(
                    accountNo = h.consumer.accountNo,
                    name = h.consumer.name,
                    meterNo = h.consumer.meterNo,
                    displayAccountNo = h.consumer.displayAccountNo,
                    address = h.consumer.address,
                    isRead = h.isRead,
                )
            },
            moreMatches = (found.size - MAX_LISTED).coerceAtLeast(0),
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
        // Kept as typed: it may be a name. Matching ignores case anyway.
        form.update { it.copy(meterInput = value, error = null) }
        // Starting on the next meter means the "saved" confirmation has done its job.
        if (savedStateHandle.get<String?>(KEY_LAST_SAVED) != null) {
            savedStateHandle.set<String?>(KEY_LAST_SAVED, null)
        }
    }

    /**
     * "Open household": a full meter or account number opens that household;
     * otherwise a search with exactly one match opens it, and more than one
     * asks the reader to pick from the list.
     */
    fun open() {
        val typed = form.value.meterInput.trim()
        if (typed.isEmpty()) {
            form.update { it.copy(error = "Type a name, account number or meter number.") }
            return
        }
        if (form.value.isLookingUp) return

        viewModelScope.launch {
            form.update { it.copy(isLookingUp = true, error = null) }
            val exact = consumerDao.findOnRouteByMeter(barangay, billingMonth, typed)
            val state = uiState.value
            val only = state.matches.singleOrNull()?.takeIf { state.moreMatches == 0 }
            when {
                exact != null -> openHousehold(exact.accountNo)
                only != null -> openHousehold(only.accountNo)
                state.matches.isEmpty() -> form.update {
                    it.copy(
                        isLookingUp = false,
                        error = "Nothing on your ${barangay} route for $billingMonth matches \"$typed\". " +
                            "Check the spelling, or the number on the meter."
                    )
                }
                else -> form.update {
                    it.copy(isLookingUp = false, error = "More than one household matches — tap yours in the list.")
                }
            }
        }
    }

    /** Opens a household picked from the search results. */
    fun openHousehold(accountNo: String) {
        // Cleared now, so coming back from the reading screen starts empty.
        form.update { FormState() }
        viewModelScope.launch { _openConsumer.send(accountNo) }
    }

    companion object {
        /**
         * Set by the reading screen on its way back here, so this screen can
         * confirm the household was saved. Written to this destination's own
         * SavedStateHandle, which is the one this ViewModel receives.
         */
        const val KEY_LAST_SAVED = "lastSavedAccount"

        /** Enough to find anyone on a route; more means keep typing. */
        const val MAX_LISTED = 25
    }
}
