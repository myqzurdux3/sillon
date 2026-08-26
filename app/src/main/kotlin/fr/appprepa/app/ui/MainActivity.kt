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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsStore(this)
        val journal = JsonlJournal(File(filesDir, "journal.jsonl"))

        setContent {
            KholleTheme {
                var showJournal by remember { mutableStateOf(false) }
                var entries by remember { mutableStateOf(emptyList<JournalRecord>()) }
                var ankiMessage by remember { mutableStateOf("") }

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

                LaunchedEffect(showJournal) {
                    if (showJournal) entries = journal.today(System.currentTimeMillis())
                }

                if (showJournal) {
                    JournalScreen(entries) { showJournal = false }
                } else {
                    HomeScreen(
                        settings = settings,
                        ankiMessage = ankiMessage,
                        onStart = { SessionService.start(this@MainActivity) },
                        onStop = { SessionService.stop(this@MainActivity) },
                        onOpenJournal = { showJournal = true },
                    )
                }
            }
        }
    }
}
