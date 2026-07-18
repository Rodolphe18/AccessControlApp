package dev.rodolphe.syeksodemo.core.ble.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.syeksodemo.core.ble.AndroidSyeksoBleController
import dev.rodolphe.syeksodemo.core.ble.SyeksoBleController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindSyeksoBleController(impl: AndroidSyeksoBleController): SyeksoBleController
}
