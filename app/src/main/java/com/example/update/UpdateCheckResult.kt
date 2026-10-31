package com.example.update

/**
 * State and result of an update check operation.
 */
sealed interface UpdateCheckResult {
    object Idle : UpdateCheckResult
    object Checking : UpdateCheckResult
    data class UpToDate(val currentVersion: String = "") : UpdateCheckResult
    data class UpdateAvailable(val release: ReleaseInfo, val isDismissed: Boolean = false) : UpdateCheckResult {
        val releaseInfo: ReleaseInfo get() = release
    }
    data class RateLimited(val message: String = "Rate limited by GitHub API") : UpdateCheckResult
    data class Offline(val message: String = "Network connection is offline") : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}
