package com.sharfo.myplans.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sharfo.myplans.data.local.entity.ProjectEntity

@Database(
    entities = [ProjectEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
}
