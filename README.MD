J'ai commencé à créer une application android (Android version 8.0), qui a pour but de regrouper divers fonctionnalités tout en restant le plus rapide et minimale possible.

Je veux maintenant créer une application de flashcard, où le but est d'apprendre des mots et les définitions associés.
Voilà les fonctionnalités souhaités :
- Possibilité créer liste
- Possibilité renom/suppr liste
- Possibilité ajout éléments
- Possibilité édition éléments
- Possibilité d'entraînement intelligent
- Export/Import données via fichiers internes sur le téléphone


J'ai ce code dans ma MainActivity :
@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
Button(
onClick = onClick,
modifier = Modifier
.fillMaxWidth()
.height(70.dp)
) {
Text(text)
}
}

@Composable
fun FlashcardsScreen(onBack: () -> Unit) {
BackHandler { onBack() }
ScreenTemplate(content = {})
}


@Composable
private fun ScreenTemplate(content: @Composable () -> Unit) {
Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
Spacer(modifier = Modifier.height(32.dp))
content()
}
}

Donne moi un code simple permettant de créer des listes, les supprimer, les rennomer, ou entrer dans la liste.

Mets des commentaires pour que je comprenne bien