package com.example.myapp

import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapp.flashcards.FlashcardDetailScreen
import com.example.myapp.flashcards.FlashcardGameScreen
import com.example.myapp.flashcards.FlashcardListsScreen
import com.example.myapp.undercover.UndercoverScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ThemeManager {
    private var darkMode = false

    val isDarkMode: Flow<Boolean> = MutableStateFlow(darkMode)

    fun setDarkMode(enabled: Boolean) {
        darkMode = enabled
        (isDarkMode as MutableStateFlow).value = enabled
    }
}

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
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Configure splash screen animation
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            try {
                val icon = splashScreenView.iconView
                val scaleX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.5f)
                val scaleY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.5f)
                val alpha = ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f)

                scaleX.interpolator = AnticipateInterpolator()
                scaleY.interpolator = AnticipateInterpolator()
                alpha.interpolator = AnticipateInterpolator()

                scaleX.duration = 500L
                scaleY.duration = 500L
                alpha.duration = 500L

                alpha.doOnEnd { splashScreenView.remove() }

                scaleX.start()
                scaleY.start()
                alpha.start()
            } catch (_: NullPointerException) {
                splashScreenView.remove()
            }
        }

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
            val themeManager = remember { ThemeManager() }
            val isDarkMode by themeManager.isDarkMode.collectAsState(initial = false)

            MaterialTheme(
                colorScheme = if (isDarkMode) {
                    darkColorScheme(
                        primary = Color.White,
                        secondary = Color.White,
                        tertiary = Color.White,
                        background = Color.Black,
                        surface = Color(0xFF1C1C1C),
                        onPrimary = Color.Black,
                        onSecondary = Color.Black,
                        onBackground = Color.White,
                        onSurface = Color.White
                    )
                } else {
                    lightColorScheme(
                        primary = Color.Black,
                        secondary = Color.Black,
                        tertiary = Color.Black,
                    )
                }
            ) {
                MainScreen(themeManager = themeManager, isDarkMode = isDarkMode)
            }
        }
    }
}

val LocalIsDarkMode = compositionLocalOf { false }
@Composable
fun MainScreen(themeManager: ThemeManager, isDarkMode: Boolean) {
    CompositionLocalProvider(LocalIsDarkMode provides isDarkMode) {
        var currentScreen by remember { mutableStateOf("menu") }

        Scaffold { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    "menu" -> MenuScreen(
                        onNavigate = { screen -> currentScreen = screen },
                        themeManager = themeManager,
                        isDarkMode = isDarkMode
                    )

                    "randomGenerator" -> {
                        key("randomGenerator") {
                            RandomGeneratorScreen(onBack = { currentScreen = "menu" })
                        }
                    }

                    "volumeBooster" -> {
                        key("volumeBooster") {
                            VolumeBoosterScreen(onBack = { currentScreen = "menu" })
                        }
                    }

                    "flashcards" -> {
                        key("flashcards") {
                            FlashcardsNavGraph(onBack = { currentScreen = "menu" })
                        }
                    }

                    "undercover" -> {
                        key("undercover") {
                            UndercoverScreen(onBack = { currentScreen = "menu" })
                        }
                    }

                    "wikipedia" -> {
                        key("wikipedia") {
                            WikipediaScreen(onBack = { currentScreen = "menu" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FlashcardsNavGraph(onBack: () -> Unit) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "lists") {
        composable("lists") {
            FlashcardListsScreen(
                onBack = onBack,
                navController = navController
            )
        }
        composable("elements/{listId}") { backStackEntry ->
            val listId = backStackEntry.arguments?.getString("listId") ?: ""
            FlashcardDetailScreen(
                listId = listId,
                navController = navController,
                onBack = { navController.popBackStack() }
            )
        }
        composable("game/{listId}") { backStackEntry ->
            val listId = backStackEntry.arguments?.getString("listId") ?: ""
            FlashcardGameScreen(
                listId = listId,
                navController = navController,
                onBack = { navController.popBackStack()}
            )
        }
    }
}

@Composable
fun MenuScreen(
    onNavigate: (String) -> Unit,
    themeManager: ThemeManager,
    isDarkMode: Boolean
) {
    val spaceHeight = 32.dp
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Main content
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bienvenue !",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(25.dp))
            Text(
                text = "Choisis une option pour commencer :",
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(30.dp))
            MyButton(text = "Générateur aléatoire") { onNavigate("randomGenerator") }
            Spacer(modifier = Modifier.height(spaceHeight))
            MyButton(text = "Volume booster") { onNavigate("volumeBooster") }
            Spacer(modifier = Modifier.height(spaceHeight))
            MyButton(text = "Flashcards") { onNavigate("flashcards") }
            Spacer(modifier = Modifier.height(spaceHeight))
            MyButton(text = "Undercover") { onNavigate("undercover") }
            Spacer(modifier = Modifier.height(spaceHeight))
            MyButton(text = "Wikipedia") { onNavigate("wikipedia") }
        }

        // Floating theme toggle button (top right)
        IconButton(
            onClick = {
                coroutineScope.launch {
                    themeManager.setDarkMode(!isDarkMode)
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = 8.dp)
        ) {
            Icon(
                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = if (isDarkMode) "Mode clair" else "Mode sombre"
            )
        }
    }
}