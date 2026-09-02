package fr.appprepa.core.engine

import fr.appprepa.core.model.ReviewCard
import org.junit.Assert.assertEquals
import org.junit.Test

/** « carte 7 sur 32 » : le seul chiffre affiche pendant la conduite. */
class ProgressTest {

    /** [muette] : un verso sans texte, le seul motif qui ecarte vraiment une carte. */
    private fun card(id: Long, muette: Boolean = false) =
        ReviewCard(id, 0, "Deck", "recto $id", if (muette) "" else "verso $id", 4, muette)

    @Test
    fun `le total est fixe au chargement, hors cartes sans verso lisible`() {
        val loading = Session(state = SessionState.Loading)
        val result = ReviewSessionEngine.reduce(
            loading,
            Event.CardsLoaded(listOf(card(1), card(2, muette = true), card(3))),
            0L,
        )
        assertEquals(2, result.session.total)
    }

    @Test
    fun `la carte courante est la premiere des le depart`() {
        val loading = Session(state = SessionState.Loading)
        val started = ReviewSessionEngine.reduce(
            loading,
            Event.CardsLoaded(listOf(card(1), card(2), card(3))),
            0L,
        )
        assertEquals(1, started.session.position)
    }

    @Test
    fun `la position avance a chaque carte traitee`() {
        var session = ReviewSessionEngine.reduce(
            Session(state = SessionState.Loading),
            Event.CardsLoaded(listOf(card(1), card(2), card(3))),
            0L,
        ).session

        val inFlight = CardInFlight(card(1), "q", emptyList(), 0L)
        session = session.copy(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.SelfGrade("verso")),
        )
        session = ReviewSessionEngine.reduce(session, Event.HeardNothing, 1_000L).session

        assertEquals(2, session.position)
    }

    @Test
    fun `la position ne depasse jamais le total`() {
        val session = Session(total = 3, stats = fr.appprepa.core.model.SessionStats(answered = 3))
        assertEquals(3, session.position)
    }

    @Test
    fun `sans carte chargee la position vaut zero`() {
        assertEquals(0, Session().position)
    }
}
