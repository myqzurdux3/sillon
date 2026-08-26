package fr.appprepa.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.appprepa.core.deck.DeckInfo
import fr.appprepa.core.deck.DeckSelection

/**
 * Choix des paquets. Le nombre de cartes dues est affiche a cote de chacun : cocher
 * sans ce chiffre reviendrait a choisir a l'aveugle.
 */
@Composable
fun DeckScreen(
    decks: List<DeckInfo>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val ordered = DeckSelection.ordered(decks)
    val total = DeckSelection.dueTotal(decks, selected)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 32.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text("← Retour", color = SillonPalette.faint, fontSize = 15.sp)
        }

        Text("PAQUETS", fontSize = 13.sp, color = SillonPalette.faint)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = SillonPalette.rule)
        Spacer(Modifier.height(16.dp))

        Text(
            text = if (selected.isEmpty()) {
                "Rien de coché : tous les paquets."
            } else {
                "$total cartes dues dans la sélection."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SillonPalette.faint,
        )

        Spacer(Modifier.height(16.dp))

        if (ordered.isEmpty()) {
            Text("Aucun paquet trouvé dans AnkiDroid.", color = SillonPalette.faint)
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ordered, key = { it.id }) { deck ->
                DeckRow(deck, deck.id in selected) { onToggle(deck.id) }
            }
            item {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onClear) {
                    Text("Tout décocher", color = SillonPalette.faint, fontSize = 15.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DeckRow(deck: DeckInfo, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // L'indentation dit la hierarchie sans dessiner d'arbre.
            Spacer(Modifier.width((deck.depth * 20).dp))
            Text(
                text = if (checked) "◼" else "◻",
                color = if (checked) SillonPalette.accent else SillonPalette.faint,
                fontSize = 17.sp,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = deck.shortName,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = if (deck.dueCount > 0) "${deck.dueCount}" else "—",
            fontSize = 15.sp,
            color = if (deck.dueCount > 0) SillonPalette.faint else SillonPalette.rule,
        )
    }
}
