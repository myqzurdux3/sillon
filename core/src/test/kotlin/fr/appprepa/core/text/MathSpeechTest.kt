package fr.appprepa.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathSpeechTest {

    @Test
    fun `retire les delimiteurs inline`() {
        assertEquals("x", MathSpeech.verbalize("""\(x\)"""))
        assertEquals("x", MathSpeech.verbalize("""${'$'}x${'$'}"""))
    }

    @Test
    fun `nomme les fonctions usuelles`() {
        assertEquals(
            "cosinus de x",
            MathSpeech.verbalize("""\(\cos(x)\)"""),
        )
        assertTrue(MathSpeech.verbalize("""\(\ln(x)\)""").contains("logarithme"))
    }

    @Test
    fun `nomme les lettres grecques`() {
        assertTrue(MathSpeech.verbalize("""\(2\pi\)""").contains("pi"))
        assertTrue(MathSpeech.verbalize("""\(\alpha + \beta\)""").contains("alpha"))
    }

    @Test
    fun `dit les fractions`() {
        assertEquals("pi sur 2", MathSpeech.verbalize("""\(\frac{\pi}{2}\)"""))
        assertEquals("1 sur 2", MathSpeech.verbalize("""\(\dfrac{1}{2}\)"""))
    }

    @Test
    fun `dit les puissances et les indices`() {
        assertEquals("x au carré", MathSpeech.verbalize("""\(x^2\)"""))
        assertEquals("x puissance n", MathSpeech.verbalize("""\(x^n\)"""))
        assertEquals("a indice n", MathSpeech.verbalize("""\(a_n\)"""))
    }

    @Test
    fun `dit les operateurs`() {
        assertEquals("x égale y", MathSpeech.verbalize("""\(x = y\)"""))
        assertTrue(MathSpeech.verbalize("""\(x \pm y\)""").contains("plus ou moins"))
        assertTrue(MathSpeech.verbalize("""\(n \in \mathbb{N}\)""").contains("appartient à"))
    }

    @Test
    fun `supprime les commandes d'espacement et de taille`() {
        val spoken = MathSpeech.verbalize("""\(\cos\!\left(\frac{\pi}{2} - x\right)\)""")
        assertFalse("aucune commande LaTeX ne doit survivre : $spoken", spoken.contains("\\"))
        assertTrue(spoken.contains("cosinus"))
    }

    @Test
    fun `laisse le texte ordinaire intact`() {
        assertEquals(
            "Théorème des segments emboîtés",
            MathSpeech.verbalize("Théorème des segments emboîtés"),
        )
    }

    @Test
    fun `traite une carte reelle sans laisser de backslash`() {
        val carte = """\(\cos(-x) = \cos(x)\) et \(\cos(x + 2\pi) = \cos(x)\). """ +
            """\(\cos\!\left(\frac{\pi}{2} - x\right) = \sin(x)\)."""
        val spoken = MathSpeech.verbalize(carte)
        assertFalse("il reste du LaTeX : $spoken", spoken.contains("\\"))
        assertFalse(spoken.contains("{"))
        assertTrue(spoken.contains("cosinus"))
        assertTrue(spoken.contains("sinus"))
    }

    @Test
    fun `normalise les espaces`() {
        assertEquals("a plus b", MathSpeech.verbalize("""  \(a   +   b\)   """))
    }

    @Test
    fun `separe un coefficient de ce qui le suit`() {
        // « 2pi » et « 2sinus » sont illisibles a l'oreille.
        assertEquals("2 pi", MathSpeech.verbalize("""\(2\pi\)"""))
        assertTrue(MathSpeech.verbalize("""\(2\sin(a)\)""").contains("2 sinus"))
    }

    @Test
    fun `separe deux fonctions accolees`() {
        val spoken = MathSpeech.verbalize("""\(2\sin(a)\cos(a)\)""")
        assertFalse("fonctions collees : $spoken", spoken.contains("acosinus"))
        assertTrue(spoken.contains("sinus de a"))
        assertTrue(spoken.contains("cosinus de a"))
    }

    @Test
    fun `laisse les apostrophes francaises tranquilles`() {
        // « d'un » ne doit pas devenir « d prime un » : le prime n'existe qu'en formule.
        assertEquals(
            "Divisibilité d'un produit d'entiers consécutifs",
            MathSpeech.verbalize("Divisibilité d'un produit d'entiers consécutifs"),
        )
        assertEquals(
            "au sens de l'inclusion",
            MathSpeech.verbalize("au sens de l'inclusion"),
        )
    }

    @Test
    fun `dit encore la derivee a l'interieur d'une formule`() {
        assertTrue(MathSpeech.verbalize("""\(f'(x)\)""").contains("prime"))
    }

    @Test
    fun `ne confond pas cdots avec cdot`() {
        val spoken = MathSpeech.verbalize("""\(n(n+1)\cdots(n+k)\)""")
        assertFalse("« foiss » : $spoken", spoken.contains("foiss"))
        assertTrue(spoken.contains("et ainsi de suite"))
    }

    @Test
    fun `dit l'etoile des ensembles prives de zero`() {
        assertTrue(MathSpeech.verbalize("""\(\mathbb{N}^*\)""").contains("étoile"))
    }

    @Test
    fun `retire le HTML residuel`() {
        assertEquals(
            "Si x est nul",
            MathSpeech.verbalize("<div class=\"bloc\">Si x est nul</div>"),
        )
    }

    @Test
    fun `survit a une formule non refermee`() {
        // Une carte tronquee laisse un delimiteur ouvert : il ne doit pas etre prononce.
        val spoken = MathSpeech.verbalize("""Une somme vide : \(n""")
        assertFalse("delimiteur restant : $spoken", spoken.contains("\\"))
        assertTrue(spoken.startsWith("Une somme vide"))
    }

    @Test
    fun `dit vrai quand le texte contient des maths`() {
        assertTrue(MathSpeech.containsMath("""\(x^2\)"""))
        assertFalse(MathSpeech.containsMath("Théorème des segments emboîtés"))
    }
}
