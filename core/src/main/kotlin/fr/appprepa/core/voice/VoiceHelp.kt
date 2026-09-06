package fr.appprepa.core.voice

/**
 * La notice des commandes vocales, tenue a cote du parseur qui les reconnait.
 *
 * Elle vit dans `:core` et non dans l'interface pour une raison precise : c'est la meme
 * source qui alimente l'ecran de l'application et que les tests confrontent au parseur.
 * Une notice recopiee a la main derive — celle des documents l'avait deja fait, en
 * annoncant des mots que le parseur ne connaissait pas.
 */
object VoiceHelp {

    /** Les deux moments ou l'application ecoute. Le reste du temps, elle parle. */
    enum class Fenetre(val libelle: String) {
        REPONSE("pendant ta réponse"),
        CORRECTION("après le verdict"),
        LES_DEUX("les deux"),
    }

    data class Entree(
        /** Ce que ca fait, dit comme on le dirait a quelqu'un. */
        val action: String,
        /** Les tournures reconnues. La premiere est celle a retenir. */
        val phrases: List<String>,
        val fenetre: Fenetre,
        /** La commande attendue : c'est ce que le test confronte au parseur. */
        val commande: VoiceCommand,
        /**
         * Les memes, sur un paquet anglais. Le micro y ecoute en anglais : « répète » n'y
         * sera jamais transcrit, donc la notice doit dire les deux ou l'utilisateur
         * croira que les commandes ne marchent pas sur ces paquets.
         */
        val phrasesAnglaises: List<String> = emptyList(),
    )

    val ENTREES: List<Entree> = listOf(
        Entree(
            "Noter « à revoir »",
            listOf("à revoir", "encore", "raté", "faux", "mauvais"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.AGAIN),
            listOf("again", "wrong", "missed"),
        ),
        Entree(
            "Noter « difficile »",
            listOf("difficile", "dur", "trop dur", "chaud", "moyen"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.HARD),
            listOf("hard", "difficult", "tough"),
        ),
        Entree(
            "Noter « bien »",
            listOf("bien", "correct", "juste", "exact", "ok", "oui"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.GOOD),
            listOf("good", "right", "yes"),
        ),
        Entree(
            "Noter « facile »",
            listOf("facile", "évident", "trop facile", "par cœur"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.EASY),
            listOf("easy", "obvious", "by heart"),
        ),
        Entree(
            "Réentendre la question, ou le verdict",
            listOf("répète", "répète la question", "j'ai pas entendu", "redis"),
            Fenetre.LES_DEUX,
            VoiceCommand.Repeat,
            listOf("repeat", "say it again", "I didn't catch that"),
        ),
        Entree(
            "Passer la carte sans la noter",
            listOf("passe", "passe la carte", "suivante", "saute"),
            Fenetre.REPONSE,
            VoiceCommand.Skip,
            listOf("skip", "next", "skip this card"),
        ),
        Entree(
            "Se faire donner la réponse",
            listOf("explique", "explique moi", "je sèche", "je bloque", "je ne sais pas"),
            Fenetre.REPONSE,
            VoiceCommand.Explain,
            listOf("explain", "I don't know", "no idea"),
        ),
        Entree(
            "Revenir sur la carte précédente",
            listOf("reviens", "reviens en arrière", "la précédente", "retour"),
            Fenetre.LES_DEUX,
            VoiceCommand.Revisit,
            listOf("go back", "previous card"),
        ),
        Entree(
            "Se faire réexpliquer la précédente",
            listOf("explique la précédente", "c'était quoi déjà", "explique avant"),
            Fenetre.LES_DEUX,
            VoiceCommand.RevisitExplain,
            listOf("what was that", "explain previous"),
        ),
        Entree(
            "Jeter la note de la carte précédente",
            listOf("annule", "annule ça", "oublie"),
            Fenetre.LES_DEUX,
            VoiceCommand.Undo,
            listOf("undo", "cancel", "forget it"),
        ),
        Entree(
            "Terminer la session",
            listOf("stop", "arrête tout", "terminé", "pause"),
            Fenetre.LES_DEUX,
            VoiceCommand.Stop,
            listOf("stop", "quit", "pause"),
        ),
    )

    /**
     * La regle qui explique la plupart des surprises. Elle a ete assouplie : seule la
     * commande nue etait reconnue, et « répète la question » partait au modele comme une
     * reponse, se faisait juger faux et faisait defiler la carte. Elle reste une regle,
     * sinon la moitie des reponses de fiche declencheraient une commande.
     */
    const val REGLE = "Une commande doit ouvrir ta phrase et rester courte. Tu peux la dire " +
        "naturellement — « répète la question », « tu peux répéter » — mais " +
        "« je ne suis pas sûr, passe » reste une réponse. Les notes, elles, se disent d'un mot."

    /** La regle propre aux paquets anglais, qui surprend si on ne la connait pas. */
    const val REGLE_ANGLAIS = "Sur un paquet réglé en anglais, le micro écoute en anglais : " +
        "c'est « repeat », pas « répète ». Les deux vocabulaires ne se mélangent jamais, " +
        "sinon la moitié des réponses déclencheraient une commande dans l'autre langue."

    /**
     * Ce que le modele comprend au-dela des tournures listees.
     *
     * La liste des phrases ci-dessus n'est plus un catalogue ferme : c'est le modele qui
     * lit l'intention, et il accepte n'importe quelle formulation. Ce qui reste ferme,
     * c'est la liste des actions — et il le dit quand une demande n'en fait pas partie.
     */
    const val REGLE_INTENTION = "Tu peux parler normalement : « attends deux secondes », " +
        "« relis la question », « mets très dur à la précédente au lieu de facile » sont " +
        "compris tels quels. Si tu demandes quelque chose que l'application ne sait pas " +
        "faire, elle te répond qu'elle n'a pas compris — elle ne devine pas, parce " +
        "qu'une action devinée te coûterait une carte ou une note."

    /** Ce qui n'est pas une commande mais se dit quand meme. */
    const val NOTE_PAUSE = "« pause » termine la session comme « stop » : il n'y a pas de reprise."
}
