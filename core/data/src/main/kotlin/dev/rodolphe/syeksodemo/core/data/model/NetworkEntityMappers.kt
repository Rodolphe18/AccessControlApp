package dev.rodolphe.syeksodemo.core.data.model

import dev.rodolphe.syeksodemo.core.database.model.DoorEntity
import dev.rodolphe.syeksodemo.core.network.model.DoorNetwork

/**
 * Network wire type → Room entity. This is the one-way street of an offline-first repo: the network
 * writes into the database, and the UI only ever reads domain models back out of the database.
 */
fun DoorNetwork.asEntity(): DoorEntity = DoorEntity(
    id = id,
    name = name,
    buildingId = buildingId,
    buildingName = buildingName,
    bleLocalName = bleLocalName,
)
