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
    fun `les fins de phrase naturelles ne sont jamais relancees`() {
        // Interrompre une reponse complete est la pire des reactions : on penche vers la
        // relance dans le doute, mais pas au point de couper quelqu'un qui a fini.
        listOf(
            "c'est tout",
            "la suite converge bien",
            "il converge aussi",
            "c'est vrai surtout",
            "le raisonnement est bien fait",
            "il n'y en a plus",
            "c'est trop",
            "il n'y en a aucun",
            "il y en a plusieurs",
            "on peut le faire",
            "elles y vont",
            "c'est n'importe quoi",
        ).forEach {
            assertFalse("« $it » est une fin de phrase", Utterance.looksUnfinished(it))
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
