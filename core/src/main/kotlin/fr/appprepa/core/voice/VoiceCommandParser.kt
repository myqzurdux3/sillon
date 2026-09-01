package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Langue
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
 * 1. La phrase entiere figure dans [Vocabulaire.exact] : c'est la reconnaissance d'origine.
 * 2. La phrase contient une tournure figee — « j'ai pas entendu », « I didn't catch that ».
 * 3. La phrase est courte et commence par un verbe de commande — « répète la question ».
 *
 * Les niveaux 2 et 3 existent parce que personne ne dit la commande nue au volant. « répète
 * la question » tombait dans le vide, partait au modele comme une reponse, se faisait juger
 * faux, et la carte defilait. Les notes (« bien », « faux »…) restent au niveau 1 : elles
 * tiennent en un mot, et les elargir ferait passer des reponses pour des notes.
 *
 * Le vocabulaire suit la langue de la carte, parce que le micro la suit aussi : sur une
 * carte anglaise, « répète » ne sera jamais transcrit — c'est « repeat » qui arrivera.
 */
object VoiceCommandParser {

    /** Une tournure figee est plus specifique : elle supporte un peu d'enveloppe. */
    private const val MAX_MOTS_PHRASE = 4

    /**
     * Un vocabulaire complet pour une langue. Les quatre listes jouent des roles distincts
     * et ne sont pas interchangeables : voir la documentation de l'objet.
     */
    private data class Vocabulaire(
        /** Mots retires avant comparaison : ils n'ajoutent pas de sens. */
        val filler: Set<String>,
        /** Phrases reconnues en entier, et elles seules. Les notes vivent ici. */
        val exact: List<Pair<Set<String>, VoiceCommand>>,
        /** Tournures figees, cherchees n'importe ou dans une phrase courte. */
        val phrases: List<Pair<List<String>, VoiceCommand>>,
        /** Verbes de commande, reconnus par prefixe et seulement en tete de phrase. */
        val verbes: List<Pair<List<String>, VoiceCommand>>,
        /**
         * Longueur maximale d'une phrase ou l'on croit un verbe de commande en tete.
         *
         * Deux en francais : « arrête tout » a besoin de la place, et l'objet des autres
         * commandes est deja du remplissage. Un seul en anglais, parce que les mots de
         * commande y sont aussi des noms courants — « a stop consonant », « back
         * formation » — et que leur objet, lui, est toujours du remplissage :
         * « skip this card » se reduit deja a « skip ».
         */
        val maxMotsVerbe: Int,
    )

