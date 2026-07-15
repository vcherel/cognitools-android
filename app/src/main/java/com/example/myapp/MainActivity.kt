package com.example.myapp

import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
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
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapp.flashcards.FlashcardDetailScreen
import com.example.myapp.flashcards.FlashcardGameScreen
import com.example.myapp.flashcards.FlashcardListsScreen
import com.example.myapp.notes.NoteEditorScreen
import com.example.myapp.notes.NotesListScreen
import com.example.myapp.undercover.UndercoverScreen
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PlayArrow
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.notes.noteTitleAndPreview
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

val Context.themeDataStore by preferencesDataStore("theme_preferences")

private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")

class ThemeManager(private val context: Context) {
    val isDarkMode: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[DARK_MODE_KEY] ?: false
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.themeDataStore.edit { prefs ->
            prefs[DARK_MODE_KEY] = enabled
        }
    }
}

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

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
            val themeManager = remember { ThemeManager(applicationContext) }
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
        val navController = rememberNavController()
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        Scaffold { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                NavHost(navController = navController, startDestination = "menu") {
                    composable("menu") {
                        MenuScreen(
                            onNavigate = { route ->
                                when (route) {
                                    "flashcardsPlay" -> {
                                        navController.navigate("lists")
                                        navController.navigate("game/all")
                                    }
                                    "todoNote" -> coroutineScope.launch {
                                        val todo = AppDatabase.get(context).noteDao().getNotes()
                                            .firstOrNull {
                                                noteTitleAndPreview(it).first
                                                    .equals("Todo list", ignoreCase = true)
                                            }
                                        navController.navigate("notes")
                                        if (todo != null) {
                                            navController.navigate("note/${todo.id}")
                                        }
                                    }
                                    else -> navController.navigate(route)
                                }
                            },
                            themeManager = themeManager,
                            isDarkMode = isDarkMode
                        )
                    }
                    composable("randomGenerator") {
                        RandomGeneratorScreen(onBack = { navController.popBackStack() })
                    }
                    composable("volumeBooster") {
                        VolumeBoosterScreen(onBack = { navController.popBackStack() })
                    }
                    composable("undercover") {
                        UndercoverScreen(onBack = { navController.popBackStack() })
                    }
                    composable("wikipedia") {
                        WikipediaScreen(onBack = { navController.popBackStack() })
                    }
                    composable("weather") {
                        WeatherScreen(onBack = { navController.popBackStack() })
                    }
                    composable("notes") {
                        NotesListScreen(navController = navController)
                    }
                    composable("note/{noteId}") { backStackEntry ->
                        val noteId = backStackEntry.arguments?.getString("noteId") ?: "new"
                        NoteEditorScreen(
                            noteId = noteId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    navigation(startDestination = "lists", route = "flashcards") {
                        composable("lists") {
                            FlashcardListsScreen(navController = navController)
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
                                navController = navController
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuScreen(
    onNavigate: (String) -> Unit,
    themeManager: ThemeManager,
    isDarkMode: Boolean
) {
    val spaceHeight = 20.dp
    val buttonHeight = 84.dp
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
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
            Spacer(modifier = Modifier.height(20.dp))
            SplitMyButton(
                text = "Notes",
                rightIcon = Icons.Default.Checklist,
                height = buttonHeight,
                onMainClick = { onNavigate("notes") },
                onRightClick = { onNavigate("todoNote") }
            )
            Spacer(modifier = Modifier.height(spaceHeight))
            SplitMyButton(
                text = "Générateur aléatoire",
                rightIcon = Icons.Default.Casino,
                height = buttonHeight,
                onMainClick = { onNavigate("randomGenerator") },
                onRightClick = {
                    coroutineScope.launch {
                        val (min, max) = context.readMinMax().first()
                        if (min <= max) {
                            val result = (min..max).random()
                            val formatted = NumberFormat.getNumberInstance(Locale.FRANCE).format(result)
                            Toast.makeText(context, formatted, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(spaceHeight))
            SplitMyButton(
                text = "Flashcards",
                rightIcon = Icons.Default.PlayArrow,
                height = buttonHeight,
                onMainClick = { onNavigate("flashcards") },
                onRightClick = { onNavigate("flashcardsPlay") }
            )
            Spacer(modifier = Modifier.height(spaceHeight))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(text = "Météo", modifier = Modifier.weight(1f), height = buttonHeight) { onNavigate("weather") }
                MyButton(text = "Undercover", modifier = Modifier.weight(1f), height = buttonHeight) { onNavigate("undercover") }
            }
            Spacer(modifier = Modifier.height(spaceHeight))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(text = "Volume", modifier = Modifier.weight(1f), height = buttonHeight) { onNavigate("volumeBooster") }
                MyButton(text = "Wikipedia", modifier = Modifier.weight(1f), height = buttonHeight) { onNavigate("wikipedia") }
            }
        }

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