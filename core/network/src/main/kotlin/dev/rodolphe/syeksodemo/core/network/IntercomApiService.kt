package dev.rodolphe.syeksodemo.core.network

import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateResponseNetwork
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** The intercom device's single call: check a PIN and (on success) get the door to open. */
interface IntercomApiService {

    @POST("intercom/validate")
    suspend fun validate(
        @Header("X-Intercom-Key") key: String,
        @Body body: IntercomValidateRequestNetwork,
    ): IntercomValidateResponseNetwork
}
