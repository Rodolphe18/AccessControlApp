package dev.rodolphe.oskeysdemo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.rodolphe.oskeysdemo.core.database.dao.DoorDao
import dev.rodolphe.oskeysdemo.core.database.model.DoorEntity

@Database(entities = [DoorEntity::class], version = 1, exportSchema = false)
abstract class OskeysDatabase : RoomDatabase() {
    abstract fun doorDao(): DoorDao
}
