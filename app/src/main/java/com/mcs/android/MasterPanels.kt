package com.mcs.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val Accent = Color(0xffaa84ff)
private val Muted = Color(0xffb6b0c8)
private val Panel = Color(0xff1d202a)

private fun at(ms: Long): String = String.format(Locale.US, "%.2f", ms.coerceAtLeast(0L) / 1000.0)

@Composable
fun MCSClipsPanel(
    hasVideo: Boolean,
    durationMs: Long,
    playheadMs: Long,
    clips: List<ClipSegment>,
    hasTranscript: Boolean,
    onFixed: (Int) -> Unit,
    onSpeech: () -> Unit,
    onChange: (List<ClipSegment>) -> Unit,
    onJump: (Long, Long) -> Unit,
    onExportChecked: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    Text("CLIP MANAGER", color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Text("Make short clips from the full video, or group timed captions into speech clips. Edit, review and bulk-export selected clips.", color = Muted, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(30, 60, 90).forEach { seconds ->
            OutlinedButton(onClick = { onFixed(seconds); page = 0; selectedIndex = -1 }, enabled = hasVideo, contentPadding = PaddingValues(horizontal = 11.dp)) {
                Text(seconds.toString() + "s")
            }
        }
    }
    Button(onClick = { onSpeech(); page = 0; selectedIndex = -1 }, enabled = hasVideo && hasTranscript) {
        Text("Generate shorts from transcript")
    }
    if (!hasTranscript) Text("Import an SRT transcript first to enable speech-based clip grouping.", color = Muted, fontSize = 11.sp)
    val count = clips.count { it.selected }
    Text(clips.size.toString() + " clips  •  " + count + " checked", color = Accent)
    if (clips.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onChange(clips.map { it.copy(selected = true) }) }) { Text("Check all") }
            TextButton(onClick = { onChange(clips.map { it.copy(selected = false) }) }) { Text("Uncheck all") }
            TextButton(onClick = { onChange(emptyList()); page = 0 }) { Text("Clear") }
        }
        Button(onClick = onExportChecked, enabled = count > 0 && hasVideo, modifier = Modifier.fillMaxWidth()) {
            Text("Bulk export " + count + " selected MP4 clips")
        }
    }
    val visible = clips.withIndex().drop(page * 12).take(12)
    visible.forEach { (index, clip) ->
        var a by remember(index, clip.startMs) { mutableStateOf(at(clip.startMs)) }
        var b by remember(index, clip.endMs) { mutableStateOf(at(clip.endMs)) }
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = clip.selected, onCheckedChange = { checked ->
                    onChange(clips.toMutableList().also { it[index] = clip.copy(selected = checked) })
                })
                Column(Modifier.weight(1f)) {
                    Text(clip.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(at(clip.startMs) + "s to " + at(clip.endMs) + "s", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { selectedIndex = index; onJump(clip.startMs, clip.endMs) }) { Text("Review") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(a, { a = it }, label = { Text("In (s)") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                OutlinedTextField(b, { b = it }, label = { Text("Out (s)") }, singleLine = true,
                    modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val begin = ((a.toDoubleOrNull() ?: -1.0) * 1000).toLong()
                    val finish = ((b.toDoubleOrNull() ?: -1.0) * 1000).toLong()
                    if (begin >= 0 && finish > begin + 500 && finish <= durationMs)
                        onChange(clips.toMutableList().also { it[index] = clip.copy(startMs = begin, endMs = finish) })
                }) { Text("Apply trim") }
                TextButton(onClick = {
                    onChange(ClipLogic.split(clips, index, playheadMs))
                    selectedIndex = -1
                }) { Text("Split at playhead") }
                TextButton(onClick = { onChange(clips.filterIndexed { i, _ -> i != index }) }) { Text("Delete") }
            }
        }
    }
    if (clips.size > 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Text("Previous") }
            Text("Page " + (page + 1) + " / " + ((clips.size + 11) / 12), color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { page = (page + 1).coerceAtMost((clips.size - 1) / 12) },
                enabled = page < (clips.size - 1) / 12) { Text("Next") }
        }
    }
    if (selectedIndex >= 0) Text("Reviewing clip " + (selectedIndex + 1) + ". Play or adjust in the shared studio preview above.", color = Muted, fontSize = 11.sp)
}

