package fr.appprepa.core.engine

import fr.appprepa.core.memory.SessionMemoryBuilder
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.text.MathSpeech
import fr.appprepa.core.voice.VoiceCommand
import fr.appprepa.core.voice.VoiceCommandParser

object ReviewSessionEngine {

    const val ANSWER_TIMEOUT_MS = 15_000L

    /** Au volant, trois secondes ne suffisent pas pour reagir. */
    const val CORRECTION_TIMEOUT_MS = 7_000L

    /** Dans la parenthese, il faut le temps de se rappeler la carte d'avant. */
    const val REVISIT_TIMEOUT_MS = 10_000L

    /** Nombre de notes gardees en attente. A 1, on peut revenir d'une carte. */
    const val MAX_PENDING = 1

    /** Deux echecs d'ecoute de suite : on arrete plutot que de defiler a l'aveugle. */
    const val MAX_LISTEN_FAILURES = 2

    /** En mode degrade, l'utilisateur doit dicter sa note : il lui faut plus de temps. */
    const val SELF_GRADE_TIMEOUT_MS = 12_000L

    fun reduce(session: Session, event: Event, nowMs: Long): Reduction = when (event) {
        is Event.Start -> Reduction(
            session.copy(state = SessionState.Loading, deckIds = event.deckIds),
            listOf(Effect.LoadCards(event.deckIds, event.limit)),
        )

        is Event.CardsLoaded -> onCardsLoaded(session, event, nowMs)
        is Event.Reformulated -> onReformulated(session, event, nowMs)
        Event.SpeechFinished -> onSpeechFinished(session, nowMs)
        is Event.Heard -> onHeard(session, event, nowMs)
        Event.HeardNothing -> onHeardNothing(session, nowMs)
        is Event.ListenFailed -> onListenFailed(session, event, nowMs)
        is Event.Judged -> onJudged(session, event, nowMs)
        is Event.Explained -> onExplained(session, event, nowMs)
        is Event.TutorFailed -> onTutorFailed(session, nowMs)
        is Event.Fatal -> Reduction(
            session.copy(state = SessionState.Failed(event.reason)),
            listOf(Effect.Speak("Erreur : ${event.reason}"), Effect.Finish),
        )
        Event.StopRequested -> finish(session, nowMs)
    }

    // --- chargement -------------------------------------------------------

    private fun onCardsLoaded(session: Session, event: Event.CardsLoaded, nowMs: Long): Reduction {
        val (usable, skipped) = event.cards.partition { !it.hasMedia }
        val skipEffects = skipped.map { card ->
            Effect.Record(
                skippedRecord(session, card, nowMs, "carte a media, inutilisable en voiture"),
            )
        }
        val afterSkips = session.copy(
            stats = session.stats.copy(skipped = session.stats.skipped + skipped.size),
            total = usable.size,
        )

        val first = usable.firstOrNull()
            ?: return Reduction(
                afterSkips.copy(state = SessionState.Finished(afterSkips.stats)),
                skipEffects + listOf(Effect.Speak("Aucune carte à réviser."), Effect.Finish),
            )

        return Reduction(
            afterSkips.copy(state = SessionState.Preparing(first), queue = usable.drop(1)),
            skipEffects + Effect.Reformulate(first, afterSkips.memory),
        )
    }

    private fun onReformulated(
        session: Session,
        event: Event.Reformulated,
        nowMs: Long,
    ): Reduction {
        val state = session.state
        // La reformulation attendue pour la carte courante : on enonce.
        if (state is SessionState.Preparing && state.card.noteId == event.card.noteId) {
            val inFlight = CardInFlight(
                card = state.card,
                question = event.question.question,
                expectedPoints = event.question.expectedPoints,
                askedAtMs = nowMs,
            )
            return Reduction(
                session.copy(state = SessionState.Asking(inFlight), retriedAnswer = false),
                listOf(Effect.Speak(event.question.question)),
            )
        }
        // Sinon c'est un prechargement : on le range pour la carte suivante.
        return Reduction(
            session.copy(prefetch = event.question, prefetchFor = event.card.noteId),
            emptyList(),
        )
    }

    // --- enonce et ecoute -------------------------------------------------

