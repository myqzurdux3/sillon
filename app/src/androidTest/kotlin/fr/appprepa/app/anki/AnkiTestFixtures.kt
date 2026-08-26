package fr.appprepa.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray

/**
 * Seme des cartes dans la collection AnkiDroid de l'emulateur.
 * Les champs d'une note sont separes par 0x1f, comme le documente le contrat AnkiDroid.
 */
object AnkiTestFixtures {

    private const val FIELD_SEPARATOR = "\u001f"

    private val NOTES_URI: Uri = AnkiDroidGateway.NOTES_URI
    private val MODELS_URI: Uri = Uri.parse("content://${AnkiDroidGateway.AUTHORITY}/models")

    fun currentModelId(resolver: ContentResolver): Long? =
        resolver.query(Uri.withAppendedPath(MODELS_URI, "current"), null, null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                } else {
                    null
                }
            }

    /** Cree [count] notes basiques et renvoie le nombre reellement insere. */
    fun seedBasicNotes(resolver: ContentResolver, count: Int): Int =
        insert(
            resolver,
            (0 until count).map { index ->
                "Question de test numero $index" to
                    ("Reponse de test numero $index, enoncee sur plusieurs mots " +
                        "pour ressembler a une vraie fiche de revision.")
            },
        )

    /**
     * Seme de vraies cartes du deck de l'utilisateur, extraites de son .apkg.
     * Elles portent du HTML et du LaTeX : c'est ce qui rend le banc d'essai realiste.
     */
    fun seedRealCards(resolver: ContentResolver): Int {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("cartes-reelles.json")
            .bufferedReader()
            .use { it.readText() }
        val array = JSONArray(json)
        val pairs = (0 until array.length()).map { i ->
            val row = array.getJSONArray(i)
            row.getString(0) to row.getString(1)
        }
        return insert(resolver, pairs)
    }

    private fun insert(resolver: ContentResolver, cards: List<Pair<String, String>>): Int {
        val modelId = currentModelId(resolver) ?: return 0
        var inserted = 0
        cards.forEach { (front, back) ->
            val values = ContentValues().apply {
                put("mid", modelId)
                put("flds", front + FIELD_SEPARATOR + back)
                put("tags", "test-vocal")
            }
            if (resolver.insert(NOTES_URI, values) != null) inserted++
        }
        return inserted
    }
}
