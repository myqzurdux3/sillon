package fr.appprepa.app.session

import android.util.Log
import fr.appprepa.app.CleDeTest
import fr.appprepa.app.llm.AnthropicTutor
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Mesure la latence du jugement, le seul temps mort que l'utilisateur subisse en silence.
 * C'est la seule facon de trancher entre les configurations : la vitesse est ici une
 * contrainte de premier rang, et l'intuition sur la latence d'un modele ne vaut rien.
 *
 * La carte est synthetique et non tiree d'AnkiDroid : on compare des configurations entre
 * elles, pas des paquets, et une carte fixe rend les mesures comparables d'un jour a l'autre.
 *
 * La cle se pousse sur l'appareil, voir `CleDeTest`. Puis :
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=fr.appprepa.app.session.LatencyTest
 */
class LatencyTest {

    private val apiKey: String get() = CleDeTest.valeur

    private val card = ReviewCard(
        noteId = 1,
        cardOrd = 0,
        deckName = "Prepa",
        question = "Enonce du theoreme de Rolle",
        answer = "Si f est continue sur [a, b], derivable sur ]a, b[ et f(a) = f(b), " +
            "alors il existe c dans ]a, b[ tel que f'(c) = 0.",
        buttonCount = 4,
        hasMedia = false,
    )

    private val reponse = "f continue sur le segment, derivable a l'interieur, " +
        "et si elle prend la meme valeur aux deux bouts sa derivee s'annule quelque part"

    @Test
    fun compareLesConfigurationsDeJugement() = runBlocking {
        assumeTrue("cle d'API absente", apiKey.isNotBlank())

        val variantes = listOf(
            Triple("Opus 5, reflexion, standard", AnthropicTutor.MODEL, false),
            Triple("Opus 5, sans reflexion, rapide", AnthropicTutor.MODEL, true),
            Triple("Haiku 4.5, rapide ignore", AnthropicTutor.FAST_JUDGE_MODEL, true),
        )

        variantes.forEach { (nom, model, rapide) ->
            val tutor = AnthropicTutor(apiKey, judgeModel = model, fast = rapide)
            val mesures = mutableListOf<Long>()

            repeat(REPETITIONS) {
                val debut = System.currentTimeMillis()
                runCatching { tutor.judge(card, emptyList(), reponse, SessionMemory()) }
                    .onSuccess { mesures += System.currentTimeMillis() - debut }
                    .onFailure { Log.i(TAG, "$nom : echec ${it.message?.take(160)}") }
            }

            if (mesures.isNotEmpty()) {
                Log.i(
                    TAG,
                    "$nom : ${mesures.joinToString(", ") { "$it ms" }} " +
                        "| mediane ${mesures.sorted()[mesures.size / 2]} ms",
                )
            }
        }
    }

    private companion object {
        const val TAG = "Latence"

        /** Impair, pour que la mediane soit une mesure et non une moyenne de deux. */
        const val REPETITIONS = 3
    }
}
