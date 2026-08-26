package fr.appprepa.app.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
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

/**
 * Les deux appels de la boucle sont courts et la latence prime sur la profondeur :
 * effort `LOW` sur les deux.
 */
class AnthropicTutor(
    apiKey: String,
    private val client: AnthropicClient =
        AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
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
        ask(Prompts.judge(card, expectedPoints, transcript, SessionMemoryBuilder.render(memory))),
        buttonCount = card.buttonCount,
    )

    override suspend fun explain(card: ReviewCard): String =
        Prompts.parseSpokenFeedback(ask(Prompts.explain(card)))

    private suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(1_024L)
            .system(Prompts.SYSTEM)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(prompt)
            .build()

        client.messages().create(params).content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("\n")
            .ifBlank { throw IllegalStateException("reponse vide du modele") }
    }

    companion object {
        const val MODEL = "claude-opus-5"
    }
}
