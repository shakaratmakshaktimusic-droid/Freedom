package com.example.domain

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure Kotlin schedule evaluation engine supporting:
 *  - Recurring schedules (same-day & overnight windows via Monday..Sunday bitmask)
 *  - One-time "Later" scheduled sessions (explicit oneTimeStartMs..oneTimeEndMs)
 * Each schedule binds to a reusable Blocklist profile via [blocklistId].
 */
object ScheduleCalculator {

    const val DAY_MON = 1 shl 0
    const val DAY_TUE = 1 shl 1
    const val DAY_WED = 1 shl 2
    const val DAY_THU = 1 shl 3
    const val DAY_FRI = 1 shl 4
    const val DAY_SAT = 1 shl 5
    const val DAY_SUN = 1 shl 6

    const val WEEKDAYS_MASK = DAY_MON or DAY_TUE or DAY_WED or DAY_THU or DAY_FRI
    const val WEEKENDS_MASK = DAY_SAT or DAY_SUN
    const val EVERY_DAY_MASK = WEEKDAYS_MASK or WEEKENDS_MASK

    data class ScheduleSpec(
        val id: Long,
        val name: String,
        val blocklistId: Long,
        val isRecurring: Boolean = true,
        val daysMask: Int = WEEKDAYS_MASK,
        val startMinuteOfDay: Int = 9 * 60,
        val endMinuteOfDay: Int = 17 * 60,
        val oneTimeStartMs: Long = 0L,
        val oneTimeEndMs: Long = 0L,
        val strictMode: Boolean = true,
        val enabled: Boolean = true
    ) {
        val isOvernight: Boolean
            get() = isRecurring && endMinuteOfDay <= startMinuteOfDay
    }

    data class ActiveScheduleWindow(
        val schedule: ScheduleSpec,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val allActiveBlocklistIds: Set<Long> = setOf(schedule.blocklistId)
    )

    data class NextTransition(
        val scheduleId: Long,
        val triggerEpochMs: Long,
        val isStart: Boolean
    )

    fun dayIndexToBit(dayIndexMonZero: Int): Int = 1 shl ((dayIndexMonZero % 7 + 7) % 7)

    fun calendarDayOfWeekToMonZero(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        Calendar.SUNDAY -> 6
        else -> 0
    }

    fun getActiveWindow(
        schedule: ScheduleSpec,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): ActiveScheduleWindow? {
        if (!schedule.enabled) return null

        // One-time "Later" session
        if (!schedule.isRecurring) {
            if (schedule.oneTimeStartMs > 0L && schedule.oneTimeEndMs > schedule.oneTimeStartMs) {
                if (nowMs in schedule.oneTimeStartMs until schedule.oneTimeEndMs) {
                    return ActiveScheduleWindow(
                        schedule = schedule,
                        startEpochMs = schedule.oneTimeStartMs,
                        endEpochMs = schedule.oneTimeEndMs
                    )
                }
            }
            return null
        }

        // Recurring schedule
        if (schedule.daysMask == 0 || schedule.startMinuteOfDay == schedule.endMinuteOfDay) return null

        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val todayIndex = calendarDayOfWeekToMonZero(cal.get(Calendar.DAY_OF_WEEK))
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayMidnight = startOfDayMs(cal)

        if (!schedule.isOvernight) {
            val todayEnabled = (schedule.daysMask and dayIndexToBit(todayIndex)) != 0
            if (todayEnabled &&
                minuteOfDay >= schedule.startMinuteOfDay &&
                minuteOfDay < schedule.endMinuteOfDay
            ) {
                val startMs = todayMidnight + schedule.startMinuteOfDay * 60_000L
                val endMs = todayMidnight + schedule.endMinuteOfDay * 60_000L
                if (nowMs in startMs until endMs) {
                    return ActiveScheduleWindow(schedule, startMs, endMs)
                }
            }
        } else {
            val todayEnabled = (schedule.daysMask and dayIndexToBit(todayIndex)) != 0
            if (todayEnabled && minuteOfDay >= schedule.startMinuteOfDay) {
                val startMs = todayMidnight + schedule.startMinuteOfDay * 60_000L
                val tomorrowMidnight = addDaysStartOfDayMs(cal, 1)
                val endMs = tomorrowMidnight + schedule.endMinuteOfDay * 60_000L
                if (nowMs in startMs until endMs) {
                    return ActiveScheduleWindow(schedule, startMs, endMs)
                }
            }
            val yesterdayIndex = (todayIndex + 6) % 7
            val yesterdayEnabled = (schedule.daysMask and dayIndexToBit(yesterdayIndex)) != 0
            if (yesterdayEnabled && minuteOfDay < schedule.endMinuteOfDay) {
                val yesterdayMidnight = addDaysStartOfDayMs(cal, -1)
                val startMs = yesterdayMidnight + schedule.startMinuteOfDay * 60_000L
                val endMs = todayMidnight + schedule.endMinuteOfDay * 60_000L
                if (nowMs in startMs until endMs) {
                    return ActiveScheduleWindow(schedule, startMs, endMs)
                }
            }
        }
        return null
    }

