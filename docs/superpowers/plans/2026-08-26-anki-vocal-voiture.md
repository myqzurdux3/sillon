# Révision Anki vocale en voiture — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une application Android qui interroge oralement l'utilisateur sur ses cartes Anki dues pendant qu'il conduit, juge ses réponses parlées et écrit les notes dans AnkiDroid, sans aucune interaction à l'écran.

**Architecture:** Machine à états pure dans un module Kotlin/JVM sans dépendance Android (`:core`), pilotée par un module Android d'adaptateurs (`:app`). Le moteur émet des effets ; l'exécuteur les réalise contre AnkiDroid, la synthèse vocale, la reconnaissance vocale et l'API Claude. Le LLM propose une note, le code la valide, la borne et l'écrit — avec un tour de décalage qui rend « annule » possible.

**Tech Stack:** Kotlin 2.1.21, Gradle 8.14.3, AGP 8.13.2, compileSdk 36, minSdk 31, Jetpack Compose, kotlinx.serialization, kotlinx.coroutines, OkHttp, `TextToSpeech` et `SpeechRecognizer` d'Android, ContentProvider AnkiDroid, API Claude Messages.

**Spec:** `docs/superpowers/specs/2026-08-26-anki-vocal-voiture-design.md`

## Global Constraints

- Package racine : `fr.appprepa`. Module `:core` en `fr.appprepa.core`, module `:app` en `fr.appprepa.app`.
- `:core` ne doit contenir **aucun** import `android.*`. C'est la règle qui rend la boucle testable sans émulateur ; toute violation est un échec de revue.
- Toute la langue visible et parlée est le français. Les identifiants de code restent en anglais.
- Le JDK de build est `/usr/lib/jvm/java-17-openjdk-amd64` (`JAVA_HOME`), jvmTarget 17.
- `ANDROID_HOME=/home/user/Android/Sdk`. Émulateur de test : `emulator-5554`, API 37, x86_64.
- Modèle Claude : `claude-opus-5`, avec `output_config.effort = "low"` sur les deux appels de la boucle — ce sont des tâches courtes où la latence prime.
- Autorité AnkiDroid : `com.ichi2.anki.flashcards`. Permission : `com.ichi2.anki.permission.READ_WRITE_DATABASE`.
- Mode d'écriture par défaut : `JOURNAL_ONLY`. Aucune écriture réelle dans Anki tant que l'utilisateur n'a pas basculé explicitement en `WRITE_THROUGH`.
- Les tests `:core` tournent avec `./gradlew :core:test`. Ils doivent rester sous la seconde au total.

---

### Task 1: Squelette Gradle et modules

**Statut : déjà réalisé pendant la phase de cadrage.** Cette tâche est décrite pour que le
plan soit reproductible depuis zéro et pour que la revue puisse vérifier l'existant.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`, `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`
- Create: `local.properties` (non versionné, contient `sdk.dir`)

**Interfaces:**
- Consumes: rien
- Produces: deux modules Gradle, `:core` (Kotlin/JVM) et `:app` (application Android), avec le catalogue de versions `libs`

- [x] **Step 1: Écrire les fichiers de build et le manifeste**

Le manifeste déclare les permissions et, point facile à oublier, le bloc `<queries>` sans
lequel le ContentProvider d'AnkiDroid est invisible depuis API 30 :

```xml
<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE" />

<queries>
    <package android:name="com.ichi2.anki" />
    <intent><action android:name="android.speech.RecognitionService" /></intent>
    <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
</queries>
```

- [x] **Step 2: Générer le wrapper Gradle**

Run: `gradle wrapper --gradle-version 8.14.3 --distribution-type bin`

- [x] **Step 3: Vérifier que le squelette compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug :core:build`
Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**

```bash
git add -A && git commit -m "chore: squelette Gradle, modules core et app"
```

---

### Task 2: Modèle de domaine et ports

**Files:**
- Create: `core/src/main/kotlin/fr/appprepa/core/model/Model.kt`
- Create: `core/src/main/kotlin/fr/appprepa/core/ports/Ports.kt`
- Test: `core/src/test/kotlin/fr/appprepa/core/model/EaseTest.kt`

**Interfaces:**
- Consumes: rien
- Produces: `ReviewCard`, `Ease`, `Verdict`, `Judgement`, `ReformulatedQuestion`,
  `SessionMemory`, `SessionStats`, `JournalRecord`, `WriteMode`, `clampTo`, et les six
  interfaces de port `AnkiGateway`, `Tutor`, `Speaker`, `Listener`, `Journal`, `Clock`.

- [ ] **Step 1: Écrire le test qui échoue**

Le bornage de l'ease est la seule logique du modèle, et c'est celle qui protège la
collection Anki d'une note hors bornes.

```kotlin
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
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*EaseTest*'`
Expected: FAIL — `Unresolved reference: Ease`

- [ ] **Step 3: Écrire le modèle**

```kotlin
package fr.appprepa.core.model

/** Une carte due, telle que lue dans AnkiDroid. */
data class ReviewCard(
    val noteId: Long,
    val cardOrd: Int,
    val deckName: String,
    /** `question_simple` : sans CSS ni HTML. */
    val question: String,
    /** `answer_pure` : sans CSS, sans reprise du recto. */
    val answer: String,
    /** Nombre de boutons de réponse, 2 à 4 selon la carte. */
    val buttonCount: Int,
    val hasMedia: Boolean,
)

enum class Verdict { CORRECT, PARTIEL, FAUX }

enum class Ease(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
    ;

    /**
     * Ramène la note dans l'intervalle réellement accepté par la carte.
     * Un `buttonCount` hors de 2..4 est traité comme 4 : mieux vaut une note
     * plausible qu'un plantage sur une valeur inattendue du provider.
     */
    fun clampTo(buttonCount: Int): Ease {
        val max = if (buttonCount in 2..4) buttonCount else 4
        return entries.first { it.value == value.coerceIn(1, max) }
    }

    companion object {
        fun fromVerdict(verdict: Verdict): Ease = when (verdict) {
            Verdict.FAUX -> AGAIN
            Verdict.PARTIEL -> HARD
            Verdict.CORRECT -> GOOD
        }

        fun fromValue(value: Int): Ease? = entries.firstOrNull { it.value == value }
    }
}

/** Question orale produite par le LLM à partir de la carte. */
data class ReformulatedQuestion(
    val question: String,
    val expectedPoints: List<String>,
)

/** Jugement d'une réponse parlée. */
data class Judgement(
    val verdict: Verdict,
    val ease: Ease,
    val spokenFeedback: String,
    val missed: List<String>,
    val formulationNote: String?,
    val topic: String?,
)

/** Contexte réinjecté dans les prompts, de taille bornée. */
data class SessionMemory(
    val missedTopics: List<String> = emptyList(),
    val formulationNotes: List<String> = emptyList(),
    val answered: Int = 0,
    val correct: Int = 0,
)

data class SessionStats(
    val answered: Int = 0,
    val correct: Int = 0,
    val skipped: Int = 0,
    val committed: Int = 0,
)

enum class WriteMode { JOURNAL_ONLY, WRITE_THROUGH }

/** Une ligne du journal, écrite pour chaque carte traitée. */
data class JournalRecord(
    val atMs: Long,
    val noteId: Long,
    val cardOrd: Int,
    val deckName: String,
    val question: String,
    val transcript: String,
    val proposedEase: Ease?,
    val committedEase: Ease?,
    val verdict: Verdict?,
    val mode: WriteMode,
    val note: String? = null,
)
```

- [ ] **Step 4: Écrire les ports**

```kotlin
package fr.appprepa.core.ports

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory

/** Accès à la collection AnkiDroid. */
interface AnkiGateway {
    suspend fun dueCards(deckId: Long?, limit: Int): List<ReviewCard>
    suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long)
    suspend fun decks(): Map<Long, String>
}

/** Le LLM, vu par le moteur. */
interface Tutor {
    suspend fun reformulate(card: ReviewCard, memory: SessionMemory): ReformulatedQuestion
    suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
    ): Judgement
    suspend fun explain(card: ReviewCard): String
}

/** Synthèse vocale. `speak` ne rend la main qu'à la fin de l'énoncé. */
interface Speaker {
    suspend fun speak(text: String)
    fun stop()
}

sealed interface ListenResult {
    data class Transcript(val text: String) : ListenResult
    data object Silence : ListenResult
    data class Failure(val cause: String) : ListenResult
}

/** Reconnaissance vocale. */
interface Listener {
    suspend fun listen(timeoutMs: Long): ListenResult
}

interface Journal {
    suspend fun record(entry: JournalRecord)
}

interface Clock {
    fun nowMs(): Long
}
```

- [ ] **Step 5: Lancer les tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*EaseTest*'`
Expected: PASS, 5 tests

- [ ] **Step 6: Commit**

```bash
git add core/src && git commit -m "feat(core): modele de domaine et ports"
```

---

### Task 3: Parseur de commandes vocales

**Files:**
- Create: `core/src/main/kotlin/fr/appprepa/core/voice/VoiceCommandParser.kt`
- Test: `core/src/test/kotlin/fr/appprepa/core/voice/VoiceCommandParserTest.kt`

**Interfaces:**
- Consumes: `Ease` (Task 2)
- Produces: `VoiceCommand` (scellée : `Correct(ease)`, `Repeat`, `Skip`, `Explain`, `Undo`,
  `Stop`, `None`) et `VoiceCommandParser.parse(transcript: String): VoiceCommand`

- [ ] **Step 1: Écrire le test qui échoue**

Le piège de ce parseur n'est pas de reconnaître les commandes, c'est de ne **pas** les
reconnaître au milieu d'une phrase de réponse. « c'est un raisonnement facile à mener »
ne doit pas valoir « facile ».

