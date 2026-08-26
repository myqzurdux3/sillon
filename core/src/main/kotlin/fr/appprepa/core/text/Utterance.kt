package fr.appprepa.core.text

import java.text.Normalizer

/**
 * Reconnait une phrase laissee en suspens.
 *
 * La reconnaissance vocale coupe apres un silence, sans savoir si la phrase etait finie.
 * Quelqu'un qui cherche ses mots au volant se fait donc couper au milieu, et la moitie de
 * sa reponse est jugee comme la totalite. Plutot que d'allonger l'attente pour tout le
 * monde, on regarde sur quel mot la phrase s'arrete : « la derivee de ce produit vaut
 * donc » ne se termine pas sur « donc ».
 *
 * Le doute penche vers la relance : redemander coute une seconde, juger une demi-reponse
 * coute une note fausse.
 */
object Utterance {

    /**
     * Mots qui appellent une suite. Conjonctions, prepositions, determinants, relatifs,
     * auxiliaires : aucun ne peut clore une phrase francaise.
     */
    private val DANGLING = setOf(
        // conjonctions et articulations
        "et", "ou", "mais", "donc", "or", "ni", "car", "puis", "ensuite", "alors",
        "parce", "puisque", "comme", "si", "quand", "lorsque", "tandis", "sauf",
        "cependant", "pourtant", "toutefois", "ainsi", "surtout", "notamment",
        // relatifs et interrogatifs
        "que", "qui", "quoi", "dont", "lequel", "laquelle", "auquel",
        // prepositions
        "de", "du", "des", "en", "dans", "sur", "sous", "par", "pour", "avec",
        "sans", "vers", "chez", "entre", "selon", "depuis", "pendant", "jusqu", "contre",
        // determinants
        "le", "la", "les", "ce", "cet", "cette", "ces", "mon", "ma", "mes",
        "son", "sa", "ses", "leur", "leurs", "notre", "votre", "aucun", "chaque", "tout",
        "toute", "tous", "toutes", "quelque", "quelques", "plusieurs",
        // auxiliaires et copules
        "est", "sont", "etait", "etaient", "sera", "seront", "ont", "avait",
        "avaient", "aura", "peut", "peuvent", "doit", "doivent", "va", "vont", "fait",
        // adverbes qui attendent leur complement
        "plus", "moins", "aussi", "tres", "assez", "trop", "bien", "presque", "environ",
        // elisions laissees par la normalisation
        "qu", "jusqu", "lorsqu", "puisqu",
    )

    /** En dessous, ce n'est pas une phrase en suspens : c'est une reponse tres courte. */
    private const val MIN_MOTS = 2

    /**
     * Vrai si la phrase s'arrete sur un mot qui appelle une suite.
     *
     * Les mots d'une seule lettre sont exclus : sur des fiches de maths, ce sont des
     * variables. « sinus de a » se termine bel et bien sur « a ». Pour la meme raison
     * « un » et « une » n'y figurent pas — « moins un » est un resultat, pas un article
     * en attente de son nom.
     */
    fun looksUnfinished(raw: String): Boolean {
        val mots = normalize(raw).split(' ').filter { it.isNotEmpty() }
        if (mots.size < MIN_MOTS) return false
        val dernier = mots.last()
        return dernier.length > 1 && dernier in DANGLING
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Recolle ce qui a ete entendu en deux fois. */
    fun join(debut: String, suite: String): String =
        listOf(debut.trim(), suite.trim()).filter { it.isNotEmpty() }.joinToString(" ")
}
