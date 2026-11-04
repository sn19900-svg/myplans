package com.sharfo.myplans.domain.model

data class ArchitecturalInput(
    val projectName: String = "مشروع سكني",
    val landAreaSqm: Double = 150.0,
    val frontageWidthM: Double = 10.0,
    val floorsCount: Int = 2,
    val roomsCount: Int = 3,
    val bathroomsCount: Int = 2,
    val hasKitchen: Boolean = true,
    val hasLivingRoom: Boolean = true,
    val hasReception: Boolean = true,
    val hasStaircase: Boolean = true,
    val sitePhotoUri: String? = null
)
