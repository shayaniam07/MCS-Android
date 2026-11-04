package com.mcs.android

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private val Purple = Color(0xff9876ef)
private val CanvasBg = Color(0xff0b0d14)
private val Elevated = Color(0xff1d202b)
private val Cyan = Color(0xff33e4f2)
private val CaptionColors = listOf(AndroidColor.WHITE, AndroidColor.CYAN, AndroidColor.YELLOW, AndroidColor.MAGENTA)

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var license: LicenseManager
    private lateinit var narrator: Narrator
    private lateinit var exporter: MediaExporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        license = LicenseManager(this)
        narrator = Narrator(this)
        exporter = MediaExporter(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Purple, secondary = Cyan, background = CanvasBg,
                surface = Elevated, onSurface = Color.White, onBackground = Color.White
            )) {
                var verified by remember { mutableStateOf(license.verify()) }
                if (!verified.valid) {
                    ActivationScreen(license, verified.message) { key -> verified = license.activate(key) }
                } else {
                    MainEditor(license, verified.message, narrator, exporter)
                }
            }
        }
        license.touchIfValid()
    }
    override fun onDestroy() { narrator.close(); super.onDestroy() }
}

@Composable
private fun ActivationScreen(license: LicenseManager, message: String, onActivate: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(CanvasBg).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MCS", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Purple)
        Text("Mastery of Clips with Shayan", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        Text("Device ID", color = Cyan)
        Text(license.deviceId, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(license.deviceId)) }) { Text("Copy device ID") }
        Spacer(Modifier.height(14.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(key, { key = it }, label = { Text("License key for this phone") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onActivate(key) }, modifier = Modifier.fillMaxWidth()) { Text("Activate MCS Android") }
    }
}

