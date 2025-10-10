package com.example.myapp

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapp.ui.MyButton

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Notification permission granted")
        } else {
            Log.d("MainActivity", "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        enableEdgeToEdge()
        setContent {
            MainScreen()
        }
        }
}

@Composable
fun MainScreen() {
    var currentScreen by remember { mutableStateOf("menu") }
    val flashcardsNavController = rememberNavController() // NavController for flashcards only

    Scaffold { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "menu" -> MenuScreen(onNavigate = { screen -> currentScreen = screen })
                "randomGenerator" -> RandomGeneratorScreen(onBack = { currentScreen = "menu" })
                "volumeBooster" -> VolumeBoosterScreen(onBack = { currentScreen = "menu" })
                "flashcards" -> {

                    NavHost(navController = flashcardsNavController, startDestination = "lists") {
                        composable("lists") {
                            FlashcardListsScreen(
                                onBack = { currentScreen = "menu" },
                                navController = flashcardsNavController
                            )
                        }
                        composable("elements/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardDetailScreen(
                                listId = listId,
                                navController = flashcardsNavController,
                                onBack = { flashcardsNavController.popBackStack() }
                            )
                        }
                        composable("game/{listId}") { backStackEntry ->
                            val listId = backStackEntry.arguments?.getString("listId") ?: ""
                            FlashcardGameScreen(listId = listId, onBack = { flashcardsNavController.popBackStack() })
                        }
                    }
                }
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
        Text(
            text = "Bienvenue!",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Choisis une option pour commencer :",
            style = MaterialTheme.typography.titleMedium,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        MyButton(text = "Générateur aléatoire") { onNavigate("randomGenerator") }
        Spacer(modifier = Modifier.height(40.dp))
        MyButton(text = "Volume booster") { onNavigate("volumeBooster") }
        Spacer(modifier = Modifier.height(40.dp))
        MyButton(text = "Flashcards") { onNavigate("flashcards") }
    }
}