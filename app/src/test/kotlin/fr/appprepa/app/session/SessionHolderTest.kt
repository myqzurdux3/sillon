package fr.appprepa.app.session

import fr.appprepa.core.engine.SessionState
import fr.appprepa.core.model.SessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHolderTest {

    @Test
    fun `demarre a l'arret`() {
        val holder = SessionHolder()
        assertEquals(SessionState.Idle, holder.state.value)
        assertNull(holder.outcome.value)
        assertTrue(!holder.isRunning.value)
    }

    @Test
    fun `publie l'etat courant`() {
        val holder = SessionHolder()
        holder.publish(SessionState.Loading)
        assertEquals(SessionState.Loading, holder.state.value)
    }

    @Test
    fun `publie le resultat final et repasse a l'arret`() {
        val holder = SessionHolder()
        holder.markRunning(true)
        holder.finish(SessionOutcome.Completed(SessionStats(answered = 3, correct = 2)))

        val outcome = holder.outcome.value as SessionOutcome.Completed
        assertEquals(3, outcome.stats.answered)
        assertTrue(!holder.isRunning.value)
    }

    @Test
    fun `publie un echec explicite`() {
        val holder = SessionHolder()
        holder.finish(SessionOutcome.Failed("AnkiDroid inaccessible"))
        assertEquals(
            "AnkiDroid inaccessible",
            (holder.outcome.value as SessionOutcome.Failed).reason,
        )
    }

    @Test
    fun `effacer le resultat le remet a zero`() {
        val holder = SessionHolder()
        holder.finish(SessionOutcome.Failed("erreur"))
        holder.clearOutcome()
        assertNull(holder.outcome.value)
    }
}
