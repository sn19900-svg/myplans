package com.myprojects.cad.data.repository

import com.myprojects.cad.domain.model.CadEntity
import com.myprojects.cad.domain.structural.StudyRecord
import com.myprojects.cad.domain.repository.CadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot-based undo/redo (entities are immutable, so snapshots are cheap). */
@Singleton
class InMemoryCadRepository @Inject constructor() : CadRepository {

    private val lock = Any()
    private val _entities = MutableStateFlow<List<CadEntity>>(emptyList())
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    private val undoStack = ArrayDeque<List<CadEntity>>()
    private val redoStack = ArrayDeque<List<CadEntity>>()

    private val _studies = MutableStateFlow<List<StudyRecord>>(emptyList())

    override val studies: StateFlow<List<StudyRecord>> = _studies.asStateFlow()

    override fun upsertStudy(record: StudyRecord) {
        synchronized(lock) {
            val rest = _studies.value.filterNot { it.id == record.id || it.entityIds.toSet() == record.entityIds.toSet() }
            _studies.value = rest + record
        }
    }

    override fun setStudies(list: List<StudyRecord>) {
        synchronized(lock) { _studies.value = list }
    }

    override val entities: StateFlow<List<CadEntity>> = _entities.asStateFlow()
    override val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    override val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    override fun update(transform: (List<CadEntity>) -> List<CadEntity>) {
        synchronized(lock) {
            val current = _entities.value
            val next = transform(current)
            if (next == current) return
            undoStack.addLast(current)
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
            _entities.value = next
            refreshFlags()
        }
    }

    override fun setAll(entities: List<CadEntity>) {
        synchronized(lock) {
            undoStack.clear()
            redoStack.clear()
            _entities.value = entities
            refreshFlags()
        }
    }

    override fun undo() {
        synchronized(lock) {
            val previous = undoStack.removeLastOrNull() ?: return
            redoStack.addLast(_entities.value)
            _entities.value = previous
            refreshFlags()
        }
    }

    override fun redo() {
        synchronized(lock) {
            val next = redoStack.removeLastOrNull() ?: return
            undoStack.addLast(_entities.value)
            _entities.value = next
            refreshFlags()
        }
    }

    private fun refreshFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}
