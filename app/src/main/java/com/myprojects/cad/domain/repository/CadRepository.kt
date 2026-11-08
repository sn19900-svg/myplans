package com.myprojects.cad.domain.repository

import com.myprojects.cad.domain.model.Wall
import kotlinx.coroutines.flow.StateFlow

interface CadRepository {
    val walls: StateFlow<List<Wall>>
    val canUndo: StateFlow<Boolean>
    val canRedo: StateFlow<Boolean>
    fun addWall(wall: Wall)
    fun undo()
    fun redo()
    fun clear()
}