    private fun onSpeechFinished(session: Session, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Asking -> {
                val prefetchEffect = session.queue.firstOrNull()
                    ?.takeIf { !session.degraded && session.prefetchFor != it.noteId }
                    ?.let { Effect.Reformulate(it, session.memory) }
                Reduction(
                    session.copy(state = SessionState.Listening(state.inFlight)),
                    listOfNotNull(
                        Effect.Listen(ListenKind.ANSWER, ANSWER_TIMEOUT_MS),
                        prefetchEffect,
                    ),
                )
            }

            is SessionState.Revisiting ->
                if (state.explaining) {
                    // L'explication vient d'etre enoncee : on referme et on reprend.
                    resumeAfterRevisit(session, state, nowMs)
                } else {
                    Reduction(session, listOf(Effect.Listen(ListenKind.CORRECTION, REVISIT_TIMEOUT_MS)))
                }

            is SessionState.SpeakingVerdict -> Reduction(
                session.copy(
                    state = SessionState.AwaitingCorrection(
                        state.inFlight,
                        state.assessment,
                        state.transcript,
                    ),
                ),
                listOf(
                    Effect.Listen(
                        ListenKind.CORRECTION,
                        if (state.assessment is Assessment.SelfGrade) {
                            SELF_GRADE_TIMEOUT_MS
                        } else {
                            CORRECTION_TIMEOUT_MS
                        },
                    ),
                ),
            )

            else -> Reduction(session, emptyList())
        }

    private fun onHeard(session: Session, event: Event.Heard, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Listening ->
                onAnswerHeard(session.copy(listenFailures = 0), state, event.transcript, nowMs)
            is SessionState.AwaitingCorrection ->
                onCorrectionHeard(session, state, event.transcript, nowMs)
            is SessionState.Revisiting -> onRevisitHeard(session, state, event.transcript, nowMs)
            else -> Reduction(session, emptyList())
        }

    // --- parenthese sur la carte precedente --------------------------------

    /**
     * Ouvre la parenthese : rappelle la carte visee, puis attend une note ou une demande
     * d'explication. La reponse en cours est abandonnee — si l'utilisateur revient en
     * arriere, c'est que la carte d'avant l'occupe davantage.
     */
    private fun openRevisit(
        session: Session,
        inFlight: CardInFlight,
        explaining: Boolean,
    ): Reduction {
        val target = session.pending.lastOrNull()
            ?: return Reduction(
                session,
                listOf(Effect.Speak("Pas de carte précédente à reprendre.")),
            )

        val state = SessionState.Revisiting(inFlight, target, explaining)
        return if (explaining) {
            Reduction(session.copy(state = state), listOf(Effect.Explain(target.card)))
        } else {
            Reduction(
                session.copy(state = state),
                listOf(
                    Effect.Speak(
                        "Carte précédente : ${MathSpeech.verbalize(target.card.question)}. " +
                            "Tu mets quoi, ou tu veux que j'explique ?",
                    ),
                ),
            )
        }
    }

    private fun onRevisitHeard(
        session: Session,
        state: SessionState.Revisiting,
        transcript: String,
        nowMs: Long,
    ): Reduction = when (val command = VoiceCommandParser.parse(transcript)) {
        is VoiceCommand.Correct -> resumeAfterRevisit(
            regrade(session, state.target, command.ease),
            state,
            nowMs,
        )

        VoiceCommand.Explain, VoiceCommand.RevisitExplain -> Reduction(
            session.copy(state = state.copy(explaining = true)),
            listOf(Effect.Explain(state.target.card)),
        )

        VoiceCommand.Undo -> {
            val cleared = session.copy(
                pending = session.pending.filterNot { it.card.noteId == state.target.card.noteId },
            )
            val trace = Effect.Record(
                state.target.record.copy(committedEase = null, note = "annulee a la voix"),
            )
            val resumed = resumeAfterRevisit(cleared, state, nowMs)
            Reduction(resumed.session, listOf(trace) + resumed.effects)
        }

        VoiceCommand.Stop -> finish(session, nowMs)

        else -> resumeAfterRevisit(session, state, nowMs)
    }

    /** Remplace la note d'une carte encore en attente d'ecriture. */
    private fun regrade(session: Session, target: PendingAnswer, ease: Ease): Session {
        val bounded = ease.clampTo(target.card.buttonCount)
        return session.copy(
            pending = session.pending.map {
                if (it.card.noteId == target.card.noteId && it.card.cardOrd == target.card.cardOrd) {
                    it.copy(ease = bounded, record = it.record.copy(committedEase = bounded))
                } else {
                    it
                }
            },
        )
    }

    /** Referme la parenthese : la question en cours est reenoncee. */
    private fun resumeAfterRevisit(
        session: Session,
        state: SessionState.Revisiting,
        nowMs: Long,
    ): Reduction = Reduction(
        session.copy(state = SessionState.Asking(state.inFlight), retriedAnswer = false),
        listOf(Effect.Speak(state.inFlight.question)),
    )