```kotlin
package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun `reconnait les corrections de note`() {
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse("encore"))
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse("à revoir"))
        assertEquals(VoiceCommand.Correct(Ease.HARD), VoiceCommandParser.parse("difficile"))
        assertEquals(VoiceCommand.Correct(Ease.GOOD), VoiceCommandParser.parse("bien"))
        assertEquals(VoiceCommand.Correct(Ease.EASY), VoiceCommandParser.parse("facile"))
    }

    @Test
    fun `ignore les accents et la casse`() {
        assertEquals(VoiceCommand.Correct(Ease.AGAIN), VoiceCommandParser.parse("A REVOIR"))
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("Répète"))
    }

    @Test
    fun `reconnait les commandes de navigation`() {
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("repete"))
        assertEquals(VoiceCommand.Skip, VoiceCommandParser.parse("passe"))
        assertEquals(VoiceCommand.Explain, VoiceCommandParser.parse("je seche"))
        assertEquals(VoiceCommand.Explain, VoiceCommandParser.parse("je ne sais pas"))
        assertEquals(VoiceCommand.Undo, VoiceCommandParser.parse("annule"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("stop"))
    }

    @Test
    fun `tolere la ponctuation et les espaces`() {
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("  Stop.  "))
        assertEquals(VoiceCommand.Correct(Ease.EASY), VoiceCommandParser.parse("facile !"))
    }

    @Test
    fun `ne declenche pas sur une commande noyee dans une phrase`() {
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("c'est un calcul facile a mener"))
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("il faut passer par la contraposee"))
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("non, la reponse est l'inverse"))
    }

    @Test
    fun `accepte une commande accompagnee d'un mot de politesse`() {
        assertEquals(VoiceCommand.Correct(Ease.EASY), VoiceCommandParser.parse("c'etait facile"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("stop s'il te plait"))
    }

    @Test
    fun `renvoie None sur une reponse ordinaire`() {
        assertEquals(
            VoiceCommand.None,
            VoiceCommandParser.parse("le theoreme de Rolle s'applique sur un intervalle ferme"),
        )
    }

    @Test
    fun `renvoie None sur une chaine vide`() {
        assertEquals(VoiceCommand.None, VoiceCommandParser.parse("   "))
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*VoiceCommandParserTest*'`
Expected: FAIL — `Unresolved reference: VoiceCommand`

- [ ] **Step 3: Écrire le parseur**

La règle qui fait passer les tests « noyée dans une phrase » : une commande n'est reconnue
que si l'énoncé entier, une fois les mots de remplissage retirés, se réduit à la commande.

```kotlin
package fr.appprepa.core.voice

import fr.appprepa.core.model.Ease
import java.text.Normalizer

sealed interface VoiceCommand {
    data class Correct(val ease: Ease) : VoiceCommand
    data object Repeat : VoiceCommand
    data object Skip : VoiceCommand
    data object Explain : VoiceCommand
    data object Undo : VoiceCommand
    data object Stop : VoiceCommand
    data object None : VoiceCommand
}

object VoiceCommandParser {

    /** Mots que l'on retire avant de comparer : ils n'ajoutent pas de sens. */
    private val FILLER = setOf(
        "c", "cetait", "cest", "etait", "est", "ca", "s", "sil", "te", "plait",
        "la", "le", "les", "de", "du", "on", "met", "mets", "je", "dirais", "plutot", "alors",
    )

    private val EXACT: List<Pair<Set<String>, VoiceCommand>> = listOf(
        setOf("encore", "a revoir", "revoir", "rate", "rater", "non") to
            VoiceCommand.Correct(Ease.AGAIN),
        setOf("difficile", "dur", "durs", "moyen") to VoiceCommand.Correct(Ease.HARD),
        setOf("bien", "correct", "ok", "oui", "bon") to VoiceCommand.Correct(Ease.GOOD),
        setOf("facile", "tres facile", "evident") to VoiceCommand.Correct(Ease.EASY),
        setOf("repete", "repeter", "pardon", "quoi", "redis") to VoiceCommand.Repeat,
        setOf("passe", "passer", "suivante", "suivant", "saute") to VoiceCommand.Skip,
        setOf(
            "explique", "expliquer", "je seche", "seche", "je ne sais pas",
            "sais pas", "aucune idee",
        ) to VoiceCommand.Explain,
        setOf("annule", "annuler", "reviens", "retour") to VoiceCommand.Undo,
        setOf("stop", "pause", "termine", "terminer", "fini", "arrete") to VoiceCommand.Stop,
    )

    fun parse(transcript: String): VoiceCommand {
        val normalized = normalize(transcript)
        if (normalized.isEmpty()) return VoiceCommand.None

        val stripped = normalized.split(' ')
            .filter { it.isNotEmpty() && it !in FILLER }
            .joinToString(" ")
        if (stripped.isEmpty()) return VoiceCommand.None

        return EXACT.firstOrNull { (phrases, _) -> stripped in phrases }?.second
            ?: VoiceCommand.None
    }

    /** Minuscules, accents retirés, ponctuation retirée, espaces normalisés. */
    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
```

- [ ] **Step 4: Lancer les tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*VoiceCommandParserTest*'`
Expected: PASS, 8 tests

- [ ] **Step 5: Commit**

```bash
git add core/src && git commit -m "feat(core): parseur de commandes vocales francaises"
```

---

### Task 4: Mémoire de session

**Files:**
- Create: `core/src/main/kotlin/fr/appprepa/core/memory/SessionMemoryBuilder.kt`
- Test: `core/src/test/kotlin/fr/appprepa/core/memory/SessionMemoryBuilderTest.kt`

**Interfaces:**
- Consumes: `SessionMemory`, `Judgement`, `Verdict` (Task 2)
- Produces: `SessionMemoryBuilder.absorb(memory, judgement): SessionMemory` et
  `SessionMemoryBuilder.render(memory): String`

- [ ] **Step 1: Écrire le test qui échoue**

```kotlin
package fr.appprepa.core.memory

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryBuilderTest {

    private fun judgement(
        verdict: Verdict = Verdict.CORRECT,
        topic: String? = null,
        formulation: String? = null,
    ) = Judgement(
        verdict = verdict,
        ease = Ease.fromVerdict(verdict),
        spokenFeedback = "peu importe",
        missed = emptyList(),
        formulationNote = formulation,
        topic = topic,
    )

    @Test
    fun `compte les reponses et les bonnes reponses`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.CORRECT))
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX))
        assertEquals(2, memory.answered)
        assertEquals(1, memory.correct)
    }

    @Test
    fun `retient le theme uniquement quand la reponse n'est pas correcte`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.CORRECT, topic = "Rolle"))
        assertEquals(emptyList<String>(), memory.missedTopics)

        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = "Cauchy"))
        assertEquals(listOf("Cauchy"), memory.missedTopics)
    }

    @Test
    fun `ne repete pas deux fois le meme theme rate`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = "Cauchy"))
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.PARTIEL, topic = "Cauchy"))
        assertEquals(listOf("Cauchy"), memory.missedTopics)
    }

    @Test
    fun `borne les themes rates a huit entrees en gardant les plus recentes`() {
        var memory = SessionMemory()
        repeat(12) { index ->
            memory = SessionMemoryBuilder.absorb(
                memory,
                judgement(Verdict.FAUX, topic = "theme$index"),
            )
        }
        assertEquals(8, memory.missedTopics.size)
        assertEquals("theme11", memory.missedTopics.last())
        assertEquals("theme4", memory.missedTopics.first())
    }

    @Test
    fun `borne les remarques de formulation a cinq entrees`() {
        var memory = SessionMemory()
        repeat(7) { index ->
            memory = SessionMemoryBuilder.absorb(
                memory,
                judgement(formulation = "remarque$index"),
            )
        }
        assertEquals(5, memory.formulationNotes.size)
        assertEquals("remarque6", memory.formulationNotes.last())
    }

    @Test
    fun `ignore un theme vide ou absent`() {
        var memory = SessionMemory()
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = null))
        memory = SessionMemoryBuilder.absorb(memory, judgement(Verdict.FAUX, topic = "  "))
        assertEquals(emptyList<String>(), memory.missedTopics)
    }

    @Test
    fun `rend une memoire vide comme chaine vide`() {
        assertEquals("", SessionMemoryBuilder.render(SessionMemory()))
    }

    @Test
    fun `rend une memoire non vide sous 400 caracteres`() {
        var memory = SessionMemory()
        repeat(20) { index ->
            memory = SessionMemoryBuilder.absorb(
                memory,
                judgement(
                    verdict = Verdict.FAUX,
                    topic = "un theme plutot long numero $index",
                    formulation = "une remarque de formulation plutot longue numero $index",
                ),
            )
        }
        val rendered = SessionMemoryBuilder.render(memory)
        assertTrue("rendu de ${rendered.length} caracteres", rendered.length <= 400)
        assertTrue(rendered.contains("theme"))
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*SessionMemoryBuilderTest*'`
Expected: FAIL — `Unresolved reference: SessionMemoryBuilder`

- [ ] **Step 3: Écrire l'implémentation**

```kotlin
package fr.appprepa.core.memory

import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.Verdict

object SessionMemoryBuilder {

    private const val MAX_TOPICS = 8
    private const val MAX_NOTES = 5
    private const val MAX_RENDERED = 400

    fun absorb(memory: SessionMemory, judgement: Judgement): SessionMemory {
        val topic = judgement.topic?.trim()?.takeIf { it.isNotEmpty() }
        val missed = if (topic != null && judgement.verdict != Verdict.CORRECT) {
            (memory.missedTopics - topic + topic).takeLast(MAX_TOPICS)
        } else {
            memory.missedTopics
        }

        val note = judgement.formulationNote?.trim()?.takeIf { it.isNotEmpty() }
        val notes = if (note != null) {
            (memory.formulationNotes + note).takeLast(MAX_NOTES)
        } else {
            memory.formulationNotes
        }

        return memory.copy(
            missedTopics = missed,
            formulationNotes = notes,
            answered = memory.answered + 1,
            correct = memory.correct + if (judgement.verdict == Verdict.CORRECT) 1 else 0,
        )
    }

    /**
     * Rendu injecté dans les prompts. Tronqué à [MAX_RENDERED] caractères : le coût du
     * contexte doit rester constant, quelle que soit la longueur de la session.
     */
    fun render(memory: SessionMemory): String {
        if (memory.answered == 0) return ""

        val parts = buildList {
            add("Déjà traité : ${memory.answered} cartes, ${memory.correct} justes.")
            if (memory.missedTopics.isNotEmpty()) {
                add("Thèmes ratés : ${memory.missedTopics.joinToString(", ")}.")
            }
            if (memory.formulationNotes.isNotEmpty()) {
                add("Formulation : ${memory.formulationNotes.joinToString(" / ")}.")
            }
        }

        val rendered = parts.joinToString(" ")
        return if (rendered.length <= MAX_RENDERED) rendered else rendered.take(MAX_RENDERED - 1) + "…"
    }
}
```

- [ ] **Step 4: Lancer les tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*SessionMemoryBuilderTest*'`
Expected: PASS, 8 tests

- [ ] **Step 5: Commit**

```bash
git add core/src && git commit -m "feat(core): memoire de session glissante"
```

---

### Task 5: Types de la machine à états et boucle nominale

**Files:**
- Create: `core/src/main/kotlin/fr/appprepa/core/engine/SessionTypes.kt`
- Create: `core/src/main/kotlin/fr/appprepa/core/engine/ReviewSessionEngine.kt`
- Test: `core/src/test/kotlin/fr/appprepa/core/engine/NominalLoopTest.kt`

**Interfaces:**
- Consumes: tout le modèle (Task 2), `SessionMemoryBuilder` (Task 4)
- Produces: `Session`, `SessionState`, `Event`, `Effect`, `ListenKind`, `Assessment`,
  `CardInFlight`, `PendingAnswer`, `Reduction`, et
  `ReviewSessionEngine.reduce(session: Session, event: Event, nowMs: Long): Reduction`

Le moteur est une fonction pure : aucune coroutine, aucun accès réseau, aucun `android.*`.
C'est ce qui permet de rejouer une session entière en quelques millisecondes.

- [ ] **Step 1: Écrire le test qui échoue**

Ce test décrit la boucle heureuse de bout en bout, y compris le préchargement — le
déclenchement de `Reformulate` sur la carte suivante **au moment où l'écoute commence**
est la propriété qui masque la latence, donc elle est testée explicitement.

```kotlin
package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NominalLoopTest {

    private fun card(id: Long, media: Boolean = false) = ReviewCard(
        noteId = id,
        cardOrd = 0,
        deckName = "Prepa",
        question = "recto $id",
        answer = "verso $id",
        buttonCount = 4,
        hasMedia = media,
    )

    private fun reformulated(id: Long) =
        ReformulatedQuestion("question orale $id", listOf("point $id"))

    private fun judgement(verdict: Verdict = Verdict.CORRECT) = Judgement(
        verdict = verdict,
        ease = Ease.fromVerdict(verdict),
        spokenFeedback = "retour parle",
        missed = emptyList(),
        formulationNote = null,
        topic = "theme",
    )

    @Test
    fun `demarrer demande le chargement des cartes`() {
        val result = ReviewSessionEngine.reduce(Session(), Event.Start(deckId = 7L, limit = 30), 0L)
        assertEquals(SessionState.Loading, result.session.state)
        assertEquals(listOf(Effect.LoadCards(7L, 30)), result.effects)
    }

    @Test
    fun `les cartes chargees declenchent la reformulation de la premiere`() {
        val loading = Session(state = SessionState.Loading)
        val result = ReviewSessionEngine.reduce(
            loading,
            Event.CardsLoaded(listOf(card(1), card(2))),
            0L,
        )
        assertEquals(SessionState.Preparing(card(1)), result.session.state)
        assertEquals(listOf(card(2)), result.session.queue)
        assertEquals(
            listOf(Effect.Reformulate(card(1), result.session.memory)),
            result.effects,
        )
    }

    @Test
    fun `une carte a media est ecartee et journalisee sans etre notee`() {
        val loading = Session(state = SessionState.Loading)
        val result = ReviewSessionEngine.reduce(
            loading,
            Event.CardsLoaded(listOf(card(1, media = true), card(2))),
            1_000L,
        )
        assertEquals(SessionState.Preparing(card(2)), result.session.state)
        val records = result.effects.filterIsInstance<Effect.Record>()
        assertEquals(1, records.size)
        assertEquals(1L, records.single().entry.noteId)
        assertEquals(null, records.single().entry.proposedEase)
        assertEquals(1, result.session.stats.skipped)
    }

    @Test
    fun `aucune carte due termine la session`() {
        val loading = Session(state = SessionState.Loading)
        val result = ReviewSessionEngine.reduce(loading, Event.CardsLoaded(emptyList()), 0L)
        assertTrue(result.session.state is SessionState.Finished)
        assertTrue(result.effects.any { it is Effect.Speak })
        assertTrue(result.effects.contains(Effect.Finish))
    }

    @Test
    fun `la reformulation recue fait enoncer la question`() {
        val preparing = Session(state = SessionState.Preparing(card(1)), queue = listOf(card(2)))
        val result = ReviewSessionEngine.reduce(
            preparing,
            Event.Reformulated(card(1), reformulated(1)),
            5_000L,
        )
        val state = result.session.state as SessionState.Asking
        assertEquals("question orale 1", state.inFlight.question)
        assertEquals(5_000L, state.inFlight.askedAtMs)
        assertEquals(listOf(Effect.Speak("question orale 1")), result.effects)
    }

    @Test
    fun `la fin de l'enonce ouvre l'ecoute et precharge la carte suivante`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val asking = Session(state = SessionState.Asking(inFlight), queue = listOf(card(2)))
        val result = ReviewSessionEngine.reduce(asking, Event.SpeechFinished, 6_000L)

        assertEquals(SessionState.Listening(inFlight), result.session.state)
        assertTrue(result.effects.contains(Effect.Listen(ListenKind.ANSWER, 15_000L)))
        assertTrue(
            "le prechargement doit partir des le debut de l'ecoute",
            result.effects.contains(Effect.Reformulate(card(2), result.session.memory)),
        )
    }

    @Test
    fun `ne precharge pas quand la file est vide`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val asking = Session(state = SessionState.Asking(inFlight), queue = emptyList())
        val result = ReviewSessionEngine.reduce(asking, Event.SpeechFinished, 6_000L)
        assertEquals(listOf(Effect.Listen(ListenKind.ANSWER, 15_000L)), result.effects)
    }

    @Test
    fun `une reponse entendue part au jugement`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val listening = Session(state = SessionState.Listening(inFlight))
        val result = ReviewSessionEngine.reduce(
            listening,
            Event.Heard("le theoreme s'applique sur un intervalle ferme"),
            9_000L,
        )
        assertTrue(result.session.state is SessionState.Judging)
        assertEquals(
            listOf(
                Effect.Judge(
                    card = card(1),
                    expectedPoints = listOf("point 1"),
                    transcript = "le theoreme s'applique sur un intervalle ferme",
                    memory = listening.memory,
                ),
            ),
            result.effects,
        )
    }

    @Test
    fun `le jugement recu est enonce avec la note proposee`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val judging = Session(state = SessionState.Judging(inFlight, "ma reponse"))
        val result = ReviewSessionEngine.reduce(judging, Event.Judged(judgement()), 10_000L)

        assertTrue(result.session.state is SessionState.SpeakingVerdict)
        val spoken = result.effects.filterIsInstance<Effect.Speak>().single().text
        assertTrue(spoken.contains("retour parle"))
        assertTrue("la note proposee doit etre annoncee", spoken.contains("bien"))
    }

    @Test
    fun `la fin du verdict ouvre une fenetre de correction courte`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val speaking = Session(
            state = SessionState.SpeakingVerdict(inFlight, Assessment.Judged(judgement())),
        )
        val result = ReviewSessionEngine.reduce(speaking, Event.SpeechFinished, 12_000L)
        assertTrue(result.session.state is SessionState.AwaitingCorrection)
        assertEquals(listOf(Effect.Listen(ListenKind.CORRECTION, 3_000L)), result.effects)
    }

    @Test
    fun `le silence pendant la correction valide la note proposee`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.Judged(judgement())),
            queue = emptyList(),
        )
        val result = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 13_000L)

        assertEquals(Ease.GOOD, result.session.pending?.ease)
        assertEquals(1L, result.session.pending?.card?.noteId)
        assertEquals(1, result.session.memory.answered)
        assertEquals(1, result.session.memory.correct)
    }

    @Test
    fun `l'ecriture de la carte precedente n'a lieu qu'a la carte suivante`() {
        val inFlight = CardInFlight(card(2), "question orale 2", listOf("point 2"), 20_000L)
        val previous = PendingAnswerFixtures.pending(card(1), Ease.GOOD)
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.Judged(judgement())),
            pending = previous,
            queue = emptyList(),
        )
        val result = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 25_000L)

        val commits = result.effects.filterIsInstance<Effect.Commit>()
        assertEquals("seule la carte 1 doit etre ecrite", 1, commits.size)
        assertEquals(1L, commits.single().pending.card.noteId)
        assertEquals("la carte 2 reste en attente", 2L, result.session.pending?.card?.noteId)
    }

    @Test
    fun `la file vide termine la session en ecrivant la derniere note`() {
        val inFlight = CardInFlight(card(1), "question orale 1", listOf("point 1"), 5_000L)
        val awaiting = Session(
            state = SessionState.AwaitingCorrection(inFlight, Assessment.Judged(judgement())),
            queue = emptyList(),
        )
        val afterAnswer = ReviewSessionEngine.reduce(awaiting, Event.HeardNothing, 13_000L)
        assertTrue(afterAnswer.session.state is SessionState.Finished)
        assertTrue(afterAnswer.effects.filterIsInstance<Effect.Commit>().size == 1)
        assertTrue(afterAnswer.effects.contains(Effect.Finish))
    }
}

