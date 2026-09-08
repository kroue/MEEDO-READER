package com.waterdistrict.meterreader.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuthException
import com.waterdistrict.meterreader.data.remote.AuthRepository
import com.waterdistrict.meterreader.data.remote.NotAuthorizedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onUsernameChanged(value: String) {
        _uiState.value = _uiState.value.copy(username = value, error = null)
    }

    fun onPasswordChanged(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun login() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = "Enter your username and password.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                authRepository.login(state.username.trim(), state.password)
                // Success: AuthViewModel's listener picks up the new session and
                // the app root swaps away from this screen — nothing else to do.
            } catch (e: NotAuthorizedException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (e: FirebaseAuthException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = friendlyError(e.errorCode))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Sign-in failed. Check your connection and try again."
                )
            }
        }
    }

    private fun friendlyError(code: String): String = when (code) {
        "ERROR_INVALID_EMAIL" -> "That username isn't valid."
        "ERROR_USER_DISABLED" -> "This account has been disabled. Contact your admin."
        "ERROR_USER_NOT_FOUND", "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" ->
            "Incorrect username or password."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a moment and try again."
        else -> "Sign-in failed. Please try again."
    }
}
