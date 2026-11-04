import com.mcs.android.*

fun main() {
    val s = "1\n00:00:01,200 --> 00:00:03,500\nHello from MCS!\n\n2\n00:00:03,500 --> 00:00:05,000\nSecond caption\n"
    val cues = EditorLogic.parseSrt(s)
    check(cues.size == 2 && cues[0].startMs == 1200L)
    check(EditorLogic.activeCaption(cues, 2500L) == "Hello from MCS!")
    check(EditorLogic.activeCaption(cues, 3500L) == "Second caption")
    check(EditorLogic.parseSrt(EditorLogic.toSrt(cues)) == cues)
    val original = (1..2500).joinToString(" ") { "word$it" }
    val scenes = EditorLogic.splitScript(original)
    check(scenes.size in 1..30)
    check(scenes.sumOf { it.text.split(' ').size } == 2500)
    check(scenes.first().number == 1)
    check(EditorLogic.frameScale(1080,1920,1080,960,false) == 0.5f)
    check(EditorLogic.frameScale(1080,1920,1080,960,true) == 1f)
    println("PASS: SRT parsing, cue timing, SRT roundtrip, 2500-word no-loss 30-scene limit, fit/fill")
}
