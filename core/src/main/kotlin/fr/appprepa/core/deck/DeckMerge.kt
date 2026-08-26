package fr.appprepa.core.deck

object DeckMerge {

    /**
     * Entrelace les paquets une carte a la fois, en respectant l'ordre du planificateur
     * a l'interieur de chacun. Un paquet epuise cede simplement son tour.
     *
     * Les doublons sont ecartes sur [key] : deux paquets peuvent renvoyer la meme carte
     * si l'un est le parent de l'autre et que le provider inclut les descendants.
     *
     * L'entrelacement travaille sur ce que le planificateur a rendu, avant que le texte
     * des cartes ne soit lu : c'est ce qui evite d'aller chercher un recto et un verso
     * pour chaque carte de chaque paquet, alors qu'on n'en gardera que [limit] au total.
     */
    fun <T> interleave(perDeck: List<List<T>>, limit: Int, key: (T) -> Any): List<T> {
        val queues = perDeck.map { ArrayDeque(it) }.filter { it.isNotEmpty() }
        val seen = mutableSetOf<Any>()
        val merged = mutableListOf<T>()

        while (merged.size < limit && queues.any { it.isNotEmpty() }) {
            for (queue in queues) {
                if (merged.size >= limit) break
                val item = queue.removeFirstOrNull() ?: continue
                if (seen.add(key(item))) merged += item
            }
        }
        return merged
    }
}
