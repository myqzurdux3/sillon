# Installer Sillon sur ton téléphone

Sillon t'interroge à l'oral sur tes cartes Anki dues, pendant que tu conduis. Tu réponds
à voix haute, l'application juge, annonce la note qu'elle propose, et tu peux la corriger
d'un mot avant qu'elle ne soit écrite dans Anki.

## Ce qu'il te faut

- Un téléphone Android 12 ou plus récent.
- **AnkiDroid installé sur le même téléphone**, avec ta collection dedans. Sillon lit et
  écrit directement dans cette collection : ni PC, ni serveur, ni AnkiWeb.
- Une clé d'API Anthropic, à créer sur `console.anthropic.com`.

## 1. Construire l'application

Depuis le dossier du projet, sur un poste avec le SDK Android :

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleRelease
```

L'APK sort dans `app/build/outputs/apk/release/`. Il n'est pas signé : pour l'installer
sur ton téléphone, construis plutôt la variante de développement, déjà signée :

```bash
./gradlew :app:assembleDebug
```

## 2. Installer

Branche le téléphone en USB, active le débogage USB dans les options développeur, puis :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. Accorder les trois permissions

Au premier lancement, Sillon les demande toutes les trois d'un coup :

| Permission | Pourquoi |
|---|---|
| Micro | entendre tes réponses |
| Notifications | garder la session vivante écran éteint |
| Accès à la collection AnkiDroid | lire les cartes dues et écrire les notes |

Si la troisième n'apparaît pas, c'est qu'AnkiDroid n'est pas installé. L'écran d'accueil
de Sillon te le dit explicitement.

## 4. Coller ta clé d'API

Dans l'écran d'accueil. Elle est stockée chiffrée sur le téléphone
(`EncryptedSharedPreferences`) et n'est jamais écrite dans un fichier du projet.

## 5. Les premiers trajets : laisse le mode journal actif

**C'est l'étape qui protège ta collection.** Par défaut, Sillon ne modifie rien dans Anki :
elle se contente d'enregistrer la note qu'elle *aurait* mise. Une mauvaise note décale le
calendrier d'une carte pour des semaines, et l'API AnkiDroid n'offre aucune annulation
après coup.

Fais deux ou trois trajets, puis le soir, ouvre « Voir le journal ». Pour chaque carte tu
vois la question posée, ce que tu as répondu, et la note proposée. Si le jugement te paraît
juste sur tes propres fiches, alors seulement active « Écrire les notes dans Anki ».

## 6. En voiture

Lance la session **à l'arrêt**, avant de démarrer. Ensuite tout est vocal : l'écran
n'affiche qu'un état en gros caractères et ne te demande jamais rien.

### Ce que tu peux dire

| Pour | Dis |
|---|---|
| Corriger la note en « à revoir » | « encore », « à revoir », « raté » |
| Corriger en « difficile » | « difficile », « dur » |
| Corriger en « bien » | « bien », « correct », « ok » |
| Corriger en « facile » | « facile » |
| Réentendre la question | « répète », « pardon » |
| Passer sans noter | « passe », « suivante » |
| Te faire expliquer | « explique », « je sèche », « je ne sais pas » |
| Annuler la note de la carte précédente | « annule » |
| Terminer | « stop », « pause », « terminé » |

Après chaque verdict, tu as environ trois secondes pour corriger. Si tu ne dis rien, la
note proposée est retenue et l'application enchaîne.

« annule » vise la carte **précédente** : sa note n'est écrite qu'au moment où tu réponds
à la suivante, ce qui te laisse une vraie fenêtre pour la rattraper. La carte annulée
reste due.

### Si le réseau tombe

La session continue en mode dégradé : Sillon lit le recto tel quel, écoute ta réponse, lit
le verso, et te demande de dicter toi-même ta note. Elle repasse en mode normal dès que le
réseau revient.

### Cartes à image ou à son

Elles sont écartées et journalisées, sans être notées. Inutilisables en conduisant, elles
restent dues pour une révision à l'écran.

## Le seul réglage qui touche à la vitesse

Le trajet a deux temps d'attente. Celui de la **question** est masqué : pendant que tu
réponds à une carte, la suivante est déjà en cours de reformulation. Celui de la
**correction** ne l'est pas — c'est le silence entre ta réponse et le verdict.

Mesuré sur tes propres cartes, sur l'appareil :

| Modèle de correction | Latence médiane |
|---|---|
| Le modèle principal (par défaut) | 4,3 s |
| « Correction rapide » | 2,3 s |

Le mode rapide juge moins finement. À toi de voir ce qui te gêne le plus : attendre, ou
être mal noté. Le mode journal permet de trancher sur pièces.

Le « mode rapide » de l'API, qui aurait accéléré le modèle principal sans changer sa
qualité, n'est pas disponible sur ce compte (quota à zéro) — vérifié, pas supposé.

## Mise au point sans voiture

Dans les réglages, « Répondre au clavier » remplace le micro par un champ de texte. Toute
la boucle s'exerce assis, ce qui permet d'ajuster les prompts sans prendre le volant.

## Ce qui reste à éprouver

Deux choses ne se vérifient qu'à l'usage réel, et le mode journal existe précisément pour
la première :

1. **La qualité du jugement sur tes cartes.** Une définition juridique ou une démonstration
   se juge mal à l'oreille.
2. **La reconnaissance vocale en conduisant** — bruit de roulement, micro du kit
   mains-libres, vocabulaire technique. Le prompt demande au modèle de ne jamais
   sanctionner une erreur de transcription, seulement une erreur de savoir.
