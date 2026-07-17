package dev.rodolphe.oskeysdemo.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.rodolphe.oskeysdemo.core.database.model.DoorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DoorDao {

    @Query("SELECT * FROM doors ORDER BY buildingName, name")
    fun observeDoors(): Flow<List<DoorEntity>>

    @Upsert
    suspend fun upsertDoors(doors: List<DoorEntity>)

    @Query("DELETE FROM doors")
    suspend fun clearDoors()
}
