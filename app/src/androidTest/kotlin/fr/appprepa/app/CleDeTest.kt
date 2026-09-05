package fr.appprepa.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Les cles d'API des tests qui appellent vraiment un service distant.
 *
 * Elles se lisent dans le repertoire prive de l'application, jamais dans un argument
 * d'instrumentation : `am instrument` recoit ses arguments sur une ligne de commande, et
 * `adbd` journalise cette ligne — passer une cle par `-e apiKey` l'ecrit en clair dans le
 * logcat de l'appareil.
 *
 * Le repertoire prive et non `Android/media` : sur Android 17, l'application ne peut plus
 * lire son propre dossier media, et le test se sautait alors en silence en affichant
 * « OK », ce qui est pire qu'un echec. Le stockage prive, lui, est toujours accessible, et
 * `run-as` permet d'y ecrire sur une compilation de debogage :
 *
 *   adb shell run-as fr.appprepa.app sh -c 'cat > files/deepgram.txt' < deepgram-api-key.txt
 *   ./gradlew :app:connectedDebugAndroidTest
 *   adb shell run-as fr.appprepa.app rm files/deepgram.txt
 *
 * Absente, les tests concernes se sautent au lieu d'echouer : ils coutent de l'argent et
 * ne doivent pas bloquer une verification ordinaire. Le script qui les lance doit donc
 * verifier lui-meme que la cle est en place, sans quoi un saut passe pour un succes.
 */
object CleDeTest {

    /** La cle Anthropic. */
    val valeur: String get() = lire("cle.txt")

    /** La cle Deepgram, pour les tests qui touchent la pile vocale. */
    val deepgram: String get() = lire("deepgram.txt")

    private fun lire(nom: String): String = runCatching {
        File(targetContext().filesDir, nom).readText().trim()
    }.getOrDefault("")

    /** Le contexte de l'application visee, la ou vivent ses fichiers et ses reglages. */
    fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext
}
