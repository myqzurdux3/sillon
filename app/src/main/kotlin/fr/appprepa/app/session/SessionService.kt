package fr.appprepa.app.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.appprepa.app.anki.AnkiAvailability
import fr.appprepa.app.anki.AnkiDroidGateway
import fr.appprepa.app.anki.AnkiStatus
import fr.appprepa.app.journal.JsonlJournal
import fr.appprepa.app.llm.AnthropicTutor
import fr.appprepa.app.settings.SettingsStore
import fr.appprepa.app.voice.AndroidListener
import fr.appprepa.app.voice.AndroidSpeaker
import fr.appprepa.core.engine.SessionLoop
import fr.appprepa.core.ports.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Service de premier plan : sans lui, la revision s'arreterait des que le telephone se
 * verrouille dans le support de voiture.
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var speaker: AndroidSpeaker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Session de révision en cours"))
        if (job == null) job = scope.launch { runSession() }
        return START_STICKY
    }

    private suspend fun runSession() {
        val holder = SessionHolder.shared
        holder.markRunning(true)
        holder.clearOutcome()

        val settings = SettingsStore(this)

        val status = AnkiAvailability.check(this)
        if (status != AnkiStatus.Ready) {
            holder.finish(SessionOutcome.Failed(AnkiAvailability.message(status)))
            stopSelf()
            return
        }
        if (settings.apiKey.isBlank()) {
            holder.finish(SessionOutcome.Failed("Aucune clé d'API renseignée."))
            stopSelf()
            return
        }

        val tts = AndroidSpeaker(this).also { speaker = it }
        if (!tts.awaitReady()) {
            holder.finish(
                SessionOutcome.Failed("La synthèse vocale française n'est pas disponible."),
            )
            stopSelf()
            return
        }

        val guard = AudioFocusGuard(this)

        val loop = SessionLoop(
            gateway = AnkiDroidGateway(contentResolver),
            tutor = AnthropicTutor(settings.apiKey),
            speaker = FocusAwareSpeaker(tts, guard),
            listener = if (settings.debugTranscripts) {
                DebugListener.shared
            } else {
                AndroidListener(this)
            },
            journal = JsonlJournal(File(filesDir, "journal.jsonl")),
            clock = object : Clock {
                override fun nowMs() = System.currentTimeMillis()
            },
            writeMode = settings.writeMode,
        )

        val mirror = scope.launch { loop.state.collect { holder.publish(it) } }

        val outcome = runCatching {
            guard.withFocus { loop.run(settings.deckId, settings.cardLimit) }
        }
        mirror.cancel()

        holder.finish(
            outcome.fold(
                onSuccess = { SessionOutcome.Completed(it) },
                onFailure = { SessionOutcome.Failed(it.message ?: "erreur inattendue") },
            ),
        )
        stopSelf()
    }

    private fun stopSession() {
        speaker?.stop()
        job?.cancel()
        job = null
        stopSelf()
    }

    override fun onDestroy() {
        speaker?.release()
        speaker = null
        scope.cancel()
        SessionHolder.shared.markRunning(false)
        super.onDestroy()
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anki Voix")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_STOP = "fr.appprepa.app.STOP_SESSION"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Session de révision",
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun start(context: Context) {
            ensureChannel(context)
            context.startForegroundService(Intent(context, SessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
