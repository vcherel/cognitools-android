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
    // État pour savoir quel écran afficher
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
        Button(
            onClick = { onNavigate("randomGenerator") },
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Générateur aléatoire")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onNavigate("volumeBooster") },
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Volume booster")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onNavigate("flashcards") },
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Flashcards")
        }
    }
}

// Écrans vides pour l'instant
@Composable
fun RandomGeneratorScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) { Text("Retour") }
        Text("Ici viendra le générateur aléatoire")
    }
}

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) { Text("Retour") }
        Text("Ici viendra le volume booster")
    }
}

@Composable
fun FlashcardsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) { Text("Retour") }
        Text("Ici viendront les flashcards")
    }
}
