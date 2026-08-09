package com.example.myapp.motsfleches

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.myapp.LocalIsDarkMode
import kotlin.math.max

/** The cell the player is on, and which of the two words crossing it is being filled. */
data class Selection(val row: Int, val col: Int, val across: Boolean)

private class GridColors(
    val definition: Color,
    val letter: Color,
    val highlight: Color,
    val cursor: Color,
    val line: Color,
    val clueText: Color,
    val answerText: Color,
    val revealedText: Color
)

private fun gridColors(isDark: Boolean) = if (isDark) {
    GridColors(
        definition = Color(0xFF37474F),
        letter = Color(0xFF1C262B),
        highlight = Color(0xFF14405F),
        cursor = Color(0xFF1565C0),
        line = Color(0xFF546E7A),
        clueText = Color(0xFFCFD8DC),
        answerText = Color(0xFFECEFF1),
        revealedText = Color(0xFF64B5F6)
    )
} else {
    GridColors(
        definition = Color(0xFFCFD8DC),
        letter = Color(0xFFFFFFFF),
        highlight = Color(0xFFBBDEFB),
        cursor = Color(0xFF64B5F6),
        line = Color(0xFF90A4AE),
        clueText = Color(0xFF263238),
        answerText = Color(0xFF102027),
        revealedText = Color(0xFF1565C0)
    )
}

/** A definition laid out inside its cell, with the arrow pointing at the word it introduces. */
private class ClueDraw(val layout: TextLayoutResult, val topLeft: Offset, val arrow: Path)

/**
 * The grid itself: definitions printed in their cells like a paper grid, pinch to zoom when they
 * get too small to read. Everything is drawn on one canvas, so a 12x15 grid stays one draw pass
 * instead of 180 composables.
 */
@Composable
fun PuzzleGrid(
    state: PuzzleState,
    selection: Selection?,
    modifier: Modifier = Modifier,
    onSelect: (row: Int, col: Int) -> Unit
) {
    val puzzle = state.puzzle
    val isDark = LocalIsDarkMode.current
    val colors = remember(isDark) { gridColors(isDark) }
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val viewport = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val cell = viewport.width / puzzle.width
        val gridSize = Size(cell * puzzle.width, cell * puzzle.height)
        val lineWidth = with(density) { 1.dp.toPx() }

        var scale by remember(puzzle) { mutableFloatStateOf(1f) }
        var offset by remember(puzzle) { mutableStateOf(Offset.Zero) }

        fun clamp(candidate: Offset, zoom: Float): Offset {
            val scaled = Size(gridSize.width * zoom, gridSize.height * zoom)
            val x = if (scaled.width <= viewport.width) {
                (viewport.width - scaled.width) / 2f
            } else {
                candidate.x.coerceIn(viewport.width - scaled.width, 0f)
            }
            val y = if (scaled.height <= viewport.height) {
                (viewport.height - scaled.height) / 2f
            } else {
                candidate.y.coerceIn(viewport.height - scaled.height, 0f)
            }
            return Offset(x, y)
        }

        val clues = remember(puzzle, cell, colors) { layoutClues(measurer, density, puzzle, cell, colors) }
        val letters = remember(cell, colors) { layoutLetters(measurer, density, cell, colors) }
        val highlighted = remember(puzzle, selection) { cellsOfSelection(puzzle, selection) }
        val cursor = selection?.let { puzzle.index(it.row, it.col) } ?: -1

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(puzzle) {
                    detectTapGestures { tap ->
                        val point = (tap - offset) / scale
                        val row = (point.y / cell).toInt()
                        val col = (point.x / cell).toInt()
                        if (row in 0 until puzzle.height && col in 0 until puzzle.width) {
                            onSelect(row, col)
                        }
                    }
                }
                .pointerInput(puzzle) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 4f)
                        // Keep the point under the fingers still while the grid grows.
                        val anchored = centroid - (centroid - offset) * (next / scale) + pan
                        offset = clamp(anchored, next)
                        scale = next
                    }
                }
        ) {
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                for (row in 0 until puzzle.height) {
                    for (col in 0 until puzzle.width) {
                        val index = puzzle.index(row, col)
                        val topLeft = Offset(col * cell, row * cell)
                        val background = when {
                            puzzle.isDefinition(row, col) -> colors.definition
                            index == cursor -> colors.cursor
                            index in highlighted -> colors.highlight
                            else -> colors.letter
                        }
                        drawRect(background, topLeft, Size(cell, cell))
                        drawRect(colors.line, topLeft, Size(cell, cell), style = Stroke(lineWidth))

                        val letter = state.letterAt(index)
                        if (!puzzle.isDefinition(row, col) && letter != Puzzle.EMPTY) {
                            letters[letter]?.let { layout ->
                                drawText(
                                    textLayoutResult = layout,
                                    color = if (index in state.revealed) colors.revealedText else colors.answerText,
                                    topLeft = topLeft + Offset(
                                        (cell - layout.size.width) / 2f,
                                        (cell - layout.size.height) / 2f
                                    )
                                )
                            }
                        }
                    }
                }
                clues.forEach { clue ->
                    drawText(clue.layout, topLeft = clue.topLeft)
                    drawPath(clue.arrow, colors.clueText)
                }
            }
        }
    }
}

