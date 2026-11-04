package com.sharfo.myplans.data.repository

import com.sharfo.myplans.data.local.PlanDao
import com.sharfo.myplans.data.local.entity.ProjectEntity
import com.sharfo.myplans.domain.model.ArchitecturalInput
import com.sharfo.myplans.domain.model.BuildingCode
import com.sharfo.myplans.domain.model.ProjectPlan
import com.sharfo.myplans.domain.model.SlabType
import com.sharfo.myplans.domain.model.StructuralInput
import com.sharfo.myplans.domain.repository.PlanRepository
import com.sharfo.myplans.domain.usecase.GenerateEngineeringPlanUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PlanRepositoryImpl @Inject constructor(
    private val dao: PlanDao,
    private val generator: GenerateEngineeringPlanUseCase
) : PlanRepository {

    override fun observeAllProjects(): Flow<List<ProjectPlan>> {
        return dao.observeAll().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getProjectById(id: Long): ProjectPlan? {
        return dao.getById(id)?.toDomain()
    }

    override suspend fun saveProject(project: ProjectPlan): Long {
        val entity = ProjectEntity(
            id = project.id,
            projectName = project.architectural.projectName,
            landAreaSqm = project.architectural.landAreaSqm,
            frontageWidthM = project.architectural.frontageWidthM,
            floorsCount = project.architectural.floorsCount,
            roomsCount = project.architectural.roomsCount,
            bathroomsCount = project.architectural.bathroomsCount,
            hasKitchen = project.architectural.hasKitchen,
            hasLivingRoom = project.architectural.hasLivingRoom,
            hasReception = project.architectural.hasReception,
            hasStaircase = project.architectural.hasStaircase,
            sitePhotoUri = project.architectural.sitePhotoUri,
            buildingCodeName = project.structural.buildingCode.name,
            slabTypeName = project.structural.slabType.name,
            preferredSlabBarDiaMm = project.structural.preferredSlabBarDiaMm,
            preferredBeamBarDiaMm = project.structural.preferredBeamBarDiaMm,
            preferredColumnBarDiaMm = project.structural.preferredColumnBarDiaMm,
            concreteFckMpa = project.structural.concreteFckMpa,
            steelFyMpa = project.structural.steelFyMpa,
            createdAtMillis = project.createdAtMillis
        )
        return dao.insert(entity)
    }

    override suspend fun deleteProject(id: Long) {
        dao.deleteById(id)
    }

    private fun ProjectEntity.toDomain(): ProjectPlan {
        val arch = ArchitecturalInput(
            projectName = projectName,
            landAreaSqm = landAreaSqm,
            frontageWidthM = frontageWidthM,
            floorsCount = floorsCount,
            roomsCount = roomsCount,
            bathroomsCount = bathroomsCount,
            hasKitchen = hasKitchen,
            hasLivingRoom = hasLivingRoom,
            hasReception = hasReception,
            hasStaircase = hasStaircase,
            sitePhotoUri = sitePhotoUri
        )
        val code = runCatching { BuildingCode.valueOf(buildingCodeName) }
            .getOrDefault(BuildingCode.SYRIAN)
        val slab = runCatching { SlabType.valueOf(slabTypeName) }
            .getOrDefault(SlabType.SOLID)

        val struct = StructuralInput(
            buildingCode = code,
            slabType = slab,
            preferredSlabBarDiaMm = preferredSlabBarDiaMm,
            preferredBeamBarDiaMm = preferredBeamBarDiaMm,
            preferredColumnBarDiaMm = preferredColumnBarDiaMm,
            concreteFckMpa = concreteFckMpa,
            steelFyMpa = steelFyMpa
        )
        val generated = generator(arch, struct)
        return ProjectPlan(
            id = id,
            architectural = arch,
            structural = struct,
            generated = generated,
            createdAtMillis = createdAtMillis
        )
    }
}
