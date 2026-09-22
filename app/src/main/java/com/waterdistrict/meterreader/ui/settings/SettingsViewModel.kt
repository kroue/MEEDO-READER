package com.waterdistrict.meterreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.data.prefs.AppPreferences
import com.waterdistrict.meterreader.data.prefs.ReaderPreferences
import com.waterdistrict.meterreader.data.prefs.ThemeChoice
import com.waterdistrict.meterreader.data.remote.AccountChangeException
import com.waterdistrict.meterreader.data.remote.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** A line of feedback under a form: what happened, and whether it went wrong. */
data class FormMessage(val text: String, val isError: Boolean)

data class ProfileForm(
    val firstName: String = "",
    val lastName: String = "",
    val phoneNumber: String = "",
)

data class PasswordForm(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
) {
    val mismatch: Boolean get() = confirm.isNotEmpty() && new != confirm
}

data class SettingsUiState(
    val username: String = "",
    val profile: ProfileForm = ProfileForm(),
    val profileLoaded: Boolean = false,
    /** Non-null while the reader is editing their details. */
    val draft: ProfileForm? = null,
    val savingProfile: Boolean = false,
    val profileMessage: FormMessage? = null,
    val password: PasswordForm = PasswordForm(),
    val changingPassword: Boolean = false,
    val passwordMessage: FormMessage? = null,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val pendingUploads: Int = 0,
) {
    val fullName: String get() = "${profile.firstName} ${profile.lastName}".trim()
}

private data class FormState(
    val profile: ProfileForm = ProfileForm(),
    val profileLoaded: Boolean = false,
    val draft: ProfileForm? = null,
    val savingProfile: Boolean = false,
    val profileMessage: FormMessage? = null,
    val password: PasswordForm = PasswordForm(),
    val changingPassword: Boolean = false,
    val passwordMessage: FormMessage? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPreferences: AppPreferences,
    readingDao: ReadingDao,
) : ViewModel() {

    private val username: String =
        authRepository.currentUser?.email?.substringBefore("@").orEmpty()

    private val form = MutableStateFlow(FormState())

    val uiState: StateFlow<SettingsUiState> = combine(
        form,
        appPreferences.state,
        readingDao.getPendingCount(),
    ) { f, prefs, pending ->
        SettingsUiState(
            username = username,
            profile = f.profile,
            profileLoaded = f.profileLoaded,
            draft = f.draft,
            savingProfile = f.savingProfile,
            profileMessage = f.profileMessage,
            password = f.password,
            changingPassword = f.changingPassword,
            passwordMessage = f.passwordMessage,
            preferences = prefs,
            pendingUploads = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(username = username))

    init {
        viewModelScope.launch {
            val profile = authRepository.getCurrentReaderProfile()
            form.update {
                it.copy(
                    profile = ProfileForm(
                        firstName = profile?.firstName.orEmpty(),
                        lastName = profile?.lastName.orEmpty(),
                        phoneNumber = profile?.phoneNumber.orEmpty(),
                    ),
                    profileLoaded = true,
                )
            }
        }
    }

    // ── Details ──────────────────────────────────────────────────────────────

    fun startEditing() = form.update { it.copy(draft = it.profile, profileMessage = null) }

    fun cancelEditing() = form.update { it.copy(draft = null, profileMessage = null) }

    fun onDraftChanged(draft: ProfileForm) = form.update { it.copy(draft = draft) }

    fun saveProfile() {
        val draft = form.value.draft ?: return
        viewModelScope.launch {
            form.update { it.copy(savingProfile = true, profileMessage = null) }
            val outcome = try {
                // A write made offline is kept and sent when a signal returns,
                // but its confirmation never arrives while offline. Wait a
                // while for it, then say what is actually true.
                withTimeout(SAVE_TIMEOUT_MS) {
                    authRepository.updateOwnProfile(draft.firstName, draft.lastName, draft.phoneNumber)
                }
                FormMessage("Your details have been updated.", isError = false)
            } catch (e: TimeoutCancellationException) {
                FormMessage("Saved on this phone. It reaches the office when you're back online.", isError = false)
            } catch (e: AccountChangeException) {
                FormMessage(e.message.orEmpty(), isError = true)
            }
            form.update {
                if (outcome.isError) {
                    it.copy(savingProfile = false, profileMessage = outcome)
                } else {
                    it.copy(
                        savingProfile = false,
                        profile = ProfileForm(
                            draft.firstName.trim(),
                            draft.lastName.trim(),
                            draft.phoneNumber.trim()
                        ),
                        draft = null,
                        profileMessage = outcome,
                    )
                }
            }
        }
    }

    // ── Password ─────────────────────────────────────────────────────────────

    fun onPasswordChanged(password: PasswordForm) =
        form.update { it.copy(password = password, passwordMessage = null) }

    fun changePassword() {
        val password = form.value.password
        if (password.mismatch) return
        viewModelScope.launch {
            form.update { it.copy(changingPassword = true, passwordMessage = null) }
            val message = try {
                authRepository.changePassword(password.current, password.new)
                FormMessage("Password changed. Use it the next time you sign in.", isError = false)
            } catch (e: AccountChangeException) {
                FormMessage(e.message.orEmpty(), isError = true)
            }
            form.update {
                it.copy(
                    changingPassword = false,
                    passwordMessage = message,
                    password = if (message.isError) it.password else PasswordForm(),
                )
            }
        }
    }

    // ── Display ──────────────────────────────────────────────────────────────

    fun setTheme(theme: ThemeChoice) = appPreferences.update { it.copy(theme = theme) }

    fun setLargeText(enabled: Boolean) = appPreferences.update { it.copy(largeText = enabled) }

    fun setKeepScreenOn(enabled: Boolean) = appPreferences.update { it.copy(keepScreenOn = enabled) }

    private companion object {
        const val SAVE_TIMEOUT_MS = 8_000L
    }
}
