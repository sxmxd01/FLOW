package app.focusflow.data

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

enum class BlockType { STUDY, FIXED }

/** One block on one day (a concrete task, or a virtual occurrence of a recurring class). */
data class Block(
    val id: String,
    val date: LocalDate,
    val type: BlockType,
    val title: String,
    val start: Int,            // minutes from midnight
    val dur: Int,              // minutes
    val done: Boolean,
    val pct: Int,
    val color: Int,
    val subjectName: String?,
    val recurringId: String?,
    val note: String = "",
) {
    val end: Int get() = start + dur
    val isFixed: Boolean get() = type == BlockType.FIXED
}

data class NotifPrefs(
    val start: Boolean = true,
    val end: Boolean = true,
    val leadMin: Int = 0,
    val fixed: Boolean = true,
    val dayStart: Boolean = true,
    val dayEnd: Boolean = true,
)

/** Start day / End day ritual state for one date (minutes from midnight, null = not happened). */
data class DayInfo(val startedAt: Int?, val endedAt: Int?)

private data class SubjectInfo(val name: String, val color: Int)
private data class MaterialInfo(val color: Int, val subjectIds: List<String>)

private const val DEFAULT_COLOR = 0xFF7A8194.toInt()

/** Fixed-block categories (must match the web app): CL, Rest, Content. */
private fun catColor(cat: String?): Int = when (cat) {
    "rest" -> 0xFFA392C0.toInt()
    "content" -> 0xFFC9A46A.toInt()
    else -> 0xFF7F9FC4.toInt()
}

private fun catLabel(cat: String?): String = when (cat) {
    "rest" -> "Rest"
    "content" -> "Content"
    else -> "CL"
}

private fun JSONObject.stringList(name: String): List<String> {
    val arr = optJSONArray(name) ?: return emptyList()
    val out = ArrayList<String>()
    for (i in 0 until arr.length()) {
        val v = arr.optString(i)
        if (v.isNotEmpty()) out += v
    }
    return out
}

private fun parseColor(s: String?, fallback: Int): Int =
    try {
        if (s.isNullOrBlank()) fallback else Color.parseColor(s)
    } catch (e: IllegalArgumentException) {
        fallback
    }

private fun JSONObject.strOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/**
 * Read-only view of the planner state saved by the web UI (same JSON schema as the localStorage
 * payload, version 2). Mirrors `allTasksOn()` from the web code so native alerts and the widget
 * always agree with what the user sees in the app.
 */