/** Petit constructeur partagé par les tests du moteur. */
object PendingAnswerFixtures {
    fun pending(card: fr.appprepa.core.model.ReviewCard, ease: Ease): PendingAnswer =
        PendingAnswer(
            card = card,
            ease = ease,
            timeTakenMs = 4_000L,
            record = fr.appprepa.core.model.JournalRecord(
                atMs = 0L,
                noteId = card.noteId,
                cardOrd = card.cardOrd,
                deckName = card.deckName,
                question = card.question,
                transcript = "",
                proposedEase = ease,
                committedEase = null,
                verdict = Verdict.CORRECT,
                mode = fr.appprepa.core.model.WriteMode.JOURNAL_ONLY,
            ),
        )
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*NominalLoopTest*'`
Expected: FAIL — `Unresolved reference: Session`

- [ ] **Step 3: Écrire les types de la machine à états**

```kotlin
package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.model.SessionStats
import fr.appprepa.core.model.WriteMode

/** La carte en cours de traitement, avec sa question orale. */
data class CardInFlight(
    val card: ReviewCard,
    val question: String,
    val expectedPoints: List<String>,
    val askedAtMs: Long,
)

/**
 * Une note décidée mais pas encore écrite dans Anki. Elle n'est validée qu'au moment où
 * la carte suivante est finalisée — c'est cette latence d'un tour qui rend « annule »
 * possible, alors que l'API AnkiDroid n'offre aucune annulation après écriture.
 */
data class PendingAnswer(
    val card: ReviewCard,
    val ease: Ease,
    val timeTakenMs: Long,
    val record: JournalRecord,
)

/** Ce qui est annoncé à l'utilisateur après sa réponse. */
sealed interface Assessment {
    /** Cas nominal : le LLM a jugé. */
    data class Judged(val judgement: Judgement) : Assessment

    /** Mode dégradé : on lit le verso, l'utilisateur se note lui-même. */
    data class SelfGrade(val answerText: String) : Assessment
}

sealed interface SessionState {
    data object Idle : SessionState
    data object Loading : SessionState

    /** La carte est connue, sa reformulation est en vol. */
    data class Preparing(val card: ReviewCard) : SessionState
    data class Asking(val inFlight: CardInFlight) : SessionState
    data class Listening(val inFlight: CardInFlight) : SessionState
    data class Judging(val inFlight: CardInFlight, val transcript: String) : SessionState
    data class SpeakingVerdict(
        val inFlight: CardInFlight,
        val assessment: Assessment,
    ) : SessionState
    data class AwaitingCorrection(
        val inFlight: CardInFlight,
        val assessment: Assessment,
    ) : SessionState
    data class Finished(val stats: SessionStats) : SessionState
    data class Failed(val reason: String) : SessionState
}

sealed interface Event {
    data class Start(val deckId: Long?, val limit: Int) : Event
    data class CardsLoaded(val cards: List<ReviewCard>) : Event
    data class Reformulated(val card: ReviewCard, val question: ReformulatedQuestion) : Event
    data object SpeechFinished : Event
    data class Heard(val transcript: String) : Event
    data object HeardNothing : Event
    data class Judged(val judgement: Judgement) : Event
    data class Explained(val text: String) : Event

    /** Le LLM ou le réseau a lâché : on bascule en lecture simple. */
    data class TutorFailed(val cause: String) : Event
    data class Fatal(val reason: String) : Event
    data object StopRequested : Event
}

enum class ListenKind { ANSWER, CORRECTION }

sealed interface Effect {
    data class Speak(val text: String) : Effect
    data class Listen(val kind: ListenKind, val timeoutMs: Long) : Effect
    data class LoadCards(val deckId: Long?, val limit: Int) : Effect
    data class Reformulate(val card: ReviewCard, val memory: SessionMemory) : Effect
    data class Judge(
        val card: ReviewCard,
        val expectedPoints: List<String>,
        val transcript: String,
        val memory: SessionMemory,
    ) : Effect
    data class Explain(val card: ReviewCard) : Effect
    data class Commit(val pending: PendingAnswer) : Effect
    data class Record(val entry: JournalRecord) : Effect
    data object Finish : Effect
}

/** L'intégralité de l'état d'une session. Immuable. */
data class Session(
    val state: SessionState = SessionState.Idle,
    val memory: SessionMemory = SessionMemory(),
    val queue: List<ReviewCard> = emptyList(),
    val prefetch: ReformulatedQuestion? = null,
    val prefetchFor: Long? = null,
    val pending: PendingAnswer? = null,
    val degraded: Boolean = false,
    val stats: SessionStats = SessionStats(),
    val writeMode: WriteMode = WriteMode.JOURNAL_ONLY,
    val deckId: Long? = null,
    val retriedAnswer: Boolean = false,
)

data class Reduction(val session: Session, val effects: List<Effect>)
```

- [ ] **Step 4: Écrire le moteur, boucle nominale seulement**

Les commandes vocales, l'annulation et le mode dégradé arrivent en Task 6. Ici, seul le
chemin heureux.

```kotlin
package fr.appprepa.core.engine

import fr.appprepa.core.memory.SessionMemoryBuilder
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict

object ReviewSessionEngine {

    const val ANSWER_TIMEOUT_MS = 15_000L
    const val CORRECTION_TIMEOUT_MS = 3_000L

    fun reduce(session: Session, event: Event, nowMs: Long): Reduction = when (event) {
        is Event.Start -> Reduction(
            session.copy(state = SessionState.Loading, deckId = event.deckId),
            listOf(Effect.LoadCards(event.deckId, event.limit)),
        )

        is Event.CardsLoaded -> onCardsLoaded(session, event, nowMs)
        is Event.Reformulated -> onReformulated(session, event, nowMs)
        Event.SpeechFinished -> onSpeechFinished(session, nowMs)
        is Event.Heard -> onHeard(session, event, nowMs)
        Event.HeardNothing -> onHeardNothing(session, nowMs)
        is Event.Judged -> onJudged(session, event, nowMs)
        is Event.Explained -> Reduction(session, emptyList())
        is Event.TutorFailed -> Reduction(session, emptyList())
        is Event.Fatal -> Reduction(
            session.copy(state = SessionState.Failed(event.reason)),
            listOf(Effect.Speak("Erreur : ${event.reason}"), Effect.Finish),
        )
        Event.StopRequested -> finish(session, nowMs)
    }

    // --- chargement -------------------------------------------------------

    private fun onCardsLoaded(session: Session, event: Event.CardsLoaded, nowMs: Long): Reduction {
        val (usable, skipped) = event.cards.partition { !it.hasMedia }
        val skipEffects = skipped.map { card ->
            Effect.Record(
                skippedRecord(session, card, nowMs, "carte a media, inutilisable en voiture"),
            )
        }
        val afterSkips = session.copy(
            stats = session.stats.copy(skipped = session.stats.skipped + skipped.size),
        )

        val first = usable.firstOrNull()
            ?: return Reduction(
                afterSkips.copy(state = SessionState.Finished(afterSkips.stats)),
                skipEffects + listOf(Effect.Speak("Aucune carte à réviser."), Effect.Finish),
            )

        return Reduction(
            afterSkips.copy(state = SessionState.Preparing(first), queue = usable.drop(1)),
            skipEffects + Effect.Reformulate(first, afterSkips.memory),
        )
    }

    private fun onReformulated(
        session: Session,
        event: Event.Reformulated,
        nowMs: Long,
    ): Reduction {
        val state = session.state
        // La reformulation attendue pour la carte affichée : on énonce.
        if (state is SessionState.Preparing && state.card.noteId == event.card.noteId) {
            val inFlight = CardInFlight(
                card = state.card,
                question = event.question.question,
                expectedPoints = event.question.expectedPoints,
                askedAtMs = nowMs,
            )
            return Reduction(
                session.copy(state = SessionState.Asking(inFlight), retriedAnswer = false),
                listOf(Effect.Speak(event.question.question)),
            )
        }
        // Sinon c'est un préchargement : on le range pour la carte suivante.
        return Reduction(
            session.copy(prefetch = event.question, prefetchFor = event.card.noteId),
            emptyList(),
        )
    }

    // --- énoncé et écoute -------------------------------------------------

    private fun onSpeechFinished(session: Session, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Asking -> {
                val prefetchEffect = session.queue.firstOrNull()
                    ?.takeIf { !session.degraded && session.prefetchFor != it.noteId }
                    ?.let { Effect.Reformulate(it, session.memory) }
                Reduction(
                    session.copy(state = SessionState.Listening(state.inFlight)),
                    listOfNotNull(
                        Effect.Listen(ListenKind.ANSWER, ANSWER_TIMEOUT_MS),
                        prefetchEffect,
                    ),
                )
            }

            is SessionState.SpeakingVerdict -> Reduction(
                session.copy(
                    state = SessionState.AwaitingCorrection(state.inFlight, state.assessment),
                ),
                listOf(Effect.Listen(ListenKind.CORRECTION, CORRECTION_TIMEOUT_MS)),
            )

            else -> Reduction(session, emptyList())
        }

    private fun onHeard(session: Session, event: Event.Heard, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Listening -> Reduction(
                session.copy(state = SessionState.Judging(state.inFlight, event.transcript)),
                listOf(
                    Effect.Judge(
                        card = state.inFlight.card,
                        expectedPoints = state.inFlight.expectedPoints,
                        transcript = event.transcript,
                        memory = session.memory,
                    ),
                ),
            )

            is SessionState.AwaitingCorrection -> settle(session, state, null, nowMs)

            else -> Reduction(session, emptyList())
        }

    private fun onHeardNothing(session: Session, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.AwaitingCorrection -> settle(session, state, null, nowMs)
            else -> Reduction(session, emptyList())
        }

    private fun onJudged(session: Session, event: Event.Judged, nowMs: Long): Reduction {
        val state = session.state as? SessionState.Judging ?: return Reduction(session, emptyList())
        val bounded = event.judgement.copy(
            ease = event.judgement.ease.clampTo(state.inFlight.card.buttonCount),
        )
        return Reduction(
            session.copy(
                state = SessionState.SpeakingVerdict(state.inFlight, Assessment.Judged(bounded)),
            ),
            listOf(Effect.Speak("${bounded.spokenFeedback} Je mets ${label(bounded.ease)}.")),
        )
    }

    // --- validation d'une carte ------------------------------------------

    /**
     * Fige la note de la carte courante et écrit celle de la carte précédente.
     * [override] force une note dictée par l'utilisateur ; `null` retient celle proposée.
     */
    internal fun settle(
        session: Session,
        state: SessionState.AwaitingCorrection,
        override: Ease?,
        nowMs: Long,
    ): Reduction {
        val assessment = state.assessment
        val judgement = (assessment as? Assessment.Judged)?.judgement
        val ease = (override ?: judgement?.ease ?: Ease.AGAIN)
            .clampTo(state.inFlight.card.buttonCount)

        val record = JournalRecord(
            atMs = nowMs,
            noteId = state.inFlight.card.noteId,
            cardOrd = state.inFlight.card.cardOrd,
            deckName = state.inFlight.card.deckName,
            question = state.inFlight.question,
            transcript = "",
            proposedEase = judgement?.ease,
            committedEase = if (session.writeMode == fr.appprepa.core.model.WriteMode.WRITE_THROUGH) {
                ease
            } else {
                null
            },
            verdict = judgement?.verdict,
            mode = session.writeMode,
        )

        val commitEffects = session.pending?.let {
            listOf(Effect.Commit(it), Effect.Record(it.record))
        } ?: emptyList()

        val memory = judgement?.let { SessionMemoryBuilder.absorb(session.memory, it) }
            ?: session.memory

        val settled = session.copy(
            memory = memory,
            pending = PendingAnswer(
                card = state.inFlight.card,
                ease = ease,
                timeTakenMs = nowMs - state.inFlight.askedAtMs,
                record = record.copy(committedEase = ease),
            ),
            stats = session.stats.copy(
                answered = session.stats.answered + 1,
                correct = session.stats.correct +
                    if (judgement?.verdict == Verdict.CORRECT) 1 else 0,
                committed = session.stats.committed + commitEffects.count { it is Effect.Commit },
            ),
        )

        return advance(settled, commitEffects, nowMs)
    }

    /** Passe à la carte suivante, ou termine la session si la file est vide. */
    internal fun advance(session: Session, carried: List<Effect>, nowMs: Long): Reduction {
        val next = session.queue.firstOrNull()
            ?: return finish(session, nowMs, carried)

        val rest = session.queue.drop(1)

        if (session.degraded) {
            val inFlight = CardInFlight(next, next.question, emptyList(), nowMs)
            return Reduction(
                session.copy(
                    state = SessionState.Asking(inFlight),
                    queue = rest,
                    retriedAnswer = false,
                ),
                carried + Effect.Speak(next.question),
            )
        }

        val ready = session.prefetch?.takeIf { session.prefetchFor == next.noteId }
        return if (ready != null) {
            val inFlight = CardInFlight(next, ready.question, ready.expectedPoints, nowMs)
            Reduction(
                session.copy(
                    state = SessionState.Asking(inFlight),
                    queue = rest,
                    prefetch = null,
                    prefetchFor = null,
                    retriedAnswer = false,
                ),
                carried + Effect.Speak(ready.question),
            )
        } else {
            Reduction(
                session.copy(state = SessionState.Preparing(next), queue = rest),
                carried + Effect.Reformulate(next, session.memory),
            )
        }
    }

    /** Termine : la dernière note en attente est écrite avant de rendre la main. */
    internal fun finish(
        session: Session,
        nowMs: Long,
        carried: List<Effect> = emptyList(),
    ): Reduction {
        val flush = session.pending?.let {
            listOf(Effect.Commit(it), Effect.Record(it.record))
        } ?: emptyList()
        val stats = session.stats.copy(
            committed = session.stats.committed + flush.count { it is Effect.Commit },
        )
        return Reduction(
            session.copy(state = SessionState.Finished(stats), pending = null, stats = stats),
            carried + flush + listOf(
                Effect.Speak(
                    "Session terminée. ${stats.answered} cartes, ${stats.correct} justes.",
                ),
                Effect.Finish,
            ),
        )
    }

    private fun skippedRecord(
        session: Session,
        card: ReviewCard,
        nowMs: Long,
        why: String,
    ) = JournalRecord(
        atMs = nowMs,
        noteId = card.noteId,
        cardOrd = card.cardOrd,
        deckName = card.deckName,
        question = card.question,
        transcript = "",
        proposedEase = null,
        committedEase = null,
        verdict = null,
        mode = session.writeMode,
        note = why,
    )

    internal fun label(ease: Ease): String = when (ease) {
        Ease.AGAIN -> "à revoir"
        Ease.HARD -> "difficile"
        Ease.GOOD -> "bien"
        Ease.EASY -> "facile"
    }
}
```

- [ ] **Step 5: Lancer les tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*NominalLoopTest*'`
Expected: PASS, 13 tests

- [ ] **Step 6: Commit**

```bash
git add core/src && git commit -m "feat(core): machine a etats, boucle nominale"
```

---

### Task 6: Commandes vocales, annulation et mode dégradé

**Files:**
- Modify: `core/src/main/kotlin/fr/appprepa/core/engine/ReviewSessionEngine.kt`
- Test: `core/src/test/kotlin/fr/appprepa/core/engine/CommandsAndDegradedTest.kt`

**Interfaces:**
- Consumes: tout ce que produit Task 5, plus `VoiceCommandParser` (Task 3)
- Produces: aucun type nouveau — le moteur gère désormais `VoiceCommand`, `Event.TutorFailed`
  et `Event.Explained`, et bascule `Session.degraded`.

- [ ] **Step 1: Écrire le test qui échoue**

```kotlin
package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsAndDegradedTest {

    private fun card(id: Long) = ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, false)

    private fun inFlight(id: Long) =
        CardInFlight(card(id), "question orale $id", listOf("point $id"), 1_000L)

    private fun judgement(verdict: Verdict = Verdict.CORRECT) = Judgement(
        verdict, Ease.fromVerdict(verdict), "retour", emptyList(), null, "theme",
    )

    private fun listening(id: Long, queue: List<ReviewCard> = emptyList()) =
        Session(state = SessionState.Listening(inFlight(id)), queue = queue)

    private fun awaiting(id: Long, queue: List<ReviewCard> = emptyList()) = Session(
        state = SessionState.AwaitingCorrection(inFlight(id), Assessment.Judged(judgement())),
        queue = queue,
    )

    @Test
    fun `repete reenonce la question sans consommer la carte`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.Heard("repete"), 2_000L)
        assertTrue(result.session.state is SessionState.Asking)
        assertEquals(listOf(Effect.Speak("question orale 1")), result.effects)
    }

    @Test
    fun `passe saute la carte sans la noter`() {
        val result = ReviewSessionEngine.reduce(
            listening(1, queue = listOf(card(2))),
            Event.Heard("passe"),
            2_000L,
        )
        assertNull("une carte passee ne doit rien mettre en attente", result.session.pending)
        assertEquals(1, result.session.stats.skipped)
        assertTrue(result.effects.any { it is Effect.Record })
    }

    @Test
    fun `explique demande une explication au LLM`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.Heard("je seche"), 2_000L)
        assertEquals(listOf(Effect.Explain(card(1))), result.effects)
    }

    @Test
    fun `l'explication recue est enoncee puis la carte est notee a revoir`() {
        val explaining = Session(state = SessionState.Judging(inFlight(1), "je seche"))
        val result = ReviewSessionEngine.reduce(
            explaining,
            Event.Explained("voici l'explication"),
            3_000L,
        )
        val state = result.session.state as SessionState.SpeakingVerdict
        val assessment = state.assessment as Assessment.Judged
        assertEquals(Ease.AGAIN, assessment.judgement.ease)
        assertTrue(result.effects.filterIsInstance<Effect.Speak>().single().text
            .contains("voici l'explication"))
    }

    @Test
    fun `stop pendant l'ecoute termine la session`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.Heard("stop"), 2_000L)
        assertTrue(result.session.state is SessionState.Finished)
        assertTrue(result.effects.contains(Effect.Finish))
    }

    @Test
    fun `une correction dictee remplace la note proposee`() {
        val result = ReviewSessionEngine.reduce(awaiting(1), Event.Heard("a revoir"), 4_000L)
        assertEquals(Ease.AGAIN, result.session.pending?.ease)
    }

    @Test
    fun `annule supprime la note en attente de la carte precedente`() {
        val previous = PendingAnswerFixtures.pending(card(1), Ease.EASY)
        val session = awaiting(2).copy(pending = previous)
        val result = ReviewSessionEngine.reduce(session, Event.Heard("annule"), 5_000L)

        assertTrue(
            "la carte annulee ne doit jamais etre ecrite",
            result.effects.filterIsInstance<Effect.Commit>().none { it.pending.card.noteId == 1L },
        )
        assertEquals("la carte courante prend la place", 2L, result.session.pending?.card?.noteId)
        assertTrue(
            result.effects.filterIsInstance<Effect.Record>()
                .any { it.entry.noteId == 1L && it.entry.committedEase == null },
        )
    }

    @Test
    fun `une reponse ordinaire pendant la correction vaut acceptation`() {
        val result = ReviewSessionEngine.reduce(
            awaiting(1),
            Event.Heard("oui enfin je crois que c'etait ca"),
            4_000L,
        )
        assertEquals(Ease.GOOD, result.session.pending?.ease)
    }

    @Test
    fun `un silence unique relance l'ecoute une fois`() {
        val result = ReviewSessionEngine.reduce(listening(1), Event.HeardNothing, 2_000L)
        assertTrue(result.session.retriedAnswer)
        assertTrue(result.session.state is SessionState.Asking)
        assertEquals(listOf(Effect.Speak("Je t'écoute.")), result.effects)
    }

    @Test
    fun `un second silence note la carte a revoir`() {
        val session = listening(1).copy(retriedAnswer = true)
        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 2_000L)
        val state = result.session.state as SessionState.SpeakingVerdict
        val assessment = state.assessment as Assessment.Judged
        assertEquals(Ease.AGAIN, assessment.judgement.ease)
    }

    @Test
    fun `l'echec du LLM bascule en mode degrade et lit le verso`() {
        val judging = Session(state = SessionState.Judging(inFlight(1), "ma reponse"))
        val result = ReviewSessionEngine.reduce(judging, Event.TutorFailed("timeout"), 3_000L)

        assertTrue("la session doit passer en degrade", result.session.degraded)
        val state = result.session.state as SessionState.SpeakingVerdict
        assertTrue(state.assessment is Assessment.SelfGrade)
        val spoken = result.effects.filterIsInstance<Effect.Speak>().single().text
        assertTrue(spoken.contains("verso 1"))
    }

    @Test
    fun `en mode degrade la fenetre de correction est longue`() {
        val session = Session(
            state = SessionState.SpeakingVerdict(inFlight(1), Assessment.SelfGrade("verso 1")),
            degraded = true,
        )
        val result = ReviewSessionEngine.reduce(session, Event.SpeechFinished, 4_000L)
        assertEquals(
            listOf(Effect.Listen(ListenKind.CORRECTION, ReviewSessionEngine.SELF_GRADE_TIMEOUT_MS)),
            result.effects,
        )
    }

    @Test
    fun `en mode degrade un silence vaut a revoir`() {
        val session = Session(
            state = SessionState.AwaitingCorrection(inFlight(1), Assessment.SelfGrade("verso 1")),
            degraded = true,
        )
        val result = ReviewSessionEngine.reduce(session, Event.HeardNothing, 5_000L)
        assertEquals(Ease.AGAIN, result.session.pending?.ease)
    }

    @Test
    fun `en mode degrade la carte suivante est lue telle quelle sans appel LLM`() {
        val session = Session(
            state = SessionState.AwaitingCorrection(inFlight(1), Assessment.SelfGrade("verso 1")),
            degraded = true,
            queue = listOf(card(2)),
        )
        val result = ReviewSessionEngine.reduce(session, Event.Heard("facile"), 5_000L)
        assertTrue(result.effects.none { it is Effect.Reformulate })
        assertTrue(result.effects.contains(Effect.Speak("recto 2")))
    }

    @Test
    fun `le retour du reseau ne relance pas le mode nominal en cours de carte`() {
        val session = Session(state = SessionState.Listening(inFlight(1)), degraded = true)
        val result = ReviewSessionEngine.reduce(session, Event.Heard("ma reponse"), 2_000L)
        assertFalse("pas d'appel de jugement en degrade", result.effects.any { it is Effect.Judge })
        assertTrue(result.session.state is SessionState.SpeakingVerdict)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*CommandsAndDegradedTest*'`
Expected: FAIL — `Unresolved reference: SELF_GRADE_TIMEOUT_MS`

- [ ] **Step 3: Étendre le moteur**

Remplacer `onHeard`, `onHeardNothing`, `onSpeechFinished`, et compléter `reduce` pour
`Event.TutorFailed` et `Event.Explained` :

```kotlin
    const val SELF_GRADE_TIMEOUT_MS = 8_000L

    // dans reduce(...)
    is Event.Explained -> onExplained(session, event, nowMs)
    is Event.TutorFailed -> onTutorFailed(session, nowMs)

    private fun onHeard(session: Session, event: Event.Heard, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Listening -> onAnswerHeard(session, state, event.transcript, nowMs)
            is SessionState.AwaitingCorrection ->
                onCorrectionHeard(session, state, event.transcript, nowMs)
            else -> Reduction(session, emptyList())
        }

    private fun onAnswerHeard(
        session: Session,
        state: SessionState.Listening,
        transcript: String,
        nowMs: Long,
    ): Reduction = when (VoiceCommandParser.parse(transcript)) {
        VoiceCommand.Stop -> finish(session, nowMs)

        VoiceCommand.Repeat -> Reduction(
            session.copy(state = SessionState.Asking(state.inFlight)),
            listOf(Effect.Speak(state.inFlight.question)),
        )

        VoiceCommand.Skip -> advance(
            session.copy(stats = session.stats.copy(skipped = session.stats.skipped + 1)),
            listOf(Effect.Record(skippedRecord(session, state.inFlight.card, nowMs, "passee a la voix"))),
            nowMs,
        )

        VoiceCommand.Explain -> Reduction(
            session.copy(state = SessionState.Judging(state.inFlight, transcript)),
            listOf(Effect.Explain(state.inFlight.card)),
        )

        // Une correction de note n'a pas de sens pendant la réponse : c'est du texte.
        else -> if (session.degraded) {
            speakAnswerForSelfGrade(session, state.inFlight)
        } else {
            Reduction(
                session.copy(state = SessionState.Judging(state.inFlight, transcript)),
                listOf(
                    Effect.Judge(
                        state.inFlight.card,
                        state.inFlight.expectedPoints,
                        transcript,
                        session.memory,
                    ),
                ),
            )
        }
    }

    private fun onCorrectionHeard(
        session: Session,
        state: SessionState.AwaitingCorrection,
        transcript: String,
        nowMs: Long,
    ): Reduction = when (val command = VoiceCommandParser.parse(transcript)) {
        is VoiceCommand.Correct -> settle(session, state, command.ease, nowMs)

        VoiceCommand.Stop -> settle(session, state, null, nowMs).let { settled ->
            finish(settled.session, nowMs, settled.effects)
        }

        // « annule » vise la carte précédente : elle n'est jamais écrite, elle reste due.
        VoiceCommand.Undo -> {
            val dropped = session.pending
            val cleared = session.copy(pending = null)
            val trace = dropped?.let {
                listOf(Effect.Record(it.record.copy(committedEase = null, note = "annulee a la voix")))
            } ?: emptyList()
            val settled = settle(cleared, state, null, nowMs)
            Reduction(settled.session, trace + settled.effects)
        }

        else -> settle(session, state, null, nowMs)
    }

    private fun onHeardNothing(session: Session, nowMs: Long): Reduction =
        when (val state = session.state) {
            is SessionState.Listening ->
                if (!session.retriedAnswer) {
                    Reduction(
                        session.copy(
                            state = SessionState.Asking(state.inFlight),
                            retriedAnswer = true,
                        ),
                        listOf(Effect.Speak("Je t'écoute.")),
                    )
                } else {
                    val giveUp = Judgement(
                        verdict = Verdict.FAUX,
                        ease = Ease.AGAIN,
                        spokenFeedback = "Pas de réponse. ${state.inFlight.card.answer}",
                        missed = emptyList(),
                        formulationNote = null,
                        topic = null,
                    )
                    Reduction(
                        session.copy(
                            state = SessionState.SpeakingVerdict(
                                state.inFlight,
                                Assessment.Judged(giveUp),
                            ),
                        ),
                        listOf(Effect.Speak(giveUp.spokenFeedback)),
                    )
                }

            is SessionState.AwaitingCorrection -> settle(session, state, null, nowMs)
            else -> Reduction(session, emptyList())
        }

    private fun onExplained(session: Session, event: Event.Explained, nowMs: Long): Reduction {
        val state = session.state as? SessionState.Judging ?: return Reduction(session, emptyList())
        val judgement = Judgement(
            verdict = Verdict.FAUX,
            ease = Ease.AGAIN,
            spokenFeedback = event.text,
            missed = emptyList(),
            formulationNote = null,
            topic = null,
        )
        return Reduction(
            session.copy(
                state = SessionState.SpeakingVerdict(state.inFlight, Assessment.Judged(judgement)),
            ),
            listOf(Effect.Speak("${event.text} Je mets à revoir.")),
        )
    }

    /** Le LLM a lâché : on lit le verso et l'utilisateur se note lui-même. */
    private fun onTutorFailed(session: Session, nowMs: Long): Reduction {
        val inFlight = when (val state = session.state) {
            is SessionState.Judging -> state.inFlight
            is SessionState.Preparing -> CardInFlight(state.card, state.card.question, emptyList(), nowMs)
            else -> return Reduction(session.copy(degraded = true), emptyList())
        }
        return speakAnswerForSelfGrade(session.copy(degraded = true), inFlight)
    }

    private fun speakAnswerForSelfGrade(session: Session, inFlight: CardInFlight): Reduction =
        Reduction(
            session.copy(
                degraded = true,
                state = SessionState.SpeakingVerdict(
                    inFlight,
                    Assessment.SelfGrade(inFlight.card.answer),
                ),
            ),
            listOf(
                Effect.Speak(
                    "${inFlight.card.answer} Tu mets à revoir, difficile, bien ou facile ?",
                ),
            ),
        )
```

Et dans `onSpeechFinished`, la fenêtre de correction dépend du type d'évaluation :

```kotlin
            is SessionState.SpeakingVerdict -> Reduction(
                session.copy(
                    state = SessionState.AwaitingCorrection(state.inFlight, state.assessment),
                ),
                listOf(
                    Effect.Listen(
                        ListenKind.CORRECTION,
                        if (state.assessment is Assessment.SelfGrade) {
                            SELF_GRADE_TIMEOUT_MS
                        } else {
                            CORRECTION_TIMEOUT_MS
                        },
                    ),
                ),
            )
```

`skippedRecord` doit passer de `private` à `internal` pour être appelé depuis `onAnswerHeard`.

- [ ] **Step 4: Lancer toute la suite du moteur**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test`
Expected: PASS — `NominalLoopTest` ne doit pas régresser.

- [ ] **Step 5: Commit**

```bash
git add core/src && git commit -m "feat(core): commandes vocales, annulation, mode degrade"
```

---

### Task 7: Session complète simulée

**Files:**
- Create: `core/src/test/kotlin/fr/appprepa/core/engine/FakePorts.kt`
- Create: `core/src/main/kotlin/fr/appprepa/core/engine/SessionLoop.kt`
- Test: `core/src/test/kotlin/fr/appprepa/core/engine/FullSessionTest.kt`

**Interfaces:**
- Consumes: le moteur (Tasks 5-6), les ports (Task 2)
- Produces: `SessionLoop(gateway, tutor, speaker, listener, journal, clock, writeMode)` avec
  `suspend fun run(deckId: Long?, limit: Int): SessionStats` et
  `val state: StateFlow<SessionState>`

C'est la tâche qui prouve que le découpage tient : une session de bout en bout, sans
Android, sans réseau, en quelques millisecondes.

- [ ] **Step 1: Écrire les doublures**

```kotlin
package fr.appprepa.core.engine

import fr.appprepa.core.model.*
import fr.appprepa.core.ports.*

class FakeAnkiGateway(private val cards: List<ReviewCard>) : AnkiGateway {
    val answered = mutableListOf<Triple<Long, Int, Ease>>()
    override suspend fun dueCards(deckId: Long?, limit: Int) = cards.take(limit)
    override suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long) {
        answered += Triple(noteId, cardOrd, ease)
    }
    override suspend fun decks() = mapOf(1L to "Prepa")
}

