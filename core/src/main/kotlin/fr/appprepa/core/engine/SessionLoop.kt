package fr.appprepa.core.engine

import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.SessionStats
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.AnkiGateway
import fr.appprepa.core.ports.Clock
import fr.appprepa.core.ports.Journal
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import fr.appprepa.core.ports.Speaker
import fr.appprepa.core.ports.Tutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Traduit chaque effet emis par le moteur en appel de port, et chaque resultat de port en
 * evenement. C'est la seule piece de `:core` qui connait les coroutines.
 */
class SessionLoop(
    private val gateway: AnkiGateway,
    private val tutor: Tutor,
    private val speaker: Speaker,
    private val listener: Listener,
    private val journal: Journal,
    private val clock: Clock,
    private val writeMode: WriteMode,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Rang de la carte en cours et total : « carte 7 sur 32 ». */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private var session = Session(writeMode = writeMode)
    private val queue = ArrayDeque<Event>()
    private val prefetchJobs = mutableListOf<Job>()

    @Volatile
    private var stopRequested = false
    private var writeFailures = 0

    /**
     * Volatile : [requestStop] est appele depuis l'exterieur de la boucle — le bouton
     * « Arreter », et la perte definitive du focus audio, chacun sur son propre thread.
     * Une lecture perimee ici laisserait l'ecoute en cours aller jusqu'a son delai.
     */
    @Volatile
    private var currentListen: Deferred<ListenResult>? = null

    /**
     * Arret propre demande de l'exterieur. Annuler la coroutine suffirait a rendre la main,
     * mais jetterait la note de la carte qu'on vient de repondre : elle est en attente
     * d'ecriture, et seule une fin propre la vide.
     */
    fun requestStop() {
        stopRequested = true
        speaker.stop()
        currentListen?.cancel()
    }

    suspend fun run(deckIds: Set<Long>, limit: Int): SessionStats = coroutineScope {
        session = Session(writeMode = writeMode)
        stopRequested = false
        writeFailures = 0
        queue.clear()
        queue += Event.Start(deckIds, limit)

        while (queue.isNotEmpty()) {
            val queued = queue.removeFirst()
            val event = if (stopRequested && queued != Event.StopRequested) {
                queue.clear()
                Event.StopRequested
            } else {
                queued
            }
            val reduction = ReviewSessionEngine.reduce(session, event, clock.nowMs())
            session = reduction.session
            _state.value = session.state
            _progress.value = session.position to session.total

            for (effect in reduction.effects) {
                perform(effect, this)
            }

            if (session.state is SessionState.Finished || session.state is SessionState.Failed) {
                break
            }
        }

        prefetchJobs.forEach { it.cancel() }
        prefetchJobs.clear()
        val stats = (session.state as? SessionState.Finished)?.stats ?: session.stats
        stats.copy(writeFailures = writeFailures)
    }

    /** En mode journal, rien ne part dans Anki : le journal ne doit pas dire l'inverse. */
    private fun normalized(entry: JournalRecord): JournalRecord =
        if (writeMode == WriteMode.WRITE_THROUGH) {
            entry
        } else {
            entry.copy(committedEase = null, mode = WriteMode.JOURNAL_ONLY)
        }

    private fun trace(entry: JournalRecord, ecriture: Result<Unit>?): JournalRecord {
        val echec = ecriture?.exceptionOrNull() ?: return normalized(entry)
        return entry.copy(
            committedEase = null,
            note = "AnkiDroid a refuse la note : ${echec.message ?: "raison inconnue"}",
        )
    }

    private suspend fun perform(effect: Effect, scope: CoroutineScope) {
        when (effect) {
            is Effect.LoadCards -> queue += runCatching {
                Event.CardsLoaded(gateway.dueCards(effect.deckIds, effect.limit))
            }.getOrElse { Event.Fatal(it.message ?: "lecture AnkiDroid impossible") }

            is Effect.Speak -> {
                if (!stopRequested) speaker.speak(effect.text)
                queue += Event.SpeechFinished
            }

            is Effect.Listen -> {
                val awaited = scope.async { listener.listen(effect.timeoutMs) }
                currentListen = awaited
                val result = try {
                    awaited.await()
                } catch (cancelled: CancellationException) {
                    ListenResult.Silence
                } finally {
                    currentListen = null
                }
                queue += when (result) {
                    is ListenResult.Transcript -> Event.Heard(result.text)
                    ListenResult.Silence -> Event.HeardNothing
                    // Un echec technique n'est pas un silence : le confondre ferait
                    // defiler la session entiere en notant tout « a revoir ».
                    is ListenResult.Failure -> Event.ListenFailed(result.cause)
                }
            }

            is Effect.Reformulate ->
                if (session.state is SessionState.Preparing) {
                    // Bloquant : rien a dire tant que la question n'existe pas.
                    queue += runCatching {
                        Event.Reformulated(
                            effect.card,
                            tutor.reformulate(effect.card, effect.memory),
                        )
                    }.getOrElse { Event.TutorFailed(it.message ?: "reformulation impossible") }
                } else {
                    // Prechargement : en tache de fond, pendant que l'utilisateur parle.
                    prefetchJobs += scope.launch {
                        runCatching { tutor.reformulate(effect.card, effect.memory) }
                            .onSuccess { queue += Event.Reformulated(effect.card, it) }
                    }
                }

            is Effect.Judge -> queue += runCatching {
                Event.Judged(
                    tutor.judge(
                        effect.card,
                        effect.expectedPoints,
                        effect.transcript,
                        effect.memory,
                    ),
                )
            }.getOrElse { Event.TutorFailed(it.message ?: "jugement impossible") }

            is Effect.Explain -> queue += runCatching {
                Event.Explained(tutor.explain(effect.card))
            }.getOrElse { Event.TutorFailed(it.message ?: "explication impossible") }

            // Ecriture et trace vont ensemble : c'est le seul moyen que le journal dise
            // ce qui est reellement parti dans Anki. Une ecriture refusee et journalisee
            // comme reussie rendrait le journal inutilisable, alors que c'est justement
            // le document qui sert a decider d'activer l'ecriture reelle.
            is Effect.Commit -> {
                val ecriture = if (writeMode == WriteMode.WRITE_THROUGH) {
                    runCatching {
                        gateway.answer(
                            effect.pending.card.noteId,
                            effect.pending.card.cardOrd,
                            effect.pending.ease,
                            effect.pending.timeTakenMs,
                        )
                    }
                } else {
                    null
                }
                if (ecriture?.isFailure == true) writeFailures++
                journal.record(trace(effect.pending.record, ecriture))
            }

            is Effect.Record -> journal.record(normalized(effect.entry))

            Effect.Finish -> Unit
        }
    }
}
