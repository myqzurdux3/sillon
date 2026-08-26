package fr.appprepa.core.deck

import fr.appprepa.core.model.ReviewCard

object DeckMerge {

    /**
     * Entrelace les paquets une carte a la fois, en respectant l'ordre du planificateur
     * a l'interieur de chacun. Un paquet epuise cede simplement son tour.
     *
     * Les doublons sont ecartes : deux paquets peuvent renvoyer la meme carte si l'un
     * est le parent de l'autre et que le provider inclut les descendants.
     */
    fun interleave(perDeck: List<List<ReviewCard>>, limit: Int): List<ReviewCard> {
        val queues = perDeck.map { ArrayDeque(it) }.filter { it.isNotEmpty() }
        val seen = mutableSetOf<Pair<Long, Int>>()
        val merged = mutableListOf<ReviewCard>()

        while (merged.size < limit && queues.any { it.isNotEmpty() }) {
            for (queue in queues) {
                if (merged.size >= limit) break
                val card = queue.removeFirstOrNull() ?: continue
                if (seen.add(card.noteId to card.cardOrd)) merged += card
            }
        }
        return merged
    }
}
