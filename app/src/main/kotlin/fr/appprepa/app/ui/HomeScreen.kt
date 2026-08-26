package fr.appprepa.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.appprepa.app.session.DebugListener
import fr.appprepa.app.session.SessionHolder
import fr.appprepa.app.session.SessionOutcome
import fr.appprepa.app.settings.SettingsStore
import fr.appprepa.core.model.WriteMode
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    settings: SettingsStore,
    ankiMessage: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val holder = SessionHolder.shared
    val state by holder.state.collectAsState()
    val running by holder.isRunning.collectAsState()
    val outcome by holder.outcome.collectAsState()
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var writeThrough by remember { mutableStateOf(settings.writeMode == WriteMode.WRITE_THROUGH) }
    var debugMode by remember { mutableStateOf(settings.debugTranscripts) }
    var typed by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Sans cela, l'etat passe sous la barre de statut.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Pendant la conduite, seule cette ligne compte : elle doit se lire d'un coup d'oeil.
        Text(
            text = StateLabels.of(state),
            style = MaterialTheme.typography.headlineLarge,
            color = if (running) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )

        when (val done = outcome) {
            is SessionOutcome.Failed -> Text("Échec : ${done.reason}")
            is SessionOutcome.Completed -> Text(
                // En mode journal rien n'atteint Anki : le dire, sinon le compteur ment.
                "${done.stats.answered} cartes, ${done.stats.correct} justes, " +
                    if (writeThrough) {
                        "${done.stats.committed} écrites dans Anki."
                    } else {
                        "${done.stats.committed} journalisées, aucune écrite dans Anki."
                    },
            )
            null -> Unit
        }

        Button(
            onClick = if (running) onStop else onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        ) {
            Text(if (running) "Arrêter" else "Démarrer la session", fontSize = 22.sp)
        }

        if (debugMode && running) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Réponse au clavier") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val text = typed
                    typed = ""
                    scope.launch { DebugListener.shared.submit(text) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Envoyer")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(ankiMessage)

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        settings.apiKey = it
                    },
                    label = { Text("Clé d'API Anthropic") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    Text("Écrire les notes dans Anki")
                    Text(
                        "Désactivé, l'application se contente de journaliser ce qu'elle " +
                            "aurait noté. Laisse-le désactivé le temps de vérifier le journal.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = writeThrough,
                        onCheckedChange = {
                            writeThrough = it
                            settings.writeMode =
                                if (it) WriteMode.WRITE_THROUGH else WriteMode.JOURNAL_ONLY
                        },
                    )
                }

                Column {
                    Text("Répondre au clavier (mise au point)")
                    Switch(
                        checked = debugMode,
                        onCheckedChange = {
                            debugMode = it
                            settings.debugTranscripts = it
                        },
                    )
                }

                Button(onClick = onOpenJournal, modifier = Modifier.fillMaxWidth()) {
                    Text("Voir le journal")
                }
            }
        }
    }
}
