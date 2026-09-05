package fr.appprepa.app.voice

import android.util.Log
import fr.appprepa.app.CleDeTest
import fr.appprepa.core.model.Langue
import fr.appprepa.core.ports.ListenKind
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.text.Normalizer

/**
 * Le seul test qui prouve quelque chose sur la pile vocale : la phrase fait le tour
 * complet du materiel. Deepgram la synthetise, le haut-parleur la joue, le micro
 * l'entend, Deepgram la retranscrit, et on compare au texte de depart.
 *
 * Il existe parce que trois series de corrections vocales ont ete livrees sans qu'aucune
 * n'ait jamais ete entendue : des tests JVM verts et des appels d'API valides pendant que
 * l'application, elle, ne marchait pas. Un test unitaire ne peut pas attraper un mauvais
 * taux d'echantillonnage, une voix qui se coupe ou un micro qui rend du vide — ce sont
 * exactement les pannes qui ont fait echouer ce projet.
 *
 * L'emulateur ne convient pas : pas d'entree audio realiste. Il tourne donc sur un
 * appareil reel, ne touche pas a la collection Anki, et ne dure que quelques secondes.
 *
 *   adb push deepgram-api-key.txt \
 *     /sdcard/Android/media/fr.appprepa.app/deepgram.txt
 */
class BoucleVocaleTest {

    private val cle: String get() = CleDeTest.deepgram

    /** Ce que la synthese doit dire, et ce que la transcription doit rendre. */
    private val phrase = "Le théorème de Rolle s'applique sur un segment fermé."

    /** Les mots qui doivent survivre au tour complet. Le reste peut varier. */
    private val motsAttendus = listOf("theoreme", "rolle", "segment")

    @Test
    fun laPhraseFaitLeTourDuMateriel() = runBlocking {
        assumeTrue("cle Deepgram absente", cle.isNotBlank())
        assumeTrue(
            "micro indisponible",
            AndroidListener.unavailableReason(CleDeTest.targetContext()) == null,
        )

        // Repli volontairement muet : le test doit echouer si Deepgram ne repond pas,
        // pas basculer en silence sur le moteur Android et sembler passer.
        val listener = DeepgramListener(cle, repli = SourdListener)
        val speaker = DeepgramSpeaker(cle, repli = MuetSpeaker)

        val entendu = withContext(Dispatchers.Default) {
            // L'ecoute demarre en premier : ouvrir la socket et le micro prend un
            // instant, et la phrase ne doit pas commencer avant que l'oreille soit prete.
            val ecoute = async {
                listener.listen(ListenKind.ANSWER, TIMEOUT_MS, Langue.FRANCAIS)
            }
            delay(AVANCE_ECOUTE_MS)
            speaker.speak(phrase, Langue.FRANCAIS)
            ecoute.await()
        }

        Log.i(TAG, "entendu : $entendu")

        assertTrue(
            "le tour n'a rien rendu : $entendu",
            entendu is ListenResult.Transcript,
        )
        val texte = normalise((entendu as ListenResult.Transcript).text)
        val manquants = motsAttendus.filterNot { it in texte }
        assertTrue(
            "mots perdus dans le tour : $manquants — entendu « $texte »",
            manquants.isEmpty(),
        )
    }

    private fun normalise(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** Un repli qui ne parle pas : le test doit echouer si Deepgram ne repond pas. */
    private object MuetSpeaker : Speaker {
        override suspend fun speak(text: String, langue: Langue) = Unit
        override fun stop() = Unit
    }

    /** Meme raison pour l'oreille : un repli silencieux, pour ne rien masquer. */
    private object SourdListener : fr.appprepa.core.ports.Listener {
        override suspend fun listen(kind: ListenKind, timeoutMs: Long, langue: Langue) =
            ListenResult.Failure("repli desactive pour le test")
    }

    private companion object {
        const val TAG = "BoucleVocale"

        /** De quoi laisser la socket s'ouvrir avant que la phrase commence. */
        const val AVANCE_ECOUTE_MS = 1_500L

        /** La phrase dure environ quatre secondes ; le reste est de la marge reseau. */
        const val TIMEOUT_MS = 20_000L
    }
}
