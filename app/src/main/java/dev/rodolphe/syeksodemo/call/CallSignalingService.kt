package dev.rodolphe.syeksodemo.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.rodolphe.syeksodemo.MainActivity
import dev.rodolphe.syeksodemo.core.data.repository.AuthRepository
import dev.rodolphe.syeksodemo.core.network.BuildConfig
import dev.rodolphe.syeksodemo.core.network.model.SignalingMessage
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.feature.intercomcall.IncomingCallStore
import dev.rodolphe.syeksodemo.feature.intercomcall.IncomingCallUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the resident reachable by the intercom when the app is not on screen.
 *
 * The signaling socket used to be held by an Activity-scoped ViewModel, which meant the doorbell
 * only rang while the resident was already looking at the app. Owning it here instead — in a
 * foreground service — keeps the process alive when they leave, and lets a ring become a
 * full-screen notification over whatever they are doing.
 */
@AndroidEntryPoint
class CallSignalingService : Service() {

    @Inject lateinit var signaling: Signaling
    @Inject lateinit var store: IncomingCallStore
    @Inject lateinit var authRepository: AuthRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ServiceCompat.startForeground(
            this,
            ONGOING_NOTIFICATION_ID,
            ongoingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )

        // Hold the socket for exactly as long as there is a session. Logging out stops the service
        // outright — no point keeping a notification alive for a door nobody can answer.
        scope.launch {
            authRepository.session
                .map { it.jwt }
                .distinctUntilChanged()
                .collect { jwt ->
                    if (jwt.isNotEmpty()) {
                        signaling.start(
                            BuildConfig.BASE_URL.replace("http", "ws") + "ws",
                            SignalingMessage.Hello(role = "resident", jwt = jwt),
                        )
                    } else {
                        signaling.stop()
                        stopSelf()
                    }
                }
        }

        // The store, not the socket, is the source of truth: it already decided this is a ring.
        scope.launch {
            store.uiState.collect { state ->
                if (state is IncomingCallUiState.Ringing) {
                    notifyIncomingCall(state.doorName)
                } else {
                    NotificationManagerCompat.from(this@CallSignalingService)
                        .cancel(CALL_NOTIFICATION_ID)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DECLINE) store.onDecline()
        // The doorbell is the whole point of this service, so ask to be recreated if killed.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        signaling.stop()
        super.onDestroy()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID,
                "Service Syekso",
                NotificationManager.IMPORTANCE_LOW, // silent: it is a status, not an event
            ).apply {
                description = "Maintient la connexion pour recevoir les appels de l'interphone."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CALL_CHANNEL_ID,
                "Appels de l'interphone",
                NotificationManager.IMPORTANCE_HIGH, // required for a full-screen intent to fire
            ).apply {
                description = "Sonnerie d'un visiteur à la porte."
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Syekso")
            .setContentText("Prêt à recevoir les appels de l'interphone")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun notifyIncomingCall(doorName: String) {
        val notifications = NotificationManagerCompat.from(this)
        if (!notifications.areNotificationsEnabled()) return

        val caller = Person.Builder().setName(doorName).setImportant(true).build()
        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(doorName)
            .setContentText("Appel de l'interphone")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The one mechanism Android offers to surface a call over the lock screen. The `true`
            // asks to bypass Do Not Disturb-style suppression where the system allows it.
            .setFullScreenIntent(openAppIntent(), true)
            // Answering needs camera and mic consent plus the video surface, so it happens in the
            // app: the answer action opens it rather than trying to accept from the shade.
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    declineIntent(),
                    openAppIntent(),
                ),
            )
            .build()
        notifications.notify(CALL_NOTIFICATION_ID, notification)
    }

    /**
     * Brings the existing app to the front — it must not rebuild it.
     *
     * [Intent.FLAG_ACTIVITY_SINGLE_TOP], not [Intent.FLAG_ACTIVITY_CLEAR_TOP]. There is one Activity
     * in this app (Compose Navigation handles the rest), so CLEAR_TOP has nothing to clear above
     * MainActivity — but on a `standard` launch mode it does not merely bring it forward: it
     * **finishes the running instance and creates a new one**. Measured here, onDestroy and onCreate
     * land 18 ms apart, and the window is torn down while a Choreographer frame callback is still
     * pending. The rebuilt Activity then composes once and never again: LaunchedEffect coroutines
     * keep running (delay does not need the frame clock) while recomposition and every infinite
     * animation, which do, are frozen for good. The visible symptom was the app stuck on its
     * loading spinner, the spinner itself not turning.
     *
     * SINGLE_TOP reuses the live instance and delivers onNewIntent instead, which is also the better
     * behaviour: the resident lands back on the screen they left, with the call overlay already on it.
     */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun declineIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, CallSignalingService::class.java).setAction(ACTION_DECLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val ONGOING_CHANNEL_ID = "syekso_service"
        private const val CALL_CHANNEL_ID = "syekso_incoming_call"
        private const val ONGOING_NOTIFICATION_ID = 1
        private const val CALL_NOTIFICATION_ID = 2
        const val ACTION_DECLINE = "dev.rodolphe.syeksodemo.DECLINE_CALL"

        fun start(context: android.content.Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, CallSignalingService::class.java),
            )
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CallSignalingService::class.java))
        }
    }
}
