package com.myprojects.cad.di

import com.myprojects.cad.data.repository.InMemoryCadRepository
import com.myprojects.cad.domain.repository.CadRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindCadRepository(impl: InMemoryCadRepository): CadRepository
}
