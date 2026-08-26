package fr.appprepa.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EaseTest {
    @Test
    fun `garde l'ease telle quelle quand la carte a quatre boutons`() {
        assertEquals(Ease.EASY, Ease.EASY.clampTo(4))
        assertEquals(Ease.AGAIN, Ease.AGAIN.clampTo(4))
    }

    @Test
    fun `rabat l'ease sur le dernier bouton disponible`() {
        assertEquals(Ease.HARD, Ease.EASY.clampTo(2))
        assertEquals(Ease.GOOD, Ease.EASY.clampTo(3))
    }

    @Test
    fun `ne descend jamais sous le premier bouton`() {
        assertEquals(Ease.AGAIN, Ease.AGAIN.clampTo(2))
    }

    @Test
    fun `traite un button count aberrant comme quatre boutons`() {
        assertEquals(Ease.EASY, Ease.EASY.clampTo(0))
        assertEquals(Ease.EASY, Ease.EASY.clampTo(9))
    }

    @Test
    fun `derive l'ease depuis le verdict`() {
        assertEquals(Ease.AGAIN, Ease.fromVerdict(Verdict.FAUX))
        assertEquals(Ease.HARD, Ease.fromVerdict(Verdict.PARTIEL))
        assertEquals(Ease.GOOD, Ease.fromVerdict(Verdict.CORRECT))
    }
}
