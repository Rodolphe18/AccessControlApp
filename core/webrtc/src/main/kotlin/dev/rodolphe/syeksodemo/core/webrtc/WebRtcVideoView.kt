package dev.rodolphe.syeksodemo.core.webrtc

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Compose wrapper over the native [SurfaceViewRenderer], initialized with the shared [eglContext]. */
@Composable
fun WebRtcVideoView(
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    onRenderer: (SurfaceViewRenderer) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                onRenderer(this)
            }
        },
    )
}
