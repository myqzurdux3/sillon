package fr.appprepa.core.engine

import fr.appprepa.core.memory.SessionMemoryBuilder
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.Langue
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Usability
import fr.appprepa.core.model.Intention
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.text.MathSpeech
import fr.appprepa.core.text.Phrases
import fr.appprepa.core.text.Utterance
import fr.appprepa.core.ports.ListenKind
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

    // --- les trois langues d'une session ---------------------------------
    //
    // La carte, la correction, et l'application. Les melanger dans un meme enonce est le
    // seul defaut qu'on ne peut pas rattraper : une synthese vocale ne parle qu'une langue
    // a la fois, et lire un verso anglais avec une voix francaise le rend inintelligible.

    /** La langue de la carte : sa question, et le micro pendant qu'on y repond. */
    private fun langueCarte(inFlight: CardInFlight): Langue = inFlight.card.langue

    /**
     * La langue du verdict, et donc de la fenetre de correction qui le suit : c'est dans
     * cette langue que l'utilisateur dira « bien » ou « good ».
     */
    private fun langueVerdict(session: Session, inFlight: CardInFlight): Langue =
        if (session.correctionEnFrancais) Langue.FRANCAIS else inFlight.card.langue

    fun reduce(session: Session, event: Event, nowMs: Long): Reduction = when (event) {
        is Event.Start -> Reduction(
            session.copy(state = SessionState.Loading),
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
        // Une carte s'ecarte parce qu'il n'y a rien a lire, pas parce qu'un fichier est
        // joint. L'ancienne regle — « la carte porte un media » — retirait 96 % de la
        // collection reelle, dont des cartes entierement textuelles.
        val motifs = event.cards.associateWith { Usability.raison(it) }
        val usable = event.cards.filter { motifs[it] == null }
        val skipEffects = event.cards.mapNotNull { card ->
            motifs[card]?.let { Effect.Record(skippedRecord(session, card, nowMs, it)) }
        }
        val skipped = event.cards.size - usable.size
        val afterSkips = session.copy(
            stats = session.stats.copy(skipped = session.stats.skipped + skipped),
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
                session.copy(state = SessionState.Asking(inFlight), retriedAnswer = false, askedToContinue = false),
                listOf(Effect.Speak(event.question.question, langueCarte(inFlight))),
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
                    // Le prechargement passe en premier : la boucle execute les effets en
                    // serie et l'ecoute bloque. Dans l'autre ordre, la reformulation de la
                    // carte suivante n'aurait pas lieu pendant que l'utilisateur parle.
                    listOfNotNull(
                        prefetchEffect,
                        Effect.Listen(
                            ListenKind.ANSWER,
                            ANSWER_TIMEOUT_MS,
                            langueCarte(state.inFlight),
                        ),
                    ),
                )
            }

            is SessionState.Revisiting ->
                if (state.explaining) {
                    // L'explication vient d'etre enoncee : on referme et on reprend.
                    resumeAfterRevisit(session, state, nowMs)
                } else {
                    Reduction(
                        session,
                        listOf(
                            Effect.Listen(
                                ListenKind.CORRECTION,
                                REVISIT_TIMEOUT_MS,
                                state.target.card.langue,
                            ),
                        ),
                    )
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
                        correctionTimeout(state.assessment),
                        langueVerdict(session, state.inFlight),
                    ),
                ),
            )

            // Un enonce qui n'a pas change d'etat — un refus, une repetition — doit rendre
            // l'oreille. Sans effet, la boucle vide sa file et la session s'arrete en
            // silence au milieu du trajet.
            is SessionState.Listening -> Reduction(
                session,
                listOf(
                    Effect.Listen(
                        ListenKind.ANSWER,
                        ANSWER_TIMEOUT_MS,
                        langueCarte(state.inFlight),
                    ),
                ),
            )

            is SessionState.AwaitingCorrection -> Reduction(
                session,
                listOf(
                    Effect.Listen(
                        ListenKind.CORRECTION,
                        correctionTimeout(state.assessment),
                        langueVerdict(session, state.inFlight),
                    ),
                ),
            )

            else -> Reduction(session, emptyList())
        }

    /** Dicter sa propre note prend plus de temps que corriger celle qu'on vient d'entendre. */
    private fun correctionTimeout(assessment: Assessment): Long =
        if (assessment is Assessment.SelfGrade) SELF_GRADE_TIMEOUT_MS else CORRECTION_TIMEOUT_MS

    private fun onHeard(session: Session, event: Event.Heard, nowMs: Long): Reduction {
        // Entendre quoi que ce soit prouve que le micro marche, dans n'importe quelle
        // fenetre : le compteur d'echecs ne doit pas traverser toute la session.
        val entendu = session.copy(listenFailures = 0)
        return when (val state = entendu.state) {
            is SessionState.Listening -> onAnswerHeard(entendu, state, event.transcript, nowMs)
            is SessionState.AwaitingCorrection ->
                onCorrectionHeard(entendu, state, event.transcript, nowMs)
            is SessionState.Revisiting -> onRevisitHeard(entendu, state, event.transcript, nowMs)
            else -> Reduction(entendu, emptyList())
        }
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
            Reduction(session.copy(state = state), listOf(Effect.Explain(target.card, langueVerdict(session, inFlight))))
        } else {
            Reduction(
                session.copy(state = state),
                listOf(
                    Effect.Speak(
                        Phrases.cartePrecedente(
                            target.card.langue,
                            MathSpeech.verbalize(target.card.question),
                        ),
                        target.card.langue,
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
        // La parenthese a recite la carte precedente : c'est dans sa langue que
        // l'utilisateur repond, pas dans celle de la carte en cours.
    ): Reduction = when (
        val command = VoiceCommandParser.parse(transcript, state.target.card.langue)
    ) {
        is VoiceCommand.Correct -> resumeAfterRevisit(
            regrade(session, state.target, command.ease),
            state,
            nowMs,
        )

        VoiceCommand.Explain, VoiceCommand.RevisitExplain -> Reduction(
            session.copy(state = state.copy(explaining = true)),
            listOf(Effect.Explain(state.target.card, langueVerdict(session, state.inFlight))),
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
                    it.copy(
                        ease = bounded,
                        // En mode journal rien ne part dans Anki : l'annoncer ecrit serait
                        // un mensonge dans le seul document qui sert a juger l'application.
                        record = it.record.copy(committedEase = committed(session, bounded)),
                    )
                } else {
                    it
                }
            },
        )
    }

    /** La note reellement ecrite dans Anki, ou `null` quand le mode journal l'interdit. */
    private fun committed(session: Session, ease: Ease): Ease? =
        ease.takeIf { session.writeMode == WriteMode.WRITE_THROUGH }

    /** Referme la parenthese : la question en cours est reenoncee. */
    private fun resumeAfterRevisit(
        session: Session,
        state: SessionState.Revisiting,
        nowMs: Long,
    ): Reduction = Reduction(
        session.copy(state = SessionState.Asking(state.inFlight), retriedAnswer = false, askedToContinue = false),
        listOf(Effect.Speak(state.inFlight.question, langueCarte(state.inFlight))),
    )

    private fun onAnswerHeard(
        session: Session,
        state: SessionState.Listening,
        transcript: String,
        nowMs: Long,
    ): Reduction = when (VoiceCommandParser.parse(transcript, langueCarte(state.inFlight))) {
        VoiceCommand.Stop -> finish(session, nowMs)

        // Reposer la question remet le compteur de patience a zero : la relance et la
        // reprise valent pour la nouvelle tentative, pas pour celle qu'on vient d'effacer.
        VoiceCommand.Repeat -> Reduction(
            session.copy(
                state = SessionState.Asking(state.inFlight),
                retriedAnswer = false,
                askedToContinue = false,
            ),
            listOf(Effect.Speak(state.inFlight.question, langueCarte(state.inFlight))),
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
            listOf(Effect.Explain(state.inFlight.card, langueVerdict(session, state.inFlight))),
        )

        VoiceCommand.Revisit -> openRevisit(session, state.inFlight, explaining = false)
        VoiceCommand.RevisitExplain -> openRevisit(session, state.inFlight, explaining = true)

        // « annule » vise toujours la carte precedente, y compris au milieu de la reponse.
        // L'etat ne bouge pas : la fin de l'enonce rouvre l'ecoute sur la meme question.
        VoiceCommand.Undo -> dropPending(session, "annulee a la voix")

        // Une correction de note n'a pas de sens pendant la reponse : c'est du texte.
        else -> answerGiven(session, state, Utterance.join(state.partial, transcript), nowMs)
    }

    /**
     * La reponse est complete, ou bien elle s'arrete sur un mot qui appelle une suite.
     * Dans ce second cas on redemande une fois, plutot que de juger une demi-phrase :
     * chercher ses mots au volant est normal, et la reconnaissance vocale rend la main
     * sur un silence sans savoir si c'etait la fin.
     */
    private fun answerGiven(
        session: Session,
        state: SessionState.Listening,
        transcript: String,
        nowMs: Long,
    ): Reduction {
        if (!session.askedToContinue &&
            Utterance.looksUnfinished(transcript, langueCarte(state.inFlight))
        ) {
            return Reduction(
                session.copy(
                    state = SessionState.Listening(state.inFlight, transcript),
                    askedToContinue = true,
                ),
                // L'etat ne change pas : la fin de cet enonce rouvre l'ecoute.
                listOf(
                    Effect.Speak(
                        Phrases.continue_(langueCarte(state.inFlight)),
                        langueCarte(state.inFlight),
                    ),
                ),
            )
        }

        return if (session.degraded) {
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
                        langueCorrection = langueVerdict(session, state.inFlight),
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
    ): Reduction = when (
        val command =
            VoiceCommandParser.parse(transcript, langueVerdict(session, state.inFlight))
    ) {
        is VoiceCommand.Correct -> settle(session, state, command.ease, nowMs)

        // Redire le verdict, sans le figer : l'etat ne bouge pas, donc la fin de l'enonce
        // rouvre la meme fenetre de correction.
        VoiceCommand.Repeat -> Reduction(
            session,
            listOf(
                Effect.Speak(
                    verdictText(session, state),
                    langueVerdict(session, state.inFlight),
                ),
            ),
        )

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
        // La carte en cours, elle, se fige normalement.
        VoiceCommand.Undo -> {
            val jetee = dropPending(session, "annulee a la voix")
            val settled = settle(jetee.session, state, null, nowMs)
            Reduction(settled.session, jetee.effects + settled.effects)
        }

        else -> settle(session, state, null, nowMs)
    }

    /**
     * Jette la note en attente sans rien ecrire dans Anki : la carte reste due. La trace
     * part au journal, sinon l'annulation ne laisserait rien a relire le soir.
     *
     * L'annulation est toujours dite a voix haute, y compris quand il n'y avait rien a
     * annuler : c'est cet enonce qui, une fois termine, rend l'oreille. Un effet muet
     * viderait la file de la boucle et arreterait la session sans un mot.
     */
    private fun dropPending(session: Session, why: String): Reduction {
        val dropped = session.pending.lastOrNull()
            ?: return Reduction(session, listOf(Effect.Speak("Il n'y a rien à annuler.")))
        return Reduction(
            session.copy(pending = session.pending.dropLast(1)),
            listOf(
                Effect.Record(dropped.record.copy(committedEase = null, note = why)),
                Effect.Speak("J'annule la note précédente."),
            ),
        )
    }

    /** Ce qui a ete annonce apres la reponse, pour pouvoir le redire tel quel. */
    private fun verdictText(session: Session, state: SessionState.AwaitingCorrection): String =
        when (val assessment = state.assessment) {
            is Assessment.Judged -> annonce(
                assessment.judgement.spokenFeedback,
                assessment.judgement.ease,
                langueVerdict(session, state.inFlight),
            )
            // L'auto-notation recite le verso : elle suit la langue de la carte, pas celle
            // de la correction. Un verso anglais lu par une voix francaise est perdu.
            is Assessment.SelfGrade ->
                selfGradeText(assessment.answerText, langueCarte(state.inFlight))
        }

    /** Le retour parle, suivi de la note proposee. */
    private fun annonce(feedback: String, ease: Ease, langue: Langue): String =
        "$feedback ${Phrases.jeMets(langue, ease)}"

    private fun onHeardNothing(session: Session, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Listening ->
                // Le silence apres une relance veut dire « j'ai fini » : ce qui a deja
                // ete entendu est une reponse, et la jeter serait la pire des reactions.
                if (state.partial.isNotEmpty()) {
                    answerGiven(session, state, state.partial, nowMs)
                } else if (!session.retriedAnswer) {
                    Reduction(
                        session.copy(
                            state = SessionState.Asking(state.inFlight),
                            retriedAnswer = true,
                        ),
                        listOf(
                            Effect.Speak(
                                Phrases.jecoute(langueCarte(state.inFlight)),
                                langueCarte(state.inFlight),
                            ),
                        ),
                    )
                } else {
                    // Deux silences ne prouvent pas l'ignorance : ils prouvent que rien
                    // n'a ete entendu. Le micro rend regulierement une transcription vide
                    // sur cet appareil — cinq des douze revisions du journal —, et noter
                    // « a revoir » a chaque fois abime le calendrier Anki sur la foi d'une
                    // panne. On donne la reponse, puis on passe sans rien noter : la carte
                    // reste due, ce qui est exactement la verite de la situation.
                    val langue = langueCarte(state.inFlight)
                    advance(
                        session.copy(stats = session.stats.copy(skipped = session.stats.skipped + 1)),
                        listOf(
                            Effect.Record(
                                skippedRecord(
                                    session,
                                    state.inFlight.card,
                                    nowMs,
                                    "rien entendu, carte laissee due",
                                ),
                            ),
                            Effect.Speak(
                                Phrases.pasDeReponse(langue) + " " +
                                    MathSpeech.verbalize(state.inFlight.card.answer),
                                langue,
                            ),
                        ),
                        nowMs,
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
            listOf(
                Effect.Speak(
                    annonce(event.text, Ease.AGAIN, langueVerdict(session, state.inFlight)),
                    langueVerdict(session, state.inFlight),
                ),
            ),
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
                    degraded.copy(state = SessionState.Asking(inFlight), retriedAnswer = false, askedToContinue = false),
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
                    selfGradeText(inFlight.card.answer, inFlight.card.langue),
                    inFlight.card.langue,
                ),
            ),
        )

    /** Le verso enonce, suivi de la demande de note. Redit tel quel par « repete ». */
    private fun selfGradeText(answer: String, langue: Langue): String =
        MathSpeech.verbalize(answer) + " " + Phrases.autoNotation(langue)

    private fun onJudged(session: Session, event: Event.Judged, nowMs: Long): Reduction {
        val state = session.state as? SessionState.Judging ?: return Reduction(session, emptyList())

        // Ce n'etait peut-etre pas une reponse. Le modele vient de le dire dans le meme
        // appel : agir sur sa lecture plutot que sur une liste de mots-cles est la seule
        // facon de comprendre « attends j'ai pas bien entendu tu peux redire ».
        if (event.judgement.intention != Intention.REPONSE) {
            return surIntention(session, state, event.judgement.intention, nowMs)
        }

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
            listOf(
                Effect.Speak(
                    annonce(
                        bounded.spokenFeedback,
                        bounded.ease,
                        langueVerdict(session, state.inFlight),
                    ),
                    langueVerdict(session, state.inFlight),
                ),
            ),
        )
    }

    /** Renvoie au jugement une reponse qu'on a decide de ne plus attendre. */
    private fun juger(
        session: Session,
        state: SessionState.Judging,
        transcript: String,
    ): Reduction = Reduction(
        session,
        listOf(
            Effect.Judge(
                card = state.inFlight.card,
                expectedPoints = state.inFlight.expectedPoints,
                transcript = transcript,
                memory = session.memory,
                langueCorrection = langueVerdict(session, state.inFlight),
            ),
        ),
    )

    /**
     * L'utilisateur demandait quelque chose au lieu de repondre. On rejoue exactement le
     * chemin de la commande vocale correspondante : rien n'est note, et la carte en cours
     * n'est pas perdue.
     */
    private fun surIntention(
        session: Session,
        state: SessionState.Judging,
        intention: Intention,
        nowMs: Long,
    ): Reduction {
        val enVol = state.inFlight
        val enEcoute = SessionState.Listening(enVol)
        return when (intention) {
            // Reposer la question remet la patience a zero : la relance et la reprise
            // valent pour la nouvelle tentative, pas pour celle qu'on vient d'effacer.
            Intention.REPETER -> Reduction(
                session.copy(
                    state = SessionState.Asking(enVol),
                    retriedAnswer = false,
                    askedToContinue = false,
                ),
                listOf(Effect.Speak(enVol.question, langueCarte(enVol))),
            )

            Intention.PASSER -> advance(
                session.copy(stats = session.stats.copy(skipped = session.stats.skipped + 1)),
                listOf(
                    Effect.Record(skippedRecord(session, enVol.card, nowMs, "passee a la voix")),
                ),
                nowMs,
            )

            Intention.EXPLIQUER -> Reduction(
                session,
                listOf(Effect.Explain(enVol.card, langueVerdict(session, enVol))),
            )

            Intention.REVENIR -> openRevisit(session, enVol, explaining = false)

            // L'etat repasse en ecoute : la fin de l'enonce rouvre le micro sur la meme
            // question, au lieu de laisser la file de la boucle se vider en silence.
            Intention.ANNULER -> {
                val jetee = dropPending(session, "annulee a la voix")
                Reduction(jetee.session.copy(state = enEcoute), jetee.effects)
            }

            Intention.ARRETER -> finish(session, nowMs)

            // La phrase a ete tranchee pendant qu'il reflechissait. On garde le debut,
            // on relance, et la suite viendra s'y recoller. Une seule fois par carte :
            // au-dela, insister vaut moins que juger ce qu'on a.
            Intention.INCOMPLET ->
                if (session.askedToContinue) {
                    juger(session, state, state.transcript)
                } else {
                    Reduction(
                        session.copy(
                            state = SessionState.Listening(enVol, state.transcript),
                            askedToContinue = true,
                        ),
                        listOf(
                            Effect.Speak(
                                Phrases.continue_(langueCarte(enVol)),
                                langueCarte(enVol),
                            ),
                        ),
                    )
                }

            Intention.REPONSE -> Reduction(session, emptyList())
        }
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
            committedEase = committed(session, ease),
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
        // [Effect.Commit] ecrit et journalise d'un seul geste : c'est la seule facon que
        // le journal dise ce qui est reellement parti dans Anki, refus compris.
        val commitEffects = overflow.map { Effect.Commit(it) }

        val memory = judgement?.let { SessionMemoryBuilder.absorb(session.memory, it) }
            ?: session.memory

        val settled = session.copy(
            memory = memory,
            pending = kept,
            stats = session.stats.copy(
                answered = session.stats.answered + 1,
                correct = session.stats.correct +
                    if (judgement?.verdict == Verdict.CORRECT) 1 else 0,
                committed = session.stats.committed + commitEffects.size,
            ),
        )

        return advance(settled, commitEffects, nowMs)
    }

    /** Passe a la carte suivante, ou termine la session si la file est vide. */
    internal fun advance(session: Session, carried: List<Effect>, nowMs: Long): Reduction {
        val next = session.queue.firstOrNull()
            ?: return finish(session, nowMs, carried)

        val rest = session.queue.drop(1)

        // Le mode degrade ne vaut que pour la carte ou la panne s'est produite. Chaque
        // nouvelle carte retente le LLM : sans cela, une coupure de dix secondes
        // condamnerait tout le reste du trajet a la lecture brute. L'echec est immediat
        // quand le reseau est vraiment coupe, et borne par le delai du client sinon.
        val repart = session.copy(
            degraded = false,
            queue = rest,
            retriedAnswer = false,
            askedToContinue = false,
        )

        val ready = session.prefetch?.takeIf { session.prefetchFor == next.noteId }
        return if (ready != null) {
            val inFlight = CardInFlight(next, ready.question, ready.expectedPoints, nowMs)
            Reduction(
                repart.copy(
                    state = SessionState.Asking(inFlight),
                    prefetch = null,
                    prefetchFor = null,
                ),
                carried + Effect.Speak(ready.question),
            )
        } else {
            Reduction(
                repart.copy(state = SessionState.Preparing(next)),
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
        val flush = session.pending.map { Effect.Commit(it) }
        val stats = session.stats.copy(
            committed = session.stats.committed + flush.size,
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
