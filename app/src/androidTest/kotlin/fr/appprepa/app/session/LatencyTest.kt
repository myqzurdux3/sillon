package fr.appprepa.app.session

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import fr.appprepa.app.anki.AnkiAvailability
import fr.appprepa.app.anki.AnkiDroidGateway
import fr.appprepa.app.anki.AnkiStatus
import fr.appprepa.app.llm.AnthropicTutor
import fr.appprepa.core.model.SessionMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Mesure la latence des deux appels sur de vraies cartes, en standard et en mode rapide.
 * C'est la seule facon de trancher : la vitesse est une contrainte de premier rang ici.
 */
class LatencyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val apiKey: String
        get() = InstrumentationRegistry.getArguments().getString("apiKey").orEmpty()

    @Test
    fun compareStandardEtRapide() = runBlocking {
        assumeTrue("cle d'API absente", apiKey.isNotBlank())
        assumeTrue(AnkiAvailability.check(context) == AnkiStatus.Ready)

        val cards = AnkiDroidGateway(context.contentResolver).dueCards(null, 3)
        assumeTrue(cards.isNotEmpty())

        val variantes = listOf(
            AnthropicTutor.MODEL to "jugement Opus 5",
            AnthropicTutor.FAST_JUDGE_MODEL to "jugement Haiku 4.5",
        )

        variantes.forEach { (model, nom) ->
            val tutor = AnthropicTutor(apiKey, judgeModel = model)
            val mesures = mutableListOf<Long>()

            cards.take(3).forEach { card ->
                val debut = System.currentTimeMillis()
                runCatching {
                    tutor.judge(card, emptyList(), "une reponse approximative", SessionMemory())
                }
                    .onSuccess { mesures += System.currentTimeMillis() - debut }
                    .onFailure { Log.i("Latence", "$nom : echec ${it.message?.take(120)}") }
            }

            if (mesures.isNotEmpty()) {
                Log.i(
                    "Latence",
                    "$nom : ${mesures.joinToString(", ") { "$it ms" }} " +
                        "| mediane ${mesures.sorted()[mesures.size / 2]} ms",
                )
            }
        }
    }
}
