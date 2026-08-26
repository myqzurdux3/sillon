package fr.appprepa.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.appprepa.core.model.JournalRecord

/**
 * L'ecran qui permet de decider si le jugement du modele est assez fiable pour activer
 * l'ecriture reelle dans Anki.
 */
@Composable
fun JournalScreen(entries: List<JournalRecord>, onBack: () -> Unit) {
    // La plus recente en haut. Calcule une fois, pas a chaque recomposition.
    val recentes = remember(entries) { entries.asReversed() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("← Retour", color = SillonPalette.faint, fontSize = 15.sp)
        }

        if (entries.isEmpty()) {
            Text(
                "Aucune session enregistrée aujourd'hui.",
                color = SillonPalette.faint,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(recentes, key = { "${it.atMs}-${it.noteId}-${it.cardOrd}" }) { entry ->
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
