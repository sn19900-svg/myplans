package com.myprojects.cad.domain.model

import com.myprojects.cad.domain.structural.StudyRecord

data class CadProject(
    val id: String = newId(),
    val name: String,
    val entities: List<CadEntity> = emptyList(),
    val studies: List<StudyRecord> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)

data class ProjectInfo(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val entityCount: Int
)
