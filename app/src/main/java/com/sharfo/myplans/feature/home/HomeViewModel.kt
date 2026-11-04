package com.sharfo.myplans.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sharfo.myplans.domain.model.ProjectPlan
import com.sharfo.myplans.domain.repository.PlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: PlanRepository
) : ViewModel() {

    val projects: StateFlow<List<ProjectPlan>> = repository.observeAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            repository.deleteProject(id)
        }
    }
}