    /**
     * Merges overlapping active schedules (both Later and Recurring) conservatively:
     * Strict Mode wins if any active schedule is strict, latest end time wins, and all
     * active blocklist IDs are preserved so the repository can intersect allowed apps
     * and union blocked website categories.
     */
    fun findDominantActiveWindow(
        schedules: List<ScheduleSpec>,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): ActiveScheduleWindow? {
        val active = schedules.mapNotNull { getActiveWindow(it, nowMs, timeZone) }
        if (active.isEmpty()) return null
        val anyStrict = active.any { it.schedule.strictMode }
        val latestEnd = active.maxOf { it.endEpochMs }
        val earliestStart = active.minOf { it.startEpochMs }
        val allBlocklists = active.map { it.schedule.blocklistId }.toSet()
        val primary = active.sortedWith(
            compareByDescending<ActiveScheduleWindow> { it.schedule.strictMode }
                .thenByDescending { it.endEpochMs }
        ).first()
        return ActiveScheduleWindow(
            schedule = primary.schedule.copy(strictMode = anyStrict),
            startEpochMs = earliestStart,
            endEpochMs = latestEnd,
            allActiveBlocklistIds = allBlocklists
        )
    }

    fun findNextTransition(
        schedules: List<ScheduleSpec>,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): NextTransition? {
        var earliest: NextTransition? = null

        for (schedule in schedules) {
            if (!schedule.enabled) continue

            if (!schedule.isRecurring) {
                if (schedule.oneTimeStartMs > nowMs) {
                    val candidate = NextTransition(schedule.id, schedule.oneTimeStartMs, isStart = true)
                    if (earliest == null || candidate.triggerEpochMs < earliest!!.triggerEpochMs) {
                        earliest = candidate
                    }
                } else if (schedule.oneTimeEndMs > nowMs && schedule.oneTimeStartMs <= nowMs) {
                    val candidate = NextTransition(schedule.id, schedule.oneTimeEndMs, isStart = false)
                    if (earliest == null || candidate.triggerEpochMs < earliest!!.triggerEpochMs) {
                        earliest = candidate
                    }
                }
                continue
            }

            if (schedule.daysMask == 0 || schedule.startMinuteOfDay == schedule.endMinuteOfDay) continue

            getActiveWindow(schedule, nowMs, timeZone)?.let { active ->
                if (active.endEpochMs > nowMs) {
                    val candidate = NextTransition(schedule.id, active.endEpochMs, isStart = false)
                    if (earliest == null || candidate.triggerEpochMs < earliest!!.triggerEpochMs) {
                        earliest = candidate
                    }
                }
            }

            val baseCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
            for (dayOffset in 0..7) {
                val dayCal = (baseCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                }
                val dayIndex = calendarDayOfWeekToMonZero(dayCal.get(Calendar.DAY_OF_WEEK))
                if ((schedule.daysMask and dayIndexToBit(dayIndex)) != 0) {
                    val startMs = startOfDayMs(dayCal) + schedule.startMinuteOfDay * 60_000L
                    if (startMs > nowMs) {
                        val candidate = NextTransition(schedule.id, startMs, isStart = true)
                        if (earliest == null || candidate.triggerEpochMs < earliest!!.triggerEpochMs) {
                            earliest = candidate
                        }
                        break
                    }
                }
            }
        }
        return earliest
    }

    fun nextStartTimeMs(
        schedule: ScheduleSpec,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (!schedule.enabled) return null
        if (!schedule.isRecurring) {
            return schedule.oneTimeStartMs.takeIf { it > nowMs }
        }
        if (schedule.daysMask == 0) return null
        val baseCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        for (dayOffset in 0..7) {
            val dayCal = (baseCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val dayIndex = calendarDayOfWeekToMonZero(dayCal.get(Calendar.DAY_OF_WEEK))
            if ((schedule.daysMask and dayIndexToBit(dayIndex)) != 0) {
                val startMs = startOfDayMs(dayCal) + schedule.startMinuteOfDay * 60_000L
                if (startMs > nowMs) return startMs
            }
        }
        return null
    }

    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val normalized = ((minuteOfDay % 1440) + 1440) % 1440
        val hour = normalized / 60
        val minute = normalized % 60
        return String.format("%02d:%02d", hour, minute)
    }

    fun formatDaysSummary(daysMask: Int): String {
        if (daysMask == EVERY_DAY_MASK) return "Every day"
        if (daysMask == WEEKDAYS_MASK) return "Weekdays"
        if (daysMask == WEEKENDS_MASK) return "Weekends"
        val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val selected = labels.indices.filter { (daysMask and dayIndexToBit(it)) != 0 }.map { labels[it] }
        return if (selected.isEmpty()) "No days" else selected.joinToString(", ")
    }

    private fun startOfDayMs(cal: Calendar): Long {
        return (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun addDaysStartOfDayMs(cal: Calendar, days: Int): Long {
        return (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, days)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
