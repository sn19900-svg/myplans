package com.myprojects.cad.data.repository

import com.myprojects.cad.domain.model.Wall
import com.myprojects.cad.domain.repository.CadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot-based undo/redo. Swap for a Room-backed implementation later without touching other layers. */
@Singleton
class InMemoryCadRepository @Inject constructor() : CadRepository {

    private val lock = Any()
    private val _walls = MutableStateFlow<List<Wall>>(emptyList())
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    private val undoStack = ArrayDeque<List<Wall>>()
    private val redoStack = ArrayDeque<List<Wall>>()

    override val walls: StateFlow<List<Wall>> = _walls.asStateFlow()
    override val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    override val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    override fun addWall(wall: Wall) {
        synchronized(lock) {
            pushHistory()
            _walls.value = _walls.value + wall
            refreshFlags()
        }
    }

    override fun undo() {
        synchronized(lock) {
            val previous = undoStack.removeLastOrNull() ?: return
            redoStack.addLast(_walls.value)
            _walls.value = previous
            refreshFlags()
        }
    }

    override fun redo() {
        synchronized(lock) {
            val next = redoStack.removeLastOrNull() ?: return
            undoStack.addLast(_walls.value)
            _walls.value = next
            refreshFlags()
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (_walls.value.isEmpty()) return
            pushHistory()
            _walls.value = emptyList()
            refreshFlags()
        }
    }

    private fun pushHistory() {
        undoStack.addLast(_walls.value)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun refreshFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}
