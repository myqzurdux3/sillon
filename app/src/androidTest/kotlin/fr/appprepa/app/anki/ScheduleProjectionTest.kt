package fr.appprepa.app.anki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La table `schedule` doit rendre le nombre de boutons et la liste des medias.
 *
 * Sans `media_files`, une carte a image passe pour une carte de texte : elle est enoncee
 * sans son image, jugee sur du vide, et notee. Sans `button_count`, toutes les cartes
 * sont supposees en avoir quatre. Les deux se degradent en silence — d'ou ce test, qui
 * transforme un doute sur le contrat du provider en echec visible.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleProjectionTest {

    @Test
    fun laTableSchedulePorteLesColonnesDontDependLeTri() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val projection = arrayOf("note_id", "ord", "button_count", "media_files")

        resolver.query(AnkiDroidGateway.SCHEDULE_URI, projection, "limit=?", arrayOf("1"), null)
            .use { cursor ->
                requireNotNull(cursor) { "la table schedule est illisible" }
                val colonnes = cursor.columnNames.toSet()
                projection.forEach {
                    assertTrue("colonne absente de la projection : $it", it in colonnes)
                }
            }
    }
}
