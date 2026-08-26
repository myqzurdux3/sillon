package fr.appprepa.app.anki

import android.content.Context
import android.content.pm.PackageManager

sealed interface AnkiStatus {
    data object Ready : AnkiStatus
    data object NotInstalled : AnkiStatus
    data object PermissionMissing : AnkiStatus
    data class Unreachable(val cause: String) : AnkiStatus
}

/**
 * Un echec d'acces a AnkiDroid doit etre annonce a l'arret, au demarrage de la session,
 * et jamais decouvert en plein trajet.
 */
object AnkiAvailability {

    fun check(context: Context): AnkiStatus {
        context.packageManager.resolveContentProvider(AnkiDroidGateway.AUTHORITY, 0)
            ?: return AnkiStatus.NotInstalled

        val granted = context.checkSelfPermission(AnkiDroidGateway.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return AnkiStatus.PermissionMissing

        return runCatching {
            context.contentResolver
                .query(AnkiDroidGateway.DECKS_URI, null, null, null, null)
                ?.use { AnkiStatus.Ready }
                ?: AnkiStatus.Unreachable("collection illisible")
        }.getOrElse { AnkiStatus.Unreachable(it.message ?: "acces refuse") }
    }

    fun message(status: AnkiStatus): String = when (status) {
        AnkiStatus.Ready -> "AnkiDroid est accessible."
        AnkiStatus.NotInstalled -> "AnkiDroid n'est pas installé sur ce téléphone."
        AnkiStatus.PermissionMissing -> "L'accès à la collection Anki n'a pas été autorisé."
        is AnkiStatus.Unreachable ->
            "AnkiDroid est installé mais sa collection est inaccessible : ${status.cause}. " +
                "Ferme AnkiDroid et réessaie."
    }
}
