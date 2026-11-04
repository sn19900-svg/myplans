package com.sharfo.myplans.feature.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sharfo.myplans.domain.model.ProjectPlan
import com.sharfo.myplans.domain.repository.PlanRepository
import com.sharfo.myplans.domain.usecase.ExportDxfUseCase
import com.sharfo.myplans.domain.usecase.ExportExcelUseCase
import com.sharfo.myplans.domain.usecase.ExportPdfUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PlanPreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlanRepository,
    private val exportDxfUseCase: ExportDxfUseCase,
    private val exportPdfUseCase: ExportPdfUseCase,
    private val exportExcelUseCase: ExportExcelUseCase
) : ViewModel() {

    private val projectId: Long = checkNotNull(savedStateHandle["projectId"])

    private val _project = MutableStateFlow<ProjectPlan?>(null)
    val project: StateFlow<ProjectPlan?> = _project.asStateFlow()

    init {
        viewModelScope.launch {
            _project.value = repository.getProjectById(projectId)
        }
    }

    fun exportDxf(onReady: (File, String) -> Unit) {
        val current = _project.value ?: return
        viewModelScope.launch {
            val file = exportDxfUseCase(current)
            onReady(file, "application/dxf")
        }
    }

    fun exportPdf(onReady: (File, String) -> Unit) {
        val current = _project.value ?: return
        viewModelScope.launch {
            val file = exportPdfUseCase(current)
            onReady(file, "application/pdf")
        }
    }

    fun exportExcel(onReady: (File, String) -> Unit) {
        val current = _project.value ?: return
        viewModelScope.launch {
            val file = exportExcelUseCase(current)
            onReady(file, "application/vnd.ms-excel")
        }
    }
}
