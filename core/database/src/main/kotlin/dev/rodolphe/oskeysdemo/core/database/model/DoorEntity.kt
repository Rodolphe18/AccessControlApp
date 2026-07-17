package dev.rodolphe.oskeysdemo.core.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.rodolphe.oskeysdemo.core.model.Door
import dev.rodolphe.oskeysdemo.core.model.DoorId

/**
 * A door as stored locally. Room is the source of truth for the doors list, which is what makes the
 * app work offline: the network only refreshes this table. Building fields are denormalized here
 * because the app always shows a door with its building name.
 */
@Entity(tableName = "doors")
data class DoorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val buildingId: String,
    val buildingName: String,
    val bleLocalName: String,
)

fun DoorEntity.asExternalModel(): Door = Door(
    id = DoorId(id),
    name = name,
    buildingName = buildingName,
    bleLocalName = bleLocalName,
)
