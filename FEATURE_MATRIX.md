# MCS Android — honest feature / compatibility matrix

This is an independently authored Android project; it does not mutate Windows app files.
It is a **development build**, NOT an existing full-feature parity release.
The Media3 APK has NOT been compiled or tested on a physical Android phone in this environment.

| Feature from Windows MCS | Android project status | Notes |
|---|---|---|
| Brand & launcher icon | Implemented in source | Derived from user's original MCS logo |
| License key + device ID | Implemented format/Ed25519 verification | Android gets its OWN device ID; production requires public key copied; Cloudflare server revoke/poll not wired |
| Local video import | Implemented in source | Android SAF document picker |
| Live main-video preview | Implemented in source | ExoPlayer, no file uploads |
| Import secondary video | Implemented in source | Two texture-view players, second muted |
| Top/bottom split 9:16 | Implemented in source | Fit/Fill; Media3 export experimental, requires device validation |
| 9:16 / 16:9 / 1:1 preview | Implemented in source | Whole output canvas fits within view; enlarged modal |
| Drag caption, safe zones | Implemented in source | Dragging normalized coordinates; simplified compared with Windows animation styles |
| Import/edit timed SRT | Implemented in source | Time-based imported cues; add manual 3-second captions |
| Color and emoji picker | Implemented in source | Android's own Unicode emoji graphics, NOT Apple proprietary artwork |
| Android TTS voiceover | Implemented in source | System voices, synthesize to WAV, optional mix into MP4 |
| Import prerecorded audio | Implemented in source | Selected narration audio becomes another mix track |
| Trimming clips & MP4 export | Implemented in source | Media3 Transformer; device codec and version may affect result |
| Free audio-energy AutoCut | Implemented in source | Detects audible segments, can export stitched main-video sections; NOT AI semantic highlights |
| Script 2 video storyboard | Implemented in source | Max 30 scenes, no words dropped; does NOT generate footage automatically |
| Offline Whisper ASR | NOT implemented | Requires NDK and whisper.cpp integration, models and timing pipeline |
| Custom Chatterbox voice cloning | NOT implemented | Windows worker not compatible with Android; mobile inference redesign needed |
| Auto word-level captions | NOT implemented | Import SRT works; Whisper+alignment pending |
| Animated caption presets | PARTIAL | Static caption overlay; pop-in / active word animations not yet ported |
| Full asset-backed script-to-video MP4 | NOT implemented | Current script tab is a storyboard, not scene renderer |
| License server online revocation | NOT implemented | Server URL, request/response contract, and D1 compatibility must be connected; no admin secret embedded |
| Saved projects / timeline persistence | NOT implemented | Current editor state is session-scoped |
| Live iOS Apple emoji art | NOT implemented | Redistributing Apple's emoji artwork isn't authorized here |
| Higgsfield paid service | Intentionally excluded | Free-only requirement |

## Known risks and previously observed MCS problems addressed

1. **Preview aspect:** canvas preserves exact chosen ratio and can be expanded. It does not fill-crop the outer preview by accident.
2. **Split screen:** second video appears in editor itself, not in a separate export-only dialog.
3. **Caption location:** normalized drag position shared with export overlay. Actual exported alignment needs physical-device test.
4. **Scene explosion:** long scripts split to no more than 30 scenes without deleting words.
5. **Frozen voice worker / package metadata (`requests`):** avoided because Android does not package PyInstaller/Chatterbox worker.
6. **Large EXE/AI models:** no 8–16GB Windows package or model caches in APK.
7. **License identity:** never reuse Windows hardware ID on the phone; new Android-specific signed key.
8. **GitHub upload:** only Android source (small). CI can assemble a *debug* APK if dependencies resolve.
9. **Space:** project only megabytes; Android Studio/SDK downloads still require local space if built locally.

## Validation actually performed

- Compiled independent `EditorLogic.kt` with `kotlinc` and ran tests for SRT parsing, timing, no-loss 2,500-word scene splitting, Fit/Fill scale math.
- Inspected existing Windows licensing format and MCS source folders.
- Static project packaging checks.
- **Not validated:** Android Gradle build, UI on phone, codec/Media3 exports, license key with a real Android device, offline ASR, or cloned-voice output.
