package fr.appprepa.app.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.RateLimitException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaThinkingConfigDisabled
import com.anthropic.models.beta.messages.MessageCreateParams
import fr.appprepa.core.memory.SessionMemoryBuilder
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.Langue
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.ports.Tutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trois appels, deux exigences.
 *
 * Le jugement et l'explication sont sur le chemin critique : l'utilisateur a fini de parler
 * et attend en silence. Ils partent donc sans reflexion prealable, en effort bas, et avec
 * un plafond de sortie serre. Comparer une reponse orale a un verso connu et rendre un
 * petit objet JSON ne demande pas de raisonnement etendu. Mesure sur la meme carte, trois
 * appels chacun : 4 081 ms de mediane avec reflexion, 3 506 ms sans.
 *
 * La reformulation, elle, est prechargee pendant que l'utilisateur repond a la carte d'avant :
 * sa latence ne se voit pas, et c'est elle qui fabrique la question. Elle garde le
 * comportement par defaut du modele.
 */
class AnthropicTutor(
    apiKey: String,
    /** Modele du jugement. La reformulation garde toujours le modele principal. */
    private val judgeModel: String = MODEL,
    /**
     * Mode rapide : meme modele, sortie jusqu'a 2,5 fois plus rapide, tarif double.
     *
     * Desactive par defaut, et ce n'est pas de la prudence : c'est une preversion qu'une
     * organisation doit faire ouvrir. Sans cela le quota vaut zero, chaque appel repart
     * en 429, et la reprise en vitesse normale coute un aller-retour de plus par carte —
     * l'inverse de ce qu'on cherche. Une fois le mode ouvert sur le compte, passer ce
     * drapeau a `true` suffit.
     */
    private val fast: Boolean = false,
    private val client: AnthropicClient = defaultClient(apiKey),
) : Tutor {

    override suspend fun reformulate(
        card: ReviewCard,
        memory: SessionMemory,
    ): ReformulatedQuestion = Prompts.parseReformulation(
        ask(
            prompt = Prompts.reformulate(card, SessionMemoryBuilder.render(memory)),
            // La question suit toujours la carte : la reformuler dans une autre langue la
            // rendrait inutilisable, quel que soit le reglage des corrections.
            system = Prompts.system(card.langue),
            model = MODEL,
            reflechit = true,
            maxTokens = MAX_TOKENS_REFORMULATION,
        ),
    )

    override suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
        langueCorrection: Langue,
    ): Judgement = Prompts.parseJudgement(
        ask(
            prompt = Prompts.judge(
                card,
                expectedPoints,
                transcript,
                SessionMemoryBuilder.render(memory),
                langueCorrection,
            ),
            system = Prompts.system(card.langue),
            model = judgeModel,
            reflechit = false,
            maxTokens = MAX_TOKENS_VERDICT,
        ),
        buttonCount = card.buttonCount,
    )

    override suspend fun explain(card: ReviewCard, langueCorrection: Langue): String =
        Prompts.parseSpokenFeedback(
            ask(
                prompt = Prompts.explain(card, langueCorrection),
                system = Prompts.system(card.langue),
                model = judgeModel,
                reflechit = false,
                maxTokens = MAX_TOKENS_VERDICT,
            ),
        )

    /**
     * Vrai tant que le mode rapide n'a pas ete refuse. Le quota du mode rapide est
     * distinct du quota ordinaire ; s'il est ferme, tous les appels le seront. On ne
     * paie donc l'aller-retour perdu qu'une fois par session, pas a chaque carte.
     */
    private val rapideOuvert = AtomicBoolean(true)

    private suspend fun ask(
        prompt: String,
        system: String,
        model: String,
        reflechit: Boolean,
        maxTokens: Long,
    ): String = withContext(Dispatchers.IO) {
        val rapide = fast && rapideOuvert.get() && supportsFastMode(model)
        val texte = try {
            send(prompt, system, model, reflechit, maxTokens, rapide)
        } catch (limite: RateLimitException) {
            // Saturer le quota rapide ne doit pas arreter la seance : on repart en
            // vitesse normale, et on cesse de redemander pour le reste du trajet.
            if (!rapide) throw limite
            rapideOuvert.set(false)
            send(prompt, system, model, reflechit, maxTokens, rapide = false)
        }
        texte.ifBlank { throw IllegalStateException("reponse vide du modele") }
    }

    private fun send(
        prompt: String,
        system: String,
        model: String,
        reflechit: Boolean,
        maxTokens: Long,
        rapide: Boolean,
    ): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(system)
            .addUserMessage(prompt)

        // Haiku 4.5 ne connait ni l'effort ni la configuration de reflexion : les poser
        // ferait echouer la requete au lieu de la rendre plus rapide.
        if (supportsEffort(model)) {
            builder.outputConfig(
                BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build(),
            )
            if (!reflechit) {
                builder.thinking(BetaThinkingConfigDisabled.builder().build())
            }
        }

        if (rapide) {
            builder.speed(MessageCreateParams.Speed.FAST).addBeta(AnthropicBeta.of(FAST_MODE_BETA))
        }

        return client.beta().messages().create(builder.build()).content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("\n")
    }

    companion object {
        /**
         * Au volant, une requete qui pend est pire qu'une requete qui echoue : l'echec
         * bascule en lecture simple et la session continue, l'attente ne rend jamais la
         * main. Le delai par defaut du client se compte en minutes ; celui-ci est cale
         * sur ce qu'un conducteur peut supporter de silence.
         */
        val TIMEOUT: Duration = Duration.ofSeconds(20)

        /** Une seule reprise : au-dela, le mode degrade est plus rapide que l'insistance. */
        const val MAX_RETRIES = 1

        private fun defaultClient(apiKey: String): AnthropicClient =
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(TIMEOUT)
                .maxRetries(MAX_RETRIES)
                .build()

        const val MODEL = "claude-opus-5"

        /** Jugement plus rapide, moins capable : 2 045 ms de mediane. Le choix revient a l'utilisateur. */
        const val FAST_JUDGE_MODEL = "claude-haiku-4-5"

        const val FAST_MODE_BETA = "fast-mode-2026-02-01"

        /** Question orale plus points attendus. Large, mais jamais sur le chemin critique. */
        const val MAX_TOKENS_REFORMULATION = 700L

        /**
         * Le verdict tient en quarante mots parles plus quelques champs. Le plafond ne
         * sert qu'a borner une sortie qui deraille : la couper court vaut mieux que la
         * laisser s'ecrire pendant que l'utilisateur attend.
         */
        const val MAX_TOKENS_VERDICT = 400L

        /** Preversion, reservee aux modeles Opus recents. Ailleurs, elle fait echouer l'appel. */
        private fun supportsFastMode(model: String): Boolean =
            model == MODEL || model == "claude-opus-4-8"

        private fun supportsEffort(model: String): Boolean = !model.startsWith("claude-haiku")
    }
}
