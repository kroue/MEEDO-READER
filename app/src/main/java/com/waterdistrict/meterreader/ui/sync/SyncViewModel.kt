package com.waterdistrict.meterreader.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.waterdistrict.meterreader.data.local.MeterReaderDatabase
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.data.local.entity.ReadingEntity
import com.waterdistrict.meterreader.data.local.entity.SyncStatus
import com.waterdistrict.meterreader.data.remote.AuthRepository
import com.waterdistrict.meterreader.data.remote.DownloadedConsumer
import com.waterdistrict.meterreader.data.remote.FirebaseRepository
import com.waterdistrict.meterreader.domain.billing.BillingMonth
import com.waterdistrict.meterreader.worker.SyncReadingsWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    // Every Barangay the admin has currently handed to this reader.
    val assignedBarangays: List<String> = emptyList(),
    val assignedMonthStr: String? = null,
    // The one Barangay the reader has chosen to work on right now — null
    // means the picker (or the waiting state) should be shown instead.
    val selectedBarangay: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
    // Readings captured on this device that haven't reached Firestore yet —
    // lets the reader see at a glance whether local and server are in sync.
    val pendingUploadCount: Int = 0,
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val firebaseRepository: FirebaseRepository,
    private val authRepository: AuthRepository,
    private val consumerDao: ConsumerDao,
    private val readingDao: ReadingDao,
    private val workManager: WorkManager,
    private val database: MeterReaderDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    // Tracks the last "barangay|month" already downloaded so the same
    // assignment isn't re-fetched on every Firestore snapshot event.
    private var lastSyncedKey: String? = null

    init {
        val uid = authRepository.currentUserId
        if (uid != null) {
            viewModelScope.launch {
                firebaseRepository.listenForUserAssignment(uid).collect { assignment ->
                    val barangays = assignment.barangays

                    // If the admin recalled the Barangay the reader was actively
                    // working on, drop back to the picker/waiting state.
                    val stillSelected = _uiState.value.selectedBarangay
                        ?.takeIf { it in barangays }

                    _uiState.value = _uiState.value.copy(
                        assignedBarangays = barangays,
                        assignedMonthStr = assignment.monthStr,
                        selectedBarangay = stillSelected
                    )

                    if (barangays.isEmpty()) {
                        lastSyncedKey = null
                    }
                }
            }
        }

        viewModelScope.launch {
            readingDao.getPendingCount().collect { count ->
                _uiState.value = _uiState.value.copy(pendingUploadCount = count)
            }
        }
    }

    /** The reader chooses which of their assigned Barangays to work on first. */
    fun selectBarangay(barangay: String) {
        val monthStr = _uiState.value.assignedMonthStr ?: getCurrentMonthStr()
        _uiState.value = _uiState.value.copy(selectedBarangay = barangay, message = null, success = false)

        val key = "$barangay|$monthStr"
        if (key != lastSyncedKey) {
            lastSyncedKey = key
            downloadAssigned(barangay, monthStr)
        }
    }

    /** Returns to the Barangay picker without losing anything already downloaded. */
    fun switchBarangay() {
        _uiState.value = _uiState.value.copy(selectedBarangay = null, message = null, success = false)
    }

    /**
     * Manual two-way reconciliation: re-pull the currently selected Barangay's
     * concessionaires (in case the office changed something) and force an
     * immediate push of any readings still sitting locally, rather than
     * waiting for the next connectivity-triggered background attempt.
     */
    fun syncNow() {
        val barangay = _uiState.value.selectedBarangay
        val monthStr = _uiState.value.assignedMonthStr ?: getCurrentMonthStr()

        if (barangay != null) {
            downloadAssigned(barangay, monthStr)
        } else {
            _uiState.value = _uiState.value.copy(message = null, success = false)
        }

        // Push regardless of whether there's an active selection — readings
        // from a prior cycle may still be waiting to upload.
        SyncReadingsWorker.forceEnqueue(workManager)
    }

    private fun downloadAssigned(barangay: String, monthStr: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null, success = false)

            val result = firebaseRepository.fetchAssignedConcessionaires(barangay, monthStr)

            result.onSuccess { downloaded ->
                try {
                    // One transaction for the whole route. Outside one, every
                    // household's upsert, and every lookup and insert while
                    // hydrating readings, was its own commit — its own flush to
                    // storage — so saving a few hundred accounts meant a few
                    // hundred disk syncs, seconds of spinner on a cheap phone.
                    // It also makes the save all-or-nothing: an app killed
                    // halfway no longer leaves half a route downloaded.
                    database.withTransaction {
                        // Upsert, never REPLACE — see ConsumerDao.upsertAll for
                        // the unsynced readings REPLACE used to cascade-delete.
                        consumerDao.upsertAll(downloaded.map { it.consumer })
                        hydrateReconciledReadings(downloaded, monthStr)
                        // Drop consumers left over from earlier cycles so the
                        // route reflects this month's assignment rather than
                        // accumulating every account the device has ever seen.
                        // Accounts with unsynced readings are kept regardless.
                        consumerDao.deleteStaleCycles(monthStr)
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        success = true,
                        message = "Synced ${downloaded.size} concessionaires for $barangay."
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        success = false,
                        message = "Error saving to local database: ${e.message}"
                    )
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    success = false,
                    message = "Failed to fetch from server: ${error.message}"
                )
            }
        }
    }

    /**
     * For any consumer already billed this cycle on Firestore but with no
     * local reading (e.g. captured by a device whose local data was lost),
     * insert a real local [ReadingEntity] reconstructed from the full
     * breakdown Firestore stored. This is what lets "View Bill" / "Print"
     * work the same regardless of which device originally took the reading.
     * Only fills the gap — never overwrites an existing local row.
     */
    private suspend fun hydrateReconciledReadings(
        downloaded: List<DownloadedConsumer>,
        monthStr: String
    ) {
        downloaded.forEach { d ->
            val r = d.currentMonthReading ?: return@forEach
            // Scoped to this cycle. Checking for "any prior local reading"
            // meant a device holding last month's row never hydrated this
            // month's bill, so a reading captured on another phone stayed
            // invisible here.
            val existingLocal =
                readingDao.getReadingForAccountInMonthOnce(d.consumer.accountNo, monthStr)
            if (existingLocal != null) return@forEach

            readingDao.insert(
                ReadingEntity(
                    accountNo = d.consumer.accountNo,
                    prevReading = r.previousReading,
                    currentReading = r.currentReading,
                    consumption = r.currentReading - r.previousReading,
                    minimumCharge = r.minimumCharge,
                    commodityCharge = r.commodityCharge,
                    overdueBalance = r.overdueBalance,
                    overdueSurcharge = r.overdueSurcharge,
                    extensionFee = r.extensionFee,
                    creditApplied = r.creditApplied,
                    totalAmountDue = r.totalAmountDue,
                    dueDateMillis = r.dueDateMillis,
                    projectedOverdueTotal = r.projectedOverdueTotal,
                    readingDate = r.billingDateMillis,
                    billingMonth = monthStr,
                    syncStatus = SyncStatus.SYNCED,
                    orNumber = r.orNumber,
                    readByUserId = "synced_from_server",
                    remarks = ""
                )
            )
        }
    }

    // Shared with the reading/billing code and matched exactly by the admin
    // console's own fixed month table — see BillingMonth.
    private fun getCurrentMonthStr(): String = BillingMonth.current()
}
