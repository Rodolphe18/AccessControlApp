package dev.rodolphe.syeksodemo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.rodolphe.syeksodemo.core.database.dao.DoorDao
import dev.rodolphe.syeksodemo.core.database.model.DoorEntity

@Database(entities = [DoorEntity::class], version = 1, exportSchema = false)
abstract class SyeksoDatabase : RoomDatabase() {
    abstract fun doorDao(): DoorDao
}
