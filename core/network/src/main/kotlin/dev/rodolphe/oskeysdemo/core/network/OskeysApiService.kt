package dev.rodolphe.oskeysdemo.core.network

import dev.rodolphe.oskeysdemo.core.network.model.ActivationRequestNetwork
import dev.rodolphe.oskeysdemo.core.network.model.ActivationResponseNetwork
import dev.rodolphe.oskeysdemo.core.network.model.DoorsResponseNetwork
import dev.rodolphe.oskeysdemo.core.network.model.LoginRequestNetwork
import dev.rodolphe.oskeysdemo.core.network.model.LoginResponseNetwork
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * The server's HTTP contract as a Retrofit interface.
 *
 * The bearer token is passed explicitly on authenticated calls rather than injected via an
 * interceptor: it keeps :core:network free of any dependency on where the token is stored, and the
 * repository in :core:data (which owns the session) supplies it.
 */
interface OskeysApiService {

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestNetwork): LoginResponseNetwork

    @POST("me/activations")
    suspend fun activate(
        @Header("Authorization") bearer: String,
        @Body body: ActivationRequestNetwork,
    ): ActivationResponseNetwork

    @GET("me/doors")
    suspend fun getDoors(@Header("Authorization") bearer: String): DoorsResponseNetwork
}
