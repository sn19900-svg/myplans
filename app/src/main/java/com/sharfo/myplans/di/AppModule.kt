package com.sharfo.myplans.di

import android.content.Context
import androidx.room.Room
import com.sharfo.myplans.data.local.AppDatabase
import com.sharfo.myplans.data.local.PlanDao
import com.sharfo.myplans.data.repository.PlanRepositoryImpl
import com.sharfo.myplans.domain.repository.PlanRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindPlanRepository(impl: PlanRepositoryImpl): PlanRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "my_plans_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun providePlanDao(db: AppDatabase): PlanDao = db.planDao()
}