@UnstableApi
@Composable
private fun MainEditor(
    license: LicenseManager,
    licenseMessage: String,
    narrator: Narrator,
    exporter: MediaExporter
) {
    val ctx = LocalContext.current
    var mainUri by remember { mutableStateOf<Uri?>(null) }
    var secondUri by remember { mutableStateOf<Uri?>(null) }
    var narrationUri by remember { mutableStateOf<Uri?>(null) }
    var mainTop by remember { mutableStateOf(true) }
    var aspect by remember { mutableStateOf("9:16") }
    var fitMain by remember { mutableStateOf(true) }
    var fitSecond by remember { mutableStateOf(true) }
    var captions by remember { mutableStateOf(listOf<CaptionCue>()) }
    var captionText by remember { mutableStateOf("") }
    var captionX by remember { mutableFloatStateOf(0.5f) }
    var captionY by remember { mutableFloatStateOf(0.74f) }
    var captionColor by remember { mutableIntStateOf(AndroidColor.WHITE) }
    var captionSize by remember { mutableIntStateOf(70) }
    var nav by remember { mutableStateOf("Studio") }
    var expanded by remember { mutableStateOf(false) }
    var showGuides by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Import a video to begin") }
    var exporting by remember { mutableStateOf(false) }
    var autoCuts by remember { mutableStateOf(emptyList<CutRange>()) }
    var analyzing by remember { mutableStateOf(false) }
    var trimStart by remember { mutableLongStateOf(0L) }
    var trimEnd by remember { mutableLongStateOf(0L) }
    var playhead by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf("") }
    var voiceText by remember { mutableStateOf("") }
    val mainPlayer = remember { ExoPlayer.Builder(ctx).build() }
    val secondPlayer = remember { ExoPlayer.Builder(ctx).build().apply {
        volume = 0f; repeatMode = Player.REPEAT_MODE_ALL
    } }

    fun persist(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val importMain = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(uri); mainUri = uri; autoCuts = emptyList(); trimStart = 0; trimEnd = 0; status = "Main video imported" }
    }
    val importSecond = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(uri); secondUri = uri; status = "Second video added to Studio Preview" }
    }
    val importVoice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(uri); narrationUri = uri; status = "Audio imported — choose Export to mix it with the video" }
    }
    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val parsed = EditorLogic.parseSrt(text)
                require(parsed.isNotEmpty()) { "No valid SRT cues found" }
                captions = parsed
                status = "Imported ${parsed.size} timed captions"
            }.onFailure { status = "Caption import failed: ${it.message}" }
        }
    }

    DisposableEffect(Unit) { onDispose { mainPlayer.release(); secondPlayer.release() } }
    LaunchedEffect(mainUri) {
        mainPlayer.stop()
        if (mainUri != null) { mainPlayer.setMediaItem(MediaItem.fromUri(mainUri!!)); mainPlayer.prepare() }
    }
    LaunchedEffect(secondUri) {
        secondPlayer.stop()
        if (secondUri != null) { secondPlayer.setMediaItem(MediaItem.fromUri(secondUri!!)); secondPlayer.prepare() }
    }
    LaunchedEffect(mainUri, secondUri) {
        while (true) {
            delay(100)
            playhead = mainPlayer.currentPosition.coerceAtLeast(0L)
            if (mainPlayer.duration > 0) duration = mainPlayer.duration
            playing = mainPlayer.isPlaying
            if (secondUri != null) {
                val expected = if (secondPlayer.duration > 0) mainPlayer.currentPosition % secondPlayer.duration
                    else mainPlayer.currentPosition
                val drift = abs(expected - secondPlayer.currentPosition)
                if (drift > 240) secondPlayer.seekTo(expected)
                if (mainPlayer.isPlaying && !secondPlayer.isPlaying) secondPlayer.play()
                if (!mainPlayer.isPlaying && secondPlayer.isPlaying) secondPlayer.pause()
            }
        }
    }

    val ratio = when (aspect) { "16:9" -> 16f / 9f; "1:1" -> 1f; else -> 9f / 16f }
    val visibleCaption = if (captions.isEmpty()) captionText else EditorLogic.activeCaption(captions, playhead)

    Column(Modifier.fillMaxSize().background(CanvasBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MCS", fontSize = 24.sp, color = Purple, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Mastery of Clips", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("ANDROID STUDIO · v0.1", fontSize = 9.sp, color = Color.LightGray)
            }
            Text(license.deviceId.takeLast(4), fontSize = 11.sp, color = Cyan)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Studio", "Captions", "Narration", "Script").forEach { tab ->
                FilterChip(
                    selected = nav == tab, onClick = { nav = tab }, label = { Text(tab, fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("STUDIO PREVIEW", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = true }) { Text("Enlarge", fontSize = 12.sp) }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                if (!expanded) {
                    VideoCanvas(mainPlayer, secondPlayer, mainUri != null, secondUri != null, mainTop, ratio,
                        fitMain, fitSecond, visibleCaption, captionX, captionY, captionColor,
                        showGuides, false, onCaptionMove = { x, y -> captionX = x; captionY = y })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(playhead), fontSize = 11.sp, color = Color.LightGray)
                Slider(value = playhead.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = { v -> mainPlayer.seekTo(v.toLong()); secondPlayer.seekTo(v.toLong()) },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f))
                Text(formatClock(duration), fontSize = 11.sp, color = Color.LightGray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (playing) { mainPlayer.pause(); secondPlayer.pause() }
                    else { mainPlayer.play(); if (secondUri != null) secondPlayer.play() } }, enabled = mainUri != null) {
                    Text(if (playing) "Pause" else "Play")
                }
                OutlinedButton(onClick = { trimStart = playhead }) { Text("Set In", fontSize = 11.sp) }
                OutlinedButton(onClick = { trimEnd = playhead }) { Text("Set Out", fontSize = 11.sp) }
                Text("${formatClock(trimStart)}–${if (trimEnd > trimStart) formatClock(trimEnd) else "End"}", fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            when (nav) {
                "Studio" -> {
                    SectionTitle("VIDEO EDITOR")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { importMain.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) { Text("Import main", fontSize = 12.sp) }
                        Button(onClick = { importSecond.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) { Text("+ Second video", fontSize = 12.sp) }
                    }
                    if (secondUri != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Main video on top", modifier = Modifier.weight(1f))
                            Switch(checked = mainTop, onCheckedChange = { mainTop = it })
                            TextButton(onClick = { secondUri = null }) { Text("Remove second") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("9:16", "16:9", "1:1").forEach { opt ->
                            FilterChip(selected = aspect == opt, onClick = { aspect = opt }, label = { Text(opt) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fit main (don't crop)", modifier = Modifier.weight(1f))
                        Switch(checked = fitMain, onCheckedChange = { fitMain = it })
                    }
                    if (secondUri != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fit second (don't crop)", modifier = Modifier.weight(1f))
                        Switch(checked = fitSecond, onCheckedChange = { fitSecond = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Caption safe-zone guides", modifier = Modifier.weight(1f))
                        Switch(checked = showGuides, onCheckedChange = { showGuides = it })
                    }
                    Text("Preview always shows the complete output canvas. Fit preserves the full frame; Fill deliberately crops.", color = Color.LightGray, fontSize = 12.sp)
                    SectionTitle("FREE AUTO CUT · SILENCE REMOVAL")
                    Button(onClick = {
                        val source = mainUri
                        if (source != null) {
                            analyzing = true; status = "Analyzing audio locally (no upload)…"
                            Thread {
                                val result = runCatching { SilenceAnalyzer(ctx).findAudibleSections(source) }
                                (ctx as? android.app.Activity)?.runOnUiThread {
                                    analyzing = false
                                    result.onSuccess { ranges ->
                                        autoCuts = ranges
                                        status = "AutoCut found ${ranges.size} audible sections; select Export MP4 to apply."
                                    }.onFailure { status = "AutoCut failed: ${it.message}" }
                                }
                            }.start()
                        }
                    }, enabled = mainUri != null && secondUri == null && !analyzing) {
                        Text(if (analyzing) "Analyzing…" else "Find silence cuts")
                    }
                    if (secondUri != null) Text("AutoCut can be applied to single-video exports only in this build.", color = Color.Gray, fontSize = 11.sp)
                    if (autoCuts.isNotEmpty()) {
                        Text("${autoCuts.size} audible clips · ${autoCuts.sumOf { it.durationMs } / 1000}s selected",
                            color = Cyan, fontSize = 12.sp)
                        TextButton(onClick = { autoCuts = emptyList() }) { Text("Discard AutoCut and export full/trimmed range") }
                    }
                }
                "Captions" -> {
                    SectionTitle("CAPTIONS / SHORTS")
                    OutlinedButton(onClick = { importSrt.launch(arrayOf("text/*", "application/x-subrip", "*/*")) }) { Text("Import timed captions (.srt)") }
                    OutlinedTextField(value = captionText, onValueChange = { captionText = it },
                        label = { Text("Manual caption / title") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Button(onClick = {
                            if (captionText.isNotBlank()) {
                                captions = captions + CaptionCue(playhead, playhead + 3000, captionText)
                                status = "Caption inserted at ${formatClock(playhead)}"
                            }
                        }) { Text("Add 3-second cue") }
                        TextButton(onClick = { captions = emptyList(); captionText = "" }) { Text("Clear cues") }
                    }
                    Text("Emoji stickers (native Android emoji):", color = Color.LightGray, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("🔥", "😂", "😱", "💀", "🚀", "✨").forEach { emoji ->
                            Text(emoji, fontSize = 25.sp, modifier = Modifier.clickable { captionText += emoji }.padding(3.dp))
                        }
                    }
                    Text("Caption color", color = Color.LightGray, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CaptionColors.forEach { color ->
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                                .background(Color(color)).border(if (captionColor == color) 3.dp else 1.dp,
                                    if (captionColor == color) Purple else Color.DarkGray, RoundedCornerShape(9.dp))
                                .clickable { captionColor = color })
                        }
                    }
                    Text("Caption font size: $captionSize px")
                    Slider(value = captionSize.toFloat(), onValueChange = { captionSize = it.toInt() }, valueRange = 30f..110f)
                    Text("${captions.size} timed captions · Drag text in the preview to position it.", fontSize = 12.sp, color = Cyan)
                    captions.take(10).forEach { cue ->
                        Text("${formatClock(cue.startMs)}  ${cue.text}", fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { mainPlayer.seekTo(cue.startMs) })
                    }
                }
                "Narration" -> {
                    SectionTitle("NARRATION · NO PAID API")
                    Text("Android system voice narration is available. Custom Chatterbox voice cloning is not ported yet.", fontSize = 12.sp, color = Color.LightGray)
                    OutlinedTextField(value = voiceText, onValueChange = { voiceText = it }, label = { Text("Narration script") },
                        modifier = Modifier.fillMaxWidth(), minLines = 5)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            status = "Generating Android TTS audio…"
                            narrator.synthesize(voiceText) { result ->
                                (ctx as? android.app.Activity)?.runOnUiThread {
                                    result.onSuccess { file -> narrationUri = Uri.fromFile(file); status = "Narration created: ${file.name}" }
                                        .onFailure { status = "Narration failed: ${it.message}" }
                                }
                            }
                        }, enabled = voiceText.isNotBlank()) { Text("Generate narration", fontSize = 12.sp) }
                        OutlinedButton(onClick = { importVoice.launch(arrayOf("audio/*")) }) { Text("Import audio", fontSize = 12.sp) }
                    }
                    if (narrationUri != null) {
                        Text("Audio ready to add to export: ${narrationUri?.lastPathSegment}", color = Cyan, fontSize = 12.sp)
                        TextButton(onClick = { narrationUri = null }) { Text("Remove narration") }
                    }
                }
                else -> {
                    SectionTitle("SCRIPT TO VIDEO · STORYBOARD")
                    Text("Script segmentation is offline. Supply your own media for full video creation.", fontSize = 12.sp, color = Color.LightGray)
                    OutlinedTextField(value = script, onValueChange = { script = it }, label = { Text("Paste script") },
                        modifier = Modifier.fillMaxWidth(), minLines = 6)
                    val scenes = remember(script) { EditorLogic.splitScript(script) }
                    Text("${script.split(Regex("\\s+")).count { it.isNotBlank() }} words → ${scenes.size} scenes (max 30; no missing words)", color = Cyan)
                    scenes.take(30).forEach { scene ->
                        Text("Scene ${scene.number} · ${scene.estimatedMs / 1000}s · ${scene.text.take(110)}…",
                            fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Divider(color = Color.DarkGray)
            Text("EXPORT MP4", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("Exports the selected range, captions and optional narration. Split-screen preview uses two players; Media3 composes the MP4.", color = Color.LightGray, fontSize = 11.sp)
            Button(onClick = {
                val main = mainUri
                if (main != null) {
                    exporting = true; status = "Exporting MP4…"
                    exporter.export(MediaExporter.Request(
                        main = main, second = secondUri, mainOnTop = mainTop, aspect = aspect,
                        fillMain = !fitMain, fillSecond = !fitSecond,
                        startMs = trimStart, endMs = trimEnd, captions = captions.ifEmpty {
                            if (captionText.isNotBlank()) listOf(CaptionCue(0, Long.MAX_VALUE, captionText)) else emptyList()
                        }, captionX = captionX, captionY = captionY, captionColor = captionColor,
                        captionSizePx = captionSize, narration = narrationUri,
                        autoCuts = if (secondUri == null) autoCuts else emptyList()
                    )) { result ->
                        exporting = false
                        result.onSuccess { uri -> status = "Saved to Movies/MCS: $uri" }
                            .onFailure { status = "Export failed: ${it.message}" }
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = mainUri != null && !exporting) {
                Text(if (exporting) "Exporting…" else "Export selected MP4 to Gallery")
            }
            Text(status, color = if (status.contains("failed", true)) Color(0xffef9090) else Cyan,
                fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            Text("License: $licenseMessage · Android ID ${license.deviceId}", color = Color.Gray, fontSize = 10.sp)
            Spacer(Modifier.height(22.dp))
        }
    }

    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(CanvasBg).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("FULL OUTPUT FRAME", color = Cyan, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { expanded = false }) { Text("Done") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    VideoCanvas(mainPlayer, secondPlayer, mainUri != null, secondUri != null,
                        mainTop, ratio, fitMain, fitSecond, visibleCaption,
                        captionX, captionY, captionColor, showGuides, true,
                        onCaptionMove = { x, y -> captionX = x; captionY = y })
                }
                Text("Drag the caption inside the actual output frame. The entire 9:16 canvas stays visible.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Purple, modifier = Modifier.padding(vertical = 10.dp))
}

private fun formatClock(ms: Long): String = "%.1fs".format(Locale.US, ms.coerceAtLeast(0).toFloat() / 1000f)

@UnstableApi
@Composable
private fun VideoCanvas(
    mainPlayer: ExoPlayer,
    secondPlayer: ExoPlayer,
    hasMain: Boolean,
    hasSecond: Boolean,
    mainTop: Boolean,
    ratio: Float,
    fitMain: Boolean,
    fitSecond: Boolean,
    caption: String,
    captionX: Float,
    captionY: Float,
    captionColor: Int,
    guides: Boolean,
    big: Boolean,
    onCaptionMove: (Float, Float) -> Unit
) {
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var textSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(Modifier.fillMaxWidth().then(if (big) Modifier.fillMaxHeight() else Modifier.heightIn(max = 370.dp)), contentAlignment = Alignment.Center) {
        val outputHeight: Dp = minOf(if (big) maxHeight else 370.dp, maxWidth / ratio)
        val outputWidth: Dp = outputHeight * ratio
        Box(Modifier.size(outputWidth, outputHeight).background(Color.Black)
            .border(1.dp, Color(0xff4e5162)).onSizeChanged { contentSize = it }) {
            if (!hasMain) {
                Text("Import a video to preview", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            } else if (hasSecond) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        VideoLayer(if (mainTop) mainPlayer else secondPlayer, if (mainTop) fitMain else fitSecond)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        VideoLayer(if (mainTop) secondPlayer else mainPlayer, if (mainTop) fitSecond else fitMain)
                    }
                }
            } else {
                VideoLayer(mainPlayer, fitMain)
            }
            if (guides) Canvas(Modifier.fillMaxSize()) {
                val guideColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f)
                val insetX = size.width * .07f
                val insetY = size.height * .12f
                drawRect(guideColor, topLeft = androidx.compose.ui.geometry.Offset(insetX, insetY),
                    size = androidx.compose.ui.geometry.Size(size.width - insetX * 2, size.height - insetY * 2),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
            }
            if (caption.isNotBlank()) {
                Text(
                    caption, color = Color(captionColor), fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center, fontSize = if (big) 22.sp else 17.sp, lineHeight = if (big) 26.sp else 20.sp,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (contentSize.width * captionX).toInt() - textSize.width / 2,
                                (contentSize.height * captionY).toInt() - textSize.height / 2
                            )
                        }
                        .onSizeChanged { textSize = it }
                        .background(Color.Black.copy(alpha = .76f), RoundedCornerShape(6.dp))
                        .pointerInput(contentSize) {
                            detectDragGestures { change, delta ->
                                change.consume()
                                if (contentSize.width > 0 && contentSize.height > 0) {
                                    onCaptionMove(
                                        EditorLogic.clampNormalized(captionX + delta.x / contentSize.width),
                                        EditorLogic.clampNormalized(captionY + delta.y / contentSize.height)
                                    )
                                }
                            }
                        }
                        .padding(7.dp)
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun VideoLayer(player: ExoPlayer, fit: Boolean) {
    AndroidView(
        factory = { ctx -> LayoutInflater.from(ctx).inflate(R.layout.mcs_player_view, null, false) as PlayerView },
        update = { playerView ->
            playerView.player = player
            playerView.resizeMode = if (fit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        },
        modifier = Modifier.fillMaxSize()
    )
}
