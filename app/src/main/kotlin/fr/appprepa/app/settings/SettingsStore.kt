package fr.appprepa.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fr.appprepa.core.model.WriteMode

/** Plafond « tout » : au-dela d'un trajet, la limite n'a plus de sens. */
const val ALL_CARDS = 200

/** La cle d'API est un secret : preferences chiffrees, jamais de fichier versionne. */
class SettingsStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "reglages",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** Par defaut, rien n'est ecrit dans Anki. Le basculement est une decision explicite. */
    var writeMode: WriteMode
        get() = runCatching { WriteMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(WriteMode.JOURNAL_ONLY)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Paquets coches. Vide veut dire « tous les paquets ». */
    var deckIds: Set<Long>
        get() = prefs.getString(KEY_DECKS, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
        set(value) = prefs.edit()
            .putString(KEY_DECKS, value.joinToString(",")) 
            .apply()

    /** Paquets replies dans l'ecran de choix. Memorise, sinon on replie a chaque fois. */
    var collapsedDeckIds: Set<Long>
        get() = prefs.getString(KEY_COLLAPSED, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
        set(value) = prefs.edit().putString(KEY_COLLAPSED, value.joinToString(",")).apply()

    var cardLimit: Int
        get() = prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT)
        set(value) = prefs.edit().putInt(KEY_LIMIT, value.coerceIn(1, ALL_CARDS)).apply()

    /**
     * Juger avec un modele plus rapide et moins capable. Mesure sur cartes reelles :
     * mediane 4,3 s avec le modele principal, 2,3 s avec le rapide. C'est le seul temps
     * mort reellement percu, celui de la reformulation etant masque par le prechargement.
     */
    var fastJudge: Boolean
        get() = prefs.getBoolean(KEY_FAST_JUDGE, false)
        set(value) = prefs.edit().putBoolean(KEY_FAST_JUDGE, value).apply()

    /** Repondre au clavier au lieu du micro, pour la mise au point. */
    var debugTranscripts: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    companion object {
        /** Les tailles de session proposees. La derniere vaut « tout ce qui est du ». */
        val LIMIT_CHOICES = listOf(10, 20, 40, 60, ALL_CARDS)
        const val DEFAULT_LIMIT = 40

        private const val KEY_API = "api_key"
        private const val KEY_MODE = "write_mode"
        private const val KEY_DECKS = "deck_ids"
        private const val KEY_LIMIT = "card_limit"
        private const val KEY_COLLAPSED = "collapsed_deck_ids"
        private const val KEY_DEBUG = "debug_transcripts"
        private const val KEY_FAST_JUDGE = "fast_judge"
    }
}
