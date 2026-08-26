<p align="center">
  <img src="docs/brand/sillon.svg" alt="" width="88" height="88">
</p>

<h1 align="center">Sillon</h1>

<p align="center"><em>A groove deepens by being travelled again</em></p>

<p align="center">
  <a href="README.md">Français</a> · <strong>English</strong>
</p>

<p align="center">
  <img alt="MIT licence" src="https://img.shields.io/badge/licence-MIT-FF4D2E">
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B-FF4D2E">
  <img alt="Kotlin, 100% Compose" src="https://img.shields.io/badge/Kotlin-100%25%20Compose-FF4D2E">
  <img alt="180 JVM tests" src="https://img.shields.io/badge/JVM%20tests-180-FF4D2E">
  <img alt="15 instrumented tests" src="https://img.shields.io/badge/instrumented%20tests-15-FF4D2E">
  <img alt="Hands free" src="https://img.shields.io/badge/hands-free-0B0B0C">
</p>

---

An Android app that **quizzes you aloud on your due Anki cards while you drive**. It turns
each card into a spoken question, listens to your answer, judges it, announces the grade it
proposes — and lets you override it with one word before it reaches Anki.

Nothing to touch, nothing to look at. You start the session parked; everything after that is
voice.

The name is the mechanism: a groove deepens by being travelled again, and it is also the one
a stylus follows. The icon draws nothing else — three concentric arcs, the deepest one in
colour. *Sillon* is French for groove, and the app speaks French.

## How it works

```
due card  →  rephrased as a spoken question  →  read aloud
                                                    ↓
      grade written to Anki  ←  spoken override  ←  judged  ←  your spoken answer
```

Four decisions carry the rest.

**The model proposes, the code disposes.** The LLM never touches the collection. It returns
a JSON object; the code validates it, clamps the grade to the card's button count, and
recomputes it from the verdict when it is missing or out of range.

**Writes lag by one card.** A card's grade is only written when you answer the next one.
That delay is what makes going back possible, since the AnkiDroid API offers no undo.

**Journal mode is the default.** The first drives write nothing: they record what the app
*would* have graded, so you can judge its reliability on your own cards before handing it
your review schedule.

**A failure never looks like silence.** Denied microphone, missing recognition service, lost
network, a grade AnkiDroid refuses to write: each one is reported, spoken, and written to
the journal. Conflating failure with silence would run a whole commute grading everything
"again", without a word of explanation.

Degraded mode only applies to the card the failure happened on: every new card retries the
model, so ten seconds under a bridge does not condemn the rest of the commute.

## What it looks like

<p align="center">
  <img src="docs/ecrans/accueil.png" alt="Home screen" width="240">
  <img src="docs/ecrans/session.png" alt="Live session" width="240">
  <img src="docs/ecrans/paquets.png" alt="Deck picker" width="240">
</p>

One state word, a rule, the card's rank. Colour serves one purpose: signalling that the app
is listening.

## Voice commands

The app listens in French. Grades: « à revoir », « faux », « difficile », « bien »,
« facile ». Navigation: « répète », « passe », « explique », « stop ». Going back:
« reviens » opens a parenthesis on the previous card — regrade it or ask for an explanation
— then the current question is read again, so you never lose your place.

A command is only recognised when it is the whole sentence: "je ne suis pas sûr, passe"
counts as an answer. Otherwise half the answers would trigger a command.

## LaTeX, spoken rather than spelled

French *prépa* flashcards are written in mathematical notation. Read verbatim, they come out
as "backslash cos". The prompt forbids the model any raw notation, and a local converter
takes over when the network drops.

```
\(\sin(2a) = 2\sin(a)\cos(a)\)
  → "sinus de 2 a égale 2 sinus de a cosinus de a"
```

## Architecture

| Module | Role |
|---|---|
| `:core` | Pure Kotlin/JVM, **zero `android.*` imports**. Domain model, state machine, session memory, command parser, formula verbalisation, deck selection. |
| `:app` | Android. Adapters only: AnkiDroid ContentProvider, speech synthesis and recognition, Claude client, journal, foreground service, Compose UI. |

That boundary is what makes the loop testable: a full session replays in milliseconds,
without an emulator, a microphone or a network, through six doubled ports.

```bash
./gradlew :core:test               # 151 tests, no Android
bash scripts/prepare-emulator.sh   # installs AnkiDroid on the emulator
./gradlew :app:connectedDebugAndroidTest
```

Instrumented tests run against a **real** AnkiDroid: they read due cards, write a grade, and
check that the graded card leaves the queue.

## Requirements

AnkiDroid installed on the same phone, Android 12 or later, and an Anthropic API key. The
key is stored encrypted on the device and never leaves the phone except for the API. No card
is sent anywhere else.

`docs/INSTALLATION.md` walks through setup command by command (in French).
`docs/conception.md` explains why the app is built this way.

## Licence

MIT.
