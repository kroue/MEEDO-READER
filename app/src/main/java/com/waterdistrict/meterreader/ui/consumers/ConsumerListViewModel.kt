package com.waterdistrict.meterreader.ui.consumers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.data.local.entity.ConsumerEntity
import com.waterdistrict.meterreader.domain.billing.BillingMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ConsumerListItem(
    val consumer: ConsumerEntity,
    val isRead: Boolean,
    // Non-null only when a local copy of the bill exists to view/print
    // directly — a consumer read on another device (Firestore-only) has none.
    val localReadingId: Long?
)

data class ConsumerListUiState(
    val barangay: String = "",
    val billingMonth: String = "",
    val items: List<ConsumerListItem> = emptyList()
) {
    val readCount: Int get() = items.count { it.isRead }
}

@HiltViewModel
class ConsumerListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    consumerDao: ConsumerDao,
    readingDao: ReadingDao
) : ViewModel() {

    // Scoped to whichever Barangay the reader picked on the Sync screen, so
    // a reader who has worked multiple Barangays doesn't see a mixed list.
    private val barangay: String = savedStateHandle.get<String>("barangay").orEmpty()

    // ...and to the billing cycle. Both halves of "already read" have to be
    // month-scoped or the second cycle opens with every consumer already
    // ticked off: `alreadyBilledThisMonth` comes from Firestore and resets on
    // download, but the local read-set used to span all time, so from month
    // two onward the route list was permanently, uselessly complete.
    private val billingMonth: String =
        savedStateHandle.get<String>("billingMonth")?.takeIf { it.isNotBlank() }
            ?: BillingMonth.current()

    val uiState: StateFlow<ConsumerListUiState> = combine(
        if (barangay.isNotBlank()) {
            consumerDao.getConsumersByRoute(barangay, billingMonth)
        } else {
            consumerDao.getAllConsumers()
        },
        readingDao.getReadAccountNumbers(billingMonth),
        readingDao.getReadingIdsByAccount(billingMonth)
    ) { consumers, readAccountNumbers, readingIds ->
        val readSet = readAccountNumbers.toSet()
        val readingIdByAccount = readingIds.associate { it.accountNo to it.id }
        ConsumerListUiState(
            barangay = barangay,
            billingMonth = billingMonth,
            items = consumers.map {
                // A consumer counts as read if either this device captured a
                // reading for THIS cycle, or Firestore already has one (e.g.
                // captured by a device whose local data was lost).
                ConsumerListItem(
                    consumer = it,
                    isRead = it.alreadyBilledThisMonth || it.accountNo in readSet,
                    localReadingId = readingIdByAccount[it.accountNo]
                )
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConsumerListUiState()
    )
}
