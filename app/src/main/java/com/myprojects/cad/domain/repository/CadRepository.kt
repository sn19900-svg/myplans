package com.myprojects.cad.domain.repository

import com.myprojects.cad.domain.model.CadEntity
import kotlinx.coroutines.flow.StateFlow

/** Holds the drawing that is currently open. Every [update] is exactly one undo step. */
interface CadRepository {
    val entities: StateFlow<List<CadEntity>>
    val canUndo: StateFlow<Boolean>
    val canRedo: StateFlow<Boolean>

    fun update(transform: (List<CadEntity>) -> List<CadEntity>)

    /** Replaces the whole drawing and clears undo history (used when opening a project). */
    fun setAll(entities: List<CadEntity>)

    fun undo()
    fun redo()
}
