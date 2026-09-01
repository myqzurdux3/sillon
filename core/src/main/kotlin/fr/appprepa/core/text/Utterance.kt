package fr.appprepa.core.text

import fr.appprepa.core.model.Langue
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
    private val DANGLING_FR = setOf(
        // Conjonctions et articulations : aucune ne peut clore une phrase.
        "et", "ou", "mais", "donc", "or", "ni", "car", "puis", "ensuite", "alors",
        "parce", "puisque", "lorsque", "tandis", "cependant", "pourtant", "toutefois",
        // Relatifs. « quoi » est exclu : « n'importe quoi » est une fin.
        "que", "qui", "dont", "lequel", "laquelle", "auquel",
        // Prepositions.
        "de", "du", "des", "dans", "sur", "sous", "par", "pour", "avec",
        "sans", "vers", "chez", "entre", "selon", "depuis", "pendant", "jusqu",
        // Determinants. « aucun » et « plusieurs » sont exclus : « il n'y en a aucun ».
        "le", "la", "les", "ce", "cet", "cette", "ces", "mon", "ma", "mes",
        "son", "sa", "ses", "leur", "leurs", "notre", "votre", "chaque",
        // Copules. Les autres auxiliaires sont exclus : « il le peut », « elles y vont ».
        "est", "sont", "etait", "etaient", "sera", "seront",
        // Adverbes qui exigent un complement. Le reste du groupe a ete retire : « c'est
        // tout », « la suite converge bien », « il converge aussi », « c'est trop » sont
        // des fins de phrase, et les relancer interromprait une reponse complete.
        "tres", "environ",
        // Elisions laissees par la normalisation.
        "qu", "lorsqu", "puisqu",
    )

    /**
     * Les memes, en anglais. La liste est plus courte a dessein : l'anglais termine
     * volontiers sur une preposition — « what are you looking for », « the case I was
     * thinking of » —, et relancer ces phrases-la couperait des reponses completes.
     * Ne restent que des mots qui ne peuvent vraiment pas clore une phrase.
     */
    private val DANGLING_EN = setOf(
        // Conjonctions et articulations.
        "and", "or", "but", "because", "since", "while", "whereas", "although",
        "though", "unless", "therefore", "thus", "hence", "then",
        // Relatifs et subordonnants.
        "that", "which", "whose", "whom",
        // Determinants. « a » et « an » sont exclus : une lettre, ou trop proche.
        "the", "this", "these", "those", "my", "your", "his", "her", "their", "our",
        "every", "each", "another",
        // Copules et auxiliaires qui exigent une suite. « is » et « was » restent :
        // « that's what it is » se termine dessus, mais une reponse de fiche non.
        "are", "were", "been", "being", "having",
        // Prepositions qui n'apparaissent jamais en fin de phrase.
        "into", "onto", "upon", "within", "towards", "toward", "during", "between",
        // Adverbes qui exigent un complement.
        "very", "quite", "rather", "approximately", "roughly",
    )

    private fun dangling(langue: Langue): Set<String> = when (langue) {
        Langue.FRANCAIS -> DANGLING_FR
        Langue.ANGLAIS -> DANGLING_EN
    }

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
    fun looksUnfinished(raw: String, langue: Langue = Langue.FRANCAIS): Boolean {
        val mots = normalize(raw).split(' ').filter { it.isNotEmpty() }
        if (mots.size < MIN_MOTS) return false
        val dernier = mots.last()
        return dernier.length > 1 && dernier in dangling(langue)
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
