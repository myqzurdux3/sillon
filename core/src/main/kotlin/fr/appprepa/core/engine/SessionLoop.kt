package fr.appprepa.core.engine

import fr.appprepa.core.model.SessionStats
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.AnkiGateway
import fr.appprepa.core.ports.Clock
import fr.appprepa.core.ports.Journal
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import fr.appprepa.core.ports.Speaker
import fr.appprepa.core.ports.Tutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    private var session = Session(writeMode = writeMode)
    private val queue = ArrayDeque<Event>()
    private val prefetchJobs = mutableListOf<Job>()

    suspend fun run(deckId: Long?, limit: Int): SessionStats = coroutineScope {
        session = Session(writeMode = writeMode)
        queue.clear()
        queue += Event.Start(deckId, limit)

        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            val reduction = ReviewSessionEngine.reduce(session, event, clock.nowMs())
            session = reduction.session
            _state.value = session.state

            for (effect in reduction.effects) {
                perform(effect, this)
            }

            if (session.state is SessionState.Finished || session.state is SessionState.Failed) {
                break
            }
        }

        prefetchJobs.forEach { it.cancel() }
        prefetchJobs.clear()
        (session.state as? SessionState.Finished)?.stats ?: session.stats
    }

    private suspend fun perform(effect: Effect, scope: CoroutineScope) {
        when (effect) {
            is Effect.LoadCards -> queue += runCatching {
                Event.CardsLoaded(gateway.dueCards(effect.deckId, effect.limit))
            }.getOrElse { Event.Fatal(it.message ?: "lecture AnkiDroid impossible") }

            is Effect.Speak -> {
                speaker.speak(effect.text)
                queue += Event.SpeechFinished
            }

            is Effect.Listen -> queue += when (val result = listener.listen(effect.timeoutMs)) {
                is ListenResult.Transcript -> Event.Heard(result.text)
                ListenResult.Silence -> Event.HeardNothing
                is ListenResult.Failure -> Event.HeardNothing
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

            is Effect.Commit -> if (writeMode == WriteMode.WRITE_THROUGH) {
                runCatching {
                    gateway.answer(
                        effect.pending.card.noteId,
                        effect.pending.card.cardOrd,
                        effect.pending.ease,
                        effect.pending.timeTakenMs,
                    )
                }
                Unit
            } else {
                Unit
            }

            is Effect.Record -> journal.record(
                if (writeMode == WriteMode.WRITE_THROUGH) {
                    effect.entry
                } else {
                    effect.entry.copy(committedEase = null, mode = WriteMode.JOURNAL_ONLY)
                },
            )

            Effect.Finish -> Unit
        }
    }
}
