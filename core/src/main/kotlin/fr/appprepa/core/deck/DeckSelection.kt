package fr.appprepa.core.deck

/**
 * Choix des paquets a reviser.
 *
 * Cocher un parent coche ses descendants, et l'application interroge ensuite chaque
 * paquet explicitement. On ne parie donc pas sur ce qu'AnkiDroid fait d'un identifiant
 * de parent : ce qui est coche est ce qui est revise.
 */
object DeckSelection {

    /** Ordre d'affichage : chemin alphabetique, donc enfants sous leur parent. */
    fun ordered(decks: List<DeckInfo>): List<DeckInfo> = decks.sortedBy { it.name.lowercase() }

    /**
     * Coche ou decoche [deckId], en entrainant ses descendants.
     * Le prefixe teste inclut le separateur : « Maths2 » n'est pas un enfant de « Maths ».
     */
    fun toggle(decks: List<DeckInfo>, selected: Set<Long>, deckId: Long): Set<Long> {
        val target = decks.firstOrNull { it.id == deckId } ?: return selected
        val family = familyOf(decks, target).map { it.id }.toSet()
        return if (deckId in selected) selected - family else selected + family
    }

    /** Un paquet et tout ce qui pend en dessous. */
    fun familyOf(decks: List<DeckInfo>, deck: DeckInfo): List<DeckInfo> {
        val prefix = deck.name + DeckInfo.SEPARATOR
        return decks.filter { it.id == deck.id || it.name.startsWith(prefix) }
    }

    /** Cartes dues dans la selection, pour l'annoncer avant de demarrer. */
    fun dueTotal(decks: List<DeckInfo>, selected: Set<Long>): Int =
        decks.filter { it.id in selected }.sumOf { it.dueCount }

    /**
     * Ce qu'il faut reellement interroger. Rien de coche veut dire tous les paquets :
     * refuser de demarrer serait penible au feu vert.
     */
    fun effective(decks: List<DeckInfo>, selected: Set<Long>): Set<Long> {
        val known = decks.map { it.id }.toSet()
        val kept = selected intersect known
        return kept.ifEmpty { known }
    }
}
