package com.example.domain

/**
 * Pure Kotlin monotonic state machine governing Locked Mode Settings protection:
 *  - Only Android Settings can enter the Emergency Waiting Room during Locked Mode.
 *  - All other non-essential system apps (package installers, OEM security tools, stores)
 *    are hard-blocked with zero emergency bypass.
 *  - User must stay on the Waiting Room screen continuously for exactly 5 minutes (300_000ms)
 *    measured on monotonic elapsedRealtime() so changing the system clock cannot shorten it.
 *  - Leaving the screen resets the countdown immediately.
 *  - Completing the 5-minute wait grants exactly 3 minutes (180_000ms) of temporary
 *    Settings access before auto-relocking and ejecting from Settings.
 */
object SettingsWaitStateMachine {

    /** Confirmed mandatory emergency waiting period for Android Settings: 5 minutes. */
    const val EMERGENCY_WAIT_MINUTES = 5
    const val EMERGENCY_WAIT_DURATION_MS = EMERGENCY_WAIT_MINUTES * 60_000L

    /** Confirmed temporary unlock window after completing the 5-minute wait: 3 minutes. */
    const val TEMPORARY_UNLOCK_MINUTES = 3
    const val TEMPORARY_UNLOCK_DURATION_MS = TEMPORARY_UNLOCK_MINUTES * 60_000L

    fun isTemporarilyUnlocked(
        nowElapsedRealtimeMs: Long,
        unlockedUntilElapsedRealtimeMs: Long
    ): Boolean {
        return unlockedUntilElapsedRealtimeMs > 0L &&
            nowElapsedRealtimeMs < unlockedUntilElapsedRealtimeMs
    }

    fun remainingUnlockMs(
        nowElapsedRealtimeMs: Long,
        unlockedUntilElapsedRealtimeMs: Long
    ): Long {
        if (unlockedUntilElapsedRealtimeMs <= 0L) return 0L
        return (unlockedUntilElapsedRealtimeMs - nowElapsedRealtimeMs).coerceAtLeast(0L)
    }

    fun computeNewUnlockExpiry(nowElapsedRealtimeMs: Long): Long {
        return nowElapsedRealtimeMs + TEMPORARY_UNLOCK_DURATION_MS
    }

    fun remainingWaitMs(
        nowElapsedRealtimeMs: Long,
        waitTargetElapsedRealtimeMs: Long
    ): Long {
        return (waitTargetElapsedRealtimeMs - nowElapsedRealtimeMs).coerceAtLeast(0L)
    }

    fun hasCompletedWait(
        nowElapsedRealtimeMs: Long,
        waitTargetElapsedRealtimeMs: Long
    ): Boolean {
        return waitTargetElapsedRealtimeMs > 0L &&
            nowElapsedRealtimeMs >= waitTargetElapsedRealtimeMs
    }
}
