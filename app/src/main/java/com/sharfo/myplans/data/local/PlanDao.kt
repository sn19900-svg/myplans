package com.sharfo.myplans.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sharfo.myplans.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM projects ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProjectEntity): Long

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)
}
