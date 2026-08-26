package fr.appprepa.core.text

/**
 * Retrait du HTML des champs Anki.
 *
 * Les cartes viennent d'un editeur riche : meme les colonnes « nettoyees » du provider
 * laissent passer des balises et des entites. Une balise lue a voix haute rend la question
 * incomprehensible, d'ou ce passage systematique — a la lecture de la collection comme a
 * la verbalisation des formules, qui partageaient jusqu'ici deux copies de ces regles.
 */
object Html {

    private val TAG = Regex("<[^>]*>")
    private val LINE_BREAK = Regex("<br\\s*/?>|</div>|</p>|</li>|<li\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val ENTITIES = listOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
    )

    /** Texte nu, espaces normalises. Les balises de bloc deviennent des respirations. */
    fun strip(raw: String): String {
        var text = LINE_BREAK.replace(raw, " ")
        text = TAG.replace(text, " ")
        ENTITIES.forEach { (entity, plain) -> text = text.replace(entity, plain) }
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
