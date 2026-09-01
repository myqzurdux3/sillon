package fr.appprepa.core.deck

import fr.appprepa.core.model.Langue
import java.util.Locale

/**
 * Quels paquets sont en anglais.
 *
 * Deux sources, dans cet ordre. Tant que l'utilisateur n'a rien coche, le nom du paquet
 * decide : un paquet qui s'appelle « Anglais » contient de l'anglais, et l'application
 * doit l'ecouter en anglais des la premiere session, sans reglage prealable. Des que
 * l'ecran de choix a ete valide une fois, c'est la coche qui fait foi et l'heuristique
 * se tait — y compris pour dire « aucun paquet en anglais », qui est une reponse
 * legitime que le nom ne saurait pas exprimer.
 *
 * L'heritage suit la hierarchie : cocher « Anglais » entraine « Anglais::irregular
 * verbs ». C'est la meme regle que le choix des paquets a reviser, et elle vaut ici pour
 * la meme raison — personne ne veut recocher trente sous-paquets.
 */
object DeckLanguage {

    /**
     * Les noms qui trahissent un paquet anglophone. La liste est volontairement courte :
     * une correspondance de trop bascule le micro dans la mauvaise langue, et cette
     * panne-la ne se voit pas, elle se confond avec des reponses fausses.
     */
    private val INDICES = listOf("anglais", "english")

    /** Les paquets devines anglophones, descendance comprise. */
    fun devines(decks: List<DeckInfo>): Set<Long> {
        val racines = decks.filter { deck ->
            val nom = deck.name.lowercase(Locale.ROOT)
            INDICES.any { indice -> nom.split(DeckInfo.SEPARATOR).any { it.contains(indice) } }
        }
        return racines.flatMap { DeckSelection.familyOf(decks, it) }.map { it.id }.toSet()
    }

    /**
     * Les paquets anglophones effectifs. [choisis] vaut `null` tant que l'utilisateur n'a
     * jamais valide l'ecran : on devine alors. Sinon on obeit, meme a un ensemble vide.
     */
    fun anglophones(decks: List<DeckInfo>, choisis: Set<Long>?): Set<Long> =
        choisis?.let { it intersect decks.map { deck -> deck.id }.toSet() } ?: devines(decks)

    /** La langue d'une carte, d'apres le paquet dont elle vient. */
    fun langueDe(deckId: Long, anglophones: Set<Long>): Langue =
        if (deckId in anglophones) Langue.ANGLAIS else Langue.FRANCAIS
}
