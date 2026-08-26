package fr.appprepa.core.text

/**
 * Convertit la notation LaTeX d'une carte en francais enoncable.
 *
 * Ce n'est pas un moteur LaTeX : c'est un filet de securite pour le mode degrade, quand le
 * LLM n'est pas joignable et qu'il faut malgre tout lire la carte a voix haute. En mode
 * normal, c'est le modele qui verbalise, et il le fait mieux. Une formule tres imbriquee
 * ressortira approximative — mais jamais sous forme de « backslash cos », ce qui est le
 * seul resultat vraiment inacceptable au volant.
 */
object MathSpeech {

    private val COMMANDS: List<Pair<Regex, String>> = listOf(
        // Commandes de mise en forme : elles ne s'entendent pas.
        Regex("""\\(?:left|right|!|,|;|:|quad|qquad|displaystyle|limits|,)""") to " ",
        Regex("""\\(?:mathbb|mathcal|mathrm|mathbf|text|textrm|operatorname)\{([^{}]*)\}""") to "$1",

        // Fractions : \frac{a}{b} et \dfrac{a}{b}
        Regex("""\\[dt]?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""") to "$1 sur $2",
        Regex("""\\sqrt\s*\{([^{}]*)\}""") to "racine de $1",

        // Fonctions usuelles
        Regex("""\\cos""") to "cosinus",
        Regex("""\\sin""") to "sinus",
        Regex("""\\tan""") to "tangente",
        Regex("""\\ln""") to "logarithme",
        Regex("""\\log""") to "logarithme",
        Regex("""\\exp""") to "exponentielle",
        Regex("""\\lim""") to "limite",
        Regex("""\\sum""") to "somme",
        Regex("""\\prod""") to "produit",
        Regex("""\\int""") to "intégrale",

        // Lettres grecques courantes
        Regex("""\\pi""") to "pi",
        Regex("""\\alpha""") to "alpha",
        Regex("""\\beta""") to "bêta",
        Regex("""\\gamma""") to "gamma",
        Regex("""\\delta""") to "delta",
        Regex("""\\theta""") to "thêta",
        Regex("""\\lambda""") to "lambda",
        Regex("""\\mu""") to "mu",
        Regex("""\\sigma""") to "sigma",
        Regex("""\\varepsilon|\\epsilon""") to "epsilon",
        Regex("""\\infty""") to "l'infini",

        // Relations et operateurs
        Regex("""\\pm""") to "plus ou moins",
        Regex("""\\mp""") to "moins ou plus",
        Regex("""\\times(?![a-zA-Z])""") to "fois",
        Regex("""\\cdots(?![a-zA-Z])""") to "et ainsi de suite",
        Regex("""\\cdot(?![a-zA-Z])""") to "fois",
        Regex("""\\div""") to "divisé par",
        Regex("""\\neq""") to "différent de",
        Regex("""\\leq|\\le""") to "inférieur ou égal à",
        Regex("""\\geq|\\ge""") to "supérieur ou égal à",
        Regex("""\\approx""") to "environ",
        Regex("""\\equiv""") to "congru à",
        Regex("""\\in""") to "appartient à",
        Regex("""\\notin""") to "n'appartient pas à",
        Regex("""\\subset""") to "inclus dans",
        Regex("""\\cup""") to "union",
        Regex("""\\cap""") to "inter",
        Regex("""\\to|\\rightarrow""") to "tend vers",
        Regex("""\\implies|\\Rightarrow""") to "donc",
        Regex("""\\iff|\\Leftrightarrow""") to "équivaut à",
        Regex("""\\forall""") to "pour tout",
        Regex("""\\exists""") to "il existe",
        Regex("""\\dots(?![a-zA-Z])|\\ldots(?![a-zA-Z])""") to "et ainsi de suite",
    )

    /** Reconnait la presence de notation mathematique. */
    fun containsMath(raw: String): Boolean =
        raw.contains("""\(""") || raw.contains("""\[""") ||
            raw.contains('$') || Regex("""\\[a-zA-Z]+""").containsMatchIn(raw)

    /** Reconnait une formule delimitee, sous ses trois formes usuelles. */
    private val SPAN = Regex(
        """\\\((.+?)\\\)|\\\[(.+?)\\\]|\$\$?(.+?)\$\$?""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val HTML_TAG = Regex("""<[^>]*>""")

    /**
     * Seule la notation est traduite. La prose francaise reste telle quelle : sans cela,
     * « d'un produit » devient « d prime un produit », l'apostrophe etant prise pour une
     * derivee.
     */
    fun verbalize(raw: String): String {
        val stripped = HTML_TAG.replace(raw, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")

        val rebuilt = SPAN.replace(stripped) { m ->
            val formula = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            " " + speakFormula(formula) + " "
        }

        // Une carte peut porter des commandes hors delimiteur : on ne les prononce pas.
        // Et une carte tronquee laisse un delimiteur ouvert, que rien n'a ferme.
        return Regex("""\\[a-zA-Z]+""").replace(rebuilt, " ")
            .replace(Regex("""\\[\(\)\[\]]"""), " ")
            // Un antislash isole — saut de ligne LaTeX, reste de troncature — ne se dit pas.
            .replace("\\", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun speakFormula(raw: String): String {
        var text = raw

        COMMANDS.forEach { (pattern, replacement) ->
            text = pattern.replace(text, replacement)
        }

        // « N^* » se dit « N etoile ».
        text = Regex("""\^\s*\{?\s*\*\s*\}?""").replace(text, " étoile ")

        // Puissances et indices, apres les commandes pour ne pas manger \pi^2.
        text = Regex("""\^\s*\{?\s*2\s*\}?""").replace(text, " au carré")
        text = Regex("""\^\s*\{?\s*3\s*\}?""").replace(text, " au cube")
        text = Regex("""\^\s*\{([^{}]*)\}""").replace(text, " puissance $1")
        text = Regex("""\^\s*([A-Za-z0-9]+)""").replace(text, " puissance $1")
        text = Regex("""_\s*\{([^{}]*)\}""").replace(text, " indice $1")
        text = Regex("""_\s*([A-Za-z0-9]+)""").replace(text, " indice $1")

        // « cosinus(x) » s'entend mal ; « cosinus de x » s'entend.
        text = Regex(
            """(cosinus|sinus|tangente|logarithme|exponentielle|racine de)\s*\(([^()]*)\)""",
        ).replace(text) { m -> " ${m.groupValues[1]} de ${m.groupValues[2]} " }

        // Les parentheses restantes ne se prononcent pas.
        text = text.replace(Regex("""[()\[\]]"""), " ")

        // Le prime se dit, sinon la derivee passe a la trappe.
        text = text.replace("'", " prime ")

        text = text.replace("=", " égale ")
        text = text.replace("+", " plus ")
        // Un tiret entre deux espaces est une soustraction ; un trait d'union reste tel quel.
        text = Regex("""(?<=[\s\)\]0-9A-Za-z])-(?=[\s\(\[0-9A-Za-z])""").replace(text, " moins ")

        // Ce qui reste de LaTeX ne doit pas etre prononce.
        text = Regex("""\\[a-zA-Z]+""").replace(text, " ")
        text = text.replace(Regex("""[{}\\]"""), " ")

        // Un coefficient colle a un mot ne s'entend pas : « 2pi », « 2sinus ».
        text = Regex("""(?<=[0-9])(?=[a-zA-Zà-ÿ])""").replace(text, " ")

        return text.replace(Regex("""\s+"""), " ").trim()
    }
}
