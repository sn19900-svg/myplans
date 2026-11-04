package com.sharfo.myplans.feature.cad.presentation

import androidx.lifecycle.ViewModel
import com.sharfo.myplans.feature.cad.domain.model.Point2D
import com.sharfo.myplans.feature.cad.domain.model.WallElement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class CADUiState(
    val walls: List<WallElement> = emptyList(),
    val currentThicknessCm: Double = 20.0,
    val isExternalWall: Boolean = true,
    val undoStack: List<List<WallElement>> = emptyList(),
    val redoStack: List<List<WallElement>> = emptyList(),
    val selectedWallId: String? = null
)

@HiltViewModel
class CADViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(CADUiState())
    val state: StateFlow<CADUiState> = _state.asStateFlow()

    fun addWall(start: Point2D, end: Point2D) {
        if (start.distanceTo(end) < 0.1) return // تجاهل الخطوط القصيرة جداً
        
        val currentWalls = _state.value.walls
        val newWall = WallElement(
            id = "W_${System.currentTimeMillis()}",
            start = start,
            end = end,
            thicknessCm = _state.value.currentThicknessCm,
            isExternal = _state.value.isExternalWall
        )

        _state.update {
            it.copy(
                walls = currentWalls + newWall,
                undoStack = it.undoStack + listOf(currentWalls),
                redoStack = emptyList()
            )
        }
    }

    fun setThickness(thickness: Double) {
        _state.update { it.copy(currentThicknessCm = thickness) }
    }

    fun undo() {
        val s = _state.value
        if (s.undoStack.isNotEmpty()) {
            val previous = s.undoStack.last()
            val newUndo = s.undoStack.dropLast(1)
            _state.update {
                it.copy(
                    walls = previous,
                    undoStack = newUndo,
                    redoStack = it.redoStack + listOf(s.walls)
                )
            }
        }
    }

    fun redo() {
        val s = _state.value
        if (s.redoStack.isNotEmpty()) {
            val next = s.redoStack.last()
            val newRedo = s.redoStack.dropLast(1)
            _state.update {
                it.copy(
                    walls = next,
                    undoStack = it.undoStack + listOf(s.walls),
                    redoStack = newRedo
                )
            }
        }
    }
    
    fun clearAll() {
        _state.update {
            it.copy(
                walls = emptyList(),
                undoStack = it.undoStack + listOf(it.walls),
                redoStack = emptyList()
            )
        }
    }
}
