package com.example.myapp.journal

import org.json.JSONArray
import org.json.JSONObject

// The shapes below mirror `src/daily_journal/issue.py` in ~/Documents/Code/daily-journal, which
// writes the issues and reads the feedback: a field changed here must change there.

data class JournalSource(val name: String, val url: String)

data class JournalItem(
    val id: String,
    val title: String,
    val summary: String,
    val body: String,
    val meta: String,
    /** Set on the exploration items: the subject being tried. */
    val subject: String,
    /** An https picture URL, blank when the item has none. */
    val image: String,
    val sources: List<JournalSource>
)

enum class SectionKind { FIXED, EXPLORE }

data class JournalSection(val id: String, val title: String, val kind: SectionKind, val items: List<JournalItem>)

data class JournalIdea(val id: String, val text: String, val reason: String)

/** One day's issue, [date] as yyyy-MM-dd, which is also what names its files. */
data class JournalIssue(
    val date: String,
    val headline: String,
    val sections: List<JournalSection>,
    val ideas: List<JournalIdea>
)

enum class IdeaAnswer(val code: String) { ACCEPTED("accepted"), DECLINED("declined") }

/** What was done with one issue: the laptop learns from it at its next run. */
data class JournalFeedback(
    /** Item id to 1 (liked) or -1 (disliked); absent means not rated. */
    val ratings: Map<String, Int> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    val ideas: Map<String, IdeaAnswer> = emptyMap(),
    /** Items with a source opened, a signal of interest even unrated. */
    val opened: Set<String> = emptySet(),
    val general: String = ""
)

/** Where an issue was left: the first visible row of its list with its offset, and the cards opened in it. */
data class JournalPosition(val index: Int = 0, val offset: Int = 0, val expanded: Set<String> = emptySet())

/** The positions per date plus the date of the issue shown last, what reopening the screen starts from. */
data class JournalPositions(val last: String? = null, val byDate: Map<String, JournalPosition> = emptyMap())

fun JournalPositions.toJson(): String = JSONObject().apply {
    last?.let { put("last", it) }
    put("issues", JSONObject().apply {
        byDate.forEach { (date, p) ->
            put(date, JSONObject().apply {
                put("index", p.index)
                put("offset", p.offset)
                put("expanded", JSONArray(p.expanded.toList()))
            })
        }
    })
}.toString()

fun parsePositions(raw: String): JournalPositions {
    val obj = JSONObject(raw)
    val issues = obj.optJSONObject("issues") ?: JSONObject()
    return JournalPositions(
        last = obj.optString("last").ifBlank { null },
        byDate = issues.keys().asSequence().associateWith { date ->
            val p = issues.getJSONObject(date)
            val expanded = p.optJSONArray("expanded") ?: JSONArray()
            JournalPosition(p.optInt("index"), p.optInt("offset"), (0 until expanded.length()).map(expanded::getString).toSet())
        }
    )
}

fun parseIssue(raw: String): JournalIssue {
    val obj = JSONObject(raw)
    return JournalIssue(
        date = obj.getString("date"),
        headline = obj.optString("headline"),
        sections = obj.getJSONArray("sections").objects().map { section ->
            JournalSection(
                id = section.getString("id"),
                title = section.getString("title"),
                kind = if (section.optString("kind") == "explore") SectionKind.EXPLORE else SectionKind.FIXED,
                items = section.getJSONArray("items").objects().map { item ->
                    JournalItem(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        summary = item.optString("summary"),
                        body = item.optString("body"),
                        meta = item.optString("meta"),
                        subject = item.optString("subject"),
                        image = item.optString("image"),
                        sources = item.getJSONArray("sources").objects().map {
                            JournalSource(it.optString("name"), it.getString("url"))
                        }
                    )
                }
            )
        },
        ideas = (obj.optJSONArray("ideas") ?: JSONArray()).objects().map {
            JournalIdea(it.getString("id"), it.getString("text"), it.optString("reason"))
        }
    )
}

fun JournalFeedback.toJson(date: String): String = JSONObject().apply {
    put("date", date)
    put("items", JSONObject().apply {
        (ratings.keys + notes.keys).forEach { id ->
            put(id, JSONObject().apply {
                put("rating", ratings[id] ?: 0)
                notes[id]?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            })
        }
    })
    put("ideas", JSONObject().apply { ideas.forEach { (id, answer) -> put(id, answer.code) } })
    put("opened", JSONArray(opened.toList()))
    put("general", general)
}.toString()

fun parseFeedback(raw: String): JournalFeedback {
    val obj = JSONObject(raw)
    val items = obj.optJSONObject("items") ?: JSONObject()
    val entries = items.keys().asSequence().map { it to items.getJSONObject(it) }.toList()
    val ideas = obj.optJSONObject("ideas") ?: JSONObject()
    return JournalFeedback(
        ratings = entries.mapNotNull { (id, e) -> e.optInt("rating").takeIf { it != 0 }?.let { id to it } }.toMap(),
        notes = entries.mapNotNull { (id, e) -> e.optString("note").takeIf { it.isNotBlank() }?.let { id to it } }.toMap(),
        ideas = ideas.keys().asSequence().mapNotNull { id ->
            IdeaAnswer.entries.firstOrNull { it.code == ideas.getString(id) }?.let { id to it }
        }.toMap(),
        opened = (obj.optJSONArray("opened") ?: JSONArray()).let { a -> (0 until a.length()).map(a::getString).toSet() },
        general = obj.optString("general")
    )
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
