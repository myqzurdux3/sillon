package fr.appprepa.core.model

/**
 * Ce qui empeche une carte d'etre revisee a l'oral.
 *
 * L'ancienne regle etait « la carte porte un media, donc on l'ecarte ». Sur la collection
 * reelle de l'utilisateur, elle ecartait 96 % des cartes — jusqu'a 20 sur 20 en une
 * session — dont 233 dont le recto etait du texte parfaitement lisible. La presence d'un
 * fichier joint ne dit rien : un schema a cote d'une definition ecrite ne rend pas la
 * carte muette, et une capture d'ecran en guise de verso, si.
 *
 * La regle porte donc sur ce qu'il y a a lire et a comparer, pas sur ce qui est attache.
 */
object Usability {

    /**
     * Un seul caractere suffit, et c'est deliberement le minimum absolu.
     *
     * Toute valeur au-dessus serait une supposition sur ce qui fait « assez de texte » —
     * exactement le genre de reglage au juge qui a produit la regle precedente. « n »,
     * « 42 », « oui » sont de vraies reponses de fiche. On n'ecarte donc que ce dont on
     * est certain : un verso qui, une fois le HTML retire, ne contient plus rien.
     */
    const val MIN_CARACTERES = 1

    /** Le motif d'exclusion, ou `null` si la carte est utilisable. */
    fun raison(card: ReviewCard): String? = when {
        !lisible(card.question) -> RECTO_MUET
        !lisible(card.answer) -> VERSO_MUET
        else -> null
    }

    /**
     * Un texte exploitable une fois le HTML retire. On ne compte que les lettres et les
     * chiffres : un verso reduit a « <img> » laisse derriere lui de la ponctuation et des
     * espaces, qui ne sont pas de la matiere a reviser.
     */
    private fun lisible(texte: String): Boolean =
        texte.count { it.isLetterOrDigit() } >= MIN_CARACTERES

    /** La question est une image : on ne peut meme pas la poser. */
    const val RECTO_MUET = "recto sans texte, rien a demander"

    /**
     * La reponse est une image. C'est le cas majoritaire chez l'utilisateur : ses versos
     * sont des captures d'ecran. Sans le contenu de l'image, il n'y a pas de reference a
     * laquelle confronter ce qu'il dit, et noter sur cette base serait une opinion.
     */
    const val VERSO_MUET = "verso sans texte, rien a quoi comparer"
}
