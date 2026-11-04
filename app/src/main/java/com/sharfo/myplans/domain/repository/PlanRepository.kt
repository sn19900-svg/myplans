package com.sharfo.myplans.domain.repository

import com.sharfo.myplans.domain.model.ProjectPlan
import kotlinx.coroutines.flow.Flow

interface PlanRepository {
    fun observeAllProjects(): Flow<List<ProjectPlan>>
    suspend fun getProjectById(id: Long): ProjectPlan?
    suspend fun saveProject(project: ProjectPlan): Long
    suspend fun deleteProject(id: Long)
}
