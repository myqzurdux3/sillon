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
import fr.appprepa.app.settings.AccentAnglais
import fr.appprepa.core.deck.DeckInfo
import fr.appprepa.core.deck.DeckSelection

/**
 * Quels paquets sont en anglais, et comment les traiter.
 *
 * L'ecran existe parce que le nom d'un paquet ne suffit pas toujours : un paquet nomme
 * « Anglais » peut contenir du vocabulaire a reciter *en francais*, et un paquet nomme
 * « Shakespeare » n'annonce pas sa langue. Tant qu'il n'a pas ete valide, le nom decide.
 */
@Composable
fun LanguesScreen(
    decks: List<DeckInfo>,
    anglais: Set<Long>,
    /** Vrai tant que la selection vient du nom des paquets et non d'un choix. */
    devine: Boolean,
    collapsed: Set<Long>,
    accent: AccentAnglais,
    correctionEnFrancais: Boolean,
    onToggle: (Long) -> Unit,
    onFold: (Long) -> Unit,
    onAccent: (AccentAnglais) -> Unit,
    onCorrection: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val ordered = DeckSelection.visible(DeckSelection.ordered(decks), collapsed)

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

        Text("LANGUES", fontSize = 13.sp, color = SillonPalette.faint)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = SillonPalette.rule)
        Spacer(Modifier.height(16.dp))

        Text(
            text = if (devine) {
                "Deviné d'après le nom des paquets. Coche ou décoche pour décider toi-même."
            } else {
                "${anglais.size} paquets écoutés en anglais."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SillonPalette.faint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Un paquet coché est écouté, énoncé et commandé en anglais. " +
                "Se tromper ne se voit pas : une réponse anglaise entendue en français " +
                "est transcrite en charabia, et notée fausse.",
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
                LangueRow(
                    deck = deck,
                    anglais = deck.id in anglais,
                    hasChildren = DeckSelection.hasChildren(decks, deck),
                    collapsed = deck.id in collapsed,
                    onToggle = { onToggle(deck.id) },
                    onFold = { onFold(deck.id) },
                )
            }

            item {
                Spacer(Modifier.height(28.dp))
                HorizontalDivider(thickness = 1.dp, color = SillonPalette.rule)
                Spacer(Modifier.height(24.dp))

                Text(
                    "Accent",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Règle la voix et le micro. Le moteur de reconnaissance change de " +
                        "modèle avec le pays : celui qui te comprend le mieux est le bon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SillonPalette.faint,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    AccentAnglais.entries.forEach { choix ->
                        Text(
                            text = choix.libelle,
                            fontSize = 17.sp,
                            color = if (choix == accent) {
                                SillonPalette.accent
                            } else {
                                SillonPalette.faint
                            },
                            modifier = Modifier.clickable { onAccent(choix) },
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    "Langue de la correction",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "La question reste toujours dans la langue de la carte. Seul le " +
                        "retour après ta réponse suit ce réglage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SillonPalette.faint,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    listOf(true to "en français", false to "dans la langue de la carte")
                        .forEach { (valeur, libelle) ->
                            Text(
                                text = libelle,
                                fontSize = 17.sp,
                                color = if (valeur == correctionEnFrancais) {
                                    SillonPalette.accent
                                } else {
                                    SillonPalette.faint
                                },
                                modifier = Modifier.clickable { onCorrection(valeur) },
                            )
                        }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun LangueRow(
    deck: DeckInfo,
    anglais: Boolean,
    hasChildren: Boolean,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onFold: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Spacer(Modifier.width((deck.depth * 20).dp))

            if (hasChildren) {
                Text(
                    text = if (collapsed) "▸" else "▾",
                    color = SillonPalette.faint,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable(onClick = onFold)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            } else {
                Spacer(Modifier.width(24.dp))
            }

            Spacer(Modifier.width(4.dp))
            Text(
                text = deck.shortName,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = onToggle),
            )
        }

        // Le drapeau dit la langue mieux qu'une case a cocher : « coché » ne dit pas
        // laquelle des deux langues on obtient.
        Text(
            text = if (anglais) "EN" else "FR",
            fontSize = 15.sp,
            color = if (anglais) SillonPalette.accent else SillonPalette.faint,
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
