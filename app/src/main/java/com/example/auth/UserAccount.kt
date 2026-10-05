package com.example.auth

data class UserAccount(
    val uid: String = "",
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val isAnonymous: Boolean = false,
    val providerId: String = "password",
    val isEmailVerified: Boolean = false
) {
    val displayInitial: String
        get() = (displayName?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: "U").first().uppercase()

    val friendlyProviderName: String
        get() = when {
            providerId.contains("google", ignoreCase = true) -> "Google Account"
            providerId.contains("password", ignoreCase = true) -> "Email & Password"
            else -> "Firebase Auth"
        }
}

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val message: String? = null) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

sealed class PasswordResetUiState {
    object Idle : PasswordResetUiState()
    object Sending : PasswordResetUiState()
    data class SentCooldown(
        val secondsRemaining: Int,
        val message: String = "Password reset email sent.\nCheck your inbox and spam folder.\nYou can request another email in 60 seconds."
    ) : PasswordResetUiState()
    data class ReadyToResend(
        val message: String = "Password reset email sent.\nDidn't receive it? You can now request another email."
    ) : PasswordResetUiState()
    data class Error(val message: String) : PasswordResetUiState()
}

class GoogleSignInCancelledException(message: String = "Sign-in cancelled") : Exception(message)
