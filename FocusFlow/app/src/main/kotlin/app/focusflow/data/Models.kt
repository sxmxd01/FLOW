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
) {
    val end: Int get() = start + dur
    val isFixed: Boolean get() = type == BlockType.FIXED
}

data class NotifPrefs(
    val start: Boolean = true,
    val end: Boolean = true,
    val leadMin: Int = 0,
    val fixed: Boolean = true,
)

private data class SubjectInfo(val name: String, val color: Int)
private data class MaterialInfo(val color: Int, val subjectId: String?)

private const val DEFAULT_COLOR = 0xFF7A8194.toInt()
private const val FIXED_COLOR = 0xFF7A8499.toInt()

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
    private val tasks: JSONObject,
    private val recurring: JSONArray,
    private val subjects: Map<String, SubjectInfo>,
    private val materials: Map<String, MaterialInfo>,
) {

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
                title = r.optString("title").ifEmpty { "Class" },
                start = r.optInt("start"),
                dur = r.optInt("dur", 60),
                done = false,
                pct = 0,
                color = FIXED_COLOR,
                subjectName = null,
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
        val subj = (o.strOrNull("subjectId") ?: mat?.subjectId)?.let { subjects[it] }
        val color = if (fixed) FIXED_COLOR else (mat?.color ?: subj?.color ?: DEFAULT_COLOR)
        val title = o.optString("title").ifEmpty { if (fixed) "Fixed block" else "Study" }
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
            subjectName = subj?.name,
            recurringId = null,
        )
    }

    companion object {
        val EMPTY = Snapshot(NotifPrefs(), JSONObject(), JSONArray(), emptyMap(), emptyMap())

        fun parse(json: String?): Snapshot {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                val root = JSONObject(json)
                val n = root.optJSONObject("settings")?.optJSONObject("notif")
                val notif = NotifPrefs(
                    start = n?.optBoolean("start", true) ?: true,
                    end = n?.optBoolean("end", true) ?: true,
                    leadMin = (n?.optInt("lead", 0) ?: 0).coerceIn(0, 120),
                    fixed = n?.optBoolean("fixed", true) ?: true,
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
                                o.strOrNull("subjectId"),
                            )
                        }
                    }
                }

                Snapshot(
                    notif = notif,
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
