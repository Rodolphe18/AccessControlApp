package dev.rodolphe.syeksodemo.core.network

import dev.rodolphe.syeksodemo.core.network.model.IntercomOpenResultRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomOpenResultResponseNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateResponseNetwork
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** The intercom device's calls: check a PIN, then report whether the door actually opened. */
interface IntercomApiService {

    @POST("intercom/validate")
    suspend fun validate(
        @Header("X-Intercom-Key") key: String,
        @Body body: IntercomValidateRequestNetwork,
    ): IntercomValidateResponseNetwork

    @POST("intercom/open-result")
    suspend fun reportOpenResult(
        @Header("X-Intercom-Key") key: String,
        @Body body: IntercomOpenResultRequestNetwork,
    ): IntercomOpenResultResponseNetwork
}
