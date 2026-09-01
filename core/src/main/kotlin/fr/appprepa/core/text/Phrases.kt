package fr.appprepa.core.text

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Langue

/**
 * Ce que l'application dit d'elle-meme, dans la langue de la carte.
 *
 * Trois langues cohabitent dans une session et il faut les tenir distinctes :
 *
 * - la **carte** — sa question, et ce qui se dit pendant qu'on y repond ;
 * - la **correction** — le verdict, reglable, en francais par defaut ;
 * - l'**application** — « Aucune carte à réviser », toujours en francais.
 *
 * Seule la premiere vit ici. Elle compte parce qu'elle encadre le tour de parole : dire
 * « Continue, je t'écoute » en francais au milieu d'une reponse anglaise reouvre le micro
 * sur une phrase qui n'appartient pas a la langue qu'on vient de demander a l'utilisateur.
 */
object Phrases {

    /** La relance, quand la phrase s'arrete sur un mot qui appelle une suite. */
    fun continue_(langue: Langue): String = when (langue) {
        Langue.FRANCAIS -> "Continue, je t'écoute."
        Langue.ANGLAIS -> "Go on, I'm listening."
    }

    /** La reprise, quand le premier silence n'a rien donne. */
    fun jecoute(langue: Langue): String = when (langue) {
        Langue.FRANCAIS -> "Je t'écoute."
        Langue.ANGLAIS -> "I'm listening."
    }

    /** L'abandon apres deux silences : on donne la reponse et on note « a revoir ». */
    fun pasDeReponse(langue: Langue): String = when (langue) {
        Langue.FRANCAIS -> "Pas de réponse."
        Langue.ANGLAIS -> "No answer."
    }

    /** La question rappelee quand on rouvre une parenthese sur la carte precedente. */
    fun cartePrecedente(langue: Langue, question: String): String = when (langue) {
        Langue.FRANCAIS ->
            "Carte précédente : $question. Tu mets quoi, ou tu veux que j'explique ?"
        Langue.ANGLAIS ->
            "Previous card: $question. What do you give it, or shall I explain?"
    }

    /** La note annoncee, dans la langue de la correction. */
    fun jeMets(langue: Langue, ease: Ease): String = when (langue) {
        Langue.FRANCAIS -> "Je mets ${labelFr(ease)}."
        Langue.ANGLAIS -> "I'll mark it ${labelEn(ease)}."
    }

    /** Le verso enonce, suivi de la demande de note. Mode degrade. */
    fun autoNotation(langue: Langue): String = when (langue) {
        Langue.FRANCAIS -> "Tu mets à revoir, difficile, bien ou facile ?"
        Langue.ANGLAIS -> "Do you give it again, hard, good or easy?"
    }

    private fun labelFr(ease: Ease): String = when (ease) {
        Ease.AGAIN -> "à revoir"
        Ease.HARD -> "difficile"
        Ease.GOOD -> "bien"
        Ease.EASY -> "facile"
    }

    private fun labelEn(ease: Ease): String = when (ease) {
        Ease.AGAIN -> "again"
        Ease.HARD -> "hard"
        Ease.GOOD -> "good"
        Ease.EASY -> "easy"
    }
}
