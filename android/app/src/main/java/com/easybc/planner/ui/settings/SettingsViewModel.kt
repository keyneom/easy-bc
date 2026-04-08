package com.easybc.planner.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easybc.planner.EasyBCApp
import com.easybc.planner.data.db.UserSettingsEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyBCApp
    private val repo = app.repository

    val settings: StateFlow<UserSettingsEntity?> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _draft = MutableStateFlow(UserSettingsEntity())
    val draft: StateFlow<UserSettingsEntity> = _draft

    init {
        viewModelScope.launch {
            val existing = repo.getSettings()
            _draft.value = existing ?: UserSettingsEntity()
        }
    }

    fun updateDraft(transform: (UserSettingsEntity) -> UserSettingsEntity) {
        _draft.value = transform(_draft.value)
    }

    fun save() {
        viewModelScope.launch {
            repo.saveSettings(_draft.value.copy(onboardingComplete = true))
        }
    }

    fun resetToDefaults() {
        _draft.value = UserSettingsEntity()
    }
}
