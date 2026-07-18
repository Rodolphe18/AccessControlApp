package dev.rodolphe.syeksodemo.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.syeksodemo.core.database.SyeksoDatabase
import dev.rodolphe.syeksodemo.core.database.dao.DoorDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideSyeksoDatabase(@ApplicationContext context: Context): SyeksoDatabase =
        Room.databaseBuilder(context, SyeksoDatabase::class.java, "syekso.db").build()

    @Provides
    fun provideDoorDao(database: SyeksoDatabase): DoorDao = database.doorDao()
}
