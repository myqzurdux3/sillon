package fr.appprepa.app.voice

import android.util.Log
import androidx.test.core.app.ActivityScenario
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.text.Normalizer
import kotlin.math.abs

/**
 * La pile vocale, eprouvee sur le materiel.
 *
 * Elle existe parce que trois series de corrections vocales ont ete livrees sans qu'aucune
 * n'ait jamais ete entendue : des tests JVM verts et des appels d'API valides pendant que
 * l'application ne marchait pas. Un test unitaire ne peut attraper ni un mauvais taux
 * d'echantillonnage, ni une voix qui ne sort pas, ni un micro qui rend du vide.
 *
 * Les trois moities sont separees a dessein. Le tour complet, quand il echoue, ne dit pas
 * laquelle a lache — c'est exactement ce qui s'est produit au premier essai.
 */
class BoucleVocaleTest {

    private val cle: String get() = CleDeTest.deepgram

    private val phrase = "Le théorème de Rolle s'applique sur un segment fermé."
    private val motsAttendus = listOf("theoreme", "rolle", "segment")

    /** Un repli qui compte les recours : s'il a servi, Deepgram n'a pas parle. */
    private class ReplieCompte : Speaker {
        var appels = 0
            private set

        override suspend fun speak(text: String, langue: Langue) { appels++ }
        override suspend fun warm(text: String, langue: Langue) = Unit
        override fun stop() = Unit
    }

    private object SourdListener : fr.appprepa.core.ports.Listener {
        override suspend fun listen(kind: ListenKind, timeoutMs: Long, langue: Langue) =
            ListenResult.Failure("repli desactive pour le test")
    }

    /**
     * Execute [bloc] avec l'application reellement visible.
     *
     * `RECORD_AUDIO` est accordee en mode « premier plan seulement » : hors du premier
     * plan, Android refuse la capture en rendant des zeros, sans erreur ni exception.
     * L'appop de l'appareil le montrait noir sur blanc — `RECORD_AUDIO: foreground` avec
     * un `rejectTime` date du test.
     *
     * Lancer l'activite depuis `adb` avant l'instrumentation ne sert a rien : `am
     * instrument` arrete l'application pour demarrer, et emporte l'activite avec elle.
     * C'est donc au test de la porter, apres, et d'attendre qu'elle soit vraiment reprise.
     */
    private fun <T> auPremierPlan(bloc: () -> T): T =
        ActivityScenario.launch(fr.appprepa.app.ui.MainActivity::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            Thread.sleep(DELAI_PREMIER_PLAN_MS)
            bloc()
        }

    // --- moitie 1 : la voix sort-elle vraiment du haut-parleur ? -----------

    @Test
    fun laSyntheseDistanteSortSansReplier() = runBlocking {
        assumeTrue("cle Deepgram absente", cle.isNotBlank())

        val repli = ReplieCompte()
        val debut = System.currentTimeMillis()
        DeepgramSpeaker(cle, repli = repli).speak(phrase, Langue.FRANCAIS)
        val duree = System.currentTimeMillis() - debut
        Log.i(TAG, "synthese jouee en $duree ms, replis=${repli.appels}")

        assertEquals("la synthese distante a echoue et le repli a pris la main", 0, repli.appels)
        // La phrase dure environ trois secondes : un retour immediat voudrait dire que
        // rien n'a ete joue, et l'attente de fin de lecture serait alors illusoire.
        assertTrue("lecture trop breve pour avoir ete entendue : $duree ms", duree > 1_500)
    }

    // --- moitie 2 : le micro rend-il des echantillons ? --------------------

    @Test
    fun leMicroSOuvreEtRendDesEchantillons() {
        assumeTrue(
            "micro indisponible",
            AndroidListener.unavailableReason(CleDeTest.targetContext()) == null,
        )

        auPremierPlan {
        val micro = MicrophoneStream()
        assertTrue("le micro ne s'ouvre pas", micro.start())
        try {
            val buffer = ByteArray(MicrophoneStream.TAILLE_PAQUET)
            var lus = 0
            var crete = 0
            repeat(20) {
                val n = micro.read(buffer)
                if (n > 0) {
                    lus += n
                    crete = maxOf(crete, amplitude(buffer, n))
                }
            }
            Log.i(TAG, "micro : $lus octets lus, amplitude crete $crete")
            assertTrue("le micro n'a rendu aucun echantillon", lus > 0)
            // Une amplitude exactement nulle n'est pas une piece silencieuse : un vrai
            // micro a toujours un bruit de fond. C'est la facon dont Android refuse la
            // capture — en rendant des zeros, sans erreur. C'est ce qu'il faisait tant que
            // le service ne declarait pas le type « microphone ».
            assertTrue(
                "le micro rend du silence parfait : capture refusee, pas piece calme",
                crete > 0,
            )
        } finally {
            micro.stop()
        }
        }
    }

    private fun amplitude(buffer: ByteArray, taille: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < taille) {
            val echantillon = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            max = maxOf(max, abs(echantillon.toShort().toInt()))
            i += 2
        }
        return max
    }

    // --- moitie 3 : le tour complet ---------------------------------------

    /**
     * La phrase fait le tour du materiel : synthese, haut-parleur, micro, transcription.
     *
     * Ce test se heurte a une difficulte de principe : la capture utilise
     * `MediaRecorder.AudioSource.VOICE_RECOGNITION`, qui applique la suppression d'echo du
     * constructeur — dont le travail est precisement de retirer du micro ce que l'appareil
     * vient de jouer. Il peut donc echouer sur un telephone dont l'annulation d'echo
     * fonctionne bien, sans que rien ne soit casse.
     *
     * Il reste utile : s'il passe, toute la chaine est prouvee d'un coup. S'il echoue, ce
     * sont les deux tests precedents qui disent ou chercher.
     */
    @Test
    fun laPhraseFaitLeTourDuMateriel() = runBlocking {
        assumeTrue("cle Deepgram absente", cle.isNotBlank())
        assumeTrue(
            "micro indisponible",
            AndroidListener.unavailableReason(CleDeTest.targetContext()) == null,
        )

        val listener = DeepgramListener(cle, repli = SourdListener)
        val speaker = DeepgramSpeaker(cle, repli = ReplieCompte())

        val entendu = auPremierPlan {
            runBlocking {
                withContext(Dispatchers.Default) {
                    val ecoute = async {
                        listener.listen(ListenKind.ANSWER, TIMEOUT_MS, Langue.FRANCAIS)
                    }
                    delay(AVANCE_ECOUTE_MS)
                    speaker.speak(phrase, Langue.FRANCAIS)
                    ecoute.await()
                }
            }
        }
        Log.i(TAG, "tour complet : $entendu")

        assertTrue(
            "le tour n'a rien rendu ($entendu). Suppression d'echo probable : " +
                "voir les deux tests de moitie pour situer la panne.",
            entendu is ListenResult.Transcript,
        )
        val texte = normalise((entendu as ListenResult.Transcript).text)
        val manquants = motsAttendus.filterNot { it in texte }
        assertTrue("mots perdus : $manquants — entendu « $texte »", manquants.isEmpty())
    }

    private fun normalise(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private companion object {
        const val TAG = "BoucleVocale"
        const val AVANCE_ECOUTE_MS = 1_500L
        const val DELAI_PREMIER_PLAN_MS = 1_500L
        const val TIMEOUT_MS = 20_000L
    }
}
