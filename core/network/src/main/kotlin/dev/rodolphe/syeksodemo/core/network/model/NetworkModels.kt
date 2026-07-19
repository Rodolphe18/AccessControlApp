package dev.rodolphe.syeksodemo.core.network.model

import kotlinx.serialization.SerialName
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

@Serializable
data class CreatePinRequestNetwork(val doorId: String)

@Serializable
data class PinCodeNetwork(val pin: String, val doorName: String, val expiresAtEpochMs: Long)

@Serializable
data class PinCodesResponseNetwork(val codes: List<PinCodeNetwork>)

@Serializable
sealed interface SignalingMessage {
    @Serializable @SerialName("hello")
    data class Hello(val role: String, val jwt: String? = null, val intercomKey: String? = null, val buildingId: String? = null) : SignalingMessage
    @Serializable @SerialName("ring")
    data class Ring(val callId: String, val targetUserId: String? = null, val doorName: String? = null) : SignalingMessage
    @Serializable @SerialName("open")
    data class Open(val callId: String) : SignalingMessage
    @Serializable @SerialName("decline")
    data class Decline(val callId: String) : SignalingMessage
    @Serializable @SerialName("open_result")
    data class OpenResult(val callId: String, val success: Boolean, val reason: String? = null) : SignalingMessage
    @Serializable @SerialName("error")
    data class ErrorMsg(val callId: String? = null, val message: String) : SignalingMessage
}

@Serializable
data class DirectoryEntryNetwork(val userId: String, val displayName: String)

@Serializable
data class DirectoryResponseNetwork(val residents: List<DirectoryEntryNetwork>)

@Serializable
data class CreateInvitationRequestNetwork(
    val title: String,
    val doorId: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationNetwork(
    val code: String,
    val title: String,
    val doorName: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
)

@Serializable
data class InvitationsResponseNetwork(val invitations: List<InvitationNetwork>)

@Serializable
data class IntercomValidateRequestNetwork(val pin: String)

@Serializable
data class IntercomValidateResponseNetwork(
    val allowed: Boolean,
    val doorName: String? = null,
    val doorBleLocalName: String? = null,
    val reason: String? = null,
)
