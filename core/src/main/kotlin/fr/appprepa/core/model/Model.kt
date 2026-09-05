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
    /**
     * La langue du paquet dont vient la carte. Elle regle le micro, la voix et le
     * vocabulaire des commandes pendant qu'on repond a cette carte.
     */
    val langue: Langue = Langue.FRANCAIS,
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
    /**
     * Ce que l'utilisateur voulait vraiment, quand ce n'etait pas repondre.
     *
     * Les listes de mots-cles echouaient des que la demande depassait quelques mots.
     * « j'ai pas bien entendu est-ce que tu peux répéter » etait note faux, tout comme
     * « puis-je corriger la question d'avant » : douze mots la ou le parseur en tolerait
     * quatre. Aucune liste ne rattrape ca, parce que personne ne parle en mots-cles. Le
     * modele qui juge deja la reponse le dit dans le meme appel, sans latence de plus.
     */
    val intention: Intention = Intention.REPONSE,
)

/** Ce que l'utilisateur voulait, quand il a parle. */
enum class Intention {
    /** Il a repondu a la question. Le cas de loin le plus frequent. */
    REPONSE,

    /**
     * Il repondait encore quand la transcription s'est arretee.
     *
     * La fenetre d'ecoute tranche apres deux secondes et demie de silence : mesure faite,
     * c'est ce qu'il faut pour tolerer une pause de reflexion de deux secondes sans
     * allonger toutes les autres reponses. Une pause plus longue coupe quand meme, et il
     * ne reste alors qu'un debut de phrase — « le théorème de Roll s'ap ». Le juger
     * reviendrait a noter faux quelqu'un qui reflechissait. Le modele, lui, voit
     * immediatement qu'une phrase est tronquee.
     */
    INCOMPLET,

    REPETER,
    PASSER,
    EXPLIQUER,
    REVENIR,
    ANNULER,
    ARRETER,
}

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
    /**
     * Le silence subi entre la fin de la reponse et le debut du verdict, en
     * millisecondes.
     *
     * C'est le seul temps d'attente que l'utilisateur vit reellement : il a fini de
     * parler et il attend. Le journal l'enregistre parce que « c'est trop lent » est
     * un ressenti, et qu'il a fallu trois series de corrections a l'aveugle avant de
     * comprendre que le probleme etait ailleurs. Nul quand la carte n'a pas ete jugee.
     */
    val attenteMs: Long? = null,
    /**
     * Ce que le modele a lu dans la phrase : une reponse, ou une demande. Enregistre
     * parce que des demandes notees fausses — « tu peux répéter » — n'etaient
     * reconnaissables dans l'ancien journal qu'en relisant les transcriptions une par une.
     */
    val intention: Intention? = null,
)
