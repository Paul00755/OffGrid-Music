package com.example.update

import java.util.Locale

/**
 * Represents a semantic application version with optional build number.
 * Guarantees reliable non-string-based version comparisons (e.g. 1.0.10 > 1.0.9).
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val buildNumber: Long = 0L,
    val raw: String
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return buildNumber.compareTo(other.buildNumber)
    }

    fun isNewerThan(other: AppVersion): Boolean = this > other

    val displayString: String get() = toDisplayString()

    fun toDisplayString(): String {
        return if (buildNumber > 0L) {
            "v$major.$minor.$patch (Build $buildNumber)"
        } else {
            "v$major.$minor.$patch"
        }
    }

    override fun toString(): String = toDisplayString()

    companion object {
        private val BUILD_REGEX = Regex("(?i)build[.\\s_-]*(\\d+)")
        private val DIGITS_ONLY_REGEX = Regex("\\d+")

        fun parse(versionString: String, fallbackBuildNumber: Long = 0L): AppVersion {
            val clean = versionString.trim()
            if (clean.isBlank()) {
                return AppVersion(0, 0, 0, fallbackBuildNumber, versionString)
            }

            // Extract build number if present (e.g. "v1.0.4-build.15", "1.0.4 (Build 5)")
            var detectedBuild: Long = fallbackBuildNumber
            val buildMatch = BUILD_REGEX.find(clean)
            if (buildMatch != null) {
                detectedBuild = buildMatch.groupValues[1].toLongOrNull() ?: fallbackBuildNumber
            }

            // Strip prefix ('v', 'V', 'release-', 'version-')
            var normalized = clean
                .replace(Regex("(?i)^v(ersion)?[-_\\s]*"), "")
                .replace(Regex("(?i)^release[-_\\s]*"), "")

            // Separate version core from pre-release / build metadata (e.g., "-build.15")
            val corePart = normalized.split('-', '+', ' ', '_').firstOrNull().orEmpty()

            // Extract numeric tokens from core part
            val parts = corePart.split('.').mapNotNull { part ->
                DIGITS_ONLY_REGEX.find(part)?.value?.toIntOrNull()
            }

            val major = parts.getOrNull(0) ?: 0
            val minor = parts.getOrNull(1) ?: 0
            val patch = parts.getOrNull(2) ?: 0

            // If a 4th numeric segment exists and no build was detected, treat it as build number
            if (detectedBuild == 0L && parts.size >= 4) {
                detectedBuild = parts[3].toLong()
            }

            return AppVersion(
                major = major,
                minor = minor,
                patch = patch,
                buildNumber = detectedBuild,
                raw = clean
            )
        }
    }
}
