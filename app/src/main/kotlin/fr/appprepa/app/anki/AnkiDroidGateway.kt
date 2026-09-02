package fr.appprepa.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import androidx.core.net.toUri
import fr.appprepa.core.deck.DeckInfo
import fr.appprepa.core.deck.DeckMerge
import fr.appprepa.core.deck.DeckLanguage
import fr.appprepa.core.deck.DeckSelection
import fr.appprepa.core.text.Html
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
class AnkiDroidGateway(
    private val resolver: ContentResolver,
    /**
     * Les paquets a ecouter en anglais, ou `null` quand l'utilisateur n'a rien choisi :
     * le nom du paquet decide alors. C'est une fonction et non un ensemble parce que la
     * liste des paquets n'est connue qu'ici, au moment de la lecture.
     */
    private val englishDeckIds: () -> Set<Long>? = { null },
) : AnkiGateway {

    /**
     * Chaque paquet coche est interroge separement, puis les resultats sont entrelaces.
     * Interroger un parent en esperant qu'il ramene ses enfants reposerait sur une
     * semantique que la documentation d'AnkiDroid ne precise pas.
     *
     * Le texte des cartes n'est lu **qu'apres** l'entrelacement : la table `schedule` ne
     * porte que des identifiants, et chaque recto coute une requete de plus. Lire avant
     * de trancher revenait a payer `paquets x limite` allers-retours pour n'en garder
     * que `limite`.
     */
    override suspend fun dueCards(deckIds: Set<Long>, limit: Int): List<ReviewCard> =
        withContext(Dispatchers.IO) {
            val decks = decksBlocking()
            val names = decks.associate { it.id to it.name }
            // La langue se decide ici, une fois, et voyage ensuite avec la carte : le
            // moteur n'a plus a savoir ce qu'est un paquet pour regler le micro.
            val anglophones = DeckLanguage.anglophones(decks, englishDeckIds())

            // Un identifiant de paquet supprime depuis le dernier reglage ne doit pas
            // consommer une requete pour rien, ni raccourcir la session en silence.
            val retenus = DeckSelection.effective(decks, deckIds)
            val perDeck = if (retenus.isEmpty()) {
                listOf(querySchedule(null, limit))
            } else {
                retenus.map { querySchedule(it, limit) }
            }

            DeckMerge.interleave(perDeck, limit) { it.noteId to it.ord }
                .mapNotNull { due ->
                    val text = cardText(due.noteId, due.ord) ?: return@mapNotNull null
                    ReviewCard(
                        noteId = due.noteId,
                        cardOrd = due.ord,
                        deckName = names[text.deckId] ?: "",
                        question = text.question,
                        answer = text.answer,
                        buttonCount = due.buttonCount,
                        hasMedia = due.hasMedia,
                        langue = DeckLanguage.langueDe(text.deckId, anglophones),
                    )
                }
        }

    /** Une ligne de la table `schedule` : des identifiants, pas encore de texte. */
    private data class DueCard(
        val noteId: Long,
        val ord: Int,
        val buttonCount: Int,
        val hasMedia: Boolean,
    )

    private fun querySchedule(deckId: Long?, limit: Int): List<DueCard> {
        val selection = if (deckId != null) "limit=?, deckID=?" else "limit=?"
        val args = if (deckId != null) {
            arrayOf(limit.toString(), deckId.toString())
        } else {
            arrayOf(limit.toString())
        }

        // La projection est demandee explicitement : c'est ce qui garantit que
        // `button_count` et `media_files` sont bien la. S'ils ne l'etaient pas, une carte
        // a image passerait pour une carte de texte et serait lue a vide. Le repli sur la
        // projection par defaut evite qu'une version d'AnkiDroid inattendue fasse
        // echouer la lecture ; le test instrumente verifie que le repli ne sert pas.
        val cursor = runCatching {
            resolver.query(SCHEDULE_URI, SCHEDULE_PROJECTION, selection, args, null)
        }.getOrElse { resolver.query(SCHEDULE_URI, null, selection, args, null) }

        return cursor
            .use { cursor ->
                if (cursor == null) return emptyList()
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            DueCard(
                                noteId = cursor.getLong(cursor.getColumnIndexOrThrow(NOTE_ID)),
                                ord = cursor.getInt(cursor.getColumnIndexOrThrow(CARD_ORD)),
                                buttonCount = cursor.int(BUTTON_COUNT) ?: DEFAULT_BUTTONS,
                                hasMedia = hasMediaFiles(cursor.string(MEDIA_FILES)),
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

    override suspend fun decks(): List<DeckInfo> = withContext(Dispatchers.IO) { decksBlocking() }

    /**
     * `deck_count` est un triplet [nouvelles, en apprentissage, a revoir] : c'est ce
     * chiffre qui permet de cocher un paquet en connaissance de cause.
     */
    private fun decksBlocking(): List<DeckInfo> =
        resolver.query(DECKS_URI, null, null, null, null).use { cursor ->
            if (cursor == null) return emptyList()
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DECK_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DECK_NAME)) ?: ""
                    val due = sumCounts(cursor.string(DECK_COUNTS))
                    add(DeckInfo(id, name, due))
                }
            }
        }

    /** « [0,0,18] » vaut dix-huit cartes a faire. */
    private fun sumCounts(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).sumOf { array.getInt(it) }
        }.getOrDefault(0)
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
            NOTE_ID, CARD_ORD, DECK_ID,
            QUESTION, ANSWER, QUESTION_SIMPLE, ANSWER_SIMPLE, ANSWER_PURE,
        )
        return resolver.query(uri, projection, null, null, null).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            CardText(
                question = Html.strip(
                    cursor.string(QUESTION_SIMPLE) ?: cursor.string(QUESTION).orEmpty(),
                ),
                answer = Html.strip(
                    cursor.string(ANSWER_PURE)
                        ?: cursor.string(ANSWER_SIMPLE)
                        ?: cursor.string(ANSWER).orEmpty(),
                ),
                deckId = cursor.long(DECK_ID)
                    ?: cursor.string(DECK_ID)?.toLongOrNull()
                    ?: 0L,
            )
        }
    }

    private fun android.database.Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }?.takeIf { it.isNotBlank() }

    private fun android.database.Cursor.int(column: String): Int? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) }

    /**
     * Les identifiants d'Anki sont des horodatages en millisecondes : treize chiffres,
     * bien au-dela d'un `Int`. Les lire avec `getInt` ne leve rien, cela rend un nombre
     * tronque — donc un paquet introuvable, un nom vide, et une langue jamais detectee.
     * Le journal de l'utilisateur portait 66 entrees sans nom de paquet a cause de cela.
     */
    private fun android.database.Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getLong(it) }

    /** `media_files` est un JSONArray serialise ; vide ou absent veut dire pas de media. */
    private fun hasMediaFiles(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching { JSONArray(raw).length() > 0 }.getOrDefault(false)
    }

    companion object {
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private val AUTHORITY_URI: Uri = "content://$AUTHORITY".toUri()
        val SCHEDULE_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
        val NOTES_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
        val DECKS_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")

        private const val NOTE_ID = "note_id"
        private const val CARD_ORD = "ord"
        private const val BUTTON_COUNT = "button_count"
        private const val MEDIA_FILES = "media_files"
        private const val EASE = "answer_ease"
        private const val TIME_TAKEN = "time_taken"
        /** Le provider tolere une colonne inconnue, mais pas une projection muette. */
        private val SCHEDULE_PROJECTION =
            arrayOf(NOTE_ID, CARD_ORD, BUTTON_COUNT, MEDIA_FILES)

        /** Quand la carte ne dit pas son nombre de boutons, quatre est le cas courant. */
        private const val DEFAULT_BUTTONS = 4

        private const val QUESTION = "question"
        private const val ANSWER = "answer"
        private const val QUESTION_SIMPLE = "question_simple"
        private const val ANSWER_SIMPLE = "answer_simple"
        private const val ANSWER_PURE = "answer_pure"
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val DECK_COUNTS = "deck_count"
    }
}
