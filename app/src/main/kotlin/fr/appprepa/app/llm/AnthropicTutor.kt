package fr.appprepa.app.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import fr.appprepa.core.memory.SessionMemoryBuilder
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.ports.Tutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Les deux appels de la boucle sont courts et la latence prime sur la profondeur :
 * effort `LOW` sur les deux.
 */
class AnthropicTutor(
    apiKey: String,
    /**
     * Mode rapide : meme modele, sortie jusqu'a 2,5 fois plus rapide, tarif superieur.
     * Mesure sur cartes reelles : 5,0 s par appel en standard, voir le journal des mesures.
     */
    private val fast: Boolean = false,
    /** Modele du jugement. La reformulation garde toujours le modele principal. */
    private val judgeModel: String = MODEL,
    private val client: AnthropicClient = defaultClient(apiKey),
) : Tutor {

    override suspend fun reformulate(
        card: ReviewCard,
        memory: SessionMemory,
    ): ReformulatedQuestion = Prompts.parseReformulation(
        ask(Prompts.reformulate(card, SessionMemoryBuilder.render(memory))),
    )

    override suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
    ): Judgement = Prompts.parseJudgement(
        ask(
            Prompts.judge(card, expectedPoints, transcript, SessionMemoryBuilder.render(memory)),
            model = judgeModel,
        ),
        buttonCount = card.buttonCount,
    )

    override suspend fun explain(card: ReviewCard): String =
        Prompts.parseSpokenFeedback(ask(Prompts.explain(card)))

    private suspend fun ask(
        prompt: String,
        model: String = MODEL,
    ): String = withContext(Dispatchers.IO) {
        val text = if (fast) askFast(prompt, model) else askStandard(prompt, model)
        text.ifBlank { throw IllegalStateException("reponse vide du modele") }
    }

    private fun askStandard(prompt: String, model: String): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(Prompts.SYSTEM)
            .addUserMessage(prompt)

        // Haiku 4.5 rejette le parametre d'effort : il n'est pose que la ou il existe.
        if (supportsEffort(model)) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        }
        val params = builder.build()

        return client.messages().create(params).content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("\n")
    }

    private fun askFast(prompt: String, model: String): String {
        val params = com.anthropic.models.beta.messages.MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(Prompts.SYSTEM)
            .speed(com.anthropic.models.beta.messages.MessageCreateParams.Speed.FAST)
            .addBeta(AnthropicBeta.of("fast-mode-2026-02-01"))
            .addUserMessage(prompt)
            .build()

        return client.beta().messages().create(params).content()
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

        /** Les deux sorties attendues tiennent tres largement dedans. */
        const val MAX_TOKENS = 700L

        /** Jugement plus rapide, moins capable. Le choix revient a l'utilisateur. */
        const val FAST_JUDGE_MODEL = "claude-haiku-4-5"

        private fun supportsEffort(model: String): Boolean = !model.startsWith("claude-haiku")
    }
}
