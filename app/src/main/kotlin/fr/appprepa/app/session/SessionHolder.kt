package fr.appprepa.app.session

import fr.appprepa.core.engine.SessionState
import fr.appprepa.core.model.SessionStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionOutcome {
    data class Completed(val stats: SessionStats) : SessionOutcome
    data class Failed(val reason: String) : SessionOutcome
}

/** Pont entre le service qui execute la session et l'interface qui l'affiche. */
class SessionHolder {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _outcome = MutableStateFlow<SessionOutcome?>(null)
    val outcome: StateFlow<SessionOutcome?> = _outcome.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun publish(state: SessionState) { _state.value = state }
    fun markRunning(running: Boolean) { _isRunning.value = running }
    fun clearOutcome() { _outcome.value = null }

    fun finish(outcome: SessionOutcome) {
        _outcome.value = outcome
        _isRunning.value = false
        _state.value = SessionState.Idle
    }

    companion object {
        /** Une seule session a la fois : le service et l'interface partagent cet objet. */
        val shared = SessionHolder()
    }
}
