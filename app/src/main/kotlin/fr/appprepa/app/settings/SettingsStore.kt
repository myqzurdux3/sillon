@file:Suppress("DEPRECATION")

package fr.appprepa.app.settings

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fr.appprepa.app.voice.AndroidSpeaker
import fr.appprepa.core.model.WriteMode

/** Plafond « tout » : au-dela d'un trajet, la limite n'a plus de sens. */
const val ALL_CARDS = 200

/**
 * La cle d'API est un secret : preferences chiffrees, jamais de fichier versionne.
 *
 * `EncryptedSharedPreferences` est depreciee depuis 2025 et sans remplacant direct chez
 * Jetpack. En changer demanderait de faire migrer la cle deja stockee sur le telephone,
 * sous peine de la perdre en silence — un chantier a part, pas un nettoyage. La
 * depreciation est donc assumee ici, et non subie a chaque compilation.
 */
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
        set(value) = prefs.edit { putString(KEY_API, value.trim()) }

    /** Par defaut, rien n'est ecrit dans Anki. Le basculement est une decision explicite. */
    var writeMode: WriteMode
        get() = runCatching { WriteMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(WriteMode.JOURNAL_ONLY)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    /** Paquets coches. Vide veut dire « tous les paquets ». */
    var deckIds: Set<Long>
        get() = prefs.getString(KEY_DECKS, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
        set(value) = prefs.edit { putString(KEY_DECKS, value.joinToString(",")) }

    /** Paquets replies dans l'ecran de choix. Memorise, sinon on replie a chaque fois. */
    var collapsedDeckIds: Set<Long>
        get() = prefs.getString(KEY_COLLAPSED, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
        set(value) = prefs.edit { putString(KEY_COLLAPSED, value.joinToString(",")) }

    var cardLimit: Int
        get() = prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT)
        set(value) = prefs.edit { putInt(KEY_LIMIT, value.coerceIn(1, ALL_CARDS)) }

    /**
     * Juger avec un modele plus petit et moins capable. Le mode rapide du modele principal
     * couvre deja l'essentiel de l'ecart de latence sans rien lacher sur la qualite du
     * jugement ; ce reglage reste pour les trajets sans reseau correct, ou pour le cout.
     */
    var fastJudge: Boolean
        get() = prefs.getBoolean(KEY_FAST_JUDGE, false)
        set(value) = prefs.edit { putBoolean(KEY_FAST_JUDGE, value) }

    /**
     * Vitesse d'elocution. Le debit par defaut des moteurs francais est cale sur la lecture
     * d'un ecran, pas sur quelqu'un qui ecoute en conduisant et connait le vocabulaire.
     */
    var speechRate: Float
        get() = prefs.getFloat(KEY_RATE, AndroidSpeaker.DEFAULT_RATE)
        set(value) = prefs.edit {
            putFloat(KEY_RATE, value.coerceIn(AndroidSpeaker.MIN_RATE, AndroidSpeaker.MAX_RATE))
        }

    /** Repondre au clavier au lieu du micro, pour la mise au point. */
    var debugTranscripts: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit { putBoolean(KEY_DEBUG, value) }

    companion object {
        /** Les tailles de session proposees. La derniere vaut « tout ce qui est du ». */
        val LIMIT_CHOICES = listOf(10, 20, 40, 60, ALL_CARDS)

        /** Les vitesses proposees. Assez espacees pour que l'ecart s'entende. */
        val RATE_CHOICES = listOf(0.9f, 1.0f, 1.15f, 1.3f, 1.45f, 1.6f)
        const val DEFAULT_LIMIT = 40

        private const val KEY_API = "api_key"
        private const val KEY_MODE = "write_mode"
        private const val KEY_DECKS = "deck_ids"
        private const val KEY_LIMIT = "card_limit"
        private const val KEY_COLLAPSED = "collapsed_deck_ids"
        private const val KEY_DEBUG = "debug_transcripts"
        private const val KEY_FAST_JUDGE = "fast_judge"
        private const val KEY_RATE = "speech_rate"
    }
}
