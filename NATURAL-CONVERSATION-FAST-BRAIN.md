# Lumi 2.0 Natural Conversation / Fast Brain pass

This build is deliberately optimized to test conversational latency before adding more security and avatar complexity.

## Startup

Fresh install now requires only the official Qwen3 0.6B Q4_K_M Fast Brain (~397 MB). After checksum verification, Lumi opens immediately. Administrator Enrollment is available later from Settings and does not block startup.

## One Lumi, multiple gears

- Rules-first instant path: tiny common conversational acknowledgments and existing local device actions.
- Fast Brain: Qwen3 0.6B Q4_K_M, 512-token runtime context, short output budget, `/no_think` for ordinary conversation.
- Deep Brain asset: existing Qwen3 4B Q4_K_M, optional ~2.5 GB download. In this latency build it is storage-only while safe single-engine model switching is completed.
- Remote booster: remains optional and is used for harder requests when configured.

All active routes use the same Lumi identity, transcript, memory and permissions. The model is an internal gear, not a separate assistant.

## Latency behavior

Voice conversations schedule a short natural acknowledgment after ~450 ms only if the real response is still pending. Simple greetings and acknowledgments can use the rules-first instant path. The Fast Brain is the only local model session kept loaded in this speed build, so ordinary conversation never loads the 4B model.

The current free `llama-android:0.1.1` API returns a completed response rather than token streaming. Therefore this build does not falsely claim true token-by-token local generation streaming. The immediate acknowledgment plus 0.6B model are the current latency strategy. True generation streaming requires a runtime that exposes token callbacks/Flow.

## Interruption behavior

Typed input and the one-shot microphone button stop current TTS immediately before accepting a new turn. Full hands-free barge-in while TTS is speaking still needs an echo-controlled duplex audio pipeline; Android SpeechRecognizer is not represented as providing that reliably in this build.

## Administrator setup

PIN + face + voice enrollment remains implemented and can be started from Settings. It is intentionally deferred while response latency is being tuned. Captured face/voice samples remain enrollment references, not production biometric matching.
