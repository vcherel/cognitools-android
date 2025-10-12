package com.example.myapp

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            .padding(horizontal = 20.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Random Integer Section
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nombre entier aléatoire", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = min, onValueChange = { min = it }, label = { Text("Min") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = max, onValueChange = { max = it }, label = { Text("Max") }, modifier = Modifier.weight(1f))
            }

            MyButton("Générer") {
                val minInt = min.toIntOrNull() ?: 1
                val maxInt = max.toIntOrNull() ?: 100
                if (minInt <= maxInt) intResult = (minInt..maxInt).random()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color(0xFFEFEFEF), shape = RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = intResult?.toString() ?: "", fontSize = 32.sp)
            }
        }

        // Random Word Section
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mot aléatoire", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)

            MyButton(text = if (isLoading) "Chargement..." else "Générer") {
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