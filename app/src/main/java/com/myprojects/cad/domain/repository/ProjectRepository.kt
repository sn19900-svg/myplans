package com.myprojects.cad.domain.repository

import com.myprojects.cad.domain.model.CadProject
import com.myprojects.cad.domain.model.ProjectInfo

interface ProjectRepository {
    suspend fun save(project: CadProject)
    suspend fun load(id: String): CadProject?
    suspend fun list(): List<ProjectInfo>
    suspend fun delete(id: String)
    suspend fun lastOpenedId(): String?
    suspend fun setLastOpened(id: String)
}