class Snapshot private constructor(
    val notif: NotifPrefs,
    val wake: Int,
    val sleep: Int,
    private val days: JSONObject,
    private val tasks: JSONObject,
    private val recurring: JSONArray,
    private val subjects: Map<String, SubjectInfo>,
    private val materials: Map<String, MaterialInfo>,
) {

    fun dayInfo(date: LocalDate): DayInfo {
        val o = days.optJSONObject(date.toString()) ?: return DayInfo(null, null)
        return DayInfo(if (o.isNull("startedAt")) null else o.optInt("startedAt"),
                       if (o.isNull("endedAt")) null else o.optInt("endedAt"))
    }

    fun blocksOn(date: LocalDate): List<Block> {
        val key = date.toString() // yyyy-MM-dd, same as the web `iso()`
        val out = ArrayList<Block>()

        tasks.optJSONArray(key)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                toBlock(o, date)?.let(out::add)
            }
        }

        val dow = date.dayOfWeek.value // Monday = 1 … Sunday = 7, same as the web code
        for (i in 0 until recurring.length()) {
            val r = recurring.optJSONObject(i) ?: continue
            val rid = r.optString("id")
            if (rid.isEmpty()) continue
            val days = r.optJSONArray("days") ?: continue
            var matches = false
            for (d in 0 until days.length()) if (days.optInt(d) == dow) matches = true
            if (!matches) continue
            val until = r.strOrNull("until")
            if (until != null && key > until) continue
            out += Block(
                id = "rec:$rid:$key",
                date = date,
                type = BlockType.FIXED,
                title = r.optString("title").ifEmpty { catLabel(r.strOrNull("cat")) },
                start = r.optInt("start"),
                dur = r.optInt("dur", 60),
                done = false,
                pct = 0,
                color = catColor(r.strOrNull("cat")),
                subjectName = catLabel(r.strOrNull("cat")),
                recurringId = rid,
            )
        }
        return out.sortedBy { it.start }
    }

    private fun toBlock(o: JSONObject, date: LocalDate): Block? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val fixed = o.optString("type") == "fixed"
        val mat = o.strOrNull("matId")?.let { materials[it] }
        // a material may belong to several subjects; fall back to the ids stored on the block
        val own = o.stringList("subjectIds").ifEmpty { listOfNotNull(o.strOrNull("subjectId")) }.filter { subjects.containsKey(it) }
        val subjIds = when {
            fixed -> emptyList<String>()
            own.isNotEmpty() -> own
            else -> mat?.subjectIds ?: emptyList()
        }
        val subjInfos = subjIds.mapNotNull { subjects[it] }
        val color = if (fixed) catColor(o.strOrNull("cat")) else (mat?.color ?: subjInfos.firstOrNull()?.color ?: DEFAULT_COLOR)
        val label = if (fixed) catLabel(o.strOrNull("cat")) else subjInfos.joinToString(" · ") { it.name }.ifEmpty { null }
        val title = o.optString("title").ifEmpty { if (fixed) catLabel(o.strOrNull("cat")) else "Study" }
        return Block(
            id = id,
            date = date,
            type = if (fixed) BlockType.FIXED else BlockType.STUDY,
            title = title,
            start = o.optInt("start"),
            dur = o.optInt("dur", 45),
            done = !fixed && o.optBoolean("done", false),
            pct = o.optInt("pct", 0),
            color = color,
            subjectName = label,
            recurringId = null,
            note = o.optString("note").trim(),
        )
    }

    companion object {
        val EMPTY = Snapshot(NotifPrefs(), 390, 1380, JSONObject(), JSONObject(), JSONArray(), emptyMap(), emptyMap())

        fun parse(json: String?): Snapshot {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                val root = JSONObject(json)
                val settings = root.optJSONObject("settings")
                val n = settings?.optJSONObject("notif")
                val notif = NotifPrefs(
                    start = n?.optBoolean("start", true) ?: true,
                    end = n?.optBoolean("end", true) ?: true,
                    leadMin = (n?.optInt("lead", 0) ?: 0).coerceIn(0, 120),
                    fixed = n?.optBoolean("fixed", true) ?: true,
                    dayStart = n?.optBoolean("dayStart", true) ?: true,
                    dayEnd = n?.optBoolean("dayEnd", true) ?: true,
                )

                val subjects = HashMap<String, SubjectInfo>()
                root.optJSONArray("subjects")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id")
                        if (id.isNotEmpty()) {
                            subjects[id] = SubjectInfo(
                                o.optString("name"),
                                parseColor(o.optString("color"), DEFAULT_COLOR),
                            )
                        }
                    }
                }
                val materials = HashMap<String, MaterialInfo>()
                root.optJSONArray("materials")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id")
                        if (id.isNotEmpty()) {
                            materials[id] = MaterialInfo(
                                parseColor(o.optString("color"), DEFAULT_COLOR),
                                o.stringList("subjectIds").ifEmpty { listOfNotNull(o.strOrNull("subjectId")) },
                            )
                        }
                    }
                }

                Snapshot(
                    notif = notif,
                    wake = settings?.optInt("wake", 390) ?: 390,
                    sleep = settings?.optInt("sleep", 1380) ?: 1380,
                    days = root.optJSONObject("days") ?: JSONObject(),
                    tasks = root.optJSONObject("tasks") ?: JSONObject(),
                    recurring = root.optJSONArray("recurring") ?: JSONArray(),
                    subjects = subjects,
                    materials = materials,
                )
            } catch (e: JSONException) {
                EMPTY
            }
        }
    }
}
