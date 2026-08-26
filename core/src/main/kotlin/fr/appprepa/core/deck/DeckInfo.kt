package fr.appprepa.core.deck

/** Un paquet Anki, avec ce qu'il reste a reviser dedans. */
data class DeckInfo(
    val id: Long,
    /** Nom complet, hierarchie comprise : « Maths::analyse::series ». */
    val name: String,
    val dueCount: Int,
) {
    /** Ce qu'on affiche une fois le chemin du parent retire. */
    val shortName: String get() = name.substringAfterLast(SEPARATOR)

    /** Niveau d'indentation dans la liste. */
    val depth: Int get() = name.split(SEPARATOR).size - 1

    companion object {
        const val SEPARATOR = "::"
    }
}
