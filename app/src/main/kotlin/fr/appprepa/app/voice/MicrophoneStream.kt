package fr.appprepa.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Le micro, en PCM brut.
 *
 * `SpeechRecognizer` faisait tout : capture, detection de fin de parole, transcription.
 * En le remplacant on reprend la capture a sa charge, et il faut la rendre exactement
 * comme le service distant l'attend — un mauvais taux d'echantillonnage ne provoque pas
 * d'erreur, il produit une transcription en charabia, c'est-a-dire le defaut qu'on
 * cherche justement a corriger.
 *
 * La source est [MediaRecorder.AudioSource.VOICE_RECOGNITION] : elle applique la
 * suppression de bruit et de l'echo du constructeur, ce qui compte dans une voiture bien
 * plus qu'ailleurs.
 */
class MicrophoneStream {

    private var record: AudioRecord? = null

    /**
     * Ouvre le micro. Rend `false` si le materiel refuse, sans lever : au volant, une
     * seance qui continue en degrade vaut mieux qu'une seance qui plante.
     */
    @SuppressLint("MissingPermission") // verifiee par AndroidListener.unavailableReason
    fun start(): Boolean {
        val taille = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT),
            TAILLE_MINIMALE,
        )
        val nouveau = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                FORMAT,
                taille,
            )
        }.getOrNull() ?: return false

        if (nouveau.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { nouveau.release() }
            return false
        }

        return runCatching {
            nouveau.startRecording()
            record = nouveau
            true
        }.getOrElse {
            Log.w(TAG, "micro indisponible : ${it.message}")
            runCatching { nouveau.release() }
            false
        }
    }

    /**
     * Lit le prochain paquet. Rend le nombre d'octets utiles, ou -1 si le micro est
     * ferme. Bloquant : l'appelant doit etre sur un fil d'entrees-sorties.
     */
    fun read(buffer: ByteArray): Int {
        val actif = record ?: return -1
        val lus = actif.read(buffer, 0, buffer.size)
        return if (lus < 0) -1 else lus
    }

    fun stop() {
        val actif = record ?: return
        record = null
        runCatching { actif.stop() }
        runCatching { actif.release() }
    }

    companion object {
        private const val TAG = "MicrophoneStream"

        /**
         * 16 kHz : le taux attendu par les modeles de reconnaissance, et le meilleur
         * compromis debit/qualite pour de la parole. Monter plus haut ne rend pas la
         * transcription meilleure et triple le volume envoye sur le reseau mobile.
         */
        const val SAMPLE_RATE = 16_000

        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Le nom que le service distant donne a ce format. */
        const val ENCODING = "linear16"

        /**
         * Paquets d'environ 100 ms. Plus petit multiplie les trames WebSocket sans rien
         * gagner ; plus gros ajoute directement au delai de detection de fin de parole,
         * qui est precisement ce qu'on cherche a raccourcir.
         */
        const val TAILLE_PAQUET = SAMPLE_RATE / 10 * 2

        /** Plancher de securite : certains appareils annoncent un tampon minuscule. */
        private const val TAILLE_MINIMALE = 4096
    }
}
