package com.easybc.planner.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.easybc.planner.data.PlannerResult
import com.easybc.planner.data.RecommendedAction
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.UserSettingsEntity
import com.easybc.planner.util.CycleCalculator
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Writes EasyBC plan + cycle data into a **local** Android calendar owned by
 * our app. No network, no server, no third party — the events live on the
 * device inside Android's own `CalendarContract` provider.
 *
 * The calendar uses [CalendarContract.ACCOUNT_TYPE_LOCAL], which is the
 * provider's built-in "account that never syncs anywhere" bucket. If the user
 * later chooses to sync it out — e.g. by moving events to their Google
 * account via the Calendar app — that's their explicit action, not ours.
 *
 * ### What we write
 *
 * For each cycle (observed + active + predicted) we emit:
 * - A **period** all-day event spanning the bleeding window.
 * - A **fertile window** all-day event spanning ovulation ± [fertileHalfWidth].
 * - One **daily planner recommendation** event per day in the cycle, titled
 *   with the recommended action (U/W/C/A) and a risk note in the description.
 *
 * ### Sync strategy
 *
 * Every call to [syncEvents] wipes all events in our calendar and rewrites
 * them. That's simple, idempotent, and keeps stale predictions from
 * lingering when the user logs a new period and predictions shift.
 *
 * ### Permissions
 *
 * Callers must already hold `WRITE_CALENDAR` + `READ_CALENDAR`. [hasPermission]
 * is provided for a quick check. Without permission the methods throw
 * [SecurityException] via the underlying ContentResolver — catch at the UI
 * layer and prompt.
 */
class EasyBCCalendarSync(private val context: Context) {

    companion object {
        const val ACCOUNT_NAME = "EasyBC"
        const val ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
        const val CALENDAR_NAME = "easybc_planner"
        const val CALENDAR_DISPLAY_NAME = "EasyBC Planner"

        /** Purple-ish default. Users can change it in their Calendar app. */
        val CALENDAR_COLOR: Int = Color.rgb(171, 71, 188)

        /** How many days forward we write daily-recommendation events. */
        const val DAILY_EVENT_HORIZON_DAYS = 180L
    }

    data class SyncResult(
        val calendarId: Long,
        /** Total composite events written (one per day). */
        val eventCount: Int,
        /** How many of those days had a period component. */
        val periodDays: Int,
        /** How many had a fertile component. */
        val fertileDays: Int,
        /** How many had a plan action component. */
        val actionDays: Int,
    )

    // ── Permission helpers ───────────────────────────────────────────────

