package fr.appprepa.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.appprepa.core.model.JournalRecord

/**
 * L'ecran qui permet de decider si le jugement du modele est assez fiable pour activer
 * l'ecriture reelle dans Anki.
 */
@Composable
fun JournalScreen(entries: List<JournalRecord>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) { Text("Retour") }

        if (entries.isEmpty()) {
            Text(
                "Aucune session enregistrée aujourd'hui.",
                modifier = Modifier.padding(vertical = 24.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries.reversed()) { entry ->
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Text(entry.question, style = MaterialTheme.typography.titleSmall)
                    if (entry.transcript.isNotBlank()) {
                        Text("Tu as dit : ${entry.transcript}")
                    }
                    Text(
                        buildString {
                            append("Proposé : ${entry.proposedEase?.name ?: "—"}")
                            append(" · Écrit : ${entry.committedEase?.name ?: "non"}")
                            entry.note?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
