package com.myprojects.cad.domain.model

data class CadProject(
    val id: String = newId(),
    val name: String,
    val entities: List<CadEntity> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)

data class ProjectInfo(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val entityCount: Int
)
