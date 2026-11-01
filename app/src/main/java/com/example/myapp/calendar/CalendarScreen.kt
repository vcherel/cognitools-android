package com.example.myapp.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.myapp.ScreenTopBar
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// A hundred years either side of the current month.
private const val PAGE_COUNT = 2401
private const val START_PAGE = 1200
private const val GRID_DAYS = 42

private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.FRENCH)
private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val dayYearFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val shortDayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
private val WEEKDAYS = listOf("L", "M", "M", "J", "V", "S", "D")

fun kindColor(kind: CalendarKind): Color = when (kind) {
    CalendarKind.TASK -> Color(0xFF42A5F5)
    CalendarKind.BIRTHDAY -> Color(0xFFEC407A)
    CalendarKind.EVENT -> Color(0xFF66BB6A)
}

/** Monday of the week holding the 1st, so every month fills the same six rows. */
private fun gridStart(month: YearMonth): LocalDate =
    month.atDay(1).let { it.minusDays((it.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()) }

private fun String.capitalized() = replaceFirstChar { it.uppercase() }

@Composable
fun CalendarScreen(onBack: () -> Unit, initialDay: String?) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).calendarDao() }
    val items by remember { dao.observeAll() }.collectAsState(initial = emptyList())
    var schoolHolidays by remember { mutableStateOf<List<SchoolHoliday>>(emptyList()) }
    LaunchedEffect(Unit) {
        schoolHolidays = SchoolHolidays.cached(context)
        SchoolHolidays.refreshIfStale(context)?.let { schoolHolidays = it }
    }

    val today = remember { LocalDate.now() }
    val baseMonth = remember { YearMonth.from(today) }
    var selected by rememberSaveable {
        mutableStateOf(initialDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today)
    }
    fun pageOf(day: LocalDate) = START_PAGE + ChronoUnit.MONTHS.between(baseMonth, YearMonth.from(day)).toInt()
    fun monthOf(page: Int): YearMonth = baseMonth.plusMonths((page - START_PAGE).toLong())
    val pagerState = rememberPagerState(initialPage = pageOf(selected)) { PAGE_COUNT }

    // Swiping to another month moves the selection into it: today in the current month, the 1st elsewhere.
    LaunchedEffect(pagerState.settledPage) {
        val month = monthOf(pagerState.settledPage)
        if (YearMonth.from(selected) != month) selected = if (month == baseMonth) today else month.atDay(1)
    }

    var editing by remember { mutableStateOf<CalendarItem?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Agenda", onBack = onBack, titleWeight = true) {
            IconButton(onClick = {
                selected = today
                scope.launch { pagerState.animateScrollToPage(START_PAGE) }
            }) { Icon(Icons.Default.Today, contentDescription = "Aujourd'hui") }
            IconButton(onClick = {
                editingIsNew = true
                editing = CalendarItem(kind = CalendarKind.TASK, title = "", date = selected.toString())
            }) { Icon(Icons.Default.Add, contentDescription = "Ajouter") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Mois précédent")
            }
            Text(
                monthFormat.format(monthOf(pagerState.currentPage)).capitalized(),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Mois suivant")
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            WEEKDAYS.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { page ->
            MonthGrid(
                month = monthOf(page),
                items = items,
                schoolHolidays = schoolHolidays,
                today = today,
                selected = selected,
                onSelect = { day ->
                    selected = day
                    if (YearMonth.from(day) != monthOf(page)) scope.launch { pagerState.animateScrollToPage(pageOf(day)) }
                }
            )
        }

        DayList(
            day = selected,
            today = today,
            items = items,
            schoolHolidays = schoolHolidays,
            modifier = Modifier.weight(1f),
            onToggleDone = { item -> scope.launch { CalendarReminders.save(context, item.copy(done = !item.done)) } },
            onOpen = { item ->
                editingIsNew = false
                editing = item
            }
        )
    }

    editing?.let { item ->
        CalendarItemDialog(
            initial = item,
            isNew = editingIsNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                scope.launch { CalendarReminders.save(context, saved) }
            },
            onDelete = {
                editing = null
                scope.launch { CalendarReminders.deleteWithUndo(context, item) }
            }
        )
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    items: List<CalendarItem>,
    schoolHolidays: List<SchoolHoliday>,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    val start = remember(month) { gridStart(month) }
    val end = remember(start) { start.plusDays(GRID_DAYS.toLong()) }
    val publicDays = remember(month) { (month.year - 1..month.year + 1).flatMap { publicHolidays(it).keys }.toSet() }
    val marks = remember(items, start, today) {
        val byDay = HashMap<LocalDate, MutableSet<CalendarKind>>()
        fun mark(day: LocalDate, kind: CalendarKind) {
            if (!day.isBefore(start) && day.isBefore(end)) byDay.getOrPut(day) { sortedSetOf() } += kind
        }
        items.forEach { item ->
            if (item.kind == CalendarKind.BIRTHDAY) {
                (start.year..end.year).forEach { mark(item.birthdayIn(it), item.kind) }
            } else {
                mark(item.localDate(), item.kind)
                if (item.isOverdue(today)) mark(today, item.kind)
            }
        }
        byDay
    }
    Column {
        (0 until GRID_DAYS / 7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                (0 until 7).forEach { weekday ->
                    val day = start.plusDays((week * 7 + weekday).toLong())
                    DayCell(
                        day = day,
                        inMonth = YearMonth.from(day) == month,
                        isToday = day == today,
                        isSelected = day == selected,
                        isPublicHoliday = day in publicDays,
                        inSchoolHoliday = schoolHolidays.any { it.contains(day) },
                        kinds = marks[day].orEmpty(),
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isPublicHoliday: Boolean,
    inSchoolHoliday: Boolean,
    kinds: Set<CalendarKind>,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val textColor = when {
        isSelected -> colors.onPrimary
        !inMonth -> colors.onSurface.copy(alpha = 0.3f)
        isPublicHoliday -> colors.onSurface.copy(alpha = 0.45f)
        else -> colors.onSurface
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .background(if (inSchoolHoliday) colors.primary.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .then(
                        when {
                            isSelected -> Modifier.background(colors.primary)
                            isToday -> Modifier.border(1.5.dp, colors.primary, CircleShape)
                            else -> Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    color = textColor,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(top = 3.dp).height(6.dp)
            ) {
                kinds.forEach { kind ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(kindColor(kind).copy(alpha = if (inMonth) 1f else 0.4f), CircleShape)
                    )
                }
            }
        }
    }
}

// Birthdays first, then by time, the all day ones before the timed ones.
private val dayOrder = compareBy<CalendarItem>({ it.kind != CalendarKind.BIRTHDAY }, { it.time ?: "" }, { it.title })

@Composable
private fun DayList(
    day: LocalDate,
    today: LocalDate,
    items: List<CalendarItem>,
    schoolHolidays: List<SchoolHoliday>,
    modifier: Modifier,
    onToggleDone: (CalendarItem) -> Unit,
    onOpen: (CalendarItem) -> Unit
) {
    val dayItems = remember(items, day) { items.filter { it.occursOn(day) }.sortedWith(dayOrder) }
    val overdue = remember(items, day, today) {
        if (day == today) items.filter { it.isOverdue(today) }.sortedBy { it.date } else emptyList()
    }
    val publicHoliday = remember(day) { publicHolidays(day.year)[day] }
    val schoolHoliday = schoolHolidays.firstOrNull { it.contains(day) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(
                (if (day.year == today.year) dayFormat else dayYearFormat).format(day).capitalized(),
                style = MaterialTheme.typography.titleMedium
            )
            listOfNotNull(publicHoliday?.let { "Férié : $it" }, schoolHoliday?.name).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (overdue.isNotEmpty()) {
            item { SectionLabel("En retard") }
            items(overdue, key = { "late_${it.id}" }) { item ->
                ItemRow(item, day, onToggleDone, onOpen, lateFrom = item.localDate())
            }
            if (dayItems.isNotEmpty()) item { SectionLabel("Aujourd'hui") }
        }
        items(dayItems, key = { it.id }) { item -> ItemRow(item, day, onToggleDone, onOpen) }
        if (dayItems.isEmpty() && overdue.isEmpty()) {
            item { Text("Rien de prévu", color = muted, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
private fun ItemRow(
    item: CalendarItem,
    day: LocalDate,
    onToggleDone: (CalendarItem) -> Unit,
    onOpen: (CalendarItem) -> Unit,
    lateFrom: LocalDate? = null
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (item.kind) {
            CalendarKind.TASK -> Checkbox(checked = item.done, onCheckedChange = { onToggleDone(item) })
            CalendarKind.BIRTHDAY -> KindIcon(Icons.Default.Cake, kindColor(item.kind))
            CalendarKind.EVENT -> KindIcon(Icons.Default.Event, kindColor(item.kind))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            val title = when (item.kind) {
                CalendarKind.BIRTHDAY -> birthdayTitle(item) + (item.ageIn(day.year)?.let { " ($it ans)" } ?: "")
                else -> item.title
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                color = if (item.done) muted else MaterialTheme.colorScheme.onSurface
            )
            val details = when {
                lateFrom != null -> "Prévu ${shortDayFormat.format(lateFrom)}"
                item.kind == CalendarKind.BIRTHDAY -> null
                else -> listOfNotNull(timeRange(item), item.place?.takeIf { it.isNotBlank() }).joinToString(", ").ifEmpty { null }
            }
            details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
    }
}

@Composable
private fun KindIcon(icon: ImageVector, tint: Color) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(24.dp))
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

private fun timeRange(item: CalendarItem): String? {
    val start = item.localTime()?.let(timeFormat::format) ?: return null
    val end = item.localEndTime()?.let(timeFormat::format)
    return if (end != null) "$start à $end" else start
}
