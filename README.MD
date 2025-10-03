Je veux créer une application android (Android version 8.0) avec ces fonctionnalités :
1. Menu principal
    * Affiche les différentes fonctionnalités sous forme de boutons ou liste :
        * Générateur aléatoire
        * Volume booster
        * Flashcards
    * Chaque bouton ouvre l’écran correspondant dans la même MainActivity via Compose (Composable).
2. Écran Générateur aléatoire
    * Deux sections :
        * Nombre entier aléatoire :
            * Par défaut, génère un entier entre 1 et 100.
            * Permet à l’utilisateur de modifier les bornes minimale et maximale avant de générer.
            * Bouton “Générer” qui affiche le résultat.
        * Mot aléatoire :
            * Génère un mot aléatoire en français à partir d’une liste intégrée dans l’application (ex : ["chat", "maison", "arbre", ...]).
            * Bouton “Générer” qui affiche le mot.
3. Navigation et design
    * Utiliser uniquement Compose pour les écrans, pas de XML.
    * Design simple et léger pour vieux téléphones.
    * Gestion simple des états pour que les résultats restent visibles après rotation de l’écran.

Contraintes :
* Application légère, pas de dépendances inutiles.
* Code clair et commenté pour que je puisse ajouter les autres fonctionnalités plus tard.

Tout d'abord, je veux faire le menu principal.
J'ai ce code:
package com.example.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
enableEdgeToEdge()
setContent {
MyAppTheme {
Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
Greeting(
name = "Android",
modifier = Modifier.padding(innerPadding)
)
}
}
}
}
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
Text(
text = "Hello $name!",
modifier = modifier
)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
MyAppTheme {
Greeting("Android")
}
}