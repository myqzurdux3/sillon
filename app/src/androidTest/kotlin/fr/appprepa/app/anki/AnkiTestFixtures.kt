package fr.appprepa.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri

/**
 * Seme des cartes de test dans la collection AnkiDroid de l'emulateur.
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
    fun seedBasicNotes(resolver: ContentResolver, count: Int): Int {
        val modelId = currentModelId(resolver) ?: return 0
        var inserted = 0
        repeat(count) { index ->
            val fields = "Question de test numero $index" + FIELD_SEPARATOR +
                "Reponse de test numero $index, enoncee sur plusieurs mots " +
                "pour ressembler a une vraie fiche de revision."
            val values = ContentValues().apply {
                put("mid", modelId)
                put("flds", fields)
                put("tags", "test-vocal")
            }
            if (resolver.insert(NOTES_URI, values) != null) inserted++
        }
        return inserted
    }
}
