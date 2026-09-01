package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import java.text.Normalizer

sealed interface VoiceCommand {
    data class Correct(val ease: Ease) : VoiceCommand
    data object Repeat : VoiceCommand
    data object Skip : VoiceCommand
    data object Explain : VoiceCommand
    data object Undo : VoiceCommand

    /** Ouvre une parenthese sur la carte precedente pour la renoter. */
    data object Revisit : VoiceCommand

    /** Meme parenthese, mais directement sur l'explication. */
    data object RevisitExplain : VoiceCommand
    data object Stop : VoiceCommand
    data object None : VoiceCommand
}

/**
 * Reconnait une commande a trois niveaux, du plus sur au plus tolerant.
 *
 * 1. La phrase entiere figure dans [EXACT] : c'est la reconnaissance d'origine.
 * 2. La phrase contient une tournure figee de [PHRASES] — « j'ai pas entendu ».
 * 3. La phrase est courte et commence par un verbe de commande — « répète la question ».
 *
 * Les niveaux 2 et 3 existent parce que personne ne dit la commande nue au volant. « répète
 * la question » tombait dans le vide, partait au modele comme une reponse, se faisait juger
 * faux, et la carte defilait. Les notes (« bien », « faux »…) restent au niveau 1 : elles
 * tiennent en un mot, et les elargir ferait passer des reponses pour des notes.
 */
object VoiceCommandParser {

    /**
     * Un verbe de commande n'est cru qu'en tete d'une phrase de deux mots au plus.
     * Au-dela on est dans une reponse : « on passe à la limite » n'est pas « passe ».
     */
    private const val MAX_MOTS_VERBE = 2

    /** Une tournure figee est plus specifique : elle supporte un peu d'enveloppe. */
    private const val MAX_MOTS_PHRASE = 4

    /** Mots que l'on retire avant de comparer : ils n'ajoutent pas de sens. */
    private val FILLER = setOf(
        "c", "cetait", "cest", "etait", "est", "ca", "s", "il", "sil", "te", "plait",
        "la", "le", "les", "de", "du", "on", "met", "mets", "je", "dirais", "plutot", "alors",
        "moi", "un", "peu", "stp",
        // Elisions : « carte d'avant » se normalise en « carte d avant ».
        "d", "l", "n", "y",
        // L'enveloppe des demandes reelles : « tu peux répéter la question, s'il te plaît ».
        "tu", "peux", "pourrais", "veux", "voudrais", "question", "attends", "attend",
        "j", "ai", "donc", "hein", "bah", "ben", "euh",
    )

    private val EXACT: List<Pair<Set<String>, VoiceCommand>> = listOf(
        setOf(
            "encore", "a revoir", "revoir", "rate", "rater", "non",
            // « faux » et « mauvais » manquaient : le plus naturel apres un verdict trop
            // genereux tombait dans le vide et validait la note proposee.
            "faux", "mauvais", "nul", "pas bon", "pas juste", "rate ca",
        ) to VoiceCommand.Correct(Ease.AGAIN),
        setOf(
            "difficile", "dur", "durs", "moyen", "trop dur", "penible", "chaud", "moyennement",
        ) to VoiceCommand.Correct(Ease.HARD),
        setOf(
            "bien", "correct", "ok", "oui", "bon", "juste", "exact", "vrai", "bonne reponse",
        ) to VoiceCommand.Correct(Ease.GOOD),
        setOf(
            "facile", "tres facile", "evident", "trop facile", "par coeur",
        ) to VoiceCommand.Correct(Ease.EASY),
        setOf("repete", "repeter", "pardon", "quoi", "redis") to VoiceCommand.Repeat,
        setOf("passe", "passer", "suivante", "suivant", "saute") to VoiceCommand.Skip,
        setOf(
            "explique", "expliquer", "je seche", "seche", "je ne sais pas",
            "ne sais pas", "sais pas", "aucune idee",
        ) to VoiceCommand.Explain,
        // « annule » jette la note de la carte precedente ; « reviens » la reprend.
        setOf("annule", "annuler", "oublie") to VoiceCommand.Undo,
        setOf(
            "reviens", "revenir", "retour", "precedente", "precedent",
            "carte avant", "carte precedente", "avant",
        ) to VoiceCommand.Revisit,
        setOf(
            "explique precedente", "explique carte precedente", "quoi deja",
            "redis precedente", "rappelle precedente", "explique avant",
        ) to VoiceCommand.RevisitExplain,
        setOf("stop", "pause", "termine", "terminer", "fini", "arrete") to VoiceCommand.Stop,
    )

    /**
     * Tournures figees, cherchees n'importe ou dans une phrase courte. Elles sont assez
     * specifiques pour ne pas apparaitre au milieu d'une reponse de fiche.
     *
     * L'ordre compte : la parenthese sur la carte precedente se lit avant l'explication
     * simple, sinon « explique la précédente » se reduirait a « explique ».
     */
    private val PHRASES: List<Pair<List<String>, VoiceCommand>> = listOf(
        listOf("quoi deja", "explique precedente", "explique avant", "redis precedente") to
            VoiceCommand.RevisitExplain,
        listOf(
            "pas entendu", "pas compris", "pas saisi", "mal entendu", "encore une fois",
        ) to VoiceCommand.Repeat,
        listOf("sais pas", "sais plus", "aucune idee", "seche", "bloque") to VoiceCommand.Explain,
        listOf("carte avant", "carte precedente", "en arriere") to VoiceCommand.Revisit,
    )

    /**
     * Verbes de commande, reconnus par prefixe et seulement en tete de phrase. Le prefixe
     * couvre les conjugaisons entendues (« répète », « répéter », « répètes ») sans lister.
     */
    private val VERBES: List<Pair<List<String>, VoiceCommand>> = listOf(
        listOf("repet", "redi", "redis", "reprend") to VoiceCommand.Repeat,
        listOf("passe", "passer", "suivant", "saute", "zappe") to VoiceCommand.Skip,
        listOf("expliqu", "seche", "bloque") to VoiceCommand.Explain,
        listOf("annul", "oublie") to VoiceCommand.Undo,
        listOf("revien", "retour", "precedent") to VoiceCommand.Revisit,
        listOf("stop", "arret", "arrete", "termin", "pause") to VoiceCommand.Stop,
    )

    fun parse(transcript: String): VoiceCommand {
        val normalized = normalize(transcript)
        if (normalized.isEmpty()) return VoiceCommand.None

        val mots = normalized.split(' ').filter { it.isNotEmpty() && it !in FILLER }
        if (mots.isEmpty()) return VoiceCommand.None
        val stripped = mots.joinToString(" ")

        EXACT.firstOrNull { (phrases, _) -> stripped in phrases }?.let { return it.second }

        if (mots.size <= MAX_MOTS_PHRASE) {
            PHRASES.firstOrNull { (tournures, _) -> tournures.any { it in stripped } }
                ?.let { return it.second }
        }

        if (mots.size <= MAX_MOTS_VERBE) {
            VERBES.firstOrNull { (prefixes, _) -> prefixes.any { mots.first().startsWith(it) } }
                ?.let { return it.second }
        }

        return VoiceCommand.None
    }

    /**
     * Minuscules, ligatures deliees, accents retires, ponctuation retiree, espaces
     * normalises. Les ligatures viennent avant le reste : sans elles, « par cœur » se
     * reduisait a « par c ur » et n'etait plus reconnu.
     */
    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace("\u0153", "oe")
            .replace("\u00e6", "ae")
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
