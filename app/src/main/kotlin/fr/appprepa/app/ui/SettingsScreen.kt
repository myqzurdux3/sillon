package fr.appprepa.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.appprepa.app.settings.SettingsStore
import fr.appprepa.core.model.WriteMode

/** Tout ce qui ne doit pas encombrer l'ecran de conduite vit ici. */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    ankiMessage: String,
    onOpenJournal: () -> Unit,
    onBack: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var writeThrough by remember { mutableStateOf(settings.writeMode == WriteMode.WRITE_THROUGH) }
    var debugMode by remember { mutableStateOf(settings.debugTranscripts) }
    var revealKey by remember { mutableStateOf(false) }
    var fastJudge by remember { mutableStateOf(settings.fastJudge) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
            Text("← Retour", color = SillonPalette.faint, fontSize = 15.sp)
        }

        Text("RÉGLAGES", fontSize = 13.sp, color = SillonPalette.faint)
        HorizontalDivider(thickness = 1.dp, color = SillonPalette.rule)

        Text(
            ankiMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                settings.apiKey = it
            },
            label = { Text("Clé d'API Anthropic") },
            singleLine = true,
            // Un secret n'a pas a rester lisible a l'ecran une fois saisi.
            visualTransformation = if (revealKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { revealKey = !revealKey }) {
                    Text(
                        if (revealKey) "masquer" else "voir",
                        fontSize = 13.sp,
                        color = SillonPalette.faint,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Toggle(
            title = "Écrire les notes dans Anki",
            detail = "Désactivé, l'application journalise ce qu'elle aurait noté sans " +
                "toucher à ta collection. Laisse-le désactivé le temps de relire le journal.",
            checked = writeThrough,
            onChange = {
                writeThrough = it
                settings.writeMode = if (it) WriteMode.WRITE_THROUGH else WriteMode.JOURNAL_ONLY
            },
        )

        Toggle(
            title = "Correction rapide",
            detail = "Juge avec un modèle plus rapide mais moins fin. Mesuré sur tes cartes : " +
                "2,3 s au lieu de 4,3 s. C'est le seul temps d'attente que tu ressens vraiment.",
            checked = fastJudge,
            onChange = {
                fastJudge = it
                settings.fastJudge = it
            },
        )

        Toggle(
            title = "Répondre au clavier",
            detail = "Remplace le micro par un champ de texte, pour mettre au point sans conduire.",
            checked = debugMode,
            onChange = {
                debugMode = it
                settings.debugTranscripts = it
            },
        )

        HorizontalDivider(thickness = 1.dp, color = SillonPalette.rule)
        TextButton(onClick = onOpenJournal) {
            Text("Voir le journal", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Toggle(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = SillonPalette.faint)
        Spacer(Modifier.height(2.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = SillonPalette.accent,
                uncheckedThumbColor = SillonPalette.faint,
                uncheckedTrackColor = MaterialTheme.colorScheme.background,
                uncheckedBorderColor = SillonPalette.rule,
            ),
            modifier = Modifier.align(Alignment.Start),
        )
    }
}
