package fr.appprepa.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSpeakerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun laSyntheseSInitialiseEnFrancais() = runBlocking {
        val speaker = AndroidSpeaker(context)
        try {
            assertTrue(
                "moteur TTS francais indisponible",
                withTimeout(15_000) { speaker.awaitReady() },
            )
        } finally {
            speaker.release()
        }
    }

    @Test
    fun speakNeRendLaMainQuALaFinDeLEnonce() = runBlocking {
        val speaker = AndroidSpeaker(context)
        try {
            withTimeout(15_000) { speaker.awaitReady() }
            val start = System.currentTimeMillis()
            withTimeout(25_000) {
                speaker.speak("Ceci est une phrase de test suffisamment longue pour durer.")
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                "speak est revenu en ${elapsed}ms, trop vite pour un enonce reel",
                elapsed > 500,
            )
        } finally {
            speaker.release()
        }
    }
}
