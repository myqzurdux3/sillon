package fr.appprepa.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.ports.AnkiGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Acces a la collection AnkiDroid par son ContentProvider.
 *
 * Contrat repris de `FlashCardsContract.kt` (depot AnkiDroid, branche main, 2026-08-26).
 * La table `schedule` donne les cartes dues mais **pas** leur texte ; il faut une seconde
 * requete sur `notes/<id>/cards/<ord>` pour obtenir recto et verso.
 */
class AnkiDroidGateway(private val resolver: ContentResolver) : AnkiGateway {

    override suspend fun dueCards(deckId: Long?, limit: Int): List<ReviewCard> =
        withContext(Dispatchers.IO) {
            val selection = if (deckId != null) "limit=?, deckID=?" else "limit=?"
            val args = if (deckId != null) {
                arrayOf(limit.toString(), deckId.toString())
            } else {
                arrayOf(limit.toString())
            }

            val deckNames = decksBlocking()

            resolver.query(SCHEDULE_URI, null, selection, args, null).use { cursor ->
                if (cursor == null) return@withContext emptyList()
                buildList {
                    while (cursor.moveToNext()) {
                        val noteId = cursor.getLong(cursor.getColumnIndexOrThrow(NOTE_ID))
                        val ord = cursor.getInt(cursor.getColumnIndexOrThrow(CARD_ORD))
                        val buttons = cursor.getColumnIndex(BUTTON_COUNT)
                            .takeIf { it >= 0 }
                            ?.let { cursor.getInt(it) }
                            ?: 4
                        val hasMedia = cursor.getColumnIndex(MEDIA_FILES)
                            .takeIf { it >= 0 }
                            ?.let { hasMediaFiles(cursor.getString(it)) }
                            ?: false

                        val text = cardText(noteId, ord) ?: continue
                        add(
                            ReviewCard(
                                noteId = noteId,
                                cardOrd = ord,
                                deckName = deckNames[text.deckId] ?: "",
                                question = text.question,
                                answer = text.answer,
                                buttonCount = buttons,
                                hasMedia = hasMedia,
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(NOTE_ID, noteId)
                put(CARD_ORD, cardOrd)
                put(EASE, ease.value)
                put(TIME_TAKEN, timeTakenMs)
            }
            val updated = resolver.update(SCHEDULE_URI, values, null, null)
            check(updated > 0) { "AnkiDroid a refuse la note pour la note $noteId" }
        }
    }

    override suspend fun decks(): Map<Long, String> = withContext(Dispatchers.IO) { decksBlocking() }

    private fun decksBlocking(): Map<Long, String> =
        resolver.query(DECKS_URI, null, null, null, null).use { cursor ->
            if (cursor == null) return emptyMap()
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DECK_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DECK_NAME)) ?: ""
                    put(id, name)
                }
            }
        }

    private data class CardText(val question: String, val answer: String, val deckId: Long)

    /**
     * Les colonnes debarrassees du HTML ne figurent pas dans la projection par defaut du
     * provider : il faut les demander explicitement. On retombe sur [QUESTION] / [ANSWER]
     * si la version d'AnkiDroid installee ne les expose pas, quitte a nettoyer soi-meme.
     */
    private fun cardText(noteId: Long, ord: Int): CardText? {
        val uri = Uri.withAppendedPath(NOTES_URI, "$noteId/cards/$ord")
        val projection = arrayOf(
            NOTE_ID, CARD_ORD, CARD_DECK_ID,
            QUESTION, ANSWER, QUESTION_SIMPLE, ANSWER_SIMPLE, ANSWER_PURE,
        )
        return resolver.query(uri, projection, null, null, null).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            CardText(
                question = stripHtml(
                    cursor.string(QUESTION_SIMPLE) ?: cursor.string(QUESTION).orEmpty(),
                ),
                answer = stripHtml(
                    cursor.string(ANSWER_PURE)
                        ?: cursor.string(ANSWER_SIMPLE)
                        ?: cursor.string(ANSWER).orEmpty(),
                ),
                deckId = cursor.getColumnIndex(CARD_DECK_ID)
                    .takeIf { it >= 0 }
                    ?.let { cursor.getLong(it) }
                    ?: 0L,
            )
        }
    }

    private fun android.database.Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }?.takeIf { it.isNotBlank() }

    /**
     * Filet de securite : meme `question_simple` peut contenir des balises residuelles,
     * et une balise lue a voix haute rend la question incomprehensible.
     */
    private fun stripHtml(raw: String): String = raw
        .replace(Regex("<br\\s*/?>|</div>|</p>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** `media_files` est un JSONArray serialise ; vide ou absent veut dire pas de media. */
    private fun hasMediaFiles(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching { JSONArray(raw).length() > 0 }.getOrDefault(false)
    }

    companion object {
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")
        val SCHEDULE_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
        val NOTES_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
        val DECKS_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")

        private const val NOTE_ID = "note_id"
        private const val CARD_ORD = "ord"
        private const val BUTTON_COUNT = "button_count"
        private const val MEDIA_FILES = "media_files"
        private const val EASE = "answer_ease"
        private const val TIME_TAKEN = "time_taken"
        private const val QUESTION = "question"
        private const val ANSWER = "answer"
        private const val QUESTION_SIMPLE = "question_simple"
        private const val ANSWER_SIMPLE = "answer_simple"
        private const val ANSWER_PURE = "answer_pure"
        private const val CARD_DECK_ID = "deck_id"
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
    }
}