    private fun onAnswerHeard(
        session: Session,
        state: SessionState.Listening,
        transcript: String,
        nowMs: Long,
    ): Reduction = when (VoiceCommandParser.parse(transcript)) {
        VoiceCommand.Stop -> finish(session, nowMs)

        VoiceCommand.Repeat -> Reduction(
            session.copy(state = SessionState.Asking(state.inFlight)),
            listOf(Effect.Speak(state.inFlight.question)),
        )

        VoiceCommand.Skip -> advance(
            session.copy(stats = session.stats.copy(skipped = session.stats.skipped + 1)),
            listOf(
                Effect.Record(
                    skippedRecord(session, state.inFlight.card, nowMs, "passee a la voix"),
                ),
            ),
            nowMs,
        )

        VoiceCommand.Explain -> Reduction(
            session.copy(state = SessionState.Judging(state.inFlight, transcript)),
            listOf(Effect.Explain(state.inFlight.card)),
        )

        VoiceCommand.Revisit -> openRevisit(session, state.inFlight, explaining = false)
        VoiceCommand.RevisitExplain -> openRevisit(session, state.inFlight, explaining = true)

        // Une correction de note n'a pas de sens pendant la reponse : c'est du texte.
        else -> if (session.degraded) {
            speakAnswerForSelfGrade(session, state.inFlight, transcript)
        } else {
            Reduction(
                session.copy(state = SessionState.Judging(state.inFlight, transcript)),
                listOf(
                    Effect.Judge(
                        card = state.inFlight.card,
                        expectedPoints = state.inFlight.expectedPoints,
                        transcript = transcript,
                        memory = session.memory,
                    ),
                ),
            )
        }
    }

    private fun onCorrectionHeard(
        session: Session,
        state: SessionState.AwaitingCorrection,
        transcript: String,
        nowMs: Long,
    ): Reduction = when (val command = VoiceCommandParser.parse(transcript)) {
        is VoiceCommand.Correct -> settle(session, state, command.ease, nowMs)

        VoiceCommand.Revisit -> openRevisit(session, state.inFlight, explaining = false)
        VoiceCommand.RevisitExplain -> openRevisit(session, state.inFlight, explaining = true)

        VoiceCommand.Stop -> {
            val settled = settle(session, state, null, nowMs)
            if (settled.session.state is SessionState.Finished) {
                settled
            } else {
                val ended = finish(settled.session, nowMs)
                Reduction(ended.session, settled.effects + ended.effects)
            }
        }

        // « annule » vise la carte precedente : elle n'est jamais ecrite, elle reste due.
        VoiceCommand.Undo -> {
            val dropped = session.pending.lastOrNull()
            val cleared = session.copy(pending = session.pending.dropLast(1))
            val trace = dropped?.let {
                listOf(
                    Effect.Record(
                        it.record.copy(committedEase = null, note = "annulee a la voix"),
                    ),
                )
            } ?: emptyList()
            val settled = settle(cleared, state, null, nowMs)
            Reduction(settled.session, trace + settled.effects)
        }

        else -> settle(session, state, null, nowMs)
    }

    private fun onHeardNothing(session: Session, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Listening ->
                if (!session.retriedAnswer) {
                    Reduction(
                        session.copy(
                            state = SessionState.Asking(state.inFlight),
                            retriedAnswer = true,
                        ),
                        listOf(Effect.Speak("Je t'écoute.")),
                    )
                } else {
                    val giveUp = Judgement(
                        verdict = Verdict.FAUX,
                        ease = Ease.AGAIN,
                        spokenFeedback = "Pas de réponse. " +
                            MathSpeech.verbalize(state.inFlight.card.answer),
                        missed = emptyList(),
                        formulationNote = null,
                        topic = null,
                    )
                    Reduction(
                        session.copy(
                            state = SessionState.SpeakingVerdict(
                                state.inFlight,
                                Assessment.Judged(giveUp),
                                "",
                            ),
                        ),
                        listOf(Effect.Speak(giveUp.spokenFeedback)),
                    )
                }

            is SessionState.AwaitingCorrection -> settle(session, state, null, nowMs)
            is SessionState.Revisiting -> resumeAfterRevisit(session, state, nowMs)
            else -> Reduction(session, emptyList())
        }