    fun hasPermission(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    // ── Public entry points ──────────────────────────────────────────────

    /**
     * Idempotently ensure our calendar exists. Returns its provider id.
     * Caller must hold WRITE_CALENDAR.
     */
    fun ensureCalendarExists(): Long {
        findCalendarId()?.let { return it }
        return createLocalCalendar()
    }

    /**
     * Wipe the existing events in our calendar and write a fresh plan.
     *
     * Writes **one composite event per relevant day** with a title like
     * `P + F + C` joining the applicable parts with ` + `. Single-component
     * days just show the bare label (`U`, `A`, …). Blank labels are dropped
     * from the join, so a user who clears "Fertile" gets `P + U` instead of
     * `P +  + U`. Nothing is written for days that have no components.
     *
     * For planner-action days, the event's **description** is the bare
     * integer risk score; everywhere else the description mirrors the title.
     *
     * Caller must hold WRITE_CALENDAR + READ_CALENDAR.
     */
    fun syncEvents(
        periods: List<PeriodRecord>,
        plan: PlannerResult?,
        settings: UserSettingsEntity,
        cycleCalc: CycleCalculator,
        today: LocalDate = LocalDate.now(),
        fertileHalfWidth: Int = 5,
    ): SyncResult {
        val calendarId = ensureCalendarExists()
        deleteAllEvents(calendarId)

        val coverageCycles = cycleCalc.buildCoverageCycles(periods, settings)
        val resolver = context.contentResolver

        val periodLabel = settings.calendarLabelPeriod
        val fertileLabel = settings.calendarLabelFertile

        // Collect per-day components across all sources, then emit.
        val perDay = HashMap<LocalDate, DayParts>()
        fun parts(date: LocalDate) = perDay.getOrPut(date) { DayParts() }

        // ── Periods: logged bleeding windows ──
        for (rec in periods) {
            val startDate = LocalDate.ofEpochDay(rec.startDate)
            val endEpoch = cycleCalc.effectiveBleedingEndEpochDay(rec, periods, today)
            val endDate = LocalDate.ofEpochDay(endEpoch)
            var d = startDate
            while (!d.isAfter(endDate)) {
                parts(d).period = true
                d = d.plusDays(1)
            }
        }

        // ── Predicted future periods ──
        val lastLoggedStart = periods.maxOfOrNull { LocalDate.ofEpochDay(it.startDate) }
        val closedSpans = periods.mapNotNull { p ->
            p.endDate?.let { (it - p.startDate + 1).toInt() }
        }
        val typicalSpan = if (closedSpans.size >= 3) {
            closedSpans.average().toInt().coerceIn(2, 14)
        } else 5
        for (cycle in coverageCycles) {
            if (cycle.isObserved) continue
            if (lastLoggedStart != null && !cycle.startDate.isAfter(lastLoggedStart)) continue
            var d = cycle.startDate
            val end = cycle.startDate.plusDays((typicalSpan - 1).toLong())
            while (!d.isAfter(end)) {
                parts(d).period = true
                d = d.plusDays(1)
            }
        }

        // ── Fertile windows (observed + predicted) ──
        for (cycle in coverageCycles) {
            val ovulationDay = cycle.lengthDays - 14
            if (ovulationDay < 1) continue
            val windowStart = cycle.startDate
                .plusDays((ovulationDay - fertileHalfWidth - 1).toLong().coerceAtLeast(0))
            val windowEnd = cycle.startDate.plusDays((ovulationDay + 1 - 1).toLong())
            if (windowEnd.isBefore(windowStart)) continue
            var d = windowStart
            while (!d.isAfter(windowEnd)) {
                parts(d).fertile = true
                d = d.plusDays(1)
            }
        }

        // ── Daily planner action (today through horizon) ──
        if (plan != null && plan.years.isNotEmpty()) {
            val horizonEnd = today.plusDays(DAILY_EVENT_HORIZON_DAYS)
            for ((cycleIdx, cycle) in coverageCycles.withIndex()) {
                if (cycle.startDate.isAfter(horizonEnd)) break
                val yearOutput = plan.years.getOrNull(cycleIdx) ?: continue
                for (dayWeight in yearOutput.dayWeights) {
                    val date = cycle.startDate.plusDays((dayWeight.day - 1).toLong())
                    if (date.isBefore(today)) continue
                    if (date.isAfter(horizonEnd)) continue
                    val p = parts(date)
                    p.actionLabel = labelForAction(dayWeight.recommendedAction, settings)
                    p.actionRiskScore = dayWeight.rawRiskScore
                }
            }
        }

        // ── Emit one composite event per day ──
        var eventCount = 0
        var periodDays = 0
        var fertileDays = 0
        var actionDays = 0
        for ((date, p) in perDay) {
            val pieces = buildList {
                if (p.period && periodLabel.isNotBlank()) add(periodLabel)
                if (p.fertile && fertileLabel.isNotBlank()) add(fertileLabel)
                p.actionLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (pieces.isEmpty()) continue
            val title = pieces.joinToString(" + ")
            // If there's a plan action, description is the bare risk score
            // (meaningless to a bystander). Otherwise mirror the title so
            // the description reveals nothing new.
            val description = p.actionRiskScore?.toString() ?: title
            insertAllDayEvent(
                resolver, calendarId,
                startDate = date,
                endDateInclusive = date,
                title = title,
                description = description,
                syncId = "day:${date.toEpochDay()}",
            )
            eventCount++
            if (p.period) periodDays++
            if (p.fertile) fertileDays++
            if (p.actionLabel != null) actionDays++
        }

        return SyncResult(calendarId, eventCount, periodDays, fertileDays, actionDays)
    }

    private class DayParts(
        var period: Boolean = false,
        var fertile: Boolean = false,
        var actionLabel: String? = null,
        var actionRiskScore: Int? = null,
    )

    /** Remove our calendar (and all its events) entirely. */
    fun removeCalendar(): Boolean {
        val id = findCalendarId() ?: return false
        val uri = ContentUris.withAppendedId(
            asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
            id,
        )
        return context.contentResolver.delete(uri, null, null) > 0
    }

    // ── Private: calendar lookup / create ───────────────────────────────

    private fun findCalendarId(): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND " +
            "${CalendarContract.Calendars.NAME} = ?"
        val args = arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE, CALENDAR_NAME)
        context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun createLocalCalendar(): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
        val inserted = context.contentResolver.insert(uri, values)
            ?: error("Failed to create EasyBC local calendar")
        return ContentUris.parseId(inserted)
    }

    private fun deleteAllEvents(calendarId: Long) {
        val uri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)
        context.contentResolver.delete(
            uri,
            "${CalendarContract.Events.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
        )
    }

    /**
     * Insert a single all-day event.
     *
     * @param endDateInclusive is the last day the event covers (inclusive).
     *   CalendarContract requires DTEND = midnight UTC of the *following*
     *   day, so we add one day before writing.
     */
    private fun insertAllDayEvent(
        resolver: ContentResolver,
        calendarId: Long,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        title: String,
        description: String,
        syncId: String,
    ) {
        val dtstart = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dtend = endDateInclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.DTSTART, dtstart)
            put(CalendarContract.Events.DTEND, dtend)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.HAS_ALARM, 0)
            // sync columns are writable only via sync-adapter URI; we use
            // asSyncAdapter() below so we can set _SYNC_ID for dedupe /
            // future incremental updates.
            put(CalendarContract.Events._SYNC_ID, syncId)
        }
        resolver.insert(asSyncAdapter(CalendarContract.Events.CONTENT_URI), values)
    }

    private fun labelForAction(action: RecommendedAction, settings: UserSettingsEntity): String =
        when (action) {
            RecommendedAction.U -> settings.calendarLabelActionU
            RecommendedAction.C -> settings.calendarLabelActionC
            RecommendedAction.A -> settings.calendarLabelActionA
            RecommendedAction.W -> settings.calendarLabelActionW
        }

    /**
     * Returns a sync-adapter-flavored URI. The CalendarProvider requires this
     * for inserting calendars and for writing `_SYNC_ID` / deleting rows that
     * belong to a local calendar.
     */
    private fun asSyncAdapter(uri: Uri): Uri {
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()
    }
}