class FakeTutor(
    private val verdicts: Map<Long, Verdict> = emptyMap(),
    private val failOn: Set<Long> = emptySet(),
) : Tutor {
    var reformulations = 0
        private set

    override suspend fun reformulate(card: ReviewCard, memory: SessionMemory): ReformulatedQuestion {
        if (card.noteId in failOn) error("panne simulee")
        reformulations++
        return ReformulatedQuestion("Question sur ${card.noteId} ?", listOf("point ${card.noteId}"))
    }

    override suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
    ): Judgement {
        if (card.noteId in failOn) error("panne simulee")
        val verdict = verdicts[card.noteId] ?: Verdict.CORRECT
        return Judgement(verdict, Ease.fromVerdict(verdict), "retour", emptyList(), null, "theme")
    }

    override suspend fun explain(card: ReviewCard) = "explication de ${card.noteId}"
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String) { spoken += text }
    override fun stop() = Unit
}

/** Rend les transcripts dans l'ordre ; `null` signifie silence. */
class ScriptedListener(private val script: MutableList<String?>) : Listener {
    override suspend fun listen(timeoutMs: Long): ListenResult =
        when (val next = script.removeFirstOrNull()) {
            null -> ListenResult.Silence
            else -> ListenResult.Transcript(next)
        }
}

class FakeJournal : Journal {
    val entries = mutableListOf<JournalRecord>()
    override suspend fun record(entry: JournalRecord) { entries += entry }
}

class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long {
        now += 1_000L
        return now
    }
}
```

- [ ] **Step 2: Écrire le test qui échoue**

```kotlin
package fr.appprepa.core.engine

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSessionTest {

    private fun card(id: Long, media: Boolean = false) =
        ReviewCard(id, 0, "Prepa", "recto $id", "verso $id", 4, media)

    private fun loop(
        cards: List<ReviewCard>,
        script: MutableList<String?>,
        tutor: FakeTutor = FakeTutor(),
        gateway: FakeAnkiGateway = FakeAnkiGateway(cards),
        journal: FakeJournal = FakeJournal(),
        mode: WriteMode = WriteMode.WRITE_THROUGH,
    ) = SessionLoop(gateway, tutor, FakeSpeaker(), ScriptedListener(script), journal, FakeClock(), mode)

    @Test
    fun `trois cartes sont posees jugees et ecrites dans l'ordre`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>(
            "reponse 1", null, "reponse 2", null, "reponse 3", null,
        )
        val stats = loop(cards, script, gateway = gateway).run(null, 30)

        assertEquals(3, stats.answered)
        assertEquals(
            listOf(1L, 2L, 3L),
            gateway.answered.map { it.first },
        )
        assertTrue(gateway.answered.all { it.third == Ease.GOOD })
    }

    @Test
    fun `en mode journal rien n'est ecrit dans anki`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val journal = FakeJournal()
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", null)
        loop(cards, script, gateway = gateway, journal = journal, mode = WriteMode.JOURNAL_ONLY)
            .run(null, 30)

        assertTrue("aucune ecriture Anki attendue", gateway.answered.isEmpty())
        assertEquals(2, journal.entries.count { it.proposedEase != null })
        assertTrue(journal.entries.all { it.committedEase == null })
    }

    @Test
    fun `une correction vocale prime sur la note du LLM`() = runTest {
        val cards = listOf(card(1))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>("reponse 1", "a revoir")
        loop(cards, script, gateway = gateway).run(null, 30)

        assertEquals(listOf(Triple(1L, 0, Ease.AGAIN)), gateway.answered)
    }

    @Test
    fun `annule empeche l'ecriture de la carte precedente`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", "annule")
        loop(cards, script, gateway = gateway).run(null, 30)

        assertEquals("seule la carte 2 doit etre ecrite", listOf(2L), gateway.answered.map { it.first })
    }

    @Test
    fun `la panne du LLM bascule en lecture simple et la session continue`() = runTest {
        val cards = listOf(card(1), card(2))
        val gateway = FakeAnkiGateway(cards)
        val tutor = FakeTutor(failOn = setOf(1L, 2L))
        val script = mutableListOf<String?>("reponse 1", "facile", "reponse 2", "bien")
        val stats = loop(cards, script, tutor = tutor, gateway = gateway).run(null, 30)

        assertEquals(2, stats.answered)
        assertEquals(
            listOf(Ease.EASY, Ease.GOOD),
            gateway.answered.map { it.third },
        )
    }

    @Test
    fun `les cartes a media sont ecartees sans etre notees`() = runTest {
        val cards = listOf(card(1, media = true), card(2))
        val gateway = FakeAnkiGateway(cards)
        val journal = FakeJournal()
        val script = mutableListOf<String?>("reponse 2", null)
        loop(cards, script, gateway = gateway, journal = journal).run(null, 30)

        assertEquals(listOf(2L), gateway.answered.map { it.first })
        assertTrue(journal.entries.any { it.noteId == 1L && it.note != null })
    }

    @Test
    fun `la reformulation de la carte suivante est demandee pendant l'ecoute`() = runTest {
        val cards = listOf(card(1), card(2), card(3))
        val tutor = FakeTutor()
        val script = mutableListOf<String?>("reponse 1", null, "reponse 2", null, "reponse 3", null)
        loop(cards, script, tutor = tutor).run(null, 30)

        assertEquals("une reformulation par carte, pas davantage", 3, tutor.reformulations)
    }

    @Test
    fun `une collection sans carte due se termine proprement`() = runTest {
        val stats = loop(emptyList(), mutableListOf()).run(null, 30)
        assertEquals(0, stats.answered)
    }
}
```

- [ ] **Step 3: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test --tests '*FullSessionTest*'`
Expected: FAIL — `Unresolved reference: SessionLoop`

