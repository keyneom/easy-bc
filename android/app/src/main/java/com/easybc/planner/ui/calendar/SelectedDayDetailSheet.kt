package com.easybc.planner.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key

/** One editor and save path for Calendar and Reconcile. Keep it outside row lists
 * so confirming an action doesn't close it before the user can add events. */
@Composable
fun SelectedDayDetailSheet(vm: CalendarViewModel) {
    val show by vm.showDayDetail.collectAsState()
    val selected by vm.selectedDayDetail.collectAsState()
    val requestedDate by vm.selectedDate.collectAsState()
    val signalsExpanded by vm.hasEverLoggedObservations.collectAsState()
    val restricted by vm.restrictedDayParts.collectAsState()
    val cell = selected ?: return
    // The derived detail flow can briefly still contain the previous selection.
    if (!show || cell.date != requestedDate) return
    key(cell.date) {
        DayDetailSheet(
            cell = cell,
            signalsDefaultExpanded = signalsExpanded,
            restricted = restricted,
            onDismiss = vm::dismissDayDetail,
            onLogPeriodStart = { vm.logPeriodStart(cell.date) },
            onClearPeriodStart = { vm.clearPeriodStart(cell.date) },
            onLogPeriodEnd = { vm.endCurrentPeriod(cell.date) },
            onClearPeriodEnd = { vm.clearPeriodEnd(cell.date) },
            onLogAction = { vm.logDayAction(cell.date, it) },
            onClearAction = { vm.clearDayAction(cell.date) },
            onLogEvent = { kind, ecType, hours -> vm.logDayEvent(cell.date, kind, ecType, hours) },
            onDeleteEvent = vm::deleteDayEvent,
            onLogObservations = { mucus, bbt, opk, mitt, tender ->
                vm.logDayObservations(cell.date, mucus, bbt, opk, mitt, tender)
            },
        )
    }
}
