package dev.rodolphe.oskeysdemo.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.oskeysdemo.core.data.repository.AuthRepository
import dev.rodolphe.oskeysdemo.core.data.repository.AuthRepositoryImpl
import dev.rodolphe.oskeysdemo.core.data.repository.DoorsRepository
import dev.rodolphe.oskeysdemo.core.data.repository.DoorsRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDoorsRepository(impl: DoorsRepositoryImpl): DoorsRepository
}
