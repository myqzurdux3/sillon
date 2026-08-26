package fr.appprepa.core.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceTest {

    @Test
    fun `une phrase coupee sur une articulation appelle une suite`() {
        listOf(
            "la dérivée de ce produit vaut donc",
            "on applique le théorème des accroissements finis et",
            "c'est vrai parce",
            "la limite quand x tend vers",
            "il faut d'abord montrer que",
        ).forEach {
            assertTrue("« $it » devrait sembler inachevé", Utterance.looksUnfinished(it))
        }
    }

    @Test
    fun `une phrase qui se tient ne declenche rien`() {
        listOf(
            "la dérivée d'un produit vaut u prime v plus u v prime",
            "c'est le théorème de Rolle",
            "je ne sais pas",
            "cosinus de deux a égale deux cosinus carré a moins un",
            // Sur des fiches de maths, une lettre isolee est une variable, pas une elision.
            "la dérivée de sinus de a",
            "la suite converge vers un",
        ).forEach {
            assertFalse("« $it » ne devrait pas sembler inachevé", Utterance.looksUnfinished(it))
        }
    }

    @Test
    fun `une reponse d'un seul mot n'est pas une phrase en suspens`() {
        // « bien » est dans la liste des mots qui appellent une suite, mais seul il
        // constitue une reponse : relancer sur un mot unique serait insupportable.
        assertFalse(Utterance.looksUnfinished("bien"))
        assertFalse(Utterance.looksUnfinished(""))
    }

    @Test
    fun `recoller deux morceaux donne une phrase`() {
        assertEquals("u prime v plus u v prime", Utterance.join("u prime v", "plus u v prime"))
        assertEquals("seul", Utterance.join("", "seul"))
        assertEquals("seul", Utterance.join("seul", "  "))
    }
}
