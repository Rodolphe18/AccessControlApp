package dev.rodolphe.syeksodemo.core.network

import dev.rodolphe.syeksodemo.core.network.model.ActivationRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.ActivationResponseNetwork
import dev.rodolphe.syeksodemo.core.network.model.CreateInvitationRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.CreatePinRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.DirectoryResponseNetwork
import dev.rodolphe.syeksodemo.core.network.model.DoorsResponseNetwork
import dev.rodolphe.syeksodemo.core.network.model.InvitationNetwork
import dev.rodolphe.syeksodemo.core.network.model.InvitationsResponseNetwork
import dev.rodolphe.syeksodemo.core.network.model.LoginRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.LoginResponseNetwork
import dev.rodolphe.syeksodemo.core.network.model.PinCodeNetwork
import dev.rodolphe.syeksodemo.core.network.model.PinCodesResponseNetwork
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The server's HTTP contract as a Retrofit interface.
 *
 * The bearer token is passed explicitly on authenticated calls rather than injected via an
 * interceptor: it keeps :core:network free of any dependency on where the token is stored, and the
 * repository in :core:data (which owns the session) supplies it.
 */
interface SyeksoApiService {

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestNetwork): LoginResponseNetwork

    @POST("me/activations")
    suspend fun activate(
        @Header("Authorization") bearer: String,
        @Body body: ActivationRequestNetwork,
    ): ActivationResponseNetwork

    @GET("me/doors")
    suspend fun getDoors(@Header("Authorization") bearer: String): DoorsResponseNetwork

    @POST("me/pin-codes")
    suspend fun createPinCode(
        @Header("Authorization") bearer: String,
        @Body body: CreatePinRequestNetwork,
    ): PinCodeNetwork

    @GET("me/pin-codes")
    suspend fun getPinCodes(@Header("Authorization") bearer: String): PinCodesResponseNetwork

    @POST("me/invitations")
    suspend fun createInvitation(
        @Header("Authorization") bearer: String,
        @Body body: CreateInvitationRequestNetwork,
    ): InvitationNetwork

    @GET("me/invitations")
    suspend fun getInvitations(@Header("Authorization") bearer: String): InvitationsResponseNetwork

    @GET("intercom/directory")
    suspend fun getDirectory(
        @Header("X-Intercom-Key") intercomKey: String,
        @Query("buildingId") buildingId: String,
    ): DirectoryResponseNetwork
}
