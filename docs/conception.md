# Révision Anki vocale en voiture — Design

Date : 2026-08-26 · révisé après audit le 2026-08-26
Statut : implémenté

Ce document dit **pourquoi** l'application est faite ainsi. Il ne contient pas de code :
le code est la seule source de vérité sur le *comment*, et un document qui le recopie
devient faux au premier commit.

## 1. Problème

Réviser ses cartes Anki dues pendant le trajet du matin en voiture, entièrement à la
voix, sans jamais toucher ni regarder le téléphone. Une IA reformule chaque carte en
question orale, écoute la réponse parlée, la juge, annonce la note qu'elle propose, et
laisse la possibilité de la corriger à la voix avant de l'écrire dans Anki.

Contexte d'usage : préparation à un concours (deck dense, réponses textuelles longues),
trajet quotidien d'environ 20 à 40 minutes.

## 2. Contraintes retenues

| Contrainte | Décision |
|---|---|
| Plateforme | Android, AnkiDroid installé sur le même téléphone |
| Source de vérité | La collection AnkiDroid locale — pas de PC, pas de serveur |
| Réseau | 4G/5G stable sur le trajet ; le cloud est autorisé, mais la perte de réseau ne doit pas casser la session |
| Forme de l'app | Application Android native Kotlin |
| Pile vocale | STT et TTS système Android + LLM distant. Couche voix abstraite pour pouvoir passer au speech-to-speech temps réel plus tard sans réécrire le reste |
| Style d'interrogation | Reformulation conversationnelle, l'IA propose la note, l'utilisateur peut corriger à la voix |
| Priorité non fonctionnelle | La vitesse. Une reformulation brillante mais lente est un échec produit |

## 3. Décision d'architecture

**Approche A : la machine à états vit dans l'application, le LLM est appelé
ponctuellement.** L'application possède la file de cartes, l'ordonnancement et, surtout,
les écritures dans Anki. Le LLM ne touche jamais la collection : il reçoit un contexte
borné et renvoie un objet JSON que le code valide avant d'agir.

Deux emprunts aux approches écartées :

