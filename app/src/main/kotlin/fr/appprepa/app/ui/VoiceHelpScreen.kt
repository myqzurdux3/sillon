package fr.appprepa.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.appprepa.core.voice.VoiceHelp

/**
 * Ce que l'application comprend, a lire a l'arret.
 *
 * La liste vient de [VoiceHelp], donc du meme endroit que ce que le parseur reconnait :
 * un ecran d'aide qui annonce un mot inexistant est pire que pas d'aide du tout.
 */
@Composable
fun VoiceHelpScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text("← Retour", color = SillonPalette.faint, fontSize = 15.sp)
        }

        Text("CE QUE TU PEUX DIRE", fontSize = 13.sp, color = SillonPalette.faint)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = SillonPalette.rule)
        Spacer(Modifier.height(20.dp))

        Text(
            VoiceHelp.REGLE,
            style = MaterialTheme.typography.bodySmall,
            color = SillonPalette.faint,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            VoiceHelp.REGLE_INTENTION,
            style = MaterialTheme.typography.bodySmall,
            color = SillonPalette.faint,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            VoiceHelp.REGLE_ANGLAIS,
            style = MaterialTheme.typography.bodySmall,
            color = SillonPalette.faint,
        )

        Spacer(Modifier.height(28.dp))

        VoiceHelp.ENTREES.forEach { entree ->
            Commande(
                action = entree.action,
                fenetre = entree.fenetre.libelle,
                principale = entree.phrases.first(),
                autres = entree.phrases.drop(1),
                anglaises = entree.phrasesAnglaises,
            )
            Spacer(Modifier.height(24.dp))
        }

        HorizontalDivider(thickness = 1.dp, color = SillonPalette.rule)
        Spacer(Modifier.height(16.dp))
        Text(
            VoiceHelp.NOTE_PAUSE,
            style = MaterialTheme.typography.bodySmall,
            color = SillonPalette.faint,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Commande(
    action: String,
    fenetre: String,
    principale: String,
    autres: List<String>,
    anglaises: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = action,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            // Une commande dite hors de sa fenetre ne fait rien : c'est ce qui surprend
            // le plus, donc ca s'affiche a cote de chaque ligne, pas en note de bas de
            // page — et dans une couleur qui se lit, pas dans celle des filets.
            Text(fenetre, fontSize = 12.sp, color = SillonPalette.faint)
        }

        Text("« $principale »", fontSize = 19.sp, color = SillonPalette.accent)

        if (autres.isNotEmpty()) {
            Text(
                text = autres.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = SillonPalette.faint,
            )
        }

        // Sur un paquet anglais, ce sont les seules qui marchent : le micro y ecoute en
        // anglais et ne transcrira jamais le mot francais.
        if (anglaises.isNotEmpty()) {
            Text(
                text = "EN  " + anglaises.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = SillonPalette.faint,
            )
        }
    }
}
