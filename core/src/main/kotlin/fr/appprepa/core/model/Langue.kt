package fr.appprepa.core.model

/**
 * La langue d'une carte, et par consequent celle du micro pendant qu'on y repond.
 *
 * C'est le point le plus important de tout ce fichier : une reponse anglaise ecoutee par
 * un moteur regle sur le francais ne provoque aucune erreur. Elle est transcrite en
 * charabia francais, jugee fausse, et la carte defile. La panne est silencieuse et se
 * confond avec de l'ignorance — c'est la pire des pannes pour une application qui note.
 *
 * L'accent, lui, ne vit pas ici : « anglais britannique » et « anglais americain » sont
 * la meme langue pour le vocabulaire des commandes et pour les prompts. Seuls
 * l'adaptateur vocal et le choix de la voix les distinguent.
 */
enum class Langue {
    FRANCAIS,
    ANGLAIS,
}