- [ ] **Step 4: Écrire l'exécuteur d'effets**

`SessionLoop` est la seule pièce de `:core` qui connaît les coroutines. Elle traduit chaque
effet en appel de port, et chaque résultat de port en événement.

```kotlin
package fr.appprepa.core.engine

import fr.appprepa.core.model.SessionStats
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.AnkiGateway
import fr.appprepa.core.ports.Clock
import fr.appprepa.core.ports.Journal
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import fr.appprepa.core.ports.Speaker
import fr.appprepa.core.ports.Tutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionLoop(
    private val gateway: AnkiGateway,
    private val tutor: Tutor,
    private val speaker: Speaker,
    private val listener: Listener,
    private val journal: Journal,
    private val clock: Clock,
    private val writeMode: WriteMode,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var session = Session(writeMode = writeMode)
    private val queue = ArrayDeque<Event>()

    /** Reformulations en vol, indexées par noteId. */
    private val inFlightReformulations = mutableMapOf<Long, Deferred<Unit>>()

    suspend fun run(deckId: Long?, limit: Int): SessionStats = coroutineScope {
        queue += Event.Start(deckId, limit)

        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            val reduction = ReviewSessionEngine.reduce(session, event, clock.nowMs())
            session = reduction.session
            _state.value = session.state

            for (effect in reduction.effects) {
                perform(effect, this)
            }
            if (session.state is SessionState.Finished || session.state is SessionState.Failed) {
                break
            }
        }
        inFlightReformulations.values.forEach { it.cancel() }
        (session.state as? SessionState.Finished)?.stats ?: session.stats
    }

    private suspend fun perform(effect: Effect, scope: CoroutineScope) {
        when (effect) {
            is Effect.LoadCards -> queue += runCatching {
                Event.CardsLoaded(gateway.dueCards(effect.deckId, effect.limit))
            }.getOrElse { Event.Fatal(it.message ?: "lecture AnkiDroid impossible") }

            is Effect.Speak -> {
                speaker.speak(effect.text)
                queue += Event.SpeechFinished
            }

            is Effect.Listen -> queue += when (val result = listener.listen(effect.timeoutMs)) {
                is ListenResult.Transcript -> Event.Heard(result.text)
                ListenResult.Silence -> Event.HeardNothing
                is ListenResult.Failure -> Event.HeardNothing
            }

            is Effect.Reformulate -> {
                val isCurrent = session.state is SessionState.Preparing
                if (isCurrent) {
                    // Bloquant : rien à dire tant que la question n'existe pas.
                    queue += runCatching {
                        Event.Reformulated(effect.card, tutor.reformulate(effect.card, effect.memory))
                    }.getOrElse { Event.TutorFailed(it.message ?: "reformulation impossible") }
                } else {
                    // Préchargement : en tâche de fond, pendant que l'utilisateur parle.
                    inFlightReformulations[effect.card.noteId] = scope.async {
                        runCatching { tutor.reformulate(effect.card, effect.memory) }
                            .onSuccess { queue += Event.Reformulated(effect.card, it) }
                        Unit
                    }
                }
            }

            is Effect.Judge -> queue += runCatching {
                Event.Judged(
                    tutor.judge(effect.card, effect.expectedPoints, effect.transcript, effect.memory),
                )
            }.getOrElse { Event.TutorFailed(it.message ?: "jugement impossible") }

            is Effect.Explain -> queue += runCatching {
                Event.Explained(tutor.explain(effect.card))
            }.getOrElse { Event.TutorFailed(it.message ?: "explication impossible") }

            is Effect.Commit -> if (writeMode == WriteMode.WRITE_THROUGH) {
                runCatching {
                    gateway.answer(
                        effect.pending.card.noteId,
                        effect.pending.card.cardOrd,
                        effect.pending.ease,
                        effect.pending.timeTakenMs,
                    )
                }
            }

            is Effect.Record -> journal.record(
                if (writeMode == WriteMode.WRITE_THROUGH) {
                    effect.entry
                } else {
                    effect.entry.copy(committedEase = null, mode = WriteMode.JOURNAL_ONLY)
                },
            )

            Effect.Finish -> Unit
        }
    }
}
```

Le préchargement est réalisé ici, pas dans le moteur : le moteur émet
`Effect.Reformulate` pour une carte qui n'est pas celle affichée, et l'exécuteur choisit
de le lancer en tâche de fond. La distinction bloquant/non bloquant est une décision
d'exécution, pas une décision de logique métier.

- [ ] **Step 5: Lancer toute la suite**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:test`
Expected: PASS — la totalité de `:core`, sous la seconde.

- [ ] **Step 6: Commit**

```bash
git add core/src && git commit -m "feat(core): executeur d'effets et session simulee de bout en bout"
```

---

### Task 8: Passerelle AnkiDroid

**Files:**
- Create: `app/src/main/kotlin/fr/appprepa/app/anki/AnkiDroidGateway.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/anki/AnkiAvailability.kt`
- Test: `app/src/androidTest/kotlin/fr/appprepa/app/anki/AnkiDroidGatewayTest.kt`
- Modify: `app/build.gradle.kts` (dépendances de test instrumenté)

**Interfaces:**
- Consumes: `AnkiGateway`, `ReviewCard`, `Ease` (Task 2)
- Produces: `AnkiDroidGateway(contentResolver)` implémentant `AnkiGateway`, et
  `AnkiAvailability.check(context): AnkiStatus` où `AnkiStatus` vaut `Ready`,
  `NotInstalled`, `PermissionMissing` ou `Unreachable(cause)`

Les URI et les noms de colonnes viennent de `FlashCardsContract.kt` du dépôt AnkiDroid,
consulté le 2026-08-26. Ils sont recopiés ici plutôt que dépendus : la bibliothèque
`com.github.ankidroid.Anki-Android:api` passe par JitPack, dépendance de build fragile
pour cinq constantes.

- [ ] **Step 1: Écrire le test instrumenté**

Ce test exige AnkiDroid installé sur l'émulateur avec au moins une carte due — la Task 14
met cela en place. Il échoue explicitement plutôt que de passer à vide.

```kotlin
package fr.appprepa.app.anki

import androidx.test.platform.app.InstrumentationRegistry
import fr.appprepa.core.model.Ease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AnkiDroidGatewayTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var gateway: AnkiDroidGateway

    @Before
    fun setUp() {
        assumeTrue(
            "AnkiDroid doit etre installe et la permission accordee",
            AnkiAvailability.check(context) == AnkiStatus.Ready,
        )
        gateway = AnkiDroidGateway(context.contentResolver)
    }

    @Test
    fun listeLesDecks() = runBlocking {
        val decks = gateway.decks()
        assertTrue("au moins un deck attendu", decks.isNotEmpty())
    }

    @Test
    fun litLesCartesDuesAvecLeurTexte() = runBlocking {
        val cards = gateway.dueCards(deckId = null, limit = 3)
        assertTrue("au moins une carte due attendue", cards.isNotEmpty())
        val card = cards.first()
        assertTrue("le recto ne doit pas etre vide", card.question.isNotBlank())
        assertTrue("le verso ne doit pas etre vide", card.answer.isNotBlank())
        assertTrue("le recto ne doit pas contenir de HTML", !card.question.contains("<div"))
        assertTrue("button count plausible", card.buttonCount in 2..4)
    }

    @Test
    fun repondreALaCarteLaRetireDesCartesDues() = runBlocking {
        val before = gateway.dueCards(deckId = null, limit = 1)
        assumeTrue(before.isNotEmpty())
        val card = before.first()

        gateway.answer(card.noteId, card.cardOrd, Ease.GOOD, timeTakenMs = 4_000L)

        val after = gateway.dueCards(deckId = null, limit = 1)
        val stillSame = after.firstOrNull()
            ?.let { it.noteId == card.noteId && it.cardOrd == card.cardOrd } ?: false
        assertEquals("la carte notee ne doit plus etre proposee", false, stillSame)
    }
}
```

- [ ] **Step 2: Ajouter les dépendances de test instrumenté**

Dans `app/build.gradle.kts` :

```kotlin
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(libs.kotlinx.coroutines.android)
```

et, pour que le test instrumenté trouve le source set Kotlin :

```kotlin
    sourceSets {
        getByName("androidTest") { java.srcDirs("src/androidTest/kotlin") }
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }
```

- [ ] **Step 3: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:connectedDebugAndroidTest`
Expected: FAIL — `Unresolved reference: AnkiDroidGateway`

- [ ] **Step 4: Écrire la passerelle**

```kotlin
package fr.appprepa.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.ports.AnkiGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Accès à la collection AnkiDroid par son ContentProvider.
 *
 * Contrat repris de `FlashCardsContract.kt` (dépôt AnkiDroid, branche main, 2026-08-26).
 * La table `schedule` donne les cartes dues mais **pas** leur texte ; il faut une seconde
 * requête sur `notes/<id>/cards/<ord>` pour obtenir recto et verso.
 */
class AnkiDroidGateway(private val resolver: ContentResolver) : AnkiGateway {

    override suspend fun dueCards(deckId: Long?, limit: Int): List<ReviewCard> =
        withContext(Dispatchers.IO) {
            val selection = if (deckId != null) "limit=?, deckID=?" else "limit=?"
            val args = if (deckId != null) {
                arrayOf(limit.toString(), deckId.toString())
            } else {
                arrayOf(limit.toString())
            }

            val deckNames = decksBlocking()

            resolver.query(SCHEDULE_URI, null, selection, args, null).use { cursor ->
                if (cursor == null) return@withContext emptyList()
                buildList {
                    while (cursor.moveToNext()) {
                        val noteId = cursor.getLong(cursor.getColumnIndexOrThrow(NOTE_ID))
                        val ord = cursor.getInt(cursor.getColumnIndexOrThrow(CARD_ORD))
                        val buttons = cursor.getColumnIndex(BUTTON_COUNT)
                            .takeIf { it >= 0 }
                            ?.let { cursor.getInt(it) }
                            ?: 4
                        val hasMedia = cursor.getColumnIndex(MEDIA_FILES)
                            .takeIf { it >= 0 }
                            ?.let { hasMediaFiles(cursor.getString(it)) }
                            ?: false

                        val text = cardText(noteId, ord) ?: continue
                        add(
                            ReviewCard(
                                noteId = noteId,
                                cardOrd = ord,
                                deckName = deckNames[text.deckId] ?: "",
                                question = text.question,
                                answer = text.answer,
                                buttonCount = buttons,
                                hasMedia = hasMedia,
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(NOTE_ID, noteId)
                put(CARD_ORD, cardOrd)
                put(EASE, ease.value)
                put(TIME_TAKEN, timeTakenMs)
            }
            val updated = resolver.update(SCHEDULE_URI, values, null, null)
            check(updated > 0) { "AnkiDroid a refuse la note pour la note $noteId" }
        }
    }

    override suspend fun decks(): Map<Long, String> = withContext(Dispatchers.IO) { decksBlocking() }

    private fun decksBlocking(): Map<Long, String> =
        resolver.query(DECKS_URI, null, null, null, null).use { cursor ->
            if (cursor == null) return emptyMap()
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DECK_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DECK_NAME)) ?: ""
                    put(id, name)
                }
            }
        }

    private data class CardText(val question: String, val answer: String, val deckId: Long)

    private fun cardText(noteId: Long, ord: Int): CardText? {
        val uri = Uri.withAppendedPath(NOTES_URI, "$noteId/cards/$ord")
        return resolver.query(uri, null, null, null, null).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            CardText(
                question = cursor.getString(cursor.getColumnIndexOrThrow(QUESTION_SIMPLE)).orEmpty(),
                answer = cursor.getString(cursor.getColumnIndexOrThrow(ANSWER_PURE)).orEmpty(),
                deckId = cursor.getColumnIndex(CARD_DECK_ID)
                    .takeIf { it >= 0 }
                    ?.let { cursor.getLong(it) }
                    ?: 0L,
            )
        }
    }

    /** `media_files` est un JSONArray sérialisé ; vide ou absent veut dire pas de média. */
    private fun hasMediaFiles(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching { JSONArray(raw).length() > 0 }.getOrDefault(false)
    }

    companion object {
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")
        val SCHEDULE_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
        val NOTES_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
        val DECKS_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")

        private const val NOTE_ID = "note_id"
        private const val CARD_ORD = "ord"
        private const val BUTTON_COUNT = "button_count"
        private const val MEDIA_FILES = "media_files"
        private const val EASE = "answer_ease"
        private const val TIME_TAKEN = "time_taken"
        private const val QUESTION_SIMPLE = "question_simple"
        private const val ANSWER_PURE = "answer_pure"
        private const val CARD_DECK_ID = "deck_id"
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
    }
}
```

- [ ] **Step 5: Écrire le diagnostic de disponibilité**

Un échec doit être annoncé au démarrage, à l'arrêt, et non en plein trajet.

```kotlin
package fr.appprepa.app.anki

import android.content.Context
import android.content.pm.PackageManager

sealed interface AnkiStatus {
    data object Ready : AnkiStatus
    data object NotInstalled : AnkiStatus
    data object PermissionMissing : AnkiStatus
    data class Unreachable(val cause: String) : AnkiStatus
}

object AnkiAvailability {

    fun check(context: Context): AnkiStatus {
        val provider = context.packageManager
            .resolveContentProvider(AnkiDroidGateway.AUTHORITY, 0)
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
            .also { if (provider.authority != AnkiDroidGateway.AUTHORITY) return AnkiStatus.NotInstalled }
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
```

