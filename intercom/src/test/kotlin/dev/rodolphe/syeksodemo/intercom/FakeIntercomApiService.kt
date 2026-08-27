package dev.rodolphe.syeksodemo.intercom

import dev.rodolphe.syeksodemo.core.network.IntercomApiService
import dev.rodolphe.syeksodemo.core.network.model.IntercomOpenResultRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomOpenResultResponseNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateRequestNetwork
import dev.rodolphe.syeksodemo.core.network.model.IntercomValidateResponseNetwork

class FakeIntercomApiService : IntercomApiService {
    var response: IntercomValidateResponseNetwork = IntercomValidateResponseNetwork(allowed = false)
    var throwable: Throwable? = null
    var lastPin: String? = null
    val openResults = mutableListOf<IntercomOpenResultRequestNetwork>()

    override suspend fun validate(
        key: String,
        body: IntercomValidateRequestNetwork,
    ): IntercomValidateResponseNetwork {
        lastPin = body.pin
        throwable?.let { throw it }
        return response
    }

    override suspend fun reportOpenResult(
        key: String,
        body: IntercomOpenResultRequestNetwork,
    ): IntercomOpenResultResponseNetwork {
        openResults += body
        return IntercomOpenResultResponseNetwork(released = !body.success)
    }
}
