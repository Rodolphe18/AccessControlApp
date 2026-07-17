package dev.rodolphe.oskeysdemo.core.network.model

import kotlinx.serialization.Serializable

/**
 * Wire types, mirroring the server's JSON contract exactly. They stay inside :core:network — the
 * repositories in :core:data map them to domain models, so a server contract change is absorbed here.
 */

@Serializable
data class LoginRequestNetwork(val email: String, val password: String)

@Serializable
data class UserNetwork(val id: String, val email: String, val displayName: String)

@Serializable
data class LoginResponseNetwork(val token: String, val user: UserNetwork)

@Serializable
data class ActivationRequestNetwork(val code: String)

@Serializable
data class BuildingNetwork(val id: String, val name: String)

@Serializable
data class DoorNetwork(
    val id: String,
    val name: String,
    val buildingId: String,
    val buildingName: String,
    val bleLocalName: String,
)

@Serializable
data class ActivationResponseNetwork(val building: BuildingNetwork, val doors: List<DoorNetwork>)

@Serializable
data class DoorsResponseNetwork(val doors: List<DoorNetwork>)
