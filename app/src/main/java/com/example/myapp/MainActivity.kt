package com.example.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyAppTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var currentScreen by remember { mutableStateOf("menu") }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "menu" -> MenuScreen(onNavigate = { screen -> currentScreen = screen })
                "randomGenerator" -> RandomGeneratorScreen(onBack = { currentScreen = "menu" })
                "volumeBooster" -> VolumeBoosterScreen(onBack = { currentScreen = "menu" })
                "flashcards" -> FlashcardsScreen(onBack = { currentScreen = "menu" })
            }
        }
    }
}

@Composable
fun MenuScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MenuButton(text = "Générateur aléatoire") { onNavigate("randomGenerator") }
        Spacer(modifier = Modifier.height(16.dp))
        MenuButton(text = "Volume booster") { onNavigate("volumeBooster") }
        Spacer(modifier = Modifier.height(16.dp))
        MenuButton(text = "Flashcards") { onNavigate("flashcards") }
    }
}

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
fun RandomGeneratorScreen(onBack: () -> Unit) {
    ScreenTemplate(title = "Générateur aléatoire", onBack = onBack) {
        RandomIntSection()
        Spacer(modifier = Modifier.height(24.dp))
        RandomWordSection()
    }
}

@Composable
fun RandomIntSection() {
    var min by remember { mutableStateOf("1") }
    var max by remember { mutableStateOf("100") }
    var result by remember { mutableStateOf<Int?>(null) }

    Column {
        Text("Nombre entier aléatoire")
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            OutlinedTextField(value = min, onValueChange = { min = it }, label = { Text("Min") }, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(value = max, onValueChange = { max = it }, label = { Text("Max") }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            val minInt = min.toIntOrNull() ?: 1
            val maxInt = max.toIntOrNull() ?: 100
            if (minInt <= maxInt) result = (minInt..maxInt).random()
        }) {
            Text("Générer")
        }
        result?.let { Text("Résultat: $it") }
    }
}

@Composable
fun RandomWordSection() {
    val words = listOf("chat", "maison", "arbre", "soleil", "voiture")
    var result by remember { mutableStateOf<String?>(null) }

    Column {
        Text("Mot aléatoire")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { result = words.random() }) {
            Text("Générer")
        }
        result?.let { Text("Résultat: $it") }
    }
}

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    ScreenTemplate(title = "Ici viendra le volume booster", onBack = onBack, content = {})
}

@Composable
fun FlashcardsScreen(onBack: () -> Unit) {
    ScreenTemplate(title = "Ici viendront les flashcards", onBack = onBack, content = {})
}

@Composable
private fun ScreenTemplate(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) {
            Text("Retour")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(title)
        Spacer(modifier = Modifier.height(16.dp))
        content()
    }
}