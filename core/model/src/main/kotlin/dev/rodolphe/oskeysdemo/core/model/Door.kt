package dev.rodolphe.oskeysdemo.core.model

@JvmInline
value class DoorId(val value: String)

/**
 * A door the current resident is allowed to open.
 *
 * [bleLocalName] is what the lock advertises; it is how a scan result is matched back to a door.
 */
data class Door(
    val id: DoorId,
    val name: String,
    val buildingName: String,
    val bleLocalName: String,
)
