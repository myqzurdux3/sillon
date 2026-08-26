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

object VoiceCommandParser {

    /** Mots que l'on retire avant de comparer : ils n'ajoutent pas de sens. */
    private val FILLER = setOf(
        "c", "cetait", "cest", "etait", "est", "ca", "s", "il", "sil", "te", "plait",
        "la", "le", "les", "de", "du", "on", "met", "mets", "je", "dirais", "plutot", "alors",
        "moi", "un", "peu", "stp",
        // Elisions : « carte d'avant » se normalise en « carte d avant ».
        "d", "l", "n", "y",
    )

    private val EXACT: List<Pair<Set<String>, VoiceCommand>> = listOf(
        setOf(
            "encore", "a revoir", "revoir", "rate", "rater", "non",
            // « faux » et « mauvais » manquaient : le plus naturel apres un verdict trop
            // genereux tombait dans le vide et validait la note proposee.
            "faux", "mauvais", "nul", "pas bon",
        ) to VoiceCommand.Correct(Ease.AGAIN),
        setOf("difficile", "dur", "durs", "moyen", "trop dur", "penible") to
            VoiceCommand.Correct(Ease.HARD),
        setOf("bien", "correct", "ok", "oui", "bon") to VoiceCommand.Correct(Ease.GOOD),
        setOf("facile", "tres facile", "evident") to VoiceCommand.Correct(Ease.EASY),
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

    fun parse(transcript: String): VoiceCommand {
        val normalized = normalize(transcript)
        if (normalized.isEmpty()) return VoiceCommand.None

        val stripped = normalized.split(' ')
            .filter { it.isNotEmpty() && it !in FILLER }
            .joinToString(" ")
        if (stripped.isEmpty()) return VoiceCommand.None

        return EXACT.firstOrNull { (phrases, _) -> stripped in phrases }?.second
            ?: VoiceCommand.None
    }

    /** Minuscules, accents retires, ponctuation retiree, espaces normalises. */
    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
