package fr.appprepa.app.llm

import android.util.Log
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Le SDK Anthropic est une bibliotheque JVM, pas Android : jackson-databind et
 * kotlin-reflect peuvent echouer au chargement de classes sur un appareil sans que rien
 * ne l'ait signale a la compilation. Une cle volontairement invalide suffit a le
 * verifier : si l'appel echoue sur une erreur d'API plutot que sur un chargement de
 * classe, c'est que tout le transport fonctionne.
 */
class AnthropicTutorSmokeTest {

    private val card = ReviewCard(1, 0, "Prepa", "recto", "verso", 4, false)

    @Test
    fun leSdkSeChargeEtParleHttpSurAndroid() = runBlocking {
        val tutor = AnthropicTutor(apiKey = "sk-ant-cle-volontairement-invalide")
        try {
            tutor.reformulate(card, SessionMemory())
            fail("une cle invalide ne doit pas aboutir")
        } catch (error: NoClassDefFoundError) {
            fail("le SDK ne se charge pas sur Android : ${error.message}")
        } catch (error: ExceptionInInitializerError) {
            fail("initialisation du SDK impossible sur Android : ${error.message}")
        } catch (expected: Throwable) {
            val label = "${expected::class.java.name}: ${expected.message}"
            Log.i("SmokeTest", "echec attendu -> $label")
            assertTrue(
                "erreur de chargement de classe deguisee : $label",
                !label.contains("NoClassDefFound") &&
                    !label.contains("ClassNotFound") &&
                    !label.contains("ExceptionInInitializer") &&
                    !label.contains("VerifyError"),
            )
        }
    }
}
