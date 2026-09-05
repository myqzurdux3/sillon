package fr.appprepa.app.llm

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Intention
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.Langue
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object Prompts {

    const val MAX_SPOKEN_WORDS = 40

    /**
     * Quand la reponse est juste, il n'y a rien a apprendre : reentendre trente mots de
     * verso qu'on vient de reciter fait perdre le rythme et allonge le trajet pour rien.
     * On confirme, et on enchaine.
     */
    const val MAX_SPOKEN_WORDS_CORRECT = 12

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Le systeme dit dans quelle langue **enoncer**. Il ne change rien au fait que le
     * modele lise du francais dans le prompt : c'est la sortie qui est bilingue, pas
     * l'entree. Une consigne francaise produit un anglais plus sur qu'une consigne
     * anglaise approximative.
     */
    fun system(langue: Langue): String = when (langue) {
        Langue.FRANCAIS -> SYSTEM
        Langue.ANGLAIS -> SYSTEM.replace(CONSIGNE_FR, CONSIGNE_EN)
    }

    private const val CONSIGNE_FR =
        "Tu parles français, en phrases courtes, sans jamais lister ni énumérer à voix haute."

    /**
     * Sur une carte anglaise, tout ce qui sera lu a voix haute doit etre anglais — la
     * question comme le retour. Une question anglaise reformulee en francais serait
     * inutilisable, et une question bilingue serait lue par une seule voix.
     */
    private const val CONSIGNE_EN =
        "Tout ce que tu écris et qui sera lu à voix haute doit être en ANGLAIS, en " +
            "phrases courtes, sans jamais lister ni énumérer. C'est une carte d'anglais : " +
            "la question, le retour et la correction s'énoncent en anglais."

    val SYSTEM = """
        Tu fais réviser des cartes Anki à l'oral, à quelqu'un qui conduit.
        Tu parles français, en phrases courtes, sans jamais lister ni énumérer à voix haute.
        Tu ne dis jamais « carte », « recto », « verso » : tu poses une question, c'est tout.

        Les cartes contiennent du LaTeX. Tout ce que tu écris sera lu par une synthèse
        vocale : énonce donc les formules en français parlé, jamais la notation brute.
        « \\(\\frac{\\pi}{2}\\) » se dit « pi sur deux », « \\(x^2\\) » se dit
        « x au carré », « \\(\\cos(x)\\) » se dit « cosinus de x ».
        Aucun backslash, aucune accolade, aucun symbole ne doit apparaître dans ta réponse.

        Ponctue ce que tu fais dire : la synthèse vocale ne tire son intonation que de la
        ponctuation. Un point fait descendre la voix, un point d'interrogation la fait
        monter, une virgule marque une respiration. Une phrase sans ponctuation est lue
        d'un ton plat et se comprend mal au volant. Écris donc des phrases courtes et
        complètes, et termine-les.

        Tu réponds uniquement par un objet JSON, sans texte autour. N'écris aucune balise
        interne ni aucun commentaire avant ou après cet objet.
    """.trimIndent()

    fun reformulate(card: ReviewCard, memoryText: String): String = buildString {
        appendLine("Deck : ${card.deckName}")
        appendLine("Recto : ${card.question}")
        appendLine("Verso : ${card.answer}")
        if (memoryText.isNotBlank()) appendLine("Contexte de la session : $memoryText")
        appendLine()
        appendLine(
            """
            Transforme le recto en UNE question orale, naturelle, énonçable d'un trait.
            Donne aussi les points attendus dans la réponse, en trois éléments au maximum.
            Réponds par : {"question": "...", "expected_points": ["...", "..."]}
            """.trimIndent(),
        )
    }

    fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memoryText: String,
        langueCorrection: Langue = Langue.FRANCAIS,
    ): String = buildString {
        appendLine("Question posée : ${card.question}")
        appendLine("Réponse de référence : ${card.answer}")
        if (expectedPoints.isNotEmpty()) {
            appendLine("Points attendus : ${expectedPoints.joinToString(" ; ")}")
        }
        appendLine("Réponse orale de l'élève : $transcript")
        if (memoryText.isNotBlank()) appendLine("Contexte de la session : $memoryText")
        appendLine()
        appendLine(
            """
            AVANT DE JUGER, demande-toi ce que l'élève voulait vraiment. Il conduit et
            parle en phrases entières : il ne dit pas « répète », il dit « attends j'ai
            pas bien entendu tu peux redire ». Renseigne alors "intention" :

              "reponse"   il a répondu à la question. C'est le cas de loin le plus
                          fréquent : dans le doute, c'est celui-là.
              "incomplet" la transcription l'a tranché en pleine phrase. Deux signes,
                          l'un ou l'autre suffit :
                            — elle finit sur un mot tronqué, qui n'existe pas tel quel :
                              « le hach », « s'ap », « la contrapo » ;
                            — elle finit sur un mot grammatical qui exige une suite :
                              « et ensuite qu'elle est », « il faut montrer que ».
                          Une réponse brève mais close n'est PAS incomplète : « n log n »,
                          « le chaînage et l'adressage ouvert », « non » sont des réponses.
                          Le critère est la coupure, jamais la longueur.
              "repeter"   il demande à réentendre la question.
              "passer"    il veut passer cette carte sans la noter.
              "expliquer" il déclare ne pas savoir et demande la réponse.
              "revenir"   il veut revenir sur la carte précédente pour la renoter.
              "annuler"   il veut annuler la note de la carte précédente.
              "arreter"   il veut terminer la session.

            Une réponse partielle ou hésitante reste une "reponse" : « je crois que c'est
            le théorème de Rolle mais je suis pas sûr » n'est pas une demande d'aide, c'est
            une réponse à juger. Ne choisis autre chose que "reponse" que si la phrase est
            une demande adressée à toi, et non une tentative de répondre.

            Quand l'intention n'est pas "reponse", les autres champs sont ignorés : mets
            verdict "faux", ease 1, spoken_feedback "".

            Juge le FOND, pas la forme sonore. La réponse vient d'une transcription
            automatique : les termes techniques peuvent être mal orthographiés ou coupés.
            Ne sanctionne jamais une erreur de transcription, seulement une erreur de savoir.

            verdict : "correct", "partiel" ou "faux".
            ease : 1 si faux, 2 si partiel, 3 si correct, 4 si correct ET formulé avec précision.
            spoken_feedback : ce que tu diras à voix haute.
              Si la réponse est CORRECTE : confirme en $MAX_SPOKEN_WORDS_CORRECT mots maximum
              et ne réexplique rien. L'élève vient de le dire, il n'a pas besoin de
              l'entendre une seconde fois. « Exact. » suffit souvent.
              Si elle est fausse ou partielle : donne l'essentiel de ce qui manque,
              $MAX_SPOKEN_WORDS mots maximum. C'est là que tu es utile.
            formulation_note : uniquement si le fond était juste mais la formulation confuse.
              Une remarque courte et actionnable. Sinon null.
            topic : le thème de la carte, deux ou trois mots.

            ${consigneLangue(langueCorrection)}

            Réponds par :
            {"intention":"...","verdict":"...","ease":0,"spoken_feedback":"...",
             "formulation_note":null,"topic":"..."}
            """.trimIndent(),
        )
    }

    fun explain(card: ReviewCard, langueCorrection: Langue = Langue.FRANCAIS): String =
        """
        Question : ${card.question}
        Réponse : ${card.answer}

        L'élève sèche. Explique-lui la réponse à voix haute, en $MAX_SPOKEN_WORDS mots maximum,
        en allant à l'essentiel.
        ${consigneLangue(langueCorrection)}
        Réponds par : {"spoken_feedback": "..."}
        """.trimIndent()

    /**
     * La langue de `spoken_feedback`, reglable independamment de celle de la carte : on
     * peut vouloir travailler l'anglais et s'entendre corriger en francais.
     */
    private fun consigneLangue(langue: Langue): String = when (langue) {
        Langue.FRANCAIS ->
            "spoken_feedback s'écrit en FRANÇAIS, même si la carte est en anglais. " +
                "Garde en anglais les termes de la carte qu'il faut citer, rien d'autre."
        Langue.ANGLAIS -> "spoken_feedback s'écrit en ANGLAIS, entièrement."
    }

    fun parseReformulation(raw: String): ReformulatedQuestion {
        val obj = extractObject(raw)
        val question = obj["question"]?.jsonPrimitive?.contentOrNull?.trim()
        require(!question.isNullOrEmpty()) { "reformulation sans question" }
        val points = obj["expected_points"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return ReformulatedQuestion(question, points)
    }

    fun parseSpokenFeedback(raw: String): String {
        val obj = extractObject(raw)
        val text = obj["spoken_feedback"]?.jsonPrimitive?.contentOrNull?.trim()
        require(!text.isNullOrEmpty()) { "sortie sans spoken_feedback" }
        return truncate(text)
    }

    /**
     * Le code ne fait pas confiance a `ease` : il la borne au nombre de boutons de la carte
     * et, si elle est absente ou aberrante, la recalcule depuis le verdict.
     */
    fun parseJudgement(raw: String, buttonCount: Int): Judgement {
        val obj = extractObject(raw)

        val verdict = when (obj["verdict"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()) {
            "correct" -> Verdict.CORRECT
            "partiel" -> Verdict.PARTIEL
            "faux" -> Verdict.FAUX
            else -> throw IllegalArgumentException("verdict absent ou inconnu")
        }

        val ease = obj["ease"]?.jsonPrimitive?.intOrNull
            ?.let { Ease.fromValue(it) }
            ?: Ease.fromVerdict(verdict)

        val feedback = obj["spoken_feedback"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        // Une intention absente ou inconnue vaut « il a repondu » : c'est le cas de loin
        // le plus frequent, et s'y tromper coute une note, alors que se tromper dans
        // l'autre sens ferait ignorer une vraie reponse.
        val intention = when (obj["intention"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()) {
            "incomplet" -> Intention.INCOMPLET
            "repeter" -> Intention.REPETER
            "passer" -> Intention.PASSER
            "expliquer" -> Intention.EXPLIQUER
            "revenir" -> Intention.REVENIR
            "annuler" -> Intention.ANNULER
            "arreter" -> Intention.ARRETER
            else -> Intention.REPONSE
        }

        return Judgement(
            intention = intention,
            verdict = verdict,
            ease = ease.clampTo(buttonCount),
            // Le code ne compte pas non plus sur le modele pour tenir sa longueur.
            spokenFeedback = if (verdict == Verdict.CORRECT) {
                confirm(feedback)
            } else {
                truncate(feedback)
            },
            formulationNote = obj["formulation_note"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
            topic = obj["topic"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Le modele peut encadrer son JSON de texte. On recupere le premier objet reellement
     * complet, accolades comptees : prendre du premier `{` au dernier `}` embarquerait
     * tout ce que le modele a pu ecrire apres, une accolade dans sa prose comprise.
     */
    private fun extractObject(raw: String): JsonObject {
        val span = firstObject(raw)
        require(span != null) { "aucun objet JSON dans la sortie du modele" }
        return runCatching { json.parseToJsonElement(span) as JsonObject }
            .getOrElse { throw IllegalArgumentException("JSON illisible : ${it.message}") }
    }

    /** Les accolades a l'interieur d'une chaine JSON ne comptent pas dans la profondeur. */
    private fun firstObject(raw: String): String? {
        val start = raw.indexOf('{').takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Une confirmation, pas un resume : on garde la premiere phrase, et rien de plus.
     * Le modele a beau avoir la consigne, c'est le code qui la fait respecter.
     */
    internal fun confirm(text: String): String {
        val propre = text.trim()
        if (propre.isEmpty()) return propre
        val fin = propre.indexOfFirst { it in charArrayOf('.', '!', '?') }
        val premiere = if (fin >= 0) propre.substring(0, fin + 1) else propre
        val mots = premiere.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (mots.size <= MAX_SPOKEN_WORDS_CORRECT) {
            premiere
        } else {
            mots.take(MAX_SPOKEN_WORDS_CORRECT).joinToString(" ") + "…"
        }
    }

    /** Coupe a la phrase : un verso de fiche de prepa lu en entier casse le rythme. */
    internal fun truncate(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= MAX_SPOKEN_WORDS) return text.trim()
        val cut = words.take(MAX_SPOKEN_WORDS).joinToString(" ")
        val lastStop = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (lastStop > cut.length / 2) cut.substring(0, lastStop + 1) else "$cut…"
    }
}
