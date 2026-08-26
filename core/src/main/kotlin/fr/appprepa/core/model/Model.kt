package fr.appprepa.core.model

/** Une carte due, telle que lue dans AnkiDroid. */
data class ReviewCard(
    val noteId: Long,
    val cardOrd: Int,
    val deckName: String,
    /** `question_simple` : sans CSS ni HTML. */
    val question: String,
    /** `answer_pure` : sans CSS, sans reprise du recto. */
    val answer: String,
    /** Nombre de boutons de reponse, 2 a 4 selon la carte. */
    val buttonCount: Int,
    val hasMedia: Boolean,
)

enum class Verdict { CORRECT, PARTIEL, FAUX }

enum class Ease(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
    ;

    /**
     * Ramene la note dans l'intervalle reellement accepte par la carte.
     * Un [buttonCount] hors de 2..4 est traite comme 4 : mieux vaut une note
     * plausible qu'un plantage sur une valeur inattendue du provider.
     */
    fun clampTo(buttonCount: Int): Ease {
        val max = if (buttonCount in 2..4) buttonCount else 4
        return entries.first { it.value == value.coerceIn(1, max) }
    }

    companion object {
        fun fromVerdict(verdict: Verdict): Ease = when (verdict) {
            Verdict.FAUX -> AGAIN
            Verdict.PARTIEL -> HARD
            Verdict.CORRECT -> GOOD
        }

        fun fromValue(value: Int): Ease? = entries.firstOrNull { it.value == value }
    }
}

/** Question orale produite par le LLM a partir de la carte. */
data class ReformulatedQuestion(
    val question: String,
    val expectedPoints: List<String>,
)

/** Jugement d'une reponse parlee. */
data class Judgement(
    val verdict: Verdict,
    val ease: Ease,
    val spokenFeedback: String,
    val formulationNote: String?,
    val topic: String?,
)

/** Contexte reinjecte dans les prompts, de taille bornee. */
data class SessionMemory(
    val missedTopics: List<String> = emptyList(),
    val formulationNotes: List<String> = emptyList(),
    val answered: Int = 0,
    val correct: Int = 0,
)

data class SessionStats(
    val answered: Int = 0,
    val correct: Int = 0,
    val skipped: Int = 0,
    val committed: Int = 0,
    /** Notes qu'AnkiDroid a refusees. Une ecriture perdue en silence ne s'invente pas. */
    val writeFailures: Int = 0,
)

enum class WriteMode { JOURNAL_ONLY, WRITE_THROUGH }

/** Une ligne du journal, ecrite pour chaque carte traitee. */
data class JournalRecord(
    val atMs: Long,
    val noteId: Long,
    val cardOrd: Int,
    val deckName: String,
    val question: String,
    val transcript: String,
    val proposedEase: Ease?,
    val committedEase: Ease?,
    val verdict: Verdict?,
    val mode: WriteMode,
    val note: String? = null,
)
