package fr.appprepa.app.ui

import fr.appprepa.core.engine.SessionState

/** Un seul mot d'etat, lisible d'un coup d'oeil, jamais une invite a agir. */
object StateLabels {
    fun of(state: SessionState): String = when (state) {
        SessionState.Idle -> "Prêt"
        SessionState.Loading -> "Chargement des cartes"
        is SessionState.Preparing -> "Préparation"
        is SessionState.Asking -> "Question"
        is SessionState.Listening -> "Je t'écoute"
        is SessionState.Judging -> "Correction"
        is SessionState.SpeakingVerdict -> "Réponse"
        is SessionState.AwaitingCorrection -> "Tu peux corriger"
        is SessionState.Finished ->
            "Terminé — ${state.stats.answered} cartes, ${state.stats.correct} justes"
        is SessionState.Failed -> "Erreur"
    }
}
