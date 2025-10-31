package com.example.myapp

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

val Context.randomDataStore by preferencesDataStore("random_generator_prefs")

object RandomPrefs {
    val MIN_KEY = intPreferencesKey("min_value")
    val MAX_KEY = intPreferencesKey("max_value")
}

suspend fun Context.saveMinMax(min: Int, max: Int) {
    randomDataStore.edit { prefs ->
        prefs[RandomPrefs.MIN_KEY] = min
        prefs[RandomPrefs.MAX_KEY] = max
    }
}

fun Context.readMinMax() = randomDataStore.data.map { prefs ->
    val min = prefs[RandomPrefs.MIN_KEY] ?: 1
    val max = prefs[RandomPrefs.MAX_KEY] ?: 100
    min to max
}


@Composable
fun RandomGeneratorScreen(onBack: () -> Unit, context: Context = LocalContext.current) {
    BackHandler { onBack() }

    // Integer state
    var min by remember { mutableStateOf("1") }
    var max by remember { mutableStateOf("100") }
    var intResult by remember { mutableStateOf<Int?>(null) }

    // Word state
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var wordResult by remember { mutableStateOf<String?>(null) }

    // Load min/max from DataStore
    LaunchedEffect(Unit) {
        context.readMinMax().collect { (savedMin, savedMax) ->
            min = savedMin.toString()
            max = savedMax.toString()
        }
    }

    // Load words
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            val loadedWords = context.assets.open("words.txt")
                .bufferedReader()
                .useLines { it.toList() }
            words = loadedWords
            isLoading = false
        }
    }

    // Top bar
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text(
                "Générateur aléatoire",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 120.dp)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Random Integer Section
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nombre entier aléatoire", fontSize = 22.sp, fontWeight = FontWeight.Bold)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = min, onValueChange = { min = it }, label = { Text("Min") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = max, onValueChange = { max = it }, label = { Text("Max") }, modifier = Modifier.weight(1f))
            }

            MyButton("Générer") {
                val minInt = min.toIntOrNull() ?: 1
                val maxInt = max.toIntOrNull() ?: 100
                if (minInt <= maxInt) {
                    intResult = (minInt..maxInt).random()
                    // Save values persistently
                    CoroutineScope(Dispatchers.IO).launch {
                        context.saveMinMax(minInt, maxInt)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color(0xFFEFEFEF), shape = RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = intResult?.let { NumberFormat.getNumberInstance(Locale.FRANCE).format(it) } ?: "",
                    fontSize = 32.sp
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Random Word Section
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mot aléatoire", fontSize = 22.sp, fontWeight = FontWeight.Bold)

            MyButton(text = if (isLoading) "Chargement..." else "Générer", enabled = !isLoading) {
                if (words.isNotEmpty()) wordResult = words.random()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color(0xFFEFEFEF), shape = RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = wordResult ?: "", fontSize = 32.sp)
            }
        }
    }
}