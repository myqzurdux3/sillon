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
./gradlew :app:assembleDebug
```

L'APK sort dans `app/build/outputs/apk/debug/`. C'est la variante à installer : elle est
signée avec la clé de développement d'Android. `assembleRelease` produit un APK plus
petit mais **non signé**, donc non installable tel quel.

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

Si la troisième n'apparaît pas, c'est qu'AnkiDroid n'est pas installé. Ouvre **Réglages**
depuis l'accueil : la première ligne dit explicitement ce qui manque.

## 4. Coller ta clé d'API

Accueil → **Réglages** → « Clé d'API Anthropic ». Elle est stockée chiffrée sur le
téléphone (`EncryptedSharedPreferences`), masquée à l'écran une fois saisie, et n'est
jamais écrite dans un fichier du projet.

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

La liste complète est **dans l'application** : Réglages → « Ce que tu peux dire ». Cet
écran-là est produit à partir du code qui reconnaît les mots, donc il ne peut pas annoncer
une tournure qui ne marche pas. Le tableau ci-dessous en est un extrait, pour lire avant
d'installer.

L'application t'écoute dans **deux fenêtres** : quand elle attend ta réponse, et pendant
les sept secondes qui suivent son verdict. La dernière colonne dit où chaque commande agit.

| Pour | Dis | Quand |
|---|---|---|
| Corriger la note en « à revoir » | « encore », « à revoir », « raté », « faux », « mauvais » | après le verdict |
| Corriger en « difficile » | « difficile », « dur », « trop dur » | après le verdict |
| Corriger en « bien » | « bien », « correct », « ok » | après le verdict |
| Corriger en « facile » | « facile », « évident » | après le verdict |
| Réentendre la question | « répète », « répète la question », « j'ai pas entendu » | pendant la réponse |
| Réentendre le verdict | « répète » | après le verdict |
| Passer sans noter | « passe », « passe la carte », « suivante » | pendant la réponse |
| Te faire expliquer | « explique », « explique moi », « je sèche », « je bloque » | pendant la réponse |
| Revenir sur la carte précédente | « reviens », « la précédente », « carte d'avant » | les deux |
| Te faire réexpliquer la précédente | « explique la précédente », « c'était quoi déjà » | les deux |
| Annuler la note de la carte précédente | « annule » | les deux |
| Terminer la session | « stop », « terminé », « pause » | les deux |

Une commande doit **ouvrir** ta phrase et rester courte. Tu peux la dire comme elle vient
— « répète la question », « tu peux répéter s'il te plaît », « j'ai pas entendu » — sans
avoir à la réciter toute nue. Mais « je ne suis pas sûr, passe » compte comme une réponse,
pas comme un « passe » : sinon la moitié des réponses déclencheraient une commande.

Les notes font exception et restent exigeantes, d'un seul mot. « bien vu la formule » est
une réponse, pas un « bien » — les élargir ferait noter des cartes à ton insu.

« pause » termine la session comme « stop » : il n'y a pas de reprise.

Après chaque verdict, tu as **sept secondes** pour corriger — douze en mode dégradé, où tu
dois dicter la note toi-même. Si tu ne dis rien, la note proposée est retenue et
l'application enchaîne.

### Si tu cherches tes mots

Deux choses te laissent le temps de réfléchir au milieu d'une phrase.

La reconnaissance vocale attend **deux secondes et demie** de silence avant de considérer
que tu as fini. Au-delà, si ta phrase s'arrête sur un mot qui appelle une suite — « la
dérivée de ce produit vaut **donc** » —, l'application ne juge pas une demi-réponse : elle
dit « continue, je t'écoute », et recolle les deux morceaux avant de corriger.

Elle ne relance qu'une fois par carte. Si tu ne dis rien après la relance, elle juge ce que
tu avais déjà dit — jamais elle ne le jette.

Une lettre isolée ne compte pas comme un mot en suspens : sur des fiches de maths, « sinus
de a » est une phrase finie.

### Quand tu as bon

Elle ne réexplique pas. Une réponse juste vaut une confirmation de quelques mots — tu viens
de le dire, l'entendre une seconde fois ne t'apprend rien et allonge le trajet. Les
explications sont réservées à ce que tu as raté : c'est là qu'elles servent.

### Revenir en arrière

La note d'une carte n'est écrite qu'au moment où tu réponds à la suivante. Tant qu'elle
n'est pas partie, tu peux la reprendre — **à n'importe quel moment où l'application
t'écoute**, y compris au milieu de la question suivante.

Dis « reviens ». L'application ouvre une parenthèse : elle rappelle la carte d'avant et
attend, fenêtre longue. Tu peux alors dicter une autre note, ou dire « explique » pour te
la faire réexpliquer. Ensuite elle réénonce la question en cours et se remet à t'écouter —
tu ne perds pas la carte où tu en étais.

« explique la précédente » ouvre la même parenthèse mais va droit à l'explication.

Si tu ne dis rien dans la parenthèse, elle reprend sans rien changer. « annule » jette la
note de la carte précédente : elle n'est jamais écrite, la carte reste due.

Une seule carte en arrière pour l'instant. Le moteur garde ses notes dans une file dont le
plafond est un simple réglage : passer à deux ou trois ne demandera qu'une constante.

### Si le réseau tombe

La session continue en mode dégradé : Sillon lit le recto tel quel, écoute ta réponse, lit
le verso, et te demande de dicter toi-même ta note.

Le mode dégradé ne vaut que **pour la carte en cours**. Chaque nouvelle carte retente le
modèle, donc la session repasse d'elle-même en mode normal dès que le réseau revient. Sans
cela, une coupure de dix secondes sous un pont condamnerait tout le reste du trajet.

Une requête qui traîne est traitée comme une panne au bout de vingt secondes : au volant,
un appel qui pend est pire qu'un appel qui échoue, puisque l'échec, lui, a une suite.

### Cartes à image ou à son

Elles sont écartées et journalisées, sans être notées. Inutilisables en conduisant, elles
restent dues pour une révision à l'écran.

## Choisir ce que tu révises

Dans Réglages → « Paquets à réviser » → **Choisir**. La liste montre tes paquets avec le
nombre de cartes dues à côté de chacun ; les sous-paquets sont indentés sous leur parent.

Cocher un parent coche ses sous-paquets. L'application interroge ensuite **chaque paquet
coché explicitement** : ce que tu coches est exactement ce que tu révises, sans dépendre
de la façon dont AnkiDroid traite un identifiant de parent.

Plusieurs paquets cochés se révisent **entrelacés** — une carte de Maths, une d'Info, une
de Maths. Un trajet coupé en deux blocs thématiques réviserait mal.

Un chevron apparaît à gauche des paquets qui ont des sous-paquets : il replie la famille
quand la liste devient trop longue. Un paquet replié affiche alors le total de cartes dues
**de toute sa famille**, pour que replier ne fasse pas disparaître les chiffres qui servent
à choisir. Ce que tu replies est mémorisé, et replier ne change rien à ce qui est coché.

Rien de coché veut dire tous les paquets.

Juste en dessous, **Cartes par session** : 10, 20, 40, 60, ou tout. Des boutons plutôt
qu'un champ, pour ne pas avoir à sortir le clavier avant de démarrer. S'il y a moins de
cartes dues que la limite, tu auras ce qu'il y a.

## Ce qui prend du temps, et ce qui a été gagné

Le trajet a trois temps d'attente. Celui de la **question** est masqué : pendant que tu
réponds à une carte, la suivante est déjà en cours de reformulation. Les deux autres se
subissent en silence.

**Le silence qui clôt ton tour de parole.** La reconnaissance vocale ne sait pas que tu as
fini : elle attend une pause. Cette pause vaut maintenant 2 s après une réponse, et 1,1 s
après un mot de correction — parce que « bien » ou « faux » ne se cherchent pas. Elle
valait 2,5 s dans les deux cas. Sur une carte notée à la voix, c'est 1,9 s de gagnées.

**La correction elle-même.** Mesuré sur la même carte, trois appels par configuration :

| Configuration du jugement | Latence médiane |
|---|---|
| Modèle principal, réflexion activée (avant) | 4,1 s |
| Modèle principal, réflexion désactivée (par défaut) | 3,5 s |
| « Juger avec un petit modèle » | 2,0 s |

Juger une réponse orale contre un verso connu ne demande pas de raisonnement étendu :
désactiver la réflexion préalable ne change pas le verdict et rend une demi-seconde.

Le petit modèle juge moins finement les réponses à moitié justes. À toi de voir ce qui te
gêne le plus : attendre, ou être mal noté. Le mode journal permet de trancher sur pièces.

Le « mode rapide » de l'API, qui accélérerait le modèle principal sans rien changer à sa
qualité, n'est **pas ouvert sur ce compte** : le quota vaut zéro, vérifié en appelant
l'API, pas supposé. S'il est ouvert un jour, le code sait déjà s'en servir — il suffit de
passer `fast` à `true` dans `AnthropicTutor`, et il retombe tout seul en vitesse normale
si le quota se referme.

## Les paquets en anglais

L'application écoute dans la langue du paquet. Ce n'est pas un détail de confort : une
réponse anglaise entendue par un moteur réglé sur le français n'est pas refusée, elle est
**transcrite en charabia et notée fausse**. La panne se confond avec de l'ignorance.

Au premier lancement, le nom décide : un paquet qui s'appelle « Anglais » ou « English »
est écouté en anglais, ses sous-paquets avec lui. **Réglages → Langues des paquets**
montre ce qui a été deviné et laisse trancher, paquet par paquet, avec la marque `FR` ou
`EN` à droite de chaque ligne. Dès que tu y touches, c'est ton choix qui fait foi — y
compris « aucun paquet en anglais », qu'un nom ne saurait pas exprimer.

Sur un paquet anglais, **les commandes se disent en anglais** : « repeat », « skip »,
« explain », « again », « good ». Le micro écoute en anglais, il ne transcrira jamais
« répète ». La notice de l'application donne les deux vocabulaires, ligne par ligne.

Deux réglages accompagnent ça, sur le même écran :

**L'accent**, britannique ou américain. Il règle la voix *et* le micro — le moteur de
reconnaissance change de modèle acoustique avec le pays, et celui qui te comprend le mieux
n'est pas forcément celui que tu préfères entendre.

**La langue de la correction**, en français par défaut, ou celle de la carte. La question
reste toujours dans la langue de la carte : ce réglage ne touche que le retour après ta
réponse. Une exception qu'on ne peut pas contourner : quand l'application récite le verso
— tu n'as rien répondu, ou le modèle est tombé — elle le dit dans la langue de la carte,
sinon une voix française lirait un verso anglais.

## La voix

Deux choses la rendaient pénible.

**Le débit.** Les moteurs français sont calés sur la lecture d'un écran, pas sur quelqu'un
qui écoute en conduisant et connaît déjà le vocabulaire. Il est monté à 1,15, et réglable
dans **Réglages → Vitesse de la voix**, entre 0,8 et 1,6. Le changement s'applique à la
session suivante.

**La voix elle-même.** Le moteur retenait la première voix française installée, souvent la
plus plate. L'application classe maintenant les voix disponibles par qualité déclarée et
prend la meilleure — en écartant les voix qui exigent le réseau, parce qu'elles ajoutent
un aller-retour à chaque phrase et se taisent dans un tunnel.

Si la voix te déplaît toujours, c'est le moteur système qu'il faut changer :
**Paramètres Android → Système → Langues → Synthèse vocale**. Installe la voix française
la mieux notée de ton moteur ; l'application la prendra d'elle-même au prochain démarrage.

## Mise au point sans voiture

Dans les réglages, « Répondre au clavier » remplace le micro par un champ de texte. Toute
la boucle s'exerce assis, ce qui permet d'ajuster les prompts sans prendre le volant.

### Si AnkiDroid refuse une note

Cela arrive quand la collection est verrouillée — AnkiDroid ouvert au premier plan, par
exemple. Le refus est compté, écrit dans le journal avec sa raison, et annoncé dans le
bilan de fin de session (« 2 non écrites dans Anki »). La carte reste due. Rien ne se perd
en silence.

## Ce qui reste à éprouver

Deux choses ne se vérifient qu'à l'usage réel, et le mode journal existe précisément pour
la première :

1. **La qualité du jugement sur tes cartes.** Une définition juridique ou une démonstration
   se juge mal à l'oreille.
2. **La reconnaissance vocale en conduisant** — bruit de roulement, micro du kit
   mains-libres, vocabulaire technique. Le prompt demande au modèle de ne jamais
   sanctionner une erreur de transcription, seulement une erreur de savoir.
