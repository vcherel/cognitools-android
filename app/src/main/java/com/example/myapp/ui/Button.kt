package com.example.myapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MyButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
    ) {
        // Shadow layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 6.dp)
                .background(Color(0xFFB0BEC5), RoundedCornerShape(50))
        )

        // Main button
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFECEFF1)),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFFECEFF1), Color(0xFFCFD8DC))
                        ),
                        shape = RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text,
                    color = Color.Black,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun MySwitch(isBoostEnabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(300.dp)
            .height(100.dp)
    ) {
        // Shadow layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 6.dp)
                .background(
                    color = if (isBoostEnabled) Color(0xFF1565C0) else Color(0xFFB0BEC5),
                    shape = RoundedCornerShape(50)
                )
        )

        // Main toggle container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(
                            if (isBoostEnabled) Color(0xFF2196F3) else Color(0xFFECEFF1),
                            if (isBoostEnabled) Color(0xFF1976D2) else Color(0xFFCFD8DC)
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onToggle(!isBoostEnabled) }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Boost",
                    color = if (isBoostEnabled) Color.White else Color.Black,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )

                Switch(
                    checked = isBoostEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF0D47A1),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFB0BEC5)
                    ),
                    modifier = Modifier.scale(1.4f)
                )
            }
        }
    }
}