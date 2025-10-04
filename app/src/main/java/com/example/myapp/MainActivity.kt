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
    ScreenTemplate(title = "Ici viendra le générateur aléatoire", onBack = onBack)
}

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    ScreenTemplate(title = "Ici viendra le volume booster", onBack = onBack)
}

@Composable
fun FlashcardsScreen(onBack: () -> Unit) {
    ScreenTemplate(title = "Ici viendront les flashcards", onBack = onBack)
}

@Composable
private fun ScreenTemplate(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) {
            Text("Retour")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(title)
    }
}