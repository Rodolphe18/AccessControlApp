package dev.rodolphe.oskeysdemo.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.oskeysdemo.core.database.OskeysDatabase
import dev.rodolphe.oskeysdemo.core.database.dao.DoorDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideOskeysDatabase(@ApplicationContext context: Context): OskeysDatabase =
        Room.databaseBuilder(context, OskeysDatabase::class.java, "oskeys.db").build()

    @Provides
    fun provideDoorDao(database: OskeysDatabase): DoorDao = database.doorDao()
}
