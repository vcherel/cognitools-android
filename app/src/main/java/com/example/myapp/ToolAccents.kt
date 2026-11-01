package com.example.myapp

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A tool's color and icon: its chip on the menu, in its headers, and the tint of its buttons. */
class ToolAccent(val color: Color, val icon: ImageVector)

object ToolAccents {
    val Notes = ToolAccent(Color(0xFFE3A72F), Icons.AutoMirrored.Filled.StickyNote2)
    val Music = ToolAccent(Color(0xFFE0457B), Icons.Default.MusicNote)
    val Flashcards = ToolAccent(Color(0xFF3F74D6), Icons.Default.Style)
    val Gallery = ToolAccent(Color(0xFF2FA383), Icons.Default.PhotoLibrary)
    val Weather = ToolAccent(Color(0xFF3D8FD6), Icons.Default.WbSunny)
    val Crosswords = ToolAccent(Color(0xFF6F7A86), Icons.Default.GridOn)
    val Translate = ToolAccent(Color(0xFF1F9DB0), Icons.Default.Translate)
    val Reader = ToolAccent(Color(0xFFA8703A), Icons.AutoMirrored.Filled.MenuBook)
    val Volume = ToolAccent(Color(0xFFDB6F33), Icons.AutoMirrored.Filled.VolumeUp)
    val Undercover = ToolAccent(Color(0xFF5F5F8F), Icons.Default.TheaterComedy)
    val Wiki = ToolAccent(Color(0xFF4F8F4F), Icons.Default.Public)
    val Random = ToolAccent(Color(0xFFC2509A), Icons.Default.Casino)
    val Files = ToolAccent(Color(0xFFC99A1E), Icons.Default.Folder)
    val News = ToolAccent(Color(0xFF7A63CC), Icons.Default.Newspaper)
    val Journal = ToolAccent(Color(0xFFE58A2B), Icons.AutoMirrored.Filled.Article)
    val Mail = ToolAccent(Color(0xFF5A6FD6), Icons.Default.Mail)
    val Workout = ToolAccent(Color(0xFF2E9E6A), Icons.Default.FitnessCenter)
    val Calendar = ToolAccent(Color(0xFFD64B4B), Icons.Default.CalendarMonth)

    /** The tool a nav destination belongs to, null on the menu. */
    fun forRoute(route: String?): ToolAccent? {
        val first = route?.substringBefore('/')?.substringBefore('?') ?: return null
        return when (first) {
            "note", "notes" -> Notes
            "deezer" -> Music
            "flashcards", "lists", "elements", "game" -> Flashcards
            "gallery" -> Gallery
            "weather" -> Weather
            "motsFleches" -> Crosswords
            "translate" -> Translate
            "reader" -> Reader
            "volumeBooster" -> Volume
            "undercover" -> Undercover
            "wikipedia" -> Wiki
            "randomGenerator" -> Random
            "files" -> Files
            "news" -> News
            "journal" -> Journal
            "mail" -> Mail
            "workout" -> Workout
            "calendar" -> Calendar
            else -> null
        }
    }
}

/** The accent of the tool on screen; buttons read it for their tint, headers for their chip. */
val LocalToolAccent = compositionLocalOf<ToolAccent?> { null }

/** The current tool's icon chip, for a header; nothing outside a tool. */
@Composable
fun ToolChip(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val accent = LocalToolAccent.current ?: return
    IconChip(accent.icon, accent.color, size, highlighted = false, modifier = modifier.padding(start = 4.dp))
}
