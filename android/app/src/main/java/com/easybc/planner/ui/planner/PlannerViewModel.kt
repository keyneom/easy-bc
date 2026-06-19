package com.easybc.planner.ui.planner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easybc.planner.EasyBCApp
import com.easybc.planner.data.PlannerResult
import com.easybc.planner.data.db.UserSettingsEntity
import kotlinx.coroutines.flow.*

data class PlannerDashboardState(
    val result: PlannerResult? = null,
    val settings: UserSettingsEntity? = null,
    val isLoading: Boolean = true,
    val isNativeBridge: Boolean = false,
)

class PlannerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyBCApp
    private val repo = app.repository

    val state: StateFlow<PlannerDashboardState> = combine(
        repo.plannerResultFlow,
        repo.settingsFlow,
    ) { result, settings ->
        PlannerDashboardState(
            result = result,
            settings = settings,
            isLoading = false,
            isNativeBridge = app.bridge.isNative,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        PlannerDashboardState(),
    )
}