- **Préchargement** (emprunté à l'approche « pré-génération ») : pendant que l'utilisateur
  répond à la carte N, la carte N+1 est déjà chargée depuis AnkiDroid et sa reformulation
  déjà demandée au LLM. La latence de reformulation devient invisible.
- **Mémoire de session glissante** (emprunté à l'approche « agent unique ») : un petit
  objet structuré — thèmes ratés, tics de formulation, compteurs — est réinjecté dans
  chaque prompt. On récupère l'adaptativité conversationnelle sans laisser le contexte
  enfler sur 40 cartes ni céder le contrôle au modèle.

Le principe directeur : **le LLM propose, le code dispose.**

## 4. Découpage en modules

Deux modules Gradle, séparés par une frontière franche : ce qui est testable sans Android
d'un côté, ce qui touche le système de l'autre.

### `:core` — module Kotlin/JVM pur, zéro dépendance Android

Contient toute la logique décisionnelle, donc tout ce qui mérite des tests rapides.

- `model/` — `ReviewCard`, `Ease`, `Verdict`, `SessionMemory`, `JournalRecord`
- `engine/ReviewSessionEngine` — machine à états pure : `reduce(state, event) -> (state, effects)`
- `voice/VoiceCommandParser` — reconnaît les commandes françaises dans un transcript
- `memory/SessionMemoryBuilder` — construit et tronque la mémoire de session
- `ports/` — interfaces que `:app` implémente : `AnkiGateway`, `Tutor`, `Speaker`,
  `Listener`, `Journal`, `Clock`

### `:app` — module Android

Adaptateurs uniquement, aussi peu de logique que possible.

- `anki/AnkiDroidGateway` — le `ContentProvider` d'AnkiDroid
- `voice/AndroidSpeaker` (`TextToSpeech`), `voice/AndroidListener` (`SpeechRecognizer`)
- `llm/AnthropicTutor` — client HTTP de l'API Messages
- `journal/JsonlJournal` — fichier JSONL dans le stockage privé de l'app
- `session/SessionRunner` — service de premier plan qui exécute les effets émis par le moteur
- `ui/` — un écran Compose minimal : bouton Démarrer, état courant en gros, journal du soir

Ce découpage a une conséquence directe sur le développement : la boucle de session
complète se teste en JUnit avec des implémentations factices des six ports, sans
émulateur et sans micro.

## 5. Intégration AnkiDroid

Faits vérifiés dans `FlashCardsContract.kt` du dépôt AnkiDroid (branche `main`,
consulté le 2026-08-26) — aucune de ces API n'est dépréciée.

**Autorité** : `com.ichi2.anki.flashcards`
**Permission** : `com.ichi2.anki.permission.READ_WRITE_DATABASE`, à demander à l'exécution.
**Visibilité de paquet** (API 30+) : `<queries><package android:name="com.ichi2.anki"/></queries>`
dans le manifeste, sans quoi le provider est invisible.

### Lire les cartes dues

`content://com.ichi2.anki.flashcards/schedule`, sélection `"limit=?, deckID=?"`.

Colonnes lues : `note_id`, `ord`, `button_count`, `next_review_times`, `media_files`.

Cette table ne contient **pas** le texte des cartes. Il faut ensuite interroger
`content://com.ichi2.anki.flashcards/notes/<note_id>/cards/<ord>` et lire
`question_simple` et `answer_pure` : versions débarrassées du CSS et du HTML, et pour
`answer_pure`, débarrassée de la reprise du recto. Ce sont ces deux champs qui partent
à la synthèse vocale et au LLM.

### Écrire la note

`update()` sur l'URI `schedule` avec `note_id`, `ord`, `answer_ease` et `time_taken`.
`button_count` vaut 2 à 4 selon la carte : la note proposée par le LLM est **bornée à
cet intervalle** avant écriture.

### Cartes à média

Si `media_files` est non vide, la carte dépend d'une image ou d'un son. En voiture elle
est inutilisable : **v1 l'écarte sans la noter**, l'enregistre au journal, et passe à la
suivante. Elle restera due pour une révision à l'écran.

## 6. Machine à états de session

Le moteur est une fonction pure. Il ne connaît ni les coroutines, ni le réseau, ni Android.

États :

```
Idle
Loading                       chargement du lot de cartes dues
Asking(card)                  le TTS énonce la question reformulée
Listening(card)               le STT capte la réponse
Judging(card, transcript)     appel LLM de jugement en cours
Verdict(card, verdict)        le TTS énonce le retour et la note proposée
AwaitingCorrection(card)      fenêtre courte d'écoute pour corriger la note
Committing(card, ease)
Finished(stats)
Failed(reason)
```

Événements entrants : `Start`, `CardsLoaded`, `SpeechFinished`, `TranscriptReady`,
`TranscriptEmpty`, `PrefetchReady`, `JudgeReady`, `CorrectionTimeout`, `NetworkLost`,
`Error`, `StopRequested`.

Effets sortants : `Speak`, `Listen`, `PrefetchNext`, `Judge`, `CommitEase`,
`RecordJournal`, `Stop`.

Le préchargement est déclenché à l'entrée dans `Listening` : au moment précis où
l'utilisateur commence à parler, la carte suivante part en chargement et en reformulation.

### Décalage d'un tour à la validation

Les écritures Anki sont **décalées d'une carte**. La note de la carte N n'est écrite qu'au
moment où la carte N+1 est énoncée. Cela crée une fenêtre réelle pendant laquelle « annule »
peut encore rattraper une note erronée, alors que l'API AnkiDroid n'offre aucune annulation
après coup. En fin de session, la dernière note en attente est validée avant `Finished`.

## 7. Couche voix

Deux interfaces dans `:core`, volontairement pauvres, pour que le passage futur au
speech-to-speech temps réel soit un remplacement d'implémentation et non une réécriture :

```kotlin
interface Speaker {
    suspend fun speak(text: String)               // rend la main à la fin de l'énoncé
    fun stop()
}

interface Listener {
    suspend fun listen(kind: ListenKind, timeoutMs: Long): ListenResult
}
```

`ListenResult` vaut `Transcript(text)`, `Silence`, ou `Failure(cause)`.

`ListenKind` distingue les deux fenêtres — `ANSWER` et `CORRECTION` — parce qu'elles
n'attendent pas la même chose. Une réponse est une phrase qu'on cherche parfois ; le
silence qui la clôt est fixé à 2 s, et le dépassement est rattrapé par la relance
(section 6). Une correction est un mot connu d'avance : 1,1 s suffit, et attendre plus
n'ajoute que du blanc entre deux cartes. Ce silence est du temps mort pur, l'utilisateur
a fini de parler et attend : c'est le poste de latence le moins visible et le plus payant.

Implémentation v1 : `TextToSpeech` et `SpeechRecognizer` d'Android, en français. Pas
d'interruption de la parole de l'IA en v1 — la commande « répète » couvre le besoin réel
à moindre risque.

### Choix de la voix

Le moteur retient la première voix française venue, souvent la plus pauvre. L'adaptateur
classe les voix installées par qualité déclarée, puis par latence de démarrage, et
**écarte les voix réseau** tant qu'il existe une voix embarquée : une voix réseau ajoute
un aller-retour à chaque énoncé et se tait dans un tunnel — le moteur signale alors une
erreur, l'application enchaîne sans un mot, et la séance continue à l'aveugle.

Le débit par défaut des moteurs français est calé sur la lecture d'un écran. Il est monté
à 1,15 et reste réglable dans l'application, entre 0,8 et 1,6.

Gestion audio : service de premier plan, `AudioAttributes` en usage assistant vocal, prise
de focus audio transitoire avec atténuation. La musique baisse pendant la question ; une
instruction GPS ou un appel entrant met la session en pause et la reprend après.

## 8. Couche LLM

Deux appels par carte, prompts courts, sortie JSON stricte validée avant usage.

### Appel 1 — reformuler

Entrée : recto, verso, nom du deck, mémoire de session.
Sortie :

```json
{ "question": "...", "expected_points": ["...", "..."] }
```

La question doit être énonçable en une phrase. Les `expected_points` sont les éléments
attendus dans la réponse ; ils rendent le jugement plus ancré et le retour plus précis.

### Appel 2 — juger

Entrée : recto, verso, `expected_points`, transcript de la réponse, mémoire de session.
Sortie :

```json
{
  "verdict": "correct | partiel | faux",
  "ease": 1,
  "spoken_feedback": "deux phrases maximum",
  "formulation_note": "... ou null",
  "topic": "..."
}
```

`spoken_feedback` est plafonné en longueur côté code : au-delà d'environ 40 mots, il est
tronqué à la phrase. Un verso de fiche de prépa peut faire dix lignes ; l'énoncer
intégralement à voix haute tuerait le rythme. Quand le verdict est `correct`, le plafond
tombe à 12 mots et à la première phrase : l'élève vient de le dire, le lui réexpliquer
allonge le trajet pour rien.

### Réglage des deux appels

Le jugement est sur le chemin critique — l'utilisateur a fini de parler et attend en
silence — alors que la reformulation est préchargée pendant qu'il répond à la carte
d'avant. Les deux appels ne sont donc pas réglés pareil :

| | reformulation | jugement et explication |
|---|---|---|
| Réflexion préalable | par défaut du modèle | désactivée |
| Effort | bas | bas |
| Mode rapide | non | oui, quand le modèle le propose |
| Plafond de sortie | 700 jetons | 400 jetons |

Comparer une réponse orale à un verso connu et rendre un petit objet JSON ne demande pas
de raisonnement étendu : l'activer coûtait plusieurs secondes par carte sans rien apporter
au verdict. Le mode rapide a son propre quota, plus étroit que le quota ordinaire ; le
saturer ne doit pas arrêter la séance, l'adaptateur repart alors en vitesse normale.

`formulation_note` est le canal par lequel l'objectif « m'apprendre à formuler des phrases
claires et précises » se réalise : le modèle ne le remplit que lorsque la réponse était
juste sur le fond mais mal formulée, et la remarque remonte dans la mémoire de session.

Le champ `ease` est produit par le modèle ; le prompt lui donne la correspondance à
suivre — faux → 1, partiel → 2, correct → 3, correct et bien formulé → 4. Le code ne fait
pas confiance à cette valeur : il la borne à l'intervalle `1..button_count` et, si elle est
absente ou hors bornes, la recalcule depuis `verdict` selon la même table.

## 9. Mémoire de session

```kotlin
data class SessionMemory(
    val missedTopics: List<String>,      // 8 derniers, dédupliqués
    val formulationNotes: List<String>,  // 5 derniers
    val answered: Int,
    val correct: Int,
)
```

Rendue en texte court (400 caractères maximum) et injectée dans les deux prompts. Coût
constant quelle que soit la longueur de la session. Elle permet au modèle des relances du
type « on avait déjà buté là-dessus tout à l'heure » sans mémoire conversationnelle réelle.

## 10. Commandes vocales

Reconnues localement par `VoiceCommandParser`, avant tout appel réseau, sur transcript
normalisé (minuscules, accents retirés).

| Intention | Déclencheurs |
|---|---|
| Corriger en 1 | « encore », « à revoir », « raté », « non » |
| Corriger en 2 | « difficile », « dur » |
| Corriger en 3 | « bien », « correct », « ok » |
| Corriger en 4 | « facile » |
| Répéter | « répète », « pardon », « quoi » |
| Passer sans noter | « passe », « suivante » |
| Expliquer | « explique », « je ne sais pas », « je sèche » |
| Annuler la note précédente | « annule » |
| Terminer | « stop », « pause », « terminé » |

« explique » déclenche un appel LLM supplémentaire qui développe la réponse, puis note
automatiquement la carte en 1. C'est le comportement voulu : ne pas savoir, c'est à revoir.

## 11. Mode journal et sécurité des écritures

Écrire une mauvaise note dans Anki décale le calendrier de la carte pour des semaines, et
l'API AnkiDroid n'offre pas d'annulation. Deux modes, sélectionnables dans l'écran de
réglages :

- `JOURNAL_ONLY` — **valeur par défaut**. La session se déroule normalement, les notes
  proposées sont enregistrées, mais rien n'est écrit dans Anki. Le soir, l'écran de journal
  affiche chaque carte, la réponse donnée et la note proposée, ce qui permet de juger la
  fiabilité du modèle sur ses propres cartes.
- `WRITE_THROUGH` — les notes partent réellement dans Anki, avec le décalage d'un tour et
  la commande « annule » décrits en section 6.

Le passage en `WRITE_THROUGH` est une action délibérée de l'utilisateur, jamais un défaut.

Format du journal, un objet JSON par ligne :

```json
{"ts":"...","noteId":123,"cardOrd":0,"deck":"...","question":"...","transcript":"...",
 "proposedEase":3,"committedEase":null,"verdict":"correct","mode":"JOURNAL_ONLY"}
```

## 12. Erreurs et dégradations

| Situation | Comportement |
|---|---|
| Réseau perdu | Mode dégradé : le recto est lu tel quel, l'utilisateur répond, le verso est lu, l'utilisateur dicte lui-même sa note. Le mode dégradé ne vaut que **pour la carte où la panne a eu lieu** : chaque nouvelle carte retente le modèle, donc la session revient d'elle-même au mode normal dès le réseau revenu. Sans cette reprise, une coupure de dix secondes condamnerait tout le trajet |
| Transcript vide ou silence | Une relance (« je t'écoute »), puis note 1 et carte suivante |
| Phrase coupée par un silence de réflexion | La reconnaissance rend la main sur un silence sans savoir si la phrase était finie. Quand elle s'arrête sur un mot qui appelle une suite, l'application redemande une fois et recolle les deux morceaux, plutôt que de juger une demi-réponse. Un silence après la relance vaut « j'ai fini » : ce qui a été entendu est jugé, jamais jeté |
| Appel LLM en échec ou JSON invalide | Un réessai, puis bascule sur le mode dégradé ci-dessus pour cette carte |
| AnkiDroid absent, permission refusée, collection verrouillée | Échec immédiat et explicite au démarrage, annoncé vocalement et affiché — jamais en cours de trajet |
| Aucune carte due | Annonce et fin propre |
| Perte de focus audio transitoire | Pause à la frontière d'une phrase, puis reprise à la carte en cours |
| Perte de focus audio définitive | Fin propre de la session : le focus ne reviendra pas, et attendre une reprise qui n'arrive jamais figerait la session sans un mot |
| Micro en panne | Annoncé, une relance, puis arrêt. Une panne technique n'est jamais confondue avec un silence : la confusion noterait tout le trajet « à revoir » |
| Écriture refusée par AnkiDroid | Comptée, portée au journal avec sa raison, et annoncée dans le bilan de fin. Une note perdue en silence rendrait le journal inutilisable — or c'est lui qui sert à décider d'activer l'écriture réelle |
| Appel au modèle qui pend | Délai borné côté client ; au-delà, c'est un échec, donc le mode dégradé. Une requête qui pend ne rend jamais la main |

Le mode dégradé mérite d'être souligné : il transforme la panne réseau en simple perte de
confort, pas en fin de session.

## 13. Usage en conduite

La session se lance à l'arrêt, avant de démarrer. Tout ce qui suit est purement audio :
aucune interaction à l'écran n'est requise jusqu'à la fin du trajet. L'écran n'affiche
qu'un état en très gros caractères, lisible d'un coup d'œil, et ne demande jamais rien.
Le service de premier plan garantit que la session survit à l'extinction de l'écran.

## 14. Stratégie de test

- **`:core`, tests unitaires JUnit** — transitions de la machine à états, parseur de
  commandes, construction et troncature de la mémoire, bornage de l'ease sur
  `button_count`, décalage de validation et « annule », bascule en mode dégradé.
- **Session complète simulée** — les six ports remplacés par des implémentations factices,
  une session de bout en bout jouée en millisecondes, sans émulateur ni micro.
- **`:app`, tests instrumentés** — `AnkiDroidGateway` contre un vrai AnkiDroid installé sur
  l'émulateur avec un deck de test : lecture des cartes dues, écriture d'une note,
  vérification que la carte n'est plus due. Un test y vérifie aussi que la table
  `schedule` expose bien `button_count` et `media_files` : sans eux, une carte à image
  passerait pour une carte de texte et serait énoncée à vide — une dégradation
  silencieuse, donc à transformer en échec visible.
- **Chemin de débogage** — un mode qui injecte des transcripts au clavier au lieu du micro,
  puisque l'émulateur n'a pas d'entrée audio réaliste. Indispensable pour itérer sur les
  prompts sans monter en voiture.

## 14 bis. Le LaTeX, découvert sur le deck réel

Le deck de l'utilisateur, importé et sondé le 2026-08-26, s'est révélé quasi intégralement
composé de notation LaTeX :

```
Recto : Symétries et dérivées de \(\cos\) et \(\sin\) : \(\cos\) en \(-x\), \(x + 2\pi\)
Verso : \(\cos\!\left(\frac{\pi}{2} - x\right) = \sin(x)\)
```

Lu tel quel, cela donne « backslash cos ». Le design initial ne prévoyait qu'un nettoyage
HTML : insuffisant. Deux réponses, une par mode :

- **Mode nominal.** Le prompt système impose au modèle d'énoncer les formules en français
  parlé et lui interdit explicitement backslash, accolades et symboles. C'est le meilleur
  verbaliseur disponible, et il a de toute façon la carte sous les yeux.
- **Mode dégradé.** Aucun modèle n'est joignable, il faut donc traduire soi-même :
  `MathSpeech` (dans `:core`) convertit les constructions fréquentes d'un deck de prépa —
  fonctions usuelles, lettres grecques, fractions, puissances, indices, relations. Ce n'est
  pas un moteur LaTeX ; une formule très imbriquée en ressortira approximative. Mais jamais
  sous forme de notation brute, seul résultat vraiment inacceptable au volant.

Vérifié sur les vingt premières cartes dues du deck réel : aucun backslash ne survit, et
`\(\sin(2a) = 2\sin(a)\cos(a)\)` s'énonce « sinus de 2 a égale 2 sinus de a cosinus de a ».

## 15. Hors périmètre v1

Écartés délibérément : application Android Auto dédiée, speech-to-speech temps réel,
interruption de la parole de l'IA, mot d'éveil, cartes à image ou à son, édition de cartes
à la voix, statistiques élaborées.

Le mélange de plusieurs paquets dans une même session figurait ici ; il a été ajouté
depuis. Les paquets cochés se révisent entrelacés, une carte à la fois, parce qu'un trajet
coupé en deux blocs thématiques révise mal.

## 16. Risques ouverts

1. **Qualité du jugement sur des cartes de prépa.** Une définition juridique ou une
   démonstration mathématique se juge mal à l'oreille. Le mode journal existe précisément
   pour mesurer ce risque avant de laisser le modèle écrire.
2. **Reconnaissance vocale sur du vocabulaire technique.** Le STT d'Android est entraîné
   sur de la langue courante ; les termes de spécialité seront parfois massacrés. Le
   jugement doit rester tolérant à la transcription approximative — c'est une consigne
   explicite du prompt.
3. **Verrouillage de la collection.** Si AnkiDroid est ouvert au premier plan, l'accès
   concurrent peut échouer. Le refus est désormais compté, journalisé et annoncé en fin
   de session ; reste à mesurer sa fréquence à l'usage.
4. **Longueur des versos.** Les fiches de prépa sont verbeuses ; le plafonnement du retour
   oral est une contrainte de rythme, pas un détail cosmétique. Une réponse juste n'a droit
   qu'à une confirmation de quelques mots : réentendre ce qu'on vient de réciter n'apprend
   rien et coûte du trajet. Les explications sont réservées à ce qui a été raté.
5. **Le seuil de relance.** La liste des mots qui « appellent une suite » est une
   heuristique, pas une grammaire. Le doute penche vers la relance : redemander coûte une
   seconde, juger une demi-réponse coûte une note fausse. Reste à voir, à l'usage, si le
   compromis est bien placé.