    /**
     * Le micro ne repond pas. On previent, on retente une fois, puis on s'arrete : mieux
     * vaut une session interrompue qu'un trajet entier note « a revoir » en silence.
     */
    private fun onListenFailed(
        session: Session,
        event: Event.ListenFailed,
        nowMs: Long,
    ): Reduction {
        val failures = session.listenFailures + 1
        val inFlight = when (val state = session.state) {
            is SessionState.Listening -> state.inFlight
            is SessionState.AwaitingCorrection -> state.inFlight
            is SessionState.Revisiting -> state.inFlight
            else -> null
        }

        if (failures >= MAX_LISTEN_FAILURES || inFlight == null) {
            val reason = "micro indisponible : ${event.cause}"
            return Reduction(
                session.copy(state = SessionState.Failed(reason), listenFailures = failures),
                listOf(
                    Effect.Speak("Je n'arrive pas à t'entendre. J'arrête la session."),
                    Effect.Finish,
                ),
            )
        }

        return Reduction(
            session.copy(
                state = SessionState.Asking(inFlight),
                listenFailures = failures,
                retriedAnswer = true,
            ),
            listOf(Effect.Speak("Je ne t'entends pas. Vérifie le micro.")),
        )
    }

    private fun onExplained(session: Session, event: Event.Explained, nowMs: Long): Reduction {
        // Explication demandee dans une parenthese : on l'enonce, la reprise suit.
        (session.state as? SessionState.Revisiting)?.let { revisiting ->
            return Reduction(
                session.copy(state = revisiting.copy(explaining = true)),
                listOf(Effect.Speak(event.text)),
            )
        }

        val state = session.state as? SessionState.Judging ?: return Reduction(session, emptyList())
        val judgement = Judgement(
            verdict = Verdict.FAUX,
            ease = Ease.AGAIN,
            spokenFeedback = event.text,
            missed = emptyList(),
            formulationNote = null,
            topic = null,
        )
        return Reduction(
            session.copy(
                state = SessionState.SpeakingVerdict(
                    state.inFlight,
                    Assessment.Judged(judgement),
                    state.transcript,
                ),
            ),
            listOf(Effect.Speak("${event.text} Je mets à revoir.")),
        )
    }

    /**
     * Le LLM a lache. Deux situations distinctes :
     * - la reformulation a echoue, la question n'a pas encore ete posee : on lit le recto
     *   tel quel et la session reprend son cours normal, en degrade ;
     * - le jugement a echoue, la reponse a deja ete donnee : on lit le verso et
     *   l'utilisateur se note lui-meme.
     */
    private fun onTutorFailed(session: Session, nowMs: Long): Reduction {
        val degraded = session.copy(degraded = true)
        return when (val state = session.state) {
            is SessionState.Judging ->
                speakAnswerForSelfGrade(degraded, state.inFlight, state.transcript)

            is SessionState.Preparing -> {
                // Sans LLM pour verbaliser, le LaTeX de la carte doit etre traduit ici,
                // sinon la synthese prononce « backslash cos ».
                val spoken = MathSpeech.verbalize(state.card.question)
                val inFlight = CardInFlight(state.card, spoken, emptyList(), nowMs)
                Reduction(
                    degraded.copy(state = SessionState.Asking(inFlight), retriedAnswer = false),
                    listOf(Effect.Speak(spoken)),
                )
            }

            else -> Reduction(degraded, emptyList())
        }
    }

    private fun speakAnswerForSelfGrade(
        session: Session,
        inFlight: CardInFlight,
        transcript: String = "",
    ): Reduction =
        Reduction(
            session.copy(
                degraded = true,
                state = SessionState.SpeakingVerdict(
                    inFlight,
                    Assessment.SelfGrade(inFlight.card.answer),
                    transcript,
                ),
            ),
            listOf(
                Effect.Speak(
                    MathSpeech.verbalize(inFlight.card.answer) +
                        " Tu mets à revoir, difficile, bien ou facile ?",
                ),
            ),
        )

    private fun onJudged(session: Session, event: Event.Judged, nowMs: Long): Reduction {
        val state = session.state as? SessionState.Judging ?: return Reduction(session, emptyList())
        val bounded = event.judgement.copy(
            ease = event.judgement.ease.clampTo(state.inFlight.card.buttonCount),
        )
        return Reduction(
            session.copy(
                state = SessionState.SpeakingVerdict(
                    state.inFlight,
                    Assessment.Judged(bounded),
                    state.transcript,
                ),
            ),
            listOf(Effect.Speak("${bounded.spokenFeedback} Je mets ${label(bounded.ease)}.")),
        )
    }

    // --- validation d'une carte ------------------------------------------