@Composable
fun MCSTranscriptPanel(
    cues: List<CaptionCue>,
    durationMs: Long,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onUpdate: (List<CaptionCue>) -> Unit,
    onJump: (Long) -> Unit,
    onGenerate: () -> Unit,
    scriptAvailable: Boolean
) {
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    Text("TRANSCRIPT & TIMED CAPTIONS", fontWeight = FontWeight.Bold, color = Accent, fontSize = 16.sp)
    Text("Edit subtitle text and timing from an SRT file. This version does not automatically transcribe speech.", color = Muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import SRT") }
        OutlinedButton(onClick = onExport, enabled = cues.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Save SRT") }
    }
    Button(onClick = { onGenerate(); page = 0 }, enabled = scriptAvailable && durationMs > 0) {
        Text("Make approximate captions from script")
    }
    Text("Timing created from a script is estimated, NOT auto-synced speech recognition.", color = Muted, fontSize = 11.sp)
    Text(cues.size.toString() + " caption segments", color = Accent)
    OutlinedTextField(search, { search = it; page = 0 }, label = { Text("Find transcript text") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    val entries = cues.withIndex().filter { search.isBlank() || it.value.text.contains(search, ignoreCase = true) }
    entries.drop(page * 15).take(15).forEach { (index, cue) ->
        var a by remember(index, cue.startMs) { mutableStateOf(at(cue.startMs)) }
        var b by remember(index, cue.endMs) { mutableStateOf(at(cue.endMs)) }
        var line by remember(index, cue.text) { mutableStateOf(cue.text) }
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cue " + (index + 1), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onJump(cue.startMs) }) { Text("Seek") }
            }
            OutlinedTextField(line, { line = it }, label = { Text("Caption text") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a, { a = it }, label = { Text("Start (s)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(b, { b = it }, label = { Text("End (s)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row {
                TextButton(onClick = {
                    val begin = ((a.toDoubleOrNull() ?: -1.0) * 1000).toLong()
                    val finish = ((b.toDoubleOrNull() ?: -1.0) * 1000).toLong()
                    if (begin >= 0 && finish > begin && line.isNotBlank())
                        onUpdate(cues.toMutableList().also { it[index] = CaptionCue(begin, finish, line) }.sortedBy { it.startMs })
                }) { Text("Save changes") }
                TextButton(onClick = { onUpdate(cues.filterIndexed { i, _ -> i != index }) }) { Text("Delete cue") }
            }
        }
    }
    if (entries.size > 15) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Text("Previous") }
            Text("Page " + (page + 1), color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { page = (page + 1).coerceAtMost((entries.size - 1) / 15) },
                enabled = page < (entries.size - 1) / 15) { Text("Next") }
        }
    }
}

@Composable
fun MCSProjectsPanel(
    names: List<String>,
    hasVideo: Boolean,
    onSave: (String) -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember { mutableStateOf("My MCS project") }
    Text("MY PROJECTS", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Text("Save an editable project with video links, caption cues, shorts, layout, narration, and script. Original media stays on the phone.", color = Muted, fontSize = 12.sp)
    OutlinedTextField(name, { name = it }, label = { Text("Project name") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { onSave(name) }, enabled = hasVideo && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save current project") }
    Spacer(Modifier.height(12.dp))
    Text("SAVED PROJECTS (" + names.size + ")", color = Accent, fontWeight = FontWeight.SemiBold)
    if (names.isEmpty()) Text("No projects saved yet.", color = Muted)
    names.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Panel, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(item.replace('_', ' '), modifier = Modifier.weight(1f), maxLines = 2)
            TextButton(onClick = { onLoad(item) }) { Text("Open") }
            TextButton(onClick = { onDelete(item) }) { Text("Delete") }
        }
    }
    Text("If a video was deleted or moved, choose it again before exporting. Projects are stored locally in this app.", color = Muted, fontSize = 11.sp)
}