    private val FRANCAIS = Vocabulaire(
        filler = setOf(
            "c", "cetait", "cest", "etait", "est", "ca", "s", "il", "sil", "te", "plait",
            "la", "le", "les", "de", "du", "on", "met", "mets", "je", "dirais", "plutot",
            "alors", "moi", "un", "peu", "stp",
            // Elisions : « carte d'avant » se normalise en « carte d avant ».
            "d", "l", "n", "y",
            // L'enveloppe des demandes reelles : « tu peux répéter la question, s'il te plaît ».
            "tu", "peux", "pourrais", "veux", "voudrais", "question", "attends", "attend",
            "j", "ai", "donc", "hein", "bah", "ben", "euh",
        ),
        exact = listOf(
            setOf(
                "encore", "a revoir", "revoir", "rate", "rater", "non",
                // « faux » et « mauvais » manquaient : le plus naturel apres un verdict trop
                // genereux tombait dans le vide et validait la note proposee.
                "faux", "mauvais", "nul", "pas bon", "pas juste", "rate ca",
            ) to VoiceCommand.Correct(Ease.AGAIN),
            setOf(
                "difficile", "dur", "durs", "moyen", "trop dur", "penible", "chaud",
                "moyennement",
            ) to VoiceCommand.Correct(Ease.HARD),
            setOf(
                "bien", "correct", "ok", "oui", "bon", "juste", "exact", "vrai",
                "bonne reponse",
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
        ),
        phrases = listOf(
            listOf("quoi deja", "explique precedente", "explique avant", "redis precedente") to
                VoiceCommand.RevisitExplain,
            listOf(
                "pas entendu", "pas compris", "pas saisi", "mal entendu", "encore une fois",
            ) to VoiceCommand.Repeat,
            listOf("sais pas", "sais plus", "aucune idee", "seche", "bloque") to
                VoiceCommand.Explain,
            listOf("carte avant", "carte precedente", "en arriere") to VoiceCommand.Revisit,
        ),
        verbes = listOf(
            listOf("repet", "redi", "redis", "reprend") to VoiceCommand.Repeat,
            listOf("passe", "passer", "suivant", "saute", "zappe") to VoiceCommand.Skip,
            listOf("expliqu", "seche", "bloque") to VoiceCommand.Explain,
            listOf("annul", "oublie") to VoiceCommand.Undo,
            listOf("revien", "retour", "precedent") to VoiceCommand.Revisit,
            listOf("stop", "arret", "arrete", "termin", "pause") to VoiceCommand.Stop,
        ),
        maxMotsVerbe = 2,
    )

    private val ANGLAIS = Vocabulaire(
        filler = setOf(
            "the", "a", "an", "it", "is", "was", "i", "you", "can", "could", "would",
            "please", "s", "m", "that", "this", "me", "my", "question", "card", "one",
            "well", "um", "uh", "so", "just", "wait", "hold", "let", "give",
        ),
        exact = setOf(
            // « again » est le libelle Anki de la note la plus basse : sur une correction,
            // c'est une note, jamais une demande de repetition. « say it again » l'est.
            setOf(
                "again", "wrong", "no", "nope", "bad", "missed", "missed it", "review",
                "not right", "incorrect",
            ) to VoiceCommand.Correct(Ease.AGAIN),
            setOf("hard", "difficult", "tough", "barely", "too hard") to
                VoiceCommand.Correct(Ease.HARD),
            setOf("good", "right", "correct", "ok", "okay", "yes", "yeah", "fine") to
                VoiceCommand.Correct(Ease.GOOD),
            setOf("easy", "very easy", "obvious", "too easy", "by heart") to
                VoiceCommand.Correct(Ease.EASY),
            setOf("repeat", "pardon", "sorry", "what", "come again") to VoiceCommand.Repeat,
            setOf("skip", "next", "pass", "move on") to VoiceCommand.Skip,
            setOf(
                "explain", "tell me", "no idea", "dunno", "stuck", "pass on this",
            ) to VoiceCommand.Explain,
            setOf("undo", "cancel", "forget it", "scratch that") to VoiceCommand.Undo,
            setOf(
                "go back", "back", "previous", "previous card", "last one", "before",
            ) to VoiceCommand.Revisit,
            setOf(
                "explain previous", "explain last one", "what was that",
                "what was previous",
            ) to VoiceCommand.RevisitExplain,
            // « done » manque exprès : « I'm done » termine une reponse, pas une session.
            setOf("stop", "pause", "quit", "finish", "end session") to VoiceCommand.Stop,
        ).toList(),
        phrases = listOf(
            listOf("what was that", "explain previous", "explain last", "what was last") to
                VoiceCommand.RevisitExplain,
            listOf(
                "say again", "didn t catch", "didn t hear", "did not hear", "not hear",
                "come again", "once more",
            ) to VoiceCommand.Repeat,
            listOf(
                "don t know", "do not know", "no idea", "dunno", "stuck", "not sure at all",
            ) to VoiceCommand.Explain,
            listOf("go back", "previous card", "last card") to VoiceCommand.Revisit,
        ),
        verbes = listOf(
            listOf("repeat", "resay") to VoiceCommand.Repeat,
            listOf("skip", "next") to VoiceCommand.Skip,
            listOf("explain") to VoiceCommand.Explain,
            listOf("undo", "cancel") to VoiceCommand.Undo,
            listOf("previous") to VoiceCommand.Revisit,
            listOf("stop", "quit", "pause") to VoiceCommand.Stop,
        ),
        maxMotsVerbe = 1,
    )

    private fun vocabulaire(langue: Langue): Vocabulaire = when (langue) {
        Langue.FRANCAIS -> FRANCAIS
        Langue.ANGLAIS -> ANGLAIS
    }

    fun parse(transcript: String, langue: Langue = Langue.FRANCAIS): VoiceCommand {
        val vocab = vocabulaire(langue)
        val normalized = normalize(transcript)
        if (normalized.isEmpty()) return VoiceCommand.None

        val mots = normalized.split(' ').filter { it.isNotEmpty() && it !in vocab.filler }
        if (mots.isEmpty()) return VoiceCommand.None
        val stripped = mots.joinToString(" ")

        // La phrase telle qu'elle a ete dite passe avant la phrase allegee. Sans cela, le
        // retrait des mots vides mange les tournures qui en sont faites : « what was that »
        // se reduisait a « what », c'est-a-dire a une demande de repetition.
        vocab.exact.firstOrNull { (phrases, _) -> normalized in phrases }?.let { return it.second }
        vocab.exact.firstOrNull { (phrases, _) -> stripped in phrases }?.let { return it.second }

        if (mots.size <= MAX_MOTS_PHRASE) {
            vocab.phrases.firstOrNull { (tournures, _) -> tournures.any { it in stripped } }
                ?.let { return it.second }
        }

        if (mots.size <= vocab.maxMotsVerbe) {
            vocab.verbes
                .firstOrNull { (prefixes, _) -> prefixes.any { mots.first().startsWith(it) } }
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
            .replace("œ", "oe")
            .replace("æ", "ae")
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
