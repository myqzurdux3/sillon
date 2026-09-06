package fr.appprepa.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.appprepa.app.session.DebugListener
import fr.appprepa.app.session.SessionHolder
import fr.appprepa.app.session.SessionOutcome
import fr.appprepa.core.engine.SessionState
import kotlinx.coroutines.launch

/**
 * Presque rien a l'ecran. Le mot d'etat occupe le centre, un filet le souligne, le rang de
 * la carte suit. Tout le reste est derriere l'engrenage : en conduisant, on ne regarde
 * qu'une chose.
 */
@Composable
fun HomeScreen(
    debugTranscripts: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val holder = SessionHolder.shared
    val state by holder.state.collectAsState()
    val running by holder.isRunning.collectAsState()
    val outcome by holder.outcome.collectAsState()
    val progress by holder.progress.collectAsState()
    val scope = rememberCoroutineScope()

    var typed by remember { mutableStateOf("") }
    val listening = state is SessionState.Listening ||
        state is SessionState.AwaitingCorrection

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = StateLabels.of(state).uppercase(),
                style = MaterialTheme.typography.displayLarge,
                color = if (listening) {
                    SillonPalette.accent
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(
                modifier = Modifier.width(120.dp),
                thickness = 1.dp,
                color = SillonPalette.rule,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = secondLine(progress, outcome, running),
                style = MaterialTheme.typography.bodySmall,
                color = SillonPalette.faint,
            )

            if (debugTranscripts && running) {
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Réponse au clavier") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        val text = typed
                        typed = ""
                        scope.launch { DebugListener.shared.submit(text) }
                    },
                ) {
                    Text("Envoyer")
                }
            }
        }

        // La cible de touche occupe la moitie basse de l'ecran, pas seulement le mot.
        // C'est un bouton qu'on cherche au volant, d'un pouce, sans regarder : viser un
        // libelle de dix-huit points dans un coin demande une precision qu'on n'a pas.
        TextButton(
            onClick = if (running) onStop else onStart,
            shape = RectangleShape,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.5f)
                .height(96.dp),
            contentPadding = PaddingValues(start = 20.dp),
        ) {
            Text(
                text = if (running) "Arrêter" else "Démarrer",
                fontSize = 24.sp,
                color = if (running) SillonPalette.accent else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp),
        ) {
            Text("Réglages", fontSize = 15.sp, color = SillonPalette.faint)
        }
    }
}

/** La ligne sous le filet : le rang de la carte, ou le bilan une fois la session finie. */
private fun secondLine(
    progress: Pair<Int, Int>,
    outcome: SessionOutcome?,
    running: Boolean,
): String {
    if (running && progress.second > 0) {
        return "carte ${progress.first} sur ${progress.second}"
    }
    return when (val done = outcome) {
        is SessionOutcome.Failed -> done.reason
        is SessionOutcome.Completed -> buildString {
            append("${done.stats.answered} cartes, ${done.stats.correct} justes")
            // Une ecriture refusee par AnkiDroid ne doit pas se decouvrir le soir.
            if (done.stats.writeFailures > 0) {
                append(" · ${done.stats.writeFailures} non écrites dans Anki")
            }
        }
        null -> "prêt à démarrer"
    }
}
