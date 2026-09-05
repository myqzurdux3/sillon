package fr.appprepa.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * La cle d'API des tests qui appellent vraiment le modele.
 *
 * Elle se lit dans un fichier pousse sur l'appareil, jamais dans un argument
 * d'instrumentation. `am instrument` recoit ses arguments sur une ligne de commande, et
 * `adbd` journalise cette ligne : passer la cle par `-e apiKey` l'ecrit en clair dans le
 * logcat de l'appareil, ou n'importe quelle application autorisee a le lire la trouve.
 *
 * Le fichier vit dans le repertoire media de l'application, accessible sans permission
 * particuliere et efface avec elle :
 *
 *   adb shell mkdir -p /sdcard/Android/media/fr.appprepa.app
 *   adb push api-key.txt /sdcard/Android/media/fr.appprepa.app/cle.txt
 *   ./gradlew :app:connectedDebugAndroidTest
 *   adb shell rm /sdcard/Android/media/fr.appprepa.app/cle.txt
 *
 * Absente, les tests concernes se sautent au lieu d'echouer : ils coutent de l'argent et
 * ne doivent pas bloquer une verification ordinaire.
 */
object CleDeTest {

    private const val DOSSIER = "/sdcard/Android/media/fr.appprepa.app"

    /** La cle Anthropic. */
    val valeur: String get() = lire("cle.txt")

    /** La cle Deepgram, pour les tests qui touchent la pile vocale. */
    val deepgram: String get() = lire("deepgram.txt")

    private fun lire(nom: String): String =
        runCatching { File("$DOSSIER/$nom").readText().trim() }.getOrDefault("")

    /** Le contexte de l'application visee, la ou vivent ses fichiers et ses reglages. */
    fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext
}
