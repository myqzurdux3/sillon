package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import java.text.Normalizer

sealed interface VoiceCommand {
    data class Correct(val ease: Ease) : VoiceCommand
    data object Repeat : VoiceCommand
    data object Skip : VoiceCommand
    data object Explain : VoiceCommand
    data object Undo : VoiceCommand
    data object Stop : VoiceCommand
    data object None : VoiceCommand
}

object VoiceCommandParser {

    /** Mots que l'on retire avant de comparer : ils n'ajoutent pas de sens. */
    private val FILLER = setOf(
        "c", "cetait", "cest", "etait", "est", "ca", "s", "il", "sil", "te", "plait",
        "la", "le", "les", "de", "du", "on", "met", "mets", "je", "dirais", "plutot", "alors",
    )

    private val EXACT: List<Pair<Set<String>, VoiceCommand>> = listOf(
        setOf("encore", "a revoir", "revoir", "rate", "rater", "non") to
            VoiceCommand.Correct(Ease.AGAIN),
        setOf("difficile", "dur", "durs", "moyen") to VoiceCommand.Correct(Ease.HARD),
        setOf("bien", "correct", "ok", "oui", "bon") to VoiceCommand.Correct(Ease.GOOD),
        setOf("facile", "tres facile", "evident") to VoiceCommand.Correct(Ease.EASY),
        setOf("repete", "repeter", "pardon", "quoi", "redis") to VoiceCommand.Repeat,
        setOf("passe", "passer", "suivante", "suivant", "saute") to VoiceCommand.Skip,
        setOf(
            "explique", "expliquer", "je seche", "seche", "je ne sais pas",
            "ne sais pas", "sais pas", "aucune idee",
        ) to VoiceCommand.Explain,
        setOf("annule", "annuler", "reviens", "retour") to VoiceCommand.Undo,
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
