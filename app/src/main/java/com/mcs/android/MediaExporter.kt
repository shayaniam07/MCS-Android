package com.mcs.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.common.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.VideoCompositorSettings
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Uses Media3 for real MP4 export. No fake/placeholder MP4 output. */
@UnstableApi
class MediaExporter(private val context: Context) {
    data class Request(
        val main: Uri,
        val second: Uri?,
        val mainOnTop: Boolean,
        val aspect: String,
        val fillMain: Boolean,
        val fillSecond: Boolean,
        val startMs: Long,
        val endMs: Long,
        val captions: List<CaptionCue>,
        val captionX: Float,
        val captionY: Float,
        val captionColor: Int,
        val captionSizePx: Int,
        val narration: Uri? = null,
        val autoCuts: List<CutRange> = emptyList()
    )

    private var transformer: Transformer? = null
    private val exporting = AtomicBoolean(false)

    fun export(request: Request, callback: (Result<Uri>) -> Unit) {
        if (!exporting.compareAndSet(false, true)) {
            callback(Result.failure(IllegalStateException("Another export is running")))
            return
        }
        val tmp = File(context.cacheDir, "mcs_export_${System.currentTimeMillis()}.mp4")
        try {
            val media = MediaItem.Builder().setUri(request.main)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(request.startMs.coerceAtLeast(0))
                        .apply { if (request.endMs > request.startMs) setEndPositionMs(request.endMs) }
                        .build()
                ).build()
            val useCuts = if (request.second == null) request.autoCuts else emptyList()
            val mainEdits = if (useCuts.isNotEmpty()) {
                useCuts.map { r ->
                    val cut = MediaItem.Builder().setUri(request.main)
                        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(r.startMs).setEndPositionMs(r.endMs).build()).build()
                    EditedMediaItem.Builder(cut).build()
                }
            } else listOf(EditedMediaItem.Builder(media).build())
            val extra = request.second
            val overlay = object : TextOverlay() {
                override fun getText(presentationTimeUs: Long): SpannableString {
                    // export timestamps are relative to the selected clip, adjust to original source timeline
                    val outputMs = presentationTimeUs / 1000L
                    var tMs = request.startMs + outputMs
                    if (useCuts.isNotEmpty()) {
                        var accumulated = 0L
                        for (cut in useCuts) {
                            if (outputMs < accumulated + cut.durationMs) {
                                tMs = cut.startMs + (outputMs - accumulated)
                                break
                            }
                            accumulated += cut.durationMs
                        }
                    }
                    val raw = EditorLogic.activeCaption(request.captions, tMs)
                    val text = SpannableString(raw)
                    if (raw.isNotEmpty()) {
                        text.setSpan(StyleSpan(Typeface.BOLD), 0, raw.length, 0)
                        text.setSpan(ForegroundColorSpan(request.captionColor), 0, raw.length, 0)
                        text.setSpan(BackgroundColorSpan(Color.BLACK), 0, raw.length, 0)
                        text.setSpan(AbsoluteSizeSpan(request.captionSizePx), 0, raw.length, 0)
                    }
                    return text
                }

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                    StaticOverlaySettings.Builder()
                        .setBackgroundFrameAnchor(request.captionX * 2f - 1f, 1f - request.captionY * 2f)
                        .build()
            }
            val effect = OverlayEffect(listOf(overlay))
            val mainSeq = EditedMediaItemSequence.withAudioAndVideoFrom(mainEdits)
            val sequences = mutableListOf(mainSeq)
            val outSize = when (request.aspect) {
                "16:9" -> Size(1280, 720)
                "1:1" -> Size(960, 960)
                else -> Size(720, 1280)
            }
            if (extra != null) {
                val secondEdit = EditedMediaItem.Builder(MediaItem.fromUri(extra)).setRemoveAudio(true).build()
                val secondSeq = EditedMediaItemSequence.withVideoFrom(listOf(secondEdit))
                    .buildUpon().setIsLooping(true).build()
                sequences.add(secondSeq)
            }
            if (request.narration != null) {
                val voiceEdit = EditedMediaItem.Builder(MediaItem.fromUri(request.narration)).build()
                sequences.add(EditedMediaItemSequence.withAudioFrom(listOf(voiceEdit)))
            }
            val composite = Composition.Builder(sequences)
            if (extra != null) {
                composite.setVideoCompositorSettings(
                    SplitLayout(outSize, request.mainOnTop, request.fillMain, request.fillSecond)
                )
            }
            if (extra == null) {
                val ratio = outSize.width.toFloat() / outSize.height
                val fitOrCrop = if (request.fillMain) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    else Presentation.LAYOUT_SCALE_TO_FIT
                composite.setEffects(Effects(emptyList(), listOf(Presentation.createForAspectRatio(ratio, fitOrCrop), effect)))
            } else {
                composite.setEffects(Effects(emptyList(), listOf(effect)))
            }
            val composition = composite.build()
            transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        Thread {
                            val result = runCatching { saveToGallery(tmp) }
                            tmp.delete()
                            (context as? android.app.Activity)?.runOnUiThread {
                                exporting.set(false); callback(result)
                            } ?: run { exporting.set(false); callback(result) }
                        }.start()
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        tmp.delete(); exporting.set(false)
                        callback(Result.failure(exportException))
                    }
                }).build()
            transformer!!.start(composition, tmp.absolutePath)
        } catch (t: Throwable) {
            tmp.delete(); exporting.set(false); callback(Result.failure(t))
        }
    }

    private fun saveToGallery(src: File): Uri {
        if (!src.exists() || src.length() < 1024) throw IOException("Media3 did not create a playable-sized MP4")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MCS_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MCS")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create video destination in Movies/MCS")
        return try {
            context.contentResolver.openOutputStream(uri)?.use { dst -> FileInputStream(src).use { it.copyTo(dst) } }
                ?: throw IOException("Cannot write exported video")
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (ex: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw ex
        }
    }

    private class SplitLayout(
        private val size: Size,
        private val mainOnTop: Boolean,
        private val fillMain: Boolean,
        private val fillSecond: Boolean
    ) : VideoCompositorSettings {
        private var input: List<Size> = emptyList()
        override fun getOutputSize(inputSizes: List<Size>): Size {
            input = inputSizes
            return size
        }
        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            val source = input.getOrNull(inputId) ?: size
            val shouldFill = if (inputId == 0) fillMain else fillSecond
            val scale = EditorLogic.frameScale(source.width, source.height, size.width, size.height / 2, shouldFill)
            val top = if (inputId == 0) mainOnTop else !mainOnTop
            // BackgroundFrameAnchor coordinates: -1 bottom, +1 top.
            return StaticOverlaySettings.Builder()
                .setScale(scale, scale)
                .setOverlayFrameAnchor(0f, 0f)
                .setBackgroundFrameAnchor(0f, if (top) 0.5f else -0.5f)
                .build()
        }
    }
}
