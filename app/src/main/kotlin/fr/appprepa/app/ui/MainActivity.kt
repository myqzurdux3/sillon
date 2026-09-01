package fr.appprepa.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import fr.appprepa.core.deck.DeckInfo
import fr.appprepa.core.deck.DeckLanguage
import fr.appprepa.core.deck.DeckSelection
import fr.appprepa.core.model.JournalRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { HOME, SETTINGS, DECKS, LANGUES, JOURNAL, VOICE_HELP }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsStore(this)
        val journal = JsonlJournal(File(filesDir, "journal.jsonl"))

        setContent {
            SillonTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                var entries by remember { mutableStateOf(emptyList<JournalRecord>()) }
                var ankiMessage by remember { mutableStateOf("") }
                var decks by remember { mutableStateOf(emptyList<DeckInfo>()) }
                var selectedDecks by remember { mutableStateOf(settings.deckIds) }
                var collapsedDecks by remember { mutableStateOf(settings.collapsedDeckIds) }
                var debugTranscripts by remember { mutableStateOf(settings.debugTranscripts) }
                var englishDecks by remember { mutableStateOf(settings.englishDeckIds) }
                var accent by remember { mutableStateOf(settings.accentAnglais) }
                var correctionFr by remember { mutableStateOf(settings.correctionEnFrancais) }

                // Les permissions sont demandees a l'arret, jamais en roulant.
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

                LaunchedEffect(screen) {
                    if (screen == Screen.JOURNAL) {
                        entries = journal.today(System.currentTimeMillis())
                    }
                    // Les compteurs changent a chaque revision : on les relit a l'ouverture.
                    // Interroger AnkiDroid traverse deux processus : cela se fait ici, une
                    // fois par ouverture d'ecran, et jamais dans le corps d'un composable.
                    if (screen == Screen.DECKS ||
                        screen == Screen.LANGUES ||
                        screen == Screen.SETTINGS
                    ) {
                        decks = withContext(Dispatchers.IO) {
                            runCatching { AnkiDroidGateway(contentResolver).decks() }
                                .getOrDefault(emptyList())
                        }
                        if (ankiMessage.isBlank()) {
                            ankiMessage = withContext(Dispatchers.IO) {
                                AnkiAvailability.message(AnkiAvailability.check(this@MainActivity))
                            }
                        }
                    }
                    if (screen == Screen.HOME) {
                        debugTranscripts = settings.debugTranscripts
                    }
                }

                // Le retour systeme remonte d'un ecran ; il ne quitte l'application que
                // depuis l'accueil.
                BackHandler(enabled = screen != Screen.HOME) {
                    screen = when (screen) {
                        Screen.DECKS, Screen.LANGUES, Screen.JOURNAL, Screen.VOICE_HELP ->
                            Screen.SETTINGS
                        else -> Screen.HOME
                    }
                }

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        debugTranscripts = debugTranscripts,
                        onStart = { SessionService.start(this@MainActivity) },
                        onStop = { SessionService.stop(this@MainActivity) },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        ankiMessage = ankiMessage,
                        deckSummary = deckSummary(decks, selectedDecks),
                        onOpenDecks = { screen = Screen.DECKS },
                        onOpenLangues = { screen = Screen.LANGUES },
                        onOpenJournal = { screen = Screen.JOURNAL },
                        onOpenVoiceHelp = { screen = Screen.VOICE_HELP },
                        onBack = { screen = Screen.HOME },
                    )

                    Screen.DECKS -> DeckScreen(
                        decks = decks,
                        selected = selectedDecks,
                        collapsed = collapsedDecks,
                        onFold = { id ->
                            collapsedDecks = if (id in collapsedDecks) {
                                collapsedDecks - id
                            } else {
                                collapsedDecks + id
                            }
                            settings.collapsedDeckIds = collapsedDecks
                        },
                        onToggle = { id ->
                            selectedDecks = DeckSelection.toggle(decks, selectedDecks, id)
                            settings.deckIds = selectedDecks
                        },
                        onClear = {
                            selectedDecks = emptySet()
                            settings.deckIds = emptySet()
                        },
                        onBack = { screen = Screen.SETTINGS },
                    )

                    Screen.LANGUES -> LanguesScreen(
                        decks = decks,
                        anglais = DeckLanguage.anglophones(decks, englishDecks),
                        devine = englishDecks == null,
                        collapsed = collapsedDecks,
                        accent = accent,
                        correctionEnFrancais = correctionFr,
                        onFold = { id ->
                            collapsedDecks = if (id in collapsedDecks) {
                                collapsedDecks - id
                            } else {
                                collapsedDecks + id
                            }
                            settings.collapsedDeckIds = collapsedDecks
                        },
                        onToggle = { id ->
                            // Le premier clic fige ce qui etait devine : sans cela, cocher
                            // un paquet effacerait tous les autres d'un coup.
                            val depart = DeckLanguage.anglophones(decks, englishDecks)
                            englishDecks = DeckSelection.toggle(decks, depart, id)
                            settings.englishDeckIds = englishDecks
                        },
                        onAccent = {
                            accent = it
                            settings.accentAnglais = it
                        },
                        onCorrection = {
                            correctionFr = it
                            settings.correctionEnFrancais = it
                        },
                        onBack = { screen = Screen.SETTINGS },
                    )

                    Screen.JOURNAL -> JournalScreen(entries) { screen = Screen.SETTINGS }

                    Screen.VOICE_HELP -> VoiceHelpScreen { screen = Screen.SETTINGS }
                }
            }
        }
    }
}

/** Ligne de resume affichee dans les reglages, sous « Paquets a reviser ». */
private fun deckSummary(decks: List<DeckInfo>, selected: Set<Long>): String {
    if (decks.isEmpty()) return "AnkiDroid n'a renvoyé aucun paquet."
    if (selected.isEmpty()) {
        val total = decks.sumOf { it.dueCount }
        return "Tous les paquets · $total cartes dues"
    }
    val chosen = decks.filter { it.id in selected }
    val total = chosen.sumOf { it.dueCount }
    val names = chosen.take(3).joinToString(", ") { it.shortName }
    val reste = if (chosen.size > 3) " et ${chosen.size - 3} autres" else ""
    return "$names$reste · $total cartes dues"
}