- [ ] **Step 6: Lancer le test instrumenté**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:connectedDebugAndroidTest`
Expected: les trois tests passent, ou sont ignorés proprement si AnkiDroid n'est pas encore
installé (Task 14 lève cette condition).

- [ ] **Step 7: Commit**

```bash
git add app/src app/build.gradle.kts && git commit -m "feat(app): passerelle ContentProvider AnkiDroid"
```

---

### Task 9: Client LLM

**Files:**
- Create: `app/src/main/kotlin/fr/appprepa/app/llm/Prompts.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/llm/AnthropicTutor.kt`
- Test: `app/src/test/kotlin/fr/appprepa/app/llm/PromptsTest.kt`
- Test: `app/src/androidTest/kotlin/fr/appprepa/app/llm/AnthropicTutorSmokeTest.kt`

**Interfaces:**
- Consumes: `Tutor`, `ReviewCard`, `Judgement`, `ReformulatedQuestion`, `SessionMemory` (Task 2),
  `SessionMemoryBuilder.render` (Task 4)
- Produces: `AnthropicTutor(apiKey: String)` implémentant `Tutor`, et
  `Prompts.reformulate(card, memoryText)`, `Prompts.judge(card, expectedPoints, transcript, memoryText)`,
  `Prompts.parseJudgement(json, buttonCount): Judgement`

**Décision technique mesurée.** Le SDK officiel `com.anthropic:anthropic-java:2.57.0` a été
testé sur ce projet : il compile, passe R8, et produit un APK release de 8,58 Mo contre
1,19 Mo sans lui — 7,4 Mo et un second fichier dex, dus à `jackson-databind`,
`kotlin-reflect` et `victools/jsonschema-generator`. C'est acceptable pour une application
personnelle, et le SDK officiel reste le bon choix par défaut. Le risque restant est le
comportement à l'exécution sur Android, que la Step 6 vérifie explicitement.

- [ ] **Step 1: Écrire le test des prompts et du parsage**

Le parsage est le point où une sortie de modèle imprévisible rencontre la collection Anki.
Il doit être hostile par défaut.

```kotlin
package fr.appprepa.app.llm

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    private val card = ReviewCard(
        noteId = 1,
        cardOrd = 0,
        deckName = "Prepa maths",
        question = "Enonce du theoreme de Rolle",
        answer = "Si f est continue sur [a,b], derivable sur ]a,b[ et f(a)=f(b), alors il existe c",
        buttonCount = 4,
        hasMedia = false,
    )

    @Test
    fun `le prompt de reformulation contient recto verso et deck`() {
        val prompt = Prompts.reformulate(card, memoryText = "")
        assertTrue(prompt.contains("Enonce du theoreme de Rolle"))
        assertTrue(prompt.contains("Prepa maths"))
        assertTrue(prompt.contains(card.answer))
    }

    @Test
    fun `le prompt de reformulation injecte la memoire quand elle existe`() {
        val prompt = Prompts.reformulate(card, memoryText = "Themes rates : Cauchy.")
        assertTrue(prompt.contains("Cauchy"))
    }

    @Test
    fun `le prompt de jugement demande la tolerance a la transcription`() {
        val prompt = Prompts.judge(card, listOf("continuite"), "euh f continue sur a b", "")
        assertTrue(prompt.contains("euh f continue sur a b"))
        assertTrue(
            "la consigne de tolerance protege des termes techniques mal transcrits",
            prompt.lowercase().contains("transcription"),
        )
    }

    @Test
    fun `parse un jugement complet`() {
        val json = """
            {"verdict":"correct","ease":3,"spoken_feedback":"Bien vu.",
             "missed":[],"formulation_note":null,"topic":"Rolle"}
        """.trimIndent()
        val judgement = Prompts.parseJudgement(json, buttonCount = 4)
        assertEquals(Verdict.CORRECT, judgement.verdict)
        assertEquals(Ease.GOOD, judgement.ease)
        assertEquals("Bien vu.", judgement.spokenFeedback)
        assertEquals("Rolle", judgement.topic)
        assertNull(judgement.formulationNote)
    }

    @Test
    fun `borne une ease hors limites sur le button count`() {
        val json = """{"verdict":"correct","ease":4,"spoken_feedback":"ok","missed":[],
            "formulation_note":null,"topic":"t"}"""
        assertEquals(Ease.HARD, Prompts.parseJudgement(json, buttonCount = 2).ease)
    }

    @Test
    fun `recalcule l'ease depuis le verdict quand elle est absente ou aberrante`() {
        val absent = """{"verdict":"partiel","spoken_feedback":"ok","missed":[],"topic":"t"}"""
        assertEquals(Ease.HARD, Prompts.parseJudgement(absent, buttonCount = 4).ease)

        val aberrante = """{"verdict":"faux","ease":99,"spoken_feedback":"ok","missed":[],"topic":"t"}"""
        assertEquals(Ease.AGAIN, Prompts.parseJudgement(aberrante, buttonCount = 4).ease)
    }

    @Test
    fun `tronque un retour parle trop long`() {
        val long = "Alors. " + "un mot ".repeat(200)
        val json = """{"verdict":"correct","ease":3,"spoken_feedback":"$long","missed":[],"topic":"t"}"""
        val judgement = Prompts.parseJudgement(json, buttonCount = 4)
        assertTrue(
            "un verso de fiche de prepa ne doit pas etre lu en entier",
            judgement.spokenFeedback.split(" ").size <= Prompts.MAX_SPOKEN_WORDS + 1,
        )
    }

    @Test
    fun `tolere du texte autour du JSON`() {
        val noisy = """Voici le resultat :
            {"verdict":"faux","ease":1,"spoken_feedback":"Non.","missed":["a"],"topic":"t"}
            Fin."""
        assertEquals(Verdict.FAUX, Prompts.parseJudgement(noisy, buttonCount = 4).verdict)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejette une sortie sans JSON exploitable`() {
        Prompts.parseJudgement("je n'ai pas compris la question", buttonCount = 4)
    }

    @Test
    fun `parse une reformulation`() {
        val json = """{"question":"Que dit le theoreme de Rolle ?","expected_points":["continuite","derivabilite"]}"""
        val reformulated = Prompts.parseReformulation(json)
        assertEquals("Que dit le theoreme de Rolle ?", reformulated.question)
        assertEquals(listOf("continuite", "derivabilite"), reformulated.expectedPoints)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests '*PromptsTest*'`
Expected: FAIL — `Unresolved reference: Prompts`

- [ ] **Step 3: Écrire les prompts et le parsage**

```kotlin
package fr.appprepa.app.llm

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object Prompts {

    const val MAX_SPOKEN_WORDS = 40

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val SYSTEM = """
        Tu fais réviser des cartes Anki à l'oral, à quelqu'un qui conduit.
        Tu parles français, en phrases courtes, sans jamais lister ni énumérer à voix haute.
        Tu ne dis jamais « carte », « recto », « verso » : tu poses une question, c'est tout.
        Tu réponds uniquement par un objet JSON, sans texte autour.
    """.trimIndent()

    fun reformulate(card: ReviewCard, memoryText: String): String = buildString {
        appendLine("Deck : ${card.deckName}")
        appendLine("Recto : ${card.question}")
        appendLine("Verso : ${card.answer}")
        if (memoryText.isNotBlank()) appendLine("Contexte de la session : $memoryText")
        appendLine()
        appendLine(
            """
            Transforme le recto en UNE question orale, naturelle, énonçable d'un trait.
            Donne aussi les points attendus dans la réponse, en trois éléments au maximum.
            Réponds par : {"question": "...", "expected_points": ["...", "..."]}
            """.trimIndent(),
        )
    }

    fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memoryText: String,
    ): String = buildString {
        appendLine("Question posée : ${card.question}")
        appendLine("Réponse de référence : ${card.answer}")
        if (expectedPoints.isNotEmpty()) {
            appendLine("Points attendus : ${expectedPoints.joinToString(" ; ")}")
        }
        appendLine("Réponse orale de l'élève : $transcript")
        if (memoryText.isNotBlank()) appendLine("Contexte de la session : $memoryText")
        appendLine()
        appendLine(
            """
            Juge le FOND, pas la forme sonore. La réponse vient d'une transcription
            automatique : les termes techniques peuvent être mal orthographiés ou coupés.
            Ne sanctionne jamais une erreur de transcription, seulement une erreur de savoir.

            verdict : "correct", "partiel" ou "faux".
            ease : 1 si faux, 2 si partiel, 3 si correct, 4 si correct ET formulé avec précision.
            spoken_feedback : ce que tu diras à voix haute. $MAX_SPOKEN_WORDS mots maximum.
              Si la réponse est fausse ou partielle, donne l'essentiel de la bonne réponse.
            formulation_note : uniquement si le fond était juste mais la formulation confuse.
              Une remarque courte et actionnable. Sinon null.
            topic : le thème de la carte, deux ou trois mots.

            Réponds par :
            {"verdict":"...","ease":0,"spoken_feedback":"...","missed":["..."],
             "formulation_note":null,"topic":"..."}
            """.trimIndent(),
        )
    }

    fun explain(card: ReviewCard): String =
        """
        Question : ${card.question}
        Réponse : ${card.answer}

        L'élève sèche. Explique-lui la réponse à voix haute, en $MAX_SPOKEN_WORDS mots maximum,
        en allant à l'essentiel. Réponds par : {"spoken_feedback": "..."}
        """.trimIndent()

    fun parseReformulation(raw: String): ReformulatedQuestion {
        val obj = extractObject(raw)
        val question = obj["question"]?.jsonPrimitive?.contentOrNull?.trim()
        require(!question.isNullOrEmpty()) { "reformulation sans question" }
        val points = obj["expected_points"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return ReformulatedQuestion(question, points)
    }

    fun parseSpokenFeedback(raw: String): String {
        val obj = extractObject(raw)
        val text = obj["spoken_feedback"]?.jsonPrimitive?.contentOrNull?.trim()
        require(!text.isNullOrEmpty()) { "sortie sans spoken_feedback" }
        return truncate(text)
    }

    /**
     * Le code ne fait pas confiance à `ease` : il la borne au nombre de boutons de la carte
     * et, si elle est absente ou aberrante, la recalcule depuis le verdict.
     */
    fun parseJudgement(raw: String, buttonCount: Int): Judgement {
        val obj = extractObject(raw)

        val verdict = when (obj["verdict"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()) {
            "correct" -> Verdict.CORRECT
            "partiel" -> Verdict.PARTIEL
            "faux" -> Verdict.FAUX
            else -> throw IllegalArgumentException("verdict absent ou inconnu")
        }

        val ease = obj["ease"]?.jsonPrimitive?.intOrNull
            ?.let { Ease.fromValue(it) }
            ?: Ease.fromVerdict(verdict)

        val feedback = obj["spoken_feedback"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        return Judgement(
            verdict = verdict,
            ease = ease.clampTo(buttonCount),
            spokenFeedback = truncate(feedback),
            missed = obj["missed"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList(),
            formulationNote = obj["formulation_note"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
            topic = obj["topic"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Le modèle peut encadrer son JSON de texte ; on récupère le premier objet complet. */
    private fun extractObject(raw: String): JsonObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "aucun objet JSON dans la sortie du modele" }
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)) as JsonObject }
            .getOrElse { throw IllegalArgumentException("JSON illisible : ${it.message}") }
    }

    /** Coupe à la phrase : un verso de fiche de prépa lu en entier casse le rythme. */
    internal fun truncate(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= MAX_SPOKEN_WORDS) return text.trim()
        val cut = words.take(MAX_SPOKEN_WORDS).joinToString(" ")
        val lastStop = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (lastStop > cut.length / 2) cut.substring(0, lastStop + 1) else "$cut…"
    }
}
```

- [ ] **Step 4: Écrire le client**

```kotlin
package fr.appprepa.app.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import fr.appprepa.core.memory.SessionMemoryBuilder
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReformulatedQuestion
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import fr.appprepa.core.ports.Tutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Les deux appels de la boucle sont courts et la latence prime sur la profondeur :
 * effort `LOW` sur les deux.
 */
class AnthropicTutor(
    apiKey: String,
    private val client: AnthropicClient =
        AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
) : Tutor {

    override suspend fun reformulate(
        card: ReviewCard,
        memory: SessionMemory,
    ): ReformulatedQuestion = Prompts.parseReformulation(
        ask(Prompts.reformulate(card, SessionMemoryBuilder.render(memory))),
    )

    override suspend fun judge(
        card: ReviewCard,
        expectedPoints: List<String>,
        transcript: String,
        memory: SessionMemory,
    ): Judgement = Prompts.parseJudgement(
        ask(Prompts.judge(card, expectedPoints, transcript, SessionMemoryBuilder.render(memory))),
        buttonCount = card.buttonCount,
    )

    override suspend fun explain(card: ReviewCard): String =
        Prompts.parseSpokenFeedback(ask(Prompts.explain(card)))

    private suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(1_024L)
            .system(Prompts.SYSTEM)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(prompt)
            .build()

        client.messages().create(params).content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("\n")
            .ifBlank { throw IllegalStateException("reponse vide du modele") }
    }

    companion object {
        const val MODEL = "claude-opus-5"
    }
}
```

- [ ] **Step 5: Lancer les tests unitaires**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests '*PromptsTest*'`
Expected: PASS, 10 tests

- [ ] **Step 6: Vérifier que le SDK fonctionne réellement sur Android**

Le SDK est une bibliothèque JVM, pas une bibliothèque Android : `jackson-databind` et
`kotlin-reflect` peuvent échouer au chargement de classes sur un appareil sans que rien ne
l'ait signalé à la compilation. Ce test le vérifie **sans clé d'API valide** : une clé
bidon doit produire une erreur d'authentification correctement désérialisée, ce qui prouve
que le chargement de classes, la sérialisation JSON et le transport HTTP fonctionnent.
Un `NoClassDefFoundError` ou un `ExceptionInInitializerError` signerait au contraire
l'incompatibilité.

```kotlin
package fr.appprepa.app.llm

import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnthropicTutorSmokeTest {

    private val card = ReviewCard(1, 0, "Prepa", "recto", "verso", 4, false)

    @Test
    fun leSdkSeChargeEtParleHttpSurAndroid() = runBlocking {
        val tutor = AnthropicTutor(apiKey = "sk-ant-cle-volontairement-invalide")
        try {
            tutor.reformulate(card, SessionMemory())
            fail("une cle invalide ne doit pas aboutir")
        } catch (error: NoClassDefFoundError) {
            fail("le SDK ne se charge pas sur Android : ${error.message}")
        } catch (error: ExceptionInInitializerError) {
            fail("initialisation du SDK impossible sur Android : ${error.message}")
        } catch (expected: Exception) {
            val text = "${expected::class.java.name} ${expected.message}"
            assertTrue(
                "une erreur d'authentification est attendue, obtenu : $text",
                text.contains("401") ||
                    text.contains("authentication", ignoreCase = true) ||
                    text.contains("invalid", ignoreCase = true) ||
                    text.contains("api", ignoreCase = true),
            )
        }
    }
}
```

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:connectedDebugAndroidTest --tests '*AnthropicTutorSmokeTest*'`
Expected: PASS. En cas d'échec sur le chargement de classes, la porte de sortie est un
client OkHttp direct : `POST https://api.anthropic.com/v1/messages`, en-têtes
`x-api-key` et `anthropic-version: 2023-06-01`, corps sérialisé avec kotlinx.serialization,
les deux dépendances étant déjà présentes dans le module. Seul `ask()` change.

- [ ] **Step 7: Commit**

```bash
git add app/src && git commit -m "feat(app): prompts, parsage defensif et client Claude"
```

---

### Task 10: Adaptateurs de voix

**Files:**
- Create: `app/src/main/kotlin/fr/appprepa/app/voice/AndroidSpeaker.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/voice/AndroidListener.kt`
- Test: `app/src/androidTest/kotlin/fr/appprepa/app/voice/AndroidSpeakerTest.kt`

**Interfaces:**
- Consumes: `Speaker`, `Listener`, `ListenResult` (Task 2)
- Produces: `AndroidSpeaker(context)` avec `suspend fun awaitReady(): Boolean`,
  et `AndroidListener(context)` implémentant `Listener`

Les deux API Android en jeu sont à rappel et à état ; le travail de cet adaptateur est de
les présenter comme des fonctions `suspend` qui rendent la main au bon moment. `speak` ne
doit rendre la main qu'à la fin réelle de l'énoncé, sans quoi la reconnaissance vocale
démarre pendant que le téléphone parle et s'entend elle-même.

- [ ] **Step 1: Écrire le test instrumenté**

```kotlin
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
            assertTrue("moteur TTS francais indisponible", withTimeout(10_000) { speaker.awaitReady() })
        } finally {
            speaker.release()
        }
    }

    @Test
    fun speakNeRendLaMainQuALaFinDeLEnonce() = runBlocking {
        val speaker = AndroidSpeaker(context)
        try {
            withTimeout(10_000) { speaker.awaitReady() }
            val start = System.currentTimeMillis()
            withTimeout(20_000) {
                speaker.speak("Ceci est une phrase de test suffisamment longue pour durer.")
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue("speak est revenu en ${elapsed}ms, trop vite pour un enonce reel", elapsed > 500)
        } finally {
            speaker.release()
        }
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:connectedDebugAndroidTest --tests '*AndroidSpeakerTest*'`
Expected: FAIL — `Unresolved reference: AndroidSpeaker`

- [ ] **Step 3: Écrire la synthèse vocale**

```kotlin
package fr.appprepa.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fr.appprepa.core.ports.Speaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class AndroidSpeaker(context: Context) : Speaker {

    private val initialized = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var counter = 0L

    /**
     * Le rappel d'initialisation peut se déclencher avant que le constructeur ait rendu
     * `engine`. On ne fait donc rien d'autre, dans ce rappel, que publier le statut ;
     * le choix de la langue attend [awaitReady], appelée après construction.
     */
    private val engine = TextToSpeech(context.applicationContext) { status ->
        initialized.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("remplacee par onError(String, Int)")
            override fun onError(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                pending.remove(utteranceId)?.complete(Unit)
            }
        })
    }

    suspend fun awaitReady(): Boolean {
        if (!initialized.await()) return false
        val result = engine.setLanguage(Locale.FRENCH)
        return result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Ne rend la main qu'à la fin réelle de l'énoncé. */
    override suspend fun speak(text: String) {
        if (!initialized.await()) return
        val id = "u${counter++}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        done.await()
    }

    override fun stop() {
        engine.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun release() {
        stop()
        engine.shutdown()
    }
}
```

- [ ] **Step 4: Écrire la reconnaissance vocale**

`SpeechRecognizer` doit être créé et piloté depuis le thread principal ; l'adaptateur s'en
charge et n'expose qu'une fonction `suspend`.

```kotlin
package fr.appprepa.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class AndroidListener(private val context: Context) : Listener {

    private val main = Handler(Looper.getMainLooper())

    override suspend fun listen(timeoutMs: Long): ListenResult =
        suspendCancellableCoroutine { continuation ->
            main.post {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    continuation.resume(ListenResult.Failure("reconnaissance vocale indisponible"))
                    return@post
                }

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                var settled = false

                fun settle(result: ListenResult) {
                    if (settled) return
                    settled = true
                    runCatching { recognizer.destroy() }
                    continuation.resume(result)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        settle(
                            if (text.isNullOrEmpty()) ListenResult.Silence
                            else ListenResult.Transcript(text),
                        )
                    }

                    override fun onError(error: Int) = settle(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> ListenResult.Silence
                            else -> ListenResult.Failure("erreur de reconnaissance $error")
                        },
                    )

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1_500L,
                    )
                }

                main.postDelayed({ settle(ListenResult.Silence) }, timeoutMs)
                continuation.invokeOnCancellation { settle(ListenResult.Silence) }
                recognizer.startListening(intent)
            }
        }
}
```

- [ ] **Step 5: Lancer le test**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:connectedDebugAndroidTest --tests '*AndroidSpeakerTest*'`
Expected: PASS, 2 tests

L'écoute n'est pas testable automatiquement sur l'émulateur, qui n'a pas d'entrée audio
réaliste. C'est le `DebugListener` de la Task 13 (Step 6) qui couvre ce chemin.

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat(app): adaptateurs synthese et reconnaissance vocale"
```

---

### Task 11: Journal et réglages

**Files:**
- Create: `app/src/main/kotlin/fr/appprepa/app/journal/JsonlJournal.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/settings/SettingsStore.kt`
- Test: `app/src/test/kotlin/fr/appprepa/app/journal/JsonlJournalTest.kt`

**Interfaces:**
- Consumes: `Journal`, `JournalRecord`, `WriteMode` (Task 2)
- Produces: `JsonlJournal(file: File)` implémentant `Journal`, avec
  `suspend fun readAll(): List<JournalRecord>` et `suspend fun today(nowMs: Long): List<JournalRecord>` ;
  `SettingsStore(context)` avec `apiKey`, `writeMode`, `deckId`, `cardLimit`

- [ ] **Step 1: Écrire le test qui échoue**

```kotlin
package fr.appprepa.app.journal

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonlJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun record(id: Long, ease: Ease? = Ease.GOOD) = JournalRecord(
        atMs = 1_700_000_000_000 + id,
        noteId = id,
        cardOrd = 0,
        deckName = "Prepa",
        question = "question $id",
        transcript = "reponse $id",
        proposedEase = ease,
        committedEase = null,
        verdict = Verdict.CORRECT,
        mode = WriteMode.JOURNAL_ONLY,
    )

    @Test
    fun `ecrit et relit les entrees dans l'ordre`() = runBlocking {
        val journal = JsonlJournal(folder.newFile("journal.jsonl"))
        journal.record(record(1))
        journal.record(record(2))

        val all = journal.readAll()
        assertEquals(listOf(1L, 2L), all.map { it.noteId })
        assertEquals("reponse 2", all[1].transcript)
    }

    @Test
    fun `une entree par ligne`() = runBlocking {
        val file = folder.newFile("journal.jsonl")
        val journal = JsonlJournal(file)
        journal.record(record(1))
        journal.record(record(2))
        assertEquals(2, file.readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `survit a une ligne corrompue`() = runBlocking {
        val file = folder.newFile("journal.jsonl")
        val journal = JsonlJournal(file)
        journal.record(record(1))
        file.appendText("ceci n'est pas du json\n")
        journal.record(record(2))

        val all = journal.readAll()
        assertEquals("la ligne illisible est ignoree", listOf(1L, 2L), all.map { it.noteId })
    }

    @Test
    fun `relit un fichier absent comme une liste vide`() = runBlocking {
        val journal = JsonlJournal(folder.root.resolve("jamais-ecrit.jsonl"))
        assertTrue(journal.readAll().isEmpty())
    }

    @Test
    fun `preserve une note nulle`() = runBlocking {
        val journal = JsonlJournal(folder.newFile("journal.jsonl"))
        journal.record(record(1, ease = null))
        assertEquals(null, journal.readAll().single().proposedEase)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests '*JsonlJournalTest*'`
Expected: FAIL — `Unresolved reference: JsonlJournal`

- [ ] **Step 3: Écrire le journal**

```kotlin
package fr.appprepa.app.journal

import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.JournalRecord
import fr.appprepa.core.model.Verdict
import fr.appprepa.core.model.WriteMode
import fr.appprepa.core.ports.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Un objet JSON par ligne : robuste à une écriture interrompue, lisible tel quel. */
class JsonlJournal(private val file: File) : Journal {

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Row(
        val ts: Long,
        val noteId: Long,
        val cardOrd: Int,
        val deck: String,
        val question: String,
        val transcript: String,
        val proposedEase: Int? = null,
        val committedEase: Int? = null,
        val verdict: String? = null,
        val mode: String,
        val note: String? = null,
    )

    override suspend fun record(entry: JournalRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(entry.toRow()) + "\n")
        }
    }

    suspend fun readAll(): List<JournalRecord> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString<Row>(line).toRecord() }.getOrNull()
            }
    }

    suspend fun today(nowMs: Long): List<JournalRecord> {
        val dayStart = nowMs - (nowMs % 86_400_000L)
        return readAll().filter { it.atMs >= dayStart }
    }

    private fun JournalRecord.toRow() = Row(
        ts = atMs,
        noteId = noteId,
        cardOrd = cardOrd,
        deck = deckName,
        question = question,
        transcript = transcript,
        proposedEase = proposedEase?.value,
        committedEase = committedEase?.value,
        verdict = verdict?.name,
        mode = mode.name,
        note = note,
    )

    private fun Row.toRecord() = JournalRecord(
        atMs = ts,
        noteId = noteId,
        cardOrd = cardOrd,
        deckName = deck,
        question = question,
        transcript = transcript,
        proposedEase = proposedEase?.let { Ease.fromValue(it) },
        committedEase = committedEase?.let { Ease.fromValue(it) },
        verdict = verdict?.let { runCatching { Verdict.valueOf(it) }.getOrNull() },
        mode = runCatching { WriteMode.valueOf(mode) }.getOrDefault(WriteMode.JOURNAL_ONLY),
        note = note,
    )
}
```

- [ ] **Step 4: Écrire les réglages**

La clé d'API est un secret : elle va dans des préférences chiffrées, jamais dans le code
ni dans un fichier versionné.

```kotlin
package fr.appprepa.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fr.appprepa.core.model.WriteMode

class SettingsStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "reglages",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** Par défaut, rien n'est écrit dans Anki. Le basculement est une décision explicite. */
    var writeMode: WriteMode
        get() = runCatching { WriteMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(WriteMode.JOURNAL_ONLY)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var deckId: Long?
        get() = prefs.getLong(KEY_DECK, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().putLong(KEY_DECK, value ?: -1L).apply()

    var cardLimit: Int
        get() = prefs.getInt(KEY_LIMIT, 40)
        set(value) = prefs.edit().putInt(KEY_LIMIT, value.coerceIn(1, 200)).apply()

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODE = "write_mode"
        const val KEY_DECK = "deck_id"
        const val KEY_LIMIT = "card_limit"
    }
}
```

- [ ] **Step 5: Lancer les tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat(app): journal JSONL et reglages chiffres"
```

---

### Task 12: Service de session et focus audio

**Files:**
- Create: `app/src/main/kotlin/fr/appprepa/app/session/SessionService.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/session/AudioFocusGuard.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/session/SessionHolder.kt`
- Modify: `app/src/main/AndroidManifest.xml` (déclaration du service)
- Test: `app/src/test/kotlin/fr/appprepa/app/session/SessionHolderTest.kt`

**Interfaces:**
- Consumes: `SessionLoop` (Task 7), `AnkiDroidGateway` (Task 8), `AnthropicTutor` (Task 9),
  `AndroidSpeaker` / `AndroidListener` (Task 10), `JsonlJournal` / `SettingsStore` (Task 11)
- Produces: `SessionService` (service de premier plan), `SessionHolder` (objet partagé
  exposant `StateFlow<SessionState>` et `StateFlow<SessionOutcome?>` à l'interface),
  `AudioFocusGuard(context)` avec `suspend fun <T> withFocus(block: suspend () -> T): T`

Le service de premier plan est ce qui permet à la session de survivre à l'extinction de
l'écran — sans lui, la révision s'arrête dès que le téléphone se verrouille dans le
support de voiture.

- [ ] **Step 1: Écrire le test qui échoue**

```kotlin
package fr.appprepa.app.session

import fr.appprepa.core.engine.SessionState
import fr.appprepa.core.model.SessionStats
import kotlinx.coroutines.test.runTest
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
    fun `publie l'etat courant`() = runTest {
        val holder = SessionHolder()
        holder.publish(SessionState.Loading)
        assertEquals(SessionState.Loading, holder.state.value)
    }

    @Test
    fun `publie le resultat final et repasse a l'arret`() = runTest {
        val holder = SessionHolder()
        holder.markRunning(true)
        holder.finish(SessionOutcome.Completed(SessionStats(answered = 3, correct = 2)))

        val outcome = holder.outcome.value as SessionOutcome.Completed
        assertEquals(3, outcome.stats.answered)
        assertTrue(!holder.isRunning.value)
    }

    @Test
    fun `publie un echec explicite`() = runTest {
        val holder = SessionHolder()
        holder.finish(SessionOutcome.Failed("AnkiDroid inaccessible"))
        assertEquals(
            "AnkiDroid inaccessible",
            (holder.outcome.value as SessionOutcome.Failed).reason,
        )
    }

    @Test
    fun `effacer le resultat le remet a zero`() = runTest {
        val holder = SessionHolder()
        holder.finish(SessionOutcome.Failed("erreur"))
        holder.clearOutcome()
        assertNull(holder.outcome.value)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests '*SessionHolderTest*'`
Expected: FAIL — `Unresolved reference: SessionHolder`

- [ ] **Step 3: Écrire l'état partagé**

```kotlin
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

/** Pont entre le service qui exécute la session et l'interface qui l'affiche. */
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
        /** Une seule session à la fois : le service et l'interface partagent cet objet. */
        val shared = SessionHolder()
    }
}
```

- [ ] **Step 4: Écrire la garde de focus audio**

```kotlin
package fr.appprepa.app.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CompletableDeferred

/**
 * Prend un focus audio transitoire avec atténuation : la musique baisse pendant la
 * question au lieu de s'arrêter, et une instruction GPS ou un appel entrant suspend
 * la session au lieu de parler par-dessus.
 */
class AudioFocusGuard(context: Context) {

    private val manager = context.getSystemService(AudioManager::class.java)
    private var paused: CompletableDeferred<Unit>? = null

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> if (paused == null) paused = CompletableDeferred()
                AudioManager.AUDIOFOCUS_GAIN -> {
                    paused?.complete(Unit)
                    paused = null
                }
                else -> Unit
            }
        }
        .build()

    suspend fun <T> withFocus(block: suspend () -> T): T {
        manager.requestAudioFocus(request)
        return try {
            block()
        } finally {
            manager.abandonAudioFocusRequest(request)
        }
    }

    /** Suspend tant que le focus est perdu. */
    suspend fun awaitResume() {
        paused?.await()
    }
}
```

Le moteur ne doit rien savoir du focus audio : c'est un décorateur de `Speaker`, côté
Android, qui attend la reprise avant chaque énoncé. Une instruction GPS ou un appel entrant
suspend donc la session à la frontière d'une phrase, jamais au milieu.

```kotlin
package fr.appprepa.app.session

import fr.appprepa.core.ports.Speaker

