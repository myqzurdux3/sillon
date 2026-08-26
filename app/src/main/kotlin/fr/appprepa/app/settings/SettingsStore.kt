package fr.appprepa.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fr.appprepa.core.model.WriteMode

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

    var deckId: Long?
        get() = prefs.getLong(KEY_DECK, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().putLong(KEY_DECK, value ?: -1L).apply()

    var cardLimit: Int
        get() = prefs.getInt(KEY_LIMIT, 40)
        set(value) = prefs.edit().putInt(KEY_LIMIT, value.coerceIn(1, 200)).apply()

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

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODE = "write_mode"
        const val KEY_DECK = "deck_id"
        const val KEY_LIMIT = "card_limit"
        const val KEY_DEBUG = "debug_transcripts"
        const val KEY_FAST_JUDGE = "fast_judge"
    }
}
