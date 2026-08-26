package fr.appprepa.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import fr.appprepa.core.model.JournalRecord
import java.io.File

private enum class Screen { HOME, SETTINGS, JOURNAL }

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
                var debugTranscripts by remember { mutableStateOf(settings.debugTranscripts) }

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
                    if (screen == Screen.HOME) {
                        debugTranscripts = settings.debugTranscripts
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
                        ankiMessage = ankiMessage.ifBlank {
                            AnkiAvailability.message(AnkiAvailability.check(this@MainActivity))
                        },
                        onOpenJournal = { screen = Screen.JOURNAL },
                        onBack = { screen = Screen.HOME },
                    )

                    Screen.JOURNAL -> JournalScreen(entries) { screen = Screen.SETTINGS }
                }
            }
        }
    }
}