private fun cellsOfSelection(puzzle: Puzzle, selection: Selection?): Set<Int> {
    val slotIndex = selection?.let { puzzle.slotIndexAt(it.row, it.col, it.across) } ?: return emptySet()
    val slot = puzzle.slots[slotIndex]
    return (0 until slot.length).map { puzzle.index(slot.cellRow(it), slot.cellCol(it)) }.toSet()
}

/** The whole alphabet measured once at the current cell size; letters are drawn hundreds of times. */
private fun layoutLetters(
    measurer: TextMeasurer,
    density: Density,
    cell: Float,
    colors: GridColors
): Map<Char, TextLayoutResult> {
    val style = TextStyle(
        fontSize = with(density) { (cell * 0.52f).toSp() },
        fontWeight = FontWeight.SemiBold,
        color = colors.answerText
    )
    return ('A'..'Z').associateWith { measurer.measure(it.toString(), style) }
}

/**
 * Lays out every definition. A cell holding two definitions splits in half, the across one on top
 * with its arrow on the right edge, the down one below with its arrow on the bottom edge.
 */
private fun layoutClues(
    measurer: TextMeasurer,
    density: Density,
    puzzle: Puzzle,
    cell: Float,
    colors: GridColors
): List<ClueDraw> {
    val arrow = cell * 0.16f
    val padding = cell * 0.05f
    val draws = mutableListOf<ClueDraw>()

    for (index in puzzle.definitionCells) {
        val row = index / puzzle.width
        val col = index % puzzle.width
        val across = puzzle.slotFromDefinition(row, col, across = true)
        val down = puzzle.slotFromDefinition(row, col, across = false)
        val shared = across != null && down != null
        val origin = Offset(col * cell, row * cell)

        listOfNotNull(
            across?.let { it to true },
            down?.let { it to false }
        ).forEachIndexed { position, (slot, isAcross) ->
            val bandTop = if (shared && position == 1) cell / 2f else 0f
            val bandHeight = if (shared) cell / 2f else cell
            // The arrow needs a strip of its own: on the right for an across word, at the bottom
            // for a down one.
            val textWidth = cell - padding * 2 - if (isAcross) arrow else 0f
            val textHeight = bandHeight - padding * 2 - if (isAcross) 0f else arrow
            val fontPx = cell * fontFactor(shared)
            val linePx = fontPx * 1.15f
            val style = TextStyle(
                fontSize = with(density) { fontPx.toSp() },
                lineHeight = with(density) { linePx.toSp() },
                color = colors.clueText,
                textAlign = TextAlign.Start
            )
            // Ellipsis needs a line count: a height constraint alone would cut a line in half.
            val layout = measurer.measure(
                text = slot.clue,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = max(1, (textHeight / linePx).toInt()),
                constraints = Constraints(maxWidth = max(1, textWidth.toInt()))
            )
            draws.add(
                ClueDraw(
                    layout = layout,
                    topLeft = origin + Offset(padding, bandTop + padding),
                    arrow = arrowPath(origin, bandTop, bandHeight, cell, arrow, isAcross)
                )
            )
        }
    }
    return draws
}

/** A small solid triangle, pointing the way the word runs, on the edge the word starts from. */
private fun arrowPath(
    origin: Offset,
    bandTop: Float,
    bandHeight: Float,
    cell: Float,
    arrow: Float,
    isAcross: Boolean
): Path = Path().apply {
    if (isAcross) {
        val centerY = origin.y + bandTop + bandHeight / 2f
        val right = origin.x + cell
        moveTo(right - arrow * 0.85f, centerY - arrow / 2f)
        lineTo(right - arrow * 0.1f, centerY)
        lineTo(right - arrow * 0.85f, centerY + arrow / 2f)
    } else {
        val centerX = origin.x + cell / 2f
        val bottom = origin.y + bandTop + bandHeight
        moveTo(centerX - arrow / 2f, bottom - arrow * 0.85f)
        lineTo(centerX, bottom - arrow * 0.1f)
        lineTo(centerX + arrow / 2f, bottom - arrow * 0.85f)
    }
    close()
}

/** Two definitions in one cell each get half the height, so they need a smaller type. */
private fun fontFactor(shared: Boolean) = if (shared) 0.135f else 0.16f
