# Lumi 2.0 — Conversation Core Dev Update

This update prioritizes a usable, measurable conversation foundation before more avatar/features are added. See `CORE-CONVERSATION-DEV-UPDATE.md` for the change list and `UPDATE-ONLY.txt` for install guidance.

# Lumi 2.0 Photo Modes Update

This package is an install-over update for the current Lumi 2.0 line. See `UPDATE-ONLY.txt`.

# Lumi Version 2.0 — Natural Fast Brain Test Build

Package: `com.distressedelk.lumi`  
Visible version: `2.0`  
Android versionCode: `207`

This is the full Lumi 2.0 Android source project for the current speed-first conversation pass.

## Fresh install flow

1. Install and open Lumi.
2. Download the official `Qwen/Qwen3-0.6B-GGUF` `Q4_K_M` Fast Brain, about 397 MB.
3. Lumi verifies the pinned SHA-256 before activating it.
4. Lumi opens directly into normal conversation.
5. Administrator Enrollment is available later in Settings and does not block testing.
6. The optional Qwen3 4B Deep Brain can be added later from Integration Center.

## Brain team

Fast Brain: `Qwen3-0.6B-Q4_K_M.gguf`  
Pinned SHA-256: `b0638f08417a2d3c8652760462eb5407c6e30173cf9608ad0820757a281eea0e`

Deep Brain: `Qwen3-4B-Q4_K_M.gguf`  
Pinned SHA-256: `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5`

Runtime: `dev.ffmpegkit-maintained:llama-android:0.1.1`.

Normal conversation uses the Fast Brain with `/no_think`, a small context window, short response budget and a battery/thermal-aware thread budget. The free local runtime is kept to one loaded model in this speed build. Harder prompts use the optional remote booster when configured; otherwise the Fast Brain gives a concise first pass. The 4B file can be stored as a future Deep Brain asset, but concurrent local 0.6B + 4B inference is deliberately disabled until safe model switching is implemented.

## Conversation changes in this build

- Speed-first 0.6B local conversation model, prewarmed at app startup
- 4B model removed from mandatory first-run flow
- Administrator PIN/face/voice enrollment deferred until requested
- Rules-first instant responses for basic greetings/acknowledgments
- Short natural spoken acknowledgment if model generation is still pending after ~450 ms
- Shorter rolling context and output budget for normal conversation
- `/no_think` enforced for normal conversation and `<think>` output stripped defensively
- Typed or one-shot-mic interruption stops Lumi's current TTS
- Status language changed from long-running “Thinking…” to a softer “With you…” / “Working…”
- Same Lumi identity/memory/context across instant, fast and optional remote paths

## Existing Lumi 2.0 systems retained

- Full-screen avatar-first home
- Updated Home/Public/Private visual mode assets
- Hands-free SpeechRecognizer + Android TTS loop
- Persistent conversation memory and learned preferences
- People Cards and relationship notes
- Private Mode / Lumi Vault shell
- Battery-aware AI policy
- Connectivity / glasses test surfaces
- Backup/restore, appearance, device-health and integration screens
- Optional remote open-model and cloud-provider paths
- Administrator PIN + face-reference + voice-reference flow, now launched later from Settings

## Important implementation boundaries

The free llama-android 0.1.1 API used here returns completed responses and does not expose token streaming. This build therefore does not claim true token-by-token local streaming. It uses a much smaller model and a quick spoken acknowledgment to reduce perceived delay.

Full hands-free barge-in while Lumi is speaking still requires a reliable duplex/echo-controlled audio pipeline. Typed input and the one-shot microphone can interrupt TTS now.

Production biometric face/speaker matching, full animated 3D lip-sync avatar, direct custom Ray-Ban Meta wake/camera integration, gallery face recognition and production web/image search remain future integrations rather than fake-complete features.

## Build

Use this project ZIP with the existing APK Factory workflow. The package/signing identity is retained for upgrade installs. A clean install downloads only the ~397 MB Fast Brain initially. Administrator Enrollment remains available later in Settings.
