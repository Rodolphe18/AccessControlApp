package dev.rodolphe.syeksodemo.intercom

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.intercom.call.IntercomConfig
import javax.inject.Inject
import dev.rodolphe.syeksodemo.core.network.BuildConfig as NetworkBuildConfig

/**
 * Opens the intercom's signaling WebSocket once, when the panel starts. The intercom is an always-on
 * kiosk, so it stays connected in the [intercoms] registry ready to receive an OPEN. Held by the root
 * [IntercomHomeScreen] so it survives panel switches.
 */
@HiltViewModel
class IntercomConnectionViewModel @Inject constructor(
    signaling: Signaling,
    config: IntercomConfig,
) : ViewModel() {
    init {
        // http(s)://host:port/ -> ws(s)://host:port/ws
        val wsUrl = NetworkBuildConfig.BASE_URL.replace("http", "ws") + "ws"
        signaling.start(
            wsUrl,
            SignalingMessage.Hello(
                role = "intercom",
                intercomKey = BuildConfig.INTERCOM_KEY,
                buildingId = config.buildingId,
            ),
        )
    }
}