/** Attend la reprise du focus audio avant de parler. */
class FocusAwareSpeaker(
    private val delegate: Speaker,
    private val guard: AudioFocusGuard,
) : Speaker {
    override suspend fun speak(text: String) {
        guard.awaitResume()
        delegate.speak(text)
    }

    override fun stop() = delegate.stop()
}
```

- [ ] **Step 5: Écrire le service**

```kotlin
package fr.appprepa.app.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.appprepa.app.anki.AnkiAvailability
import fr.appprepa.app.anki.AnkiDroidGateway
import fr.appprepa.app.anki.AnkiStatus
import fr.appprepa.app.journal.JsonlJournal
import fr.appprepa.app.llm.AnthropicTutor
import fr.appprepa.app.settings.SettingsStore
import fr.appprepa.app.voice.AndroidListener
import fr.appprepa.app.voice.AndroidSpeaker
import fr.appprepa.core.engine.SessionLoop
import fr.appprepa.core.ports.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null
    private var speaker: AndroidSpeaker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Session de révision en cours"))
        if (job == null) job = scope.launch { runSession() }
        return START_STICKY
    }

    private suspend fun runSession() {
        val holder = SessionHolder.shared
        holder.markRunning(true)
        holder.clearOutcome()

        val settings = SettingsStore(this)

        val status = AnkiAvailability.check(this)
        if (status != AnkiStatus.Ready) {
            holder.finish(SessionOutcome.Failed(AnkiAvailability.message(status)))
            stopSelf()
            return
        }
        if (settings.apiKey.isBlank()) {
            holder.finish(SessionOutcome.Failed("Aucune clé d'API renseignée."))
            stopSelf()
            return
        }

        val tts = AndroidSpeaker(this).also { speaker = it }
        if (!tts.awaitReady()) {
            holder.finish(SessionOutcome.Failed("La synthèse vocale française n'est pas disponible."))
            stopSelf()
            return
        }

        val guard = AudioFocusGuard(this)

        val loop = SessionLoop(
            gateway = AnkiDroidGateway(contentResolver),
            tutor = AnthropicTutor(settings.apiKey),
            speaker = FocusAwareSpeaker(tts, guard),
            listener = AndroidListener(this),
            journal = JsonlJournal(File(filesDir, "journal.jsonl")),
            clock = object : Clock { override fun nowMs() = System.currentTimeMillis() },
            writeMode = settings.writeMode,
        )

        val mirror = scope.launch {
            loop.state.collect { holder.publish(it) }
        }

        val outcome = runCatching {
            guard.withFocus { loop.run(settings.deckId, settings.cardLimit) }
        }
        mirror.cancel()

        holder.finish(
            outcome.fold(
                onSuccess = { SessionOutcome.Completed(it) },
                onFailure = { SessionOutcome.Failed(it.message ?: "erreur inattendue") },
            ),
        )
        stopSelf()
    }

    private fun stopSession() {
        speaker?.stop()
        job?.cancel()
        job = null
        stopSelf()
    }

    override fun onDestroy() {
        speaker?.release()
        speaker = null
        scope.cancel()
        SessionHolder.shared.markRunning(false)
        super.onDestroy()
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sillon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_STOP = "fr.appprepa.app.STOP_SESSION"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Session de révision",
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun start(context: Context) {
            ensureChannel(context)
            context.startForegroundService(Intent(context, SessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
```

- [ ] **Step 6: Déclarer le service dans le manifeste**

```xml
        <service
            android:name=".session.SessionService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
```

Ajouter aussi la permission de notification, requise depuis Android 13 :

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 7: Lancer les tests et compiler**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS et BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src && git commit -m "feat(app): service de premier plan et focus audio"
```

---

### Task 13: Interface et permissions

**Files:**
- Modify: `app/src/main/kotlin/fr/appprepa/app/ui/MainActivity.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/ui/HomeScreen.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/ui/JournalScreen.kt`
- Create: `app/src/main/kotlin/fr/appprepa/app/ui/StateLabels.kt`
- Test: `app/src/test/kotlin/fr/appprepa/app/ui/StateLabelsTest.kt`

**Interfaces:**
- Consumes: `SessionHolder`, `SessionOutcome` (Task 12), `SettingsStore`, `JsonlJournal` (Task 11),
  `AnkiAvailability` (Task 8)
- Produces: `StateLabels.of(state: SessionState): String`, `HomeScreen`, `JournalScreen`

L'écran ne demande jamais rien pendant la conduite. Il affiche un seul mot d'état, en très
gros, lisible d'un coup d'œil, et rien d'autre.

- [ ] **Step 1: Écrire le test qui échoue**

```kotlin
package fr.appprepa.app.ui

import fr.appprepa.core.engine.Assessment
import fr.appprepa.core.engine.CardInFlight
import fr.appprepa.core.engine.SessionState
import fr.appprepa.core.model.Ease
import fr.appprepa.core.model.Judgement
import fr.appprepa.core.model.ReviewCard
import fr.appprepa.core.model.SessionStats
import fr.appprepa.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateLabelsTest {

    private val inFlight = CardInFlight(
        ReviewCard(1, 0, "Prepa", "recto", "verso", 4, false),
        "question",
        emptyList(),
        0L,
    )

    @Test
    fun `chaque etat a un libelle court et non vide`() {
        val states = listOf(
            SessionState.Idle,
            SessionState.Loading,
            SessionState.Preparing(inFlight.card),
            SessionState.Asking(inFlight),
            SessionState.Listening(inFlight),
            SessionState.Judging(inFlight, "reponse"),
            SessionState.SpeakingVerdict(
                inFlight,
                Assessment.Judged(
                    Judgement(Verdict.CORRECT, Ease.GOOD, "ok", emptyList(), null, null),
                ),
            ),
            SessionState.AwaitingCorrection(inFlight, Assessment.SelfGrade("verso")),
            SessionState.Finished(SessionStats(answered = 2, correct = 1)),
            SessionState.Failed("panne"),
        )

        states.forEach { state ->
            val label = StateLabels.of(state)
            assertTrue("libelle vide pour $state", label.isNotBlank())
            assertTrue("libelle trop long pour $state : $label", label.length <= 40)
        }
    }

    @Test
    fun `l'ecoute est annoncee explicitement`() {
        assertEquals("Je t'écoute", StateLabels.of(SessionState.Listening(inFlight)))
    }

    @Test
    fun `la fin affiche le decompte`() {
        val label = StateLabels.of(SessionState.Finished(SessionStats(answered = 5, correct = 4)))
        assertTrue(label.contains("5"))
        assertTrue(label.contains("4"))
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests '*StateLabelsTest*'`
Expected: FAIL — `Unresolved reference: StateLabels`

- [ ] **Step 3: Écrire les libellés**

```kotlin
package fr.appprepa.app.ui

import fr.appprepa.core.engine.SessionState

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
```

- [ ] **Step 4: Écrire l'écran principal**

```kotlin
package fr.appprepa.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.appprepa.app.session.SessionHolder
import fr.appprepa.app.session.SessionOutcome
import fr.appprepa.app.settings.SettingsStore
import fr.appprepa.core.model.WriteMode

@Composable
fun HomeScreen(
    settings: SettingsStore,
    ankiMessage: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val holder = SessionHolder.shared
    val state by holder.state.collectAsState()
    val running by holder.isRunning.collectAsState()
    val outcome by holder.outcome.collectAsState()

    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var writeThrough by remember { mutableStateOf(settings.writeMode == WriteMode.WRITE_THROUGH) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Pendant la conduite, seule cette ligne compte.
        Text(
            text = StateLabels.of(state),
            fontSize = 34.sp,
            style = MaterialTheme.typography.headlineLarge,
        )

        when (val done = outcome) {
            is SessionOutcome.Failed -> Text("Échec : ${done.reason}")
            is SessionOutcome.Completed -> Text(
                "${done.stats.answered} cartes, ${done.stats.correct} justes, " +
                    "${done.stats.committed} notées.",
            )
            null -> Unit
        }

        Button(
            onClick = if (running) onStop else onStart,
            modifier = Modifier.fillMaxWidth().size(width = 0.dp, height = 88.dp),
        ) {
            Text(if (running) "Arrêter" else "Démarrer la session", fontSize = 22.sp)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(ankiMessage)

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        settings.apiKey = it
                    },
                    label = { Text("Clé d'API Anthropic") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    Text("Écrire les notes dans Anki")
                    Text(
                        "Désactivé, l'application se contente de journaliser ce qu'elle " +
                            "aurait noté. Laisse-le désactivé le temps de vérifier le journal.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = writeThrough,
                        onCheckedChange = {
                            writeThrough = it
                            settings.writeMode =
                                if (it) WriteMode.WRITE_THROUGH else WriteMode.JOURNAL_ONLY
                        },
                    )
                }

                Button(onClick = onOpenJournal, modifier = Modifier.fillMaxWidth()) {
                    Text("Voir le journal")
                }
            }
        }
    }
}
```

- [ ] **Step 5: Écrire l'écran de journal et l'activité**

`JournalScreen` liste les entrées du jour, de la plus récente à la plus ancienne, avec pour
chacune la question, la réponse transcrite, la note proposée et la note réellement écrite.
C'est l'écran qui permet de décider si le jugement du modèle est assez fiable pour activer
`WRITE_THROUGH`.

```kotlin
package fr.appprepa.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.appprepa.core.model.JournalRecord

@Composable
fun JournalScreen(entries: List<JournalRecord>) {
    if (entries.isEmpty()) {
        Text("Aucune session enregistrée aujourd'hui.", modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(entries.reversed()) { entry ->
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(entry.question, style = MaterialTheme.typography.titleSmall)
                if (entry.transcript.isNotBlank()) {
                    Text("Tu as dit : ${entry.transcript}")
                }
                Text(
                    buildString {
                        append("Proposé : ${entry.proposedEase?.name ?: "—"}")
                        append(" · Écrit : ${entry.committedEase?.name ?: "non"}")
                        entry.note?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }
    }
}
```

`MainActivity` demande les permissions à l'ouverture — `RECORD_AUDIO`,
`POST_NOTIFICATIONS` et celle d'AnkiDroid — puis affiche `HomeScreen`. Le démarrage passe
par `SessionService.start(this)` et l'arrêt par `SessionService.stop(this)`. Les
permissions sont demandées à l'arrêt, dans l'allée, jamais en roulant.

```kotlin
package fr.appprepa.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.appprepa.app.anki.AnkiAvailability
import fr.appprepa.app.anki.AnkiDroidGateway
import fr.appprepa.app.journal.JsonlJournal
import fr.appprepa.app.session.SessionService
import fr.appprepa.app.settings.SettingsStore
import fr.appprepa.core.model.JournalRecord
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(this)
        val journal = JsonlJournal(File(filesDir, "journal.jsonl"))

        setContent {
            MaterialTheme {
                var showJournal by remember { mutableStateOf(false) }
                var entries by remember { mutableStateOf(emptyList<JournalRecord>()) }
                var ankiMessage by remember { mutableStateOf("") }

                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { ankiMessage = AnkiAvailability.message(AnkiAvailability.check(this)) }

                LaunchedEffect(Unit) {
                    permissions.launch(
                        arrayOf(
                            android.Manifest.permission.RECORD_AUDIO,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                            AnkiDroidGateway.PERMISSION,
                        ),
                    )
                }

                LaunchedEffect(showJournal) {
                    if (showJournal) entries = journal.today(System.currentTimeMillis())
                }

                if (showJournal) {
                    JournalScreen(entries)
                } else {
                    HomeScreen(
                        settings = settings,
                        ankiMessage = ankiMessage,
                        onStart = { SessionService.start(this@MainActivity) },
                        onStop = { SessionService.stop(this@MainActivity) },
                        onOpenJournal = { showJournal = true },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 6: Écrire le chemin de débogage par injection de transcripts**

L'émulateur n'a pas d'entrée audio réaliste, et itérer sur les prompts en montant en
voiture n'est pas une méthode de travail. Ce `Listener` de remplacement lit les réponses
depuis un champ de texte au lieu du micro : toute la boucle s'exerce au clavier.

```kotlin
package fr.appprepa.app.session

import fr.appprepa.core.ports.ListenResult
import fr.appprepa.core.ports.Listener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Remplace le micro par un champ de texte. Réservé à la mise au point. */
class DebugListener : Listener {

    private val inbox = Channel<String>(Channel.BUFFERED)

    suspend fun submit(text: String) {
        inbox.send(text)
    }

    override suspend fun listen(timeoutMs: Long): ListenResult =
        when (val text = withTimeoutOrNull(timeoutMs) { inbox.receive() }) {
            null -> ListenResult.Silence
            else -> if (text.isBlank()) ListenResult.Silence else ListenResult.Transcript(text)
        }

    companion object {
        /** Partagé entre l'interface qui saisit et le service qui écoute. */
        val shared = DebugListener()
    }
}
```

Ajouter le drapeau dans `SettingsStore` :

```kotlin
    var debugTranscripts: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    // dans le companion
    const val KEY_DEBUG = "debug_transcripts"
```

Dans `SessionService.runSession`, choisir la source d'écoute :

```kotlin
            listener = if (settings.debugTranscripts) {
                DebugListener.shared
            } else {
                AndroidListener(this)
            },
```

Dans `HomeScreen`, un interrupteur « Répondre au clavier » et, lorsqu'il est actif, un
champ de saisie qui appelle `DebugListener.shared.submit(...)` à la validation. Le champ
n'apparaît jamais quand le mode est désactivé, pour ne pas encombrer l'écran de conduite.

- [ ] **Step 7: Lancer les tests et compiler**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS et BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src && git commit -m "feat(app): interface, permissions, journal et saisie de debogage"
```

---

### Task 14: Validation de bout en bout sur émulateur

**Files:**
- Create: `scripts/prepare-emulator.sh`
- Create: `docs/INSTALLATION.md`

**Interfaces:**
- Consumes: tout ce qui précède
- Produces: un émulateur muni d'AnkiDroid et d'un deck de test, et une procédure
  d'installation sur le téléphone de l'utilisateur

- [ ] **Step 1: Écrire le script de préparation**

```bash
#!/usr/bin/env bash
# Installe AnkiDroid sur l'emulateur et accorde les permissions necessaires.
set -euo pipefail

SERIAL="${1:-emulator-5554}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
APK_URL="https://github.com/ankidroid/Anki-Android/releases/latest/download/AnkiDroid-universal.apk"
APK_PATH="${TMPDIR:-/tmp}/ankidroid.apk"

echo "== telechargement d'AnkiDroid =="
curl -sSL -o "$APK_PATH" "$APK_URL"

echo "== installation =="
"$ADB" -s "$SERIAL" install -r "$APK_PATH"

echo "== permissions de l'application =="
"$ADB" -s "$SERIAL" shell pm grant fr.appprepa.app android.permission.RECORD_AUDIO || true
"$ADB" -s "$SERIAL" shell pm grant fr.appprepa.app android.permission.POST_NOTIFICATIONS || true
"$ADB" -s "$SERIAL" shell pm grant fr.appprepa.app com.ichi2.anki.permission.READ_WRITE_DATABASE || true

echo "== verification du provider =="
"$ADB" -s "$SERIAL" shell content query --uri content://com.ichi2.anki.flashcards/decks \
  || echo "provider muet : ouvre AnkiDroid une premiere fois pour creer la collection"
```

- [ ] **Step 2: Préparer l'émulateur**

Run: `bash scripts/prepare-emulator.sh emulator-5554`
Expected: AnkiDroid installé, la requête sur `decks` renvoie au moins le deck par défaut.
Si le provider est muet, ouvrir AnkiDroid une fois à la main crée la collection.

- [ ] **Step 3: Créer un deck de test**

Ajouter à la main, dans AnkiDroid sur l'émulateur, cinq cartes basiques en français dont
le verso fait plusieurs lignes — l'objectif est de vérifier le plafonnement du retour oral
sur des versos réalistes de fiche de prépa, pas sur des réponses d'un mot.

- [ ] **Step 4: Lancer toute la suite instrumentée**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:connectedDebugAndroidTest`
Expected: `AnkiDroidGatewayTest` ne doit plus être ignoré ; les trois tests passent, ainsi
que `AndroidSpeakerTest` et `AnthropicTutorSmokeTest`.

- [ ] **Step 5: Écrire la procédure d'installation**

`docs/INSTALLATION.md` doit couvrir : construire l'APK release, l'installer sur le
téléphone, accorder les trois permissions, saisir la clé d'API, laisser le mode journal
actif pendant les premiers trajets, puis relire le journal avant d'activer l'écriture.

- [ ] **Step 6: Commit**

```bash
git add scripts docs && git commit -m "chore: preparation emulateur et procedure d'installation"
```

---

## Ce que ce plan ne peut pas vérifier

Deux vérifications sont hors de portée d'un poste de développement et attendent l'appareil
réel et la clé d'API de l'utilisateur :

1. **La qualité du jugement sur les vraies cartes.** Le mode journal existe pour cela ;
   c'est la relecture du journal après quelques trajets qui tranche.
2. **La reconnaissance vocale en conditions de conduite** — bruit de roulement, micro du
   kit mains-libres, vocabulaire technique. L'émulateur n'a pas d'entrée audio réaliste.

Aucune des deux ne bloque le développement : la boucle complète est testable sans elles.

---

## Ce que l'exécution a corrigé

Trois écarts entre le plan et la réalité, découverts en faisant tourner le code sur
l'émulateur. Ils sont consignés ici parce qu'ils sont tous du même genre : des choses
qu'aucune relecture n'aurait attrapées.

### 1. La projection par défaut du ContentProvider n'expose pas le texte nettoyé

Le plan supposait qu'une requête `query(uri, null, ...)` sur une carte renverrait
`question_simple` et `answer_pure`. Elle ne renvoie que
`[_id, note_id, ord, card_name, deck_id, question, answer]` — les colonnes débarrassées du
HTML doivent être **demandées explicitement** dans la projection.

`AnkiDroidGateway.cardText` passe donc une projection nommée, avec repli sur
`answer_simple` puis `answer`, et un nettoyage HTML maison en dernier recours : une balise
lue à voix haute rend la question incompréhensible.

### 2. La réponse parlée n'atteignait pas le journal

Le champ `transcript` de `JournalRecord` était systématiquement vide. Le moteur portait la
réponse jusqu'à l'état `Judging`, puis la perdait : ni `SpeakingVerdict` ni
`AwaitingCorrection` ne la transportaient, et c'est `AwaitingCorrection` qui construit
l'enregistrement.

Or le journal existe pour comparer **ce que tu as dit** à **ce que le modèle en a fait** ;
sans la première colonne il ne sert à rien. `SessionState.SpeakingVerdict` et
`AwaitingCorrection` portent désormais le transcript. Couvert par `TranscriptTraceTest`.

### 3. Le bouton « Arrêter » jetait la note de la dernière carte

Le décalage d'un tour à la validation, qui rend « annule » possible, a un corollaire que le
plan n'avait pas tiré : **à tout instant une note est en attente d'écriture**. Or
`SessionService.stopSession()` annulait la coroutine, ce qui la faisait disparaître
silencieusement.

`SessionLoop.requestStop()` demande maintenant une fin propre : la boucle injecte
`Event.StopRequested`, le moteur vide ce qu'il a en main, puis rend. L'écoute en cours est
annulée pour ne pas attendre les quinze secondes de temporisation. Couvert par
`GracefulStopTest`.

### Et un détail d'honnêteté

Le bilan de fin de session annonçait « N notées » y compris en mode journal, où rien
n'atteint Anki. Le compteur `committed` compte les cartes finalisées, pas les écritures
réelles. Le libellé distingue désormais les deux cas — dans un mode dont toute la raison
d'être est la prudence, un compteur qui ment est pire qu'inutile.
