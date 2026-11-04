package com.sharfo.myplans.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectName: String,
    val landAreaSqm: Double,
    val frontageWidthM: Double,
    val floorsCount: Int,
    val roomsCount: Int,
    val bathroomsCount: Int,
    val hasKitchen: Boolean,
    val hasLivingRoom: Boolean,
    val hasReception: Boolean,
    val hasStaircase: Boolean,
    val sitePhotoUri: String?,
    val buildingCodeName: String,
    val slabTypeName: String,
    val preferredSlabBarDiaMm: Int,
    val preferredBeamBarDiaMm: Int,
    val preferredColumnBarDiaMm: Int,
    val concreteFckMpa: Int,
    val steelFyMpa: Int,
    val createdAtMillis: Long
)
