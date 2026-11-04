package com.sharfo.myplans.feature.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sharfo.myplans.domain.model.ArchitecturalInput
import com.sharfo.myplans.domain.model.BuildingCode
import com.sharfo.myplans.domain.model.ProjectPlan
import com.sharfo.myplans.domain.model.SlabType
import com.sharfo.myplans.domain.model.StructuralInput
import com.sharfo.myplans.domain.repository.PlanRepository
import com.sharfo.myplans.domain.usecase.GenerateEngineeringPlanUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WizardUiState(
    val projectName: String = "مخطط فيلا سكنية",
    val landAreaText: String = "160",
    val frontageWidthText: String = "10",
    val floorsCount: Int = 2,
    val roomsCount: Int = 3,
    val bathroomsCount: Int = 2,
    val hasKitchen: Boolean = true,
    val hasLivingRoom: Boolean = true,
    val hasReception: Boolean = true,
    val hasStaircase: Boolean = true,
    val sitePhotoUri: String? = null,
    val buildingCode: BuildingCode = BuildingCode.SYRIAN,
    val slabType: SlabType = SlabType.SOLID,
    val slabBarDiaMm: Int = 12,
    val beamBarDiaMm: Int = 16,
    val columnBarDiaMm: Int = 18
)

@HiltViewModel
class WizardViewModel @Inject constructor(
    private val repository: PlanRepository,
    private val generator: GenerateEngineeringPlanUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(WizardUiState())
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    fun updateProjectName(value: String) = _state.update { it.copy(projectName = value) }
    fun updateLandArea(value: String) = _state.update { it.copy(landAreaText = value) }
    fun updateFrontageWidth(value: String) = _state.update { it.copy(frontageWidthText = value) }
    fun updateFloors(value: Int) = _state.update { it.copy(floorsCount = value.coerceIn(1, 12)) }
    fun updateRooms(value: Int) = _state.update { it.copy(roomsCount = value.coerceIn(1, 10)) }
    fun updateBathrooms(value: Int) = _state.update { it.copy(bathroomsCount = value.coerceIn(1, 6)) }
    fun toggleKitchen(value: Boolean) = _state.update { it.copy(hasKitchen = value) }
    fun toggleLiving(value: Boolean) = _state.update { it.copy(hasLivingRoom = value) }
    fun toggleReception(value: Boolean) = _state.update { it.copy(hasReception = value) }
    fun toggleStaircase(value: Boolean) = _state.update { it.copy(hasStaircase = value) }
    fun updateSitePhoto(uri: String?) = _state.update { it.copy(sitePhotoUri = uri) }
    fun updateBuildingCode(code: BuildingCode) = _state.update { it.copy(buildingCode = code) }
    fun updateSlabType(type: SlabType) = _state.update { it.copy(slabType = type) }
    fun updateSlabBarDia(dia: Int) = _state.update { it.copy(slabBarDiaMm = dia) }
    fun updateBeamBarDia(dia: Int) = _state.update { it.copy(beamBarDiaMm = dia) }
    fun updateColumnBarDia(dia: Int) = _state.update { it.copy(columnBarDiaMm = dia) }

    fun generateAndSave(onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            val arch = ArchitecturalInput(
                projectName = s.projectName.ifBlank { "مشروع هندسي" },
                landAreaSqm = s.landAreaText.toDoubleOrNull() ?: 150.0,
                frontageWidthM = s.frontageWidthText.toDoubleOrNull() ?: 10.0,
                floorsCount = s.floorsCount,
                roomsCount = s.roomsCount,
                bathroomsCount = s.bathroomsCount,
                hasKitchen = s.hasKitchen,
                hasLivingRoom = s.hasLivingRoom,
                hasReception = s.hasReception,
                hasStaircase = s.hasStaircase,
                sitePhotoUri = s.sitePhotoUri
            )
            val struct = StructuralInput(
                buildingCode = s.buildingCode,
                slabType = s.slabType,
                preferredSlabBarDiaMm = s.slabBarDiaMm,
                preferredBeamBarDiaMm = s.beamBarDiaMm,
                preferredColumnBarDiaMm = s.columnBarDiaMm
            )
            val generated = generator(arch, struct)
            val id = repository.saveProject(
                ProjectPlan(
                    architectural = arch,
                    structural = struct,
                    generated = generated
                )
            )
            onSaved(id)
        }
    }
}
