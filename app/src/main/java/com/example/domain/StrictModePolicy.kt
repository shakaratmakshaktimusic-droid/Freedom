package com.example.domain

/**
 * Enforces in-app Locked Mode (Strict Mode) rules so users cannot weaken
 * active protection until the session finishes.
 */
object StrictModePolicy {

    sealed class GuardResult {
        data object Permitted : GuardResult()
        data class Denied(val reason: String) : GuardResult()
    }

    fun canStopSession(isStrictModeActive: Boolean): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. Sessions cannot be ended early.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canAddAllowedApp(isStrictModeActive: Boolean): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. You cannot allow additional apps until the session ends.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canRemoveAllowedApp(): GuardResult = GuardResult.Permitted

    fun canAddAllowedWebsite(isStrictModeActive: Boolean): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. You cannot add new website exceptions until the session ends.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canWeakenBlocklist(isStrictModeActive: Boolean): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. You cannot weaken active categories or browsing blocks until the session ends.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canDisableAutoDetect(isStrictModeActive: Boolean): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. Smart unknown-site detection cannot be disabled mid-session.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canDisableLockedModeSetting(isStrictModeActive: Boolean): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode cannot be turned off while a locked session is running.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canModifyOrDisableSchedule(
        scheduleId: Long,
        activeStrictScheduleId: Long?,
        isStrictModeActive: Boolean
    ): GuardResult {
        return if (isStrictModeActive && activeStrictScheduleId != null && activeStrictScheduleId == scheduleId) {
            GuardResult.Denied("This schedule is currently enforcing a Locked Mode session and cannot be modified or disabled.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canDisableContentProtection(
        featureName: String,
        isStrictModeActive: Boolean
    ): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. $featureName cannot be turned off until the session ends.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canModifyApprovedYouTubeChannels(isStrictModeActive: Boolean): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. Approved YouTube channels cannot be modified until the session ends.")
        } else {
            GuardResult.Permitted
        }
    }

    fun canWeakenContentProtectionRule(
        ruleDescription: String,
        isStrictModeActive: Boolean
    ): GuardResult {
        return if (isStrictModeActive) {
            GuardResult.Denied("Locked Mode is active. $ruleDescription cannot be weakened until the session ends.")
        } else {
            GuardResult.Permitted
        }
    }
}
