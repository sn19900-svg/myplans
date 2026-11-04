package com.sharfo.myplans.domain.model

data class ProjectPlan(
    val id: Long = 0,
    val architectural: ArchitecturalInput,
    val structural: StructuralInput,
    val generated: GeneratedPlan,
    val createdAtMillis: Long = System.currentTimeMillis()
)
