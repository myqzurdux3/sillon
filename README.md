# Khôlle

**Ta khôlle du matin.** Une application Android qui t'interroge à l'oral sur tes cartes
Anki dues pendant que tu conduis. Tu réponds à voix haute, elle juge, annonce la note
qu'elle propose, et tu peux la corriger d'un mot avant qu'elle ne parte dans Anki.

En prépa, une khôlle est l'interrogation orale. L'application ne fait rien d'autre.

## Le principe

Une machine à états pilote la session ; le modèle de langage ne fait que proposer.

```
carte due  →  reformulée en question orale  →  énoncée
                                                  ↓
        note écrite dans Anki  ←  correction vocale  ←  jugée  ←  réponse parlée
```

Trois décisions structurent tout le reste :

- **Le LLM propose, le code dispose.** Le modèle ne touche jamais la collection. Il rend
  un objet JSON, que le code valide, borne au nombre de boutons de la carte, et recalcule
  depuis le verdict si la valeur est absente ou aberrante.
- **Les écritures ont un tour de retard.** La note d'une carte n'est écrite qu'au moment où
  tu réponds à la suivante. Cela crée une vraie fenêtre pour dire « annule », alors que
  l'API AnkiDroid n'offre aucune annulation après coup.
- **Le LaTeX est verbalisé, jamais épelé.** Les fiches de prépa sont écrites en notation
  mathématique ; le modèle l'énonce en français parlé, et un convertisseur maison prend le
  relais quand le réseau tombe.
- **Le mode journal est le défaut.** Les premiers trajets n'écrivent rien : ils
  enregistrent ce que l'application *aurait* noté, pour que tu juges sa fiabilité sur tes
  propres fiches avant de lui confier ton calendrier de révision.

## Architecture

| Module | Rôle |
|---|---|
| `:core` | Kotlin/JVM pur, **zéro import `android.*`**. Modèle, machine à états, mémoire de session, parseur de commandes vocales, exécuteur d'effets. |
| `:app` | Android. Adaptateurs seulement : ContentProvider AnkiDroid, synthèse et reconnaissance vocales, client Claude, journal, service de premier plan, interface Compose. |

Cette frontière est ce qui rend la boucle complète testable : une session de bout en bout
se rejoue en quelques millisecondes, sans émulateur, sans micro et sans réseau, grâce à
six ports doublés.

## Tests

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :core:test              # 57 tests, sans Android
./gradlew :app:testDebugUnitTest  # prompts, journal, libellés
bash scripts/prepare-emulator.sh  # installe AnkiDroid sur l'émulateur
./gradlew :app:connectedDebugAndroidTest
```

Les tests instrumentés tournent contre un **vrai** AnkiDroid : ils lisent des cartes dues,
écrivent une note, et vérifient que la carte notée disparaît de la file.

## Documents

- `docs/INSTALLATION.md` — installer et prendre en main
- `docs/superpowers/specs/2026-08-26-anki-vocal-voiture-design.md` — le design et ses raisons
- `docs/superpowers/plans/2026-08-26-anki-vocal-voiture.md` — le plan d'implémentation

## Ce qu'il te faut

AnkiDroid installé sur le même téléphone, Android 12 ou plus, et une clé d'API Anthropic.
