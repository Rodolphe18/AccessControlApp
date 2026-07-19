package dev.rodolphe.syeksodemo.intercom.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.syeksodemo.core.network.SyeksoApiService
import dev.rodolphe.syeksodemo.core.network.model.DirectoryEntryNetwork
import dev.rodolphe.syeksodemo.intercom.BuildConfig
import dev.rodolphe.syeksodemo.intercom.call.DirectoryProvider
import dev.rodolphe.syeksodemo.intercom.call.IntercomConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IntercomModule {

    @Provides
    @Singleton
    fun provideIntercomConfig(): IntercomConfig =
        IntercomConfig(buildingId = "bld-montmartre", doorName = "Porte d'entrée", doorBleLocalName = "OSKEY-HALL-01")

    @Provides
    @Singleton
    fun provideDirectoryProvider(api: SyeksoApiService, config: IntercomConfig): DirectoryProvider =
        object : DirectoryProvider {
            override suspend fun residents(): List<DirectoryEntryNetwork> =
                api.getDirectory(BuildConfig.INTERCOM_KEY, config.buildingId).residents
        }
}
