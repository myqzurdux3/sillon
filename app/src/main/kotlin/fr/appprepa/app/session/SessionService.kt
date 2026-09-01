package fr.appprepa.app.session

import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.appprepa.app.R
import fr.appprepa.app.anki.AnkiAvailability
import fr.appprepa.app.anki.AnkiDroidGateway
import fr.appprepa.app.anki.AnkiStatus
import fr.appprepa.app.journal.JsonlJournal
import fr.appprepa.app.llm.AnthropicTutor
import fr.appprepa.app.settings.SettingsStore
import fr.appprepa.app.voice.AndroidListener
import fr.appprepa.app.ui.MainActivity
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

    /**
     * Un seul thread pour toute la session. La boucle du moteur et ses prechargements
     * poussent leurs evenements dans la meme file, qui n'est pas concurrente : deux
     * threads y perdraient des evenements au hasard. Rien n'y bloque le processeur —
     * reseau, disque et micro suspendent tous en rendant la place.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var job: Job? = null
    private var speaker: AndroidSpeaker? = null
    private var loop: SessionLoop? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Session de révision en cours"))
        if (job == null) job = scope.launch { runSession() }
        // Surtout pas START_STICKY : le systeme relancerait le service avec un intent nul
        // apres l'avoir tue, et une session demarrerait toute seule, micro compris.
        return START_NOT_STICKY
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

        // Le micro se verifie a l'arret, comme le reste : jamais en plein trajet.
        if (!settings.debugTranscripts) {
            AndroidListener.unavailableReason(this)?.let { reason ->
                holder.finish(SessionOutcome.Failed(reason))
                stopSelf()
                return
            }
        }

        val tts = AndroidSpeaker(this, settings.speechRate, settings.accentAnglais.locale)
            .also { speaker = it }
        if (!tts.awaitReady()) {
            holder.finish(
                SessionOutcome.Failed("La synthèse vocale française n'est pas disponible."),
            )
            stopSelf()
            return
        }

        // Une perte definitive du focus arrete la session au lieu de la figer.
        val guard = AudioFocusGuard(this) { loop?.requestStop() }

        val session = SessionLoop(
            gateway = AnkiDroidGateway(contentResolver) { settings.englishDeckIds },
            tutor = AnthropicTutor(
                apiKey = settings.apiKey,
                judgeModel = if (settings.fastJudge) {
                    AnthropicTutor.FAST_JUDGE_MODEL
                } else {
                    AnthropicTutor.MODEL
                },
            ),
            speaker = FocusAwareSpeaker(tts, guard),
            listener = if (settings.debugTranscripts) {
                DebugListener.shared
            } else {
                AndroidListener(this, settings.accentAnglais.locale)
            },
            journal = JsonlJournal(File(filesDir, "journal.jsonl")),
            clock = object : Clock {
                override fun nowMs() = System.currentTimeMillis()
            },
            writeMode = settings.writeMode,
            correctionEnFrancais = settings.correctionEnFrancais,
        )
        loop = session

        val mirror = scope.launch { session.state.collect { holder.publish(it) } }
        val mirrorProgress = scope.launch {
            session.progress.collect { holder.publishProgress(it.first, it.second) }
        }

        val outcome = runCatching {
            guard.withFocus { session.run(settings.deckIds, settings.cardLimit) }
        }
        mirror.cancel()
        mirrorProgress.cancel()
        loop = null

        holder.finish(
            outcome.fold(
                onSuccess = { SessionOutcome.Completed(it) },
                onFailure = { SessionOutcome.Failed(it.message ?: "erreur inattendue") },
            ),
        )
        stopSelf()
    }

    /**
     * Arret demande par l'utilisateur. On ne coupe pas la coroutine : la note de la carte
     * qu'il vient de repondre est en attente d'ecriture, et l'annulation la jetterait.
     * Le moteur recoit une demande d'arret et vide ce qu'il a en main avant de rendre.
     */
    private fun stopSession() {
        val running = loop
        if (running == null) {
            speaker?.stop()
            job?.cancel()
            job = null
            stopSelf()
            return
        }
        running.requestStop()
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
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
