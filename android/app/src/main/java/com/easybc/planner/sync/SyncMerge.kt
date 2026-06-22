package com.easybc.planner.sync

import java.time.Instant

object SyncMerge {
    private fun timestamp(value: String?): Long = SyncPayloadStore.timestamp(value)

    private fun <T> newer(a: T, aTime: String?, b: T, bTime: String?): T =
        if (timestamp(aTime) >= timestamp(bTime)) a else b

    fun merge(a: SyncPayloadV1, b: SyncPayloadV1): SyncPayloadV1 {
        val periodActive = linkedMapOf<String, SyncPeriodRecord>()
        (a.periodRecords + b.periodRecords).forEach { record ->
            val current = periodActive[record.start]
            periodActive[record.start] = when {
                current == null -> record
                timestamp(current.updatedAt) == timestamp(record.updatedAt) ->
                    if (current.end != null) current else record
                else -> newer(current, current.updatedAt, record, record.updatedAt)
            }
        }
        val periodDeleted = mergeTimestampMaps(a.deletedPeriodStarts, b.deletedPeriodStarts).toMutableMap()
        periodActive.entries.removeAll { (start, record) ->
            val deletedAt = periodDeleted[start]
            if (deletedAt != null && timestamp(deletedAt) >= timestamp(record.updatedAt)) true
            else {
                periodDeleted.remove(start)
                false
            }
        }

        val dayLogs = a.calendarDayLogs.toMutableMap()
        b.calendarDayLogs.forEach { (date, log) ->
            val current = dayLogs[date]
            if (current == null || timestamp(log.updatedAt) > timestamp(current.updatedAt)) dayLogs[date] = log
        }

        val activeAbstinenceTimes = a.voluntaryAbstinenceUpdatedAt.toMutableMap()
        a.voluntaryAbstinenceDates.keys.forEach { activeAbstinenceTimes.putIfAbsent(it, SYNC_EPOCH) }
        b.voluntaryAbstinenceDates.keys.forEach { date ->
            val candidate = b.voluntaryAbstinenceUpdatedAt[date] ?: SYNC_EPOCH
            if (timestamp(candidate) > timestamp(activeAbstinenceTimes[date])) activeAbstinenceTimes[date] = candidate
        }
        val deletedAbstinence = mergeTimestampMaps(
            a.deletedVoluntaryAbstinenceDates,
            b.deletedVoluntaryAbstinenceDates,
        ).toMutableMap()
        val abstinenceDates = mutableMapOf<String, Boolean>()
        activeAbstinenceTimes.entries.toList().forEach { (date, activeAt) ->
            val deletedAt = deletedAbstinence[date]
            if (deletedAt != null && timestamp(deletedAt) >= timestamp(activeAt)) {
                activeAbstinenceTimes.remove(date)
            } else {
                abstinenceDates[date] = true
                deletedAbstinence.remove(date)
            }
        }

        val androidPreferences = when {
            a.androidPreferences == null -> b.androidPreferences
            b.androidPreferences == null -> a.androidPreferences
            else -> newer(
                a.androidPreferences,
                a.androidPreferences.updatedAt,
                b.androidPreferences,
                b.androidPreferences.updatedAt,
            )
        }
        val selectedPlanner = newer(a.planner, a.planner.updatedAt, b.planner, b.planner.updatedAt)
        val otherPlanner = if (selectedPlanner === a.planner) b.planner else a.planner
        val planner = when {
            selectedPlanner.configured != null -> selectedPlanner
            timestamp(selectedPlanner.updatedAt) == timestamp(otherPlanner.updatedAt) &&
                otherPlanner.configured != null -> selectedPlanner.copy(configured = otherPlanner.configured)
            periodActive.isNotEmpty() -> selectedPlanner.copy(configured = true)
            else -> selectedPlanner
        }
        return SyncPayloadV1(
            exportedAt = Instant.now().toString(),
            planner = planner,
            periodRecords = periodActive.values.sortedBy { it.start },
            deletedPeriodStarts = periodDeleted,
            calendarDayLogs = dayLogs,
            voluntaryAbstinenceDates = abstinenceDates,
            voluntaryAbstinenceUpdatedAt = activeAbstinenceTimes,
            deletedVoluntaryAbstinenceDates = deletedAbstinence,
            ecJournal = newer(a.ecJournal, a.ecJournal.updatedAt, b.ecJournal, b.ecJournal.updatedAt),
            androidPreferences = androidPreferences,
        )
    }

    private fun mergeTimestampMaps(a: Map<String, String>, b: Map<String, String>): Map<String, String> {
        val merged = a.toMutableMap()
        b.forEach { (key, value) ->
            if (timestamp(value) > timestamp(merged[key])) merged[key] = value
        }
        return merged
    }
}
