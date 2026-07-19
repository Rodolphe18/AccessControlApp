package dev.rodolphe.syeksodemo.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.syeksodemo.core.data.repository.AuthRepository
import dev.rodolphe.syeksodemo.core.data.repository.AuthRepositoryImpl
import dev.rodolphe.syeksodemo.core.data.repository.DoorsRepository
import dev.rodolphe.syeksodemo.core.data.repository.DoorsRepositoryImpl
import dev.rodolphe.syeksodemo.core.data.repository.InvitationRepository
import dev.rodolphe.syeksodemo.core.data.repository.InvitationRepositoryImpl
import dev.rodolphe.syeksodemo.core.data.repository.PinCodeRepository
import dev.rodolphe.syeksodemo.core.data.repository.PinCodeRepositoryImpl
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

    @Binds
    @Singleton
    abstract fun bindPinCodeRepository(impl: PinCodeRepositoryImpl): PinCodeRepository

    @Binds
    @Singleton
    abstract fun bindInvitationRepository(impl: InvitationRepositoryImpl): InvitationRepository
}
