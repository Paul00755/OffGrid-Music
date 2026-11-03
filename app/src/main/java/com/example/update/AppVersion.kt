package com.example.update

/**
 * Represents a semantic application version with optional build number.
 * Guarantees reliable non-string-based version comparisons (e.g. 1.0.10 > 1.0.9).
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val buildNumber: Long = 0L,
    val raw: String = "",
    val isPreRelease: Boolean = false
) : Comparable<AppVersion> {

    val isValid: Boolean
        get() = major > 0 || minor > 0 || patch > 0

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (isPreRelease != other.isPreRelease) {
            return if (isPreRelease) -1 else 1
        }
        return buildNumber.compareTo(other.buildNumber)
    }

    fun isNewerThan(other: AppVersion): Boolean = this.isValid && !this.isPreRelease && this > other

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
        private val BUILD_REGEX = Regex("(?i)(?:build|run)[.#\\s_-]*(\\d+)")
        private val SEMVER_REGEX = Regex("(?i)v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")
        private val PRERELEASE_REGEX = Regex("(?i)(?:^|[-._\\s])(alpha|beta|rc\\d*|dev|nightly|canary|prerelease)(?:$|[-._\\s\\d])")

        fun parse(versionString: String?, fallbackBuildNumber: Long = 0L): AppVersion {
            val clean = versionString?.trim().orEmpty()
            if (clean.isBlank()) {
                return AppVersion(0, 0, 0, fallbackBuildNumber, clean, isPreRelease = false)
            }

            val isPre = PRERELEASE_REGEX.containsMatchIn(clean)

            // Extract build number if present (e.g. "v1.0.4-build.15", "1.0.4 (Build #5)")
            var detectedBuild: Long = fallbackBuildNumber
            val buildMatch = BUILD_REGEX.find(clean)
            if (buildMatch != null) {
                detectedBuild = buildMatch.groupValues[1].toLongOrNull() ?: fallbackBuildNumber
            }

            val semverMatch = SEMVER_REGEX.find(clean)
            if (semverMatch == null) {
                return AppVersion(0, 0, 0, detectedBuild, clean, isPreRelease = isPre)
            }

            val major = semverMatch.groupValues[1].toIntOrNull() ?: 0
            val minor = semverMatch.groupValues[2].toIntOrNull() ?: 0
            val patch = semverMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val fourth = semverMatch.groupValues[4].takeIf { it.isNotEmpty() }?.toLongOrNull()

            if (detectedBuild == 0L && fourth != null) {
                detectedBuild = fourth
            }

            return AppVersion(
                major = major,
                minor = minor,
                patch = patch,
                buildNumber = detectedBuild,
                raw = clean,
                isPreRelease = isPre
            )
        }
    }
}

