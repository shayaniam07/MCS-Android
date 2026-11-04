package com.mcs.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MCSProject(
    val name: String,
    val mainUri: String,
    val secondUri: String,
    val narrationUri: String,
    val mainOnTop: Boolean,
    val aspect: String,
    val fitMain: Boolean,
    val fitSecond: Boolean,
    val cues: List<CaptionCue>,
    val clips: List<ClipSegment>,
    val captionX: Float,
    val captionY: Float,
    val captionColor: Int,
    val captionSize: Int,
    val script: String,
    val voiceText: String
)

/** App-private project files; the source media itself is never copied or overwritten. */
class ProjectStore(context: Context) {
    private val folder = File(context.filesDir, "mcs_projects").also { it.mkdirs() }
    private fun safe(name: String): String = name.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        .take(55).ifBlank { "Untitled" }
    private fun file(name: String): File = File(folder, safe(name) + ".json")

    fun names(): List<String> = folder.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.sortedByDescending { it.lastModified() }?.map { it.name.removeSuffix(".json") } ?: emptyList()

    fun save(project: MCSProject) {
        val json = JSONObject().apply {
            put("format", "mcs-android-project-v1")
            put("name", project.name)
            put("main", project.mainUri)
            put("second", project.secondUri)
            put("narration", project.narrationUri)
            put("mainTop", project.mainOnTop)
            put("aspect", project.aspect)
            put("fitMain", project.fitMain)
            put("fitSecond", project.fitSecond)
            put("captionX", project.captionX.toDouble())
            put("captionY", project.captionY.toDouble())
            put("captionColor", project.captionColor)
            put("captionSize", project.captionSize)
            put("script", project.script)
            put("voiceText", project.voiceText)
            put("cues", JSONArray().also { a -> project.cues.forEach { cue ->
                a.put(JSONObject().put("start", cue.startMs).put("end", cue.endMs).put("text", cue.text))
            } })
            put("clips", JSONArray().also { a -> project.clips.forEach { clip ->
                a.put(JSONObject().put("start", clip.startMs).put("end", clip.endMs)
                    .put("label", clip.label).put("selected", clip.selected))
            } })
        }
        val target = file(project.name)
        val tmp = File(folder, target.name + ".tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun load(name: String): MCSProject {
        val json = JSONObject(file(name).readText(Charsets.UTF_8))
        require(json.optString("format") == "mcs-android-project-v1") { "Unsupported project format" }
        val cues = json.optJSONArray("cues") ?: JSONArray()
        val clips = json.optJSONArray("clips") ?: JSONArray()
        return MCSProject(
            name = json.optString("name", name),
            mainUri = json.optString("main", ""),
            secondUri = json.optString("second", ""),
            narrationUri = json.optString("narration", ""),
            mainOnTop = json.optBoolean("mainTop", true),
            aspect = json.optString("aspect", "9:16"),
            fitMain = json.optBoolean("fitMain", true),
            fitSecond = json.optBoolean("fitSecond", true),
            cues = (0 until cues.length()).map { i -> cues.getJSONObject(i) }.map { o ->
                CaptionCue(o.optLong("start"), o.optLong("end"), o.optString("text"))
            },
            clips = (0 until clips.length()).map { i -> clips.getJSONObject(i) }.map { o ->
                ClipSegment(o.optLong("start"), o.optLong("end"), o.optString("label"), o.optBoolean("selected", true))
            },
            captionX = json.optDouble("captionX", 0.5).toFloat(),
            captionY = json.optDouble("captionY", 0.74).toFloat(),
            captionColor = json.optInt("captionColor", -1),
            captionSize = json.optInt("captionSize", 70),
            script = json.optString("script"),
            voiceText = json.optString("voiceText")
        )
    }

    fun delete(name: String): Boolean = file(name).delete()
}
