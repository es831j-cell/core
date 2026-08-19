# Lumi 2.0 Conversation Core Development Update

This is an install-over development update. It intentionally freezes avatar feature expansion while the conversation engine is stabilized.

## Primary changes

- Development home visual changed to a Möbius-strip Lumi Core image.
- Fast Brain prompt no longer includes visible mode/outfit/profile instructions.
- Added defensive filtering for internal-state narration such as "I am in home mode" or "I should answer...".
- Normal conversation defaults to brief, direct responses and `/no_think`.
- Quick human acknowledgements are now occasional rather than automatic on every slow turn.
- Conversation preference changes can be made in natural language, including "talk less", "respond faster", "be more proactive", and "stop the little cues".
- Remote/cloud booster failures silently fall back to the local Fast Brain when available instead of announcing network failure first.
- Stale model replies are discarded if a newer user turn supersedes them.
- Added operational status answers for questions such as "why are you taking so long?", "what model are you using?", and "why did you do that?".
- During model generation, speech recognition is restarted so the owner can ask a live-status question or redirect the conversation instead of waiting for the model to finish.
- Added Conversation Diagnostics screen, lightweight core self-test, event log, and exportable plain-text diagnostic report.
- The diagnostics report includes app/device information, network state, brain status, last route, last measured latency, self-test result, and event log.

## Conversation test target

The current model runtime does not yet support token-streamed TTS, so this update does not claim ChatGPT-class streaming speech. True barge-in while Lumi is already speaking is also still limited because the recognizer is cancelled during TTS to avoid hearing Lumi's own voice. The purpose of this build is to make the current local runtime measurable and usable enough to diagnose the next latency bottleneck with real exported data.

## Administrator setup

Administrator enrollment remains deferred and does not block conversation testing.

## Update behavior

Package/application ID and existing signing material are preserved. Version code is increased so APK Factory should create an APK that installs over the current Lumi build and preserves app data/model files.
