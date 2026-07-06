package com.example.myapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Fade-to-background gradient anchored at the bottom of a scrolling list
@Composable
fun BoxScope.BottomFadeOverlay() {
    val isDarkMode = LocalIsDarkMode.current
    val brush = remember(isDarkMode) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                if (isDarkMode) Color(0xFF000000) else Color(0xFFFEF7FF)
            ),
            startY = 0f,
            endY = Float.POSITIVE_INFINITY
        )
    }
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(150.dp)
            .background(brush)
    )
}