    /**
     * Fige la note de la carte courante et ecrit celle de la carte precedente.
     * [override] force une note dictee par l'utilisateur ; `null` retient celle proposee.
     */
    internal fun settle(
        session: Session,
        state: SessionState.AwaitingCorrection,
        override: Ease?,
        nowMs: Long,
    ): Reduction {
        val assessment = state.assessment
        val judgement = (assessment as? Assessment.Judged)?.judgement
        val ease = (override ?: judgement?.ease ?: Ease.AGAIN)
            .clampTo(state.inFlight.card.buttonCount)

        val record = JournalRecord(
            atMs = nowMs,
            noteId = state.inFlight.card.noteId,
            cardOrd = state.inFlight.card.cardOrd,
            deckName = state.inFlight.card.deckName,
            question = state.inFlight.question,
            transcript = state.transcript,
            proposedEase = judgement?.ease,
            committedEase = if (session.writeMode == WriteMode.WRITE_THROUGH) ease else null,
            verdict = judgement?.verdict,
            mode = session.writeMode,
        )

        // La file deborde : la plus ancienne part a l'ecriture.
        val queued = session.pending + PendingAnswer(
            card = state.inFlight.card,
            ease = ease,
            timeTakenMs = nowMs - state.inFlight.askedAtMs,
            record = record,
        )
        val overflow = queued.dropLast(MAX_PENDING)
        val kept = queued.takeLast(MAX_PENDING)
        val commitEffects = overflow.flatMap {
            listOf(Effect.Commit(it), Effect.Record(it.record))
        }

        val memory = judgement?.let { SessionMemoryBuilder.absorb(session.memory, it) }
            ?: session.memory

        val settled = session.copy(
            memory = memory,
            pending = kept,
            stats = session.stats.copy(
                answered = session.stats.answered + 1,
                correct = session.stats.correct +
                    if (judgement?.verdict == Verdict.CORRECT) 1 else 0,
                committed = session.stats.committed + commitEffects.count { it is Effect.Commit },
            ),
        )

        return advance(settled, commitEffects, nowMs)
    }

    /** Passe a la carte suivante, ou termine la session si la file est vide. */
    internal fun advance(session: Session, carried: List<Effect>, nowMs: Long): Reduction {
        val next = session.queue.firstOrNull()
            ?: return finish(session, nowMs, carried)

        val rest = session.queue.drop(1)

        if (session.degraded) {
            val spoken = MathSpeech.verbalize(next.question)
            val inFlight = CardInFlight(next, spoken, emptyList(), nowMs)
            return Reduction(
                session.copy(
                    state = SessionState.Asking(inFlight),
                    queue = rest,
                    retriedAnswer = false,
                ),
                carried + Effect.Speak(spoken),
            )
        }

        val ready = session.prefetch?.takeIf { session.prefetchFor == next.noteId }
        return if (ready != null) {
            val inFlight = CardInFlight(next, ready.question, ready.expectedPoints, nowMs)
            Reduction(
                session.copy(
                    state = SessionState.Asking(inFlight),
                    queue = rest,
                    prefetch = null,
                    prefetchFor = null,
                    retriedAnswer = false,
                ),
                carried + Effect.Speak(ready.question),
            )
        } else {
            Reduction(
                session.copy(state = SessionState.Preparing(next), queue = rest),
                carried + Effect.Reformulate(next, session.memory),
            )
        }
    }

    /** Termine : la derniere note en attente est ecrite avant de rendre la main. */
    internal fun finish(
        session: Session,
        nowMs: Long,
        carried: List<Effect> = emptyList(),
    ): Reduction {
        val flush = session.pending.flatMap {
            listOf(Effect.Commit(it), Effect.Record(it.record))
        }
        val stats = session.stats.copy(
            committed = session.stats.committed + flush.count { it is Effect.Commit },
        )
        return Reduction(
            session.copy(state = SessionState.Finished(stats), pending = emptyList(), stats = stats),
            carried + flush + listOf(
                Effect.Speak(
                    "Session terminée. ${stats.answered} cartes, ${stats.correct} justes.",
                ),
                Effect.Finish,
            ),
        )
    }

    internal fun skippedRecord(
        session: Session,
        card: ReviewCard,
        nowMs: Long,
        why: String,
    ) = JournalRecord(
        atMs = nowMs,
        noteId = card.noteId,
        cardOrd = card.cardOrd,
        deckName = card.deckName,
        question = card.question,
        transcript = "",
        proposedEase = null,
        committedEase = null,
        verdict = null,
        mode = session.writeMode,
        note = why,
    )

    internal fun label(ease: Ease): String = when (ease) {
        Ease.AGAIN -> "à revoir"
        Ease.HARD -> "difficile"
        Ease.GOOD -> "bien"
        Ease.EASY -> "facile"
    }
}
