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
    )

    val ENTREES: List<Entree> = listOf(
        Entree(
            "Noter « à revoir »",
            listOf("à revoir", "encore", "raté", "faux", "mauvais"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.AGAIN),
        ),
        Entree(
            "Noter « difficile »",
            listOf("difficile", "dur", "trop dur", "moyen"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.HARD),
        ),
        Entree(
            "Noter « bien »",
            listOf("bien", "correct", "ok", "oui"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.GOOD),
        ),
        Entree(
            "Noter « facile »",
            listOf("facile", "évident", "très facile"),
            Fenetre.CORRECTION,
            VoiceCommand.Correct(fr.appprepa.core.model.Ease.EASY),
        ),
        Entree(
            "Réentendre la question, ou le verdict",
            listOf("répète", "pardon", "redis"),
            Fenetre.LES_DEUX,
            VoiceCommand.Repeat,
        ),
        Entree(
            "Passer la carte sans la noter",
            listOf("passe", "suivante", "saute"),
            Fenetre.REPONSE,
            VoiceCommand.Skip,
        ),
        Entree(
            "Se faire donner la réponse",
            listOf("explique", "je sèche", "je ne sais pas", "aucune idée"),
            Fenetre.REPONSE,
            VoiceCommand.Explain,
        ),
        Entree(
            "Revenir sur la carte précédente",
            listOf("reviens", "la précédente", "carte d'avant", "retour"),
            Fenetre.LES_DEUX,
            VoiceCommand.Revisit,
        ),
        Entree(
            "Se faire réexpliquer la précédente",
            listOf("explique la précédente", "c'était quoi déjà", "explique avant"),
            Fenetre.LES_DEUX,
            VoiceCommand.RevisitExplain,
        ),
        Entree(
            "Jeter la note de la carte précédente",
            listOf("annule", "annuler", "oublie"),
            Fenetre.LES_DEUX,
            VoiceCommand.Undo,
        ),
        Entree(
            "Terminer la session",
            listOf("stop", "terminé", "arrête", "pause"),
            Fenetre.LES_DEUX,
            VoiceCommand.Stop,
        ),
    )

    /**
     * La regle qui explique la plupart des surprises : une commande doit constituer
     * toute la phrase. Les formules de politesse sont tolerees, le reste non.
     */
    const val REGLE = "Une commande n'est reconnue que si elle est toute ta phrase. " +
        "« je ne suis pas sûr, passe » compte comme une réponse. Sinon la moitié des " +
        "réponses déclencheraient une commande."

    /** Ce qui n'est pas une commande mais se dit quand meme. */
    const val NOTE_PAUSE = "« pause » termine la session comme « stop » : il n'y a pas de reprise."
}
