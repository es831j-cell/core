package com.distressedelk.lumi;

import android.content.SharedPreferences;

import java.util.Locale;

/** Compact before/after counters for release effectiveness in Black Box exports. */
final class BlackBoxEffectiveness {
    private BlackBoxEffectiveness() {}

    static void captureReleaseBaseline(SharedPreferences p, long versionCode) {
        if (p == null || versionCode <= 0L) return;
        long capturedFor = p.getLong("effectiveness_baseline_version", -1L);
        if (capturedFor == versionCode) return;
        p.edit()
                .putLong("effectiveness_baseline_version", versionCode)
                .putLong("effectiveness_baseline_at", System.currentTimeMillis())
                .putInt("effectiveness_base_speech_rebuilds", p.getInt("speech_recognizer_rebuilds", 0))
                .putInt("effectiveness_base_tts_recoveries", p.getInt("tts_watchdog_recoveries", 0))
                .putInt("effectiveness_base_echo_suppressed", p.getInt("echo_suppressed_count", 0))
                .putInt("effectiveness_base_fast_prompt_misses", p.getInt("fast_brain_prompt_quality_misses", 0))
                .putInt("effectiveness_base_runtime_recoveries", p.getInt("runtime_stall_recoveries", 0))
                .putInt("effectiveness_base_owner_accepted", p.getInt("owner_accepted", 0))
                .putInt("effectiveness_base_known_accepted", p.getInt("known_speaker_accepted", 0))
                .putInt("effectiveness_base_anonymous_accepted", p.getInt("active_anonymous_speaker_accepted", 0))
                .putInt("effectiveness_base_keyboard_ambient_rejected", p.getInt("keyboard_ambient_rejected", 0))
                .putInt("effectiveness_base_handoff_blocked", p.getInt("speaker_handoff_blocked", 0))
                .putInt("effectiveness_base_owner_fallback", p.getInt("owner_continuity_fallback", 0))
                .putInt("effectiveness_base_foreground_unverified", p.getInt("active_foreground_unverified_accepted", 0))
                .putInt("effectiveness_base_foreground_unknown", p.getInt("active_foreground_unknown_accepted", 0))
                .putInt("effectiveness_base_media_rejected", p.getInt("media_or_background_rejected", 0))
                .putInt("effectiveness_base_self_rejected", p.getInt("self_audio_rejected", 0))
                .putInt("effectiveness_base_wake_rejected", p.getInt("unverified_wake_rejected", 0))
                .putInt("effectiveness_base_privilege_blocked", p.getInt("unknown_speaker_privilege_blocked", 0)+p.getInt("known_speaker_privilege_blocked", 0))
                .putInt("effectiveness_base_barge_in", p.getInt("spoken_barge_in_count", 0))
                .putInt("effectiveness_base_circuit_breaks", p.getInt("recognizer_restart_circuit_breaks", 0))
                .putLong("effectiveness_base_latency_ms", p.getLong("last_response_latency_ms", -1L))
                .apply();
    }

    static String summary(SharedPreferences p) {
        if (p == null) return "No effectiveness data available.";
        long version = p.getLong("effectiveness_baseline_version", -1L);
        long at = p.getLong("effectiveness_baseline_at", 0L);
        long lastLatency = p.getLong("last_response_latency_ms", -1L);
        int speech = delta(p.getInt("speech_recognizer_rebuilds", 0), p.getInt("effectiveness_base_speech_rebuilds", 0));
        int tts = delta(p.getInt("tts_watchdog_recoveries", 0), p.getInt("effectiveness_base_tts_recoveries", 0));
        int echoes = delta(p.getInt("echo_suppressed_count", 0), p.getInt("effectiveness_base_echo_suppressed", 0));
        int promptMiss = delta(p.getInt("fast_brain_prompt_quality_misses", 0), p.getInt("effectiveness_base_fast_prompt_misses", 0));
        int runtime = delta(p.getInt("runtime_stall_recoveries", 0), p.getInt("effectiveness_base_runtime_recoveries", 0));
        int webSources = p.getInt("last_web_source_count", 0);
        float webConfidence = p.getFloat("last_web_evidence_confidence", 0f);
        long totalTurn = p.getLong("functional_core_last_total_turn_ms", -1L);
        long postTts = p.getLong("functional_core_last_post_tts_actual_handoff_ms", -1L);
        String functionalRoute = p.getString("functional_core_last_route", "not-yet-instrumented");
        String infoRoute = p.getString("last_info_gathering_route", "not-yet-instrumented");
        String infoReason = p.getString("last_info_gathering_reason", "");
        String liveProvider = p.getString("last_live_provider", "");
        String liveError = p.getString("last_live_tool_error", "");
        int ownerAccepted=delta(p.getInt("owner_accepted",0),p.getInt("effectiveness_base_owner_accepted",0));
        int knownAccepted=delta(p.getInt("known_speaker_accepted",0),p.getInt("effectiveness_base_known_accepted",0));
        int anonymousAccepted=delta(p.getInt("active_anonymous_speaker_accepted",0),p.getInt("effectiveness_base_anonymous_accepted",0));
        int keyboardAmbientRejected=delta(p.getInt("keyboard_ambient_rejected",0),p.getInt("effectiveness_base_keyboard_ambient_rejected",0));
        int handoffBlocked=delta(p.getInt("speaker_handoff_blocked",0),p.getInt("effectiveness_base_handoff_blocked",0));
        int ownerFallback=delta(p.getInt("owner_continuity_fallback",0),p.getInt("effectiveness_base_owner_fallback",0));
        int activeUnverified=delta(p.getInt("active_foreground_unverified_accepted",0),p.getInt("effectiveness_base_foreground_unverified",0));
        int activeUnknown=delta(p.getInt("active_foreground_unknown_accepted",0),p.getInt("effectiveness_base_foreground_unknown",0));
        int legacyActive=p.getInt("active_conversation_unverified_accepted",0)+p.getInt("active_conversation_unknown_accepted",0);
        int mediaRejected=delta(p.getInt("media_or_background_rejected",0),p.getInt("effectiveness_base_media_rejected",0));
        int wakeRejected=delta(p.getInt("unverified_wake_rejected",0),p.getInt("effectiveness_base_wake_rejected",0));
        int selfRejected=delta(p.getInt("self_audio_rejected",0),p.getInt("effectiveness_base_self_rejected",0));
        int privilegeBlocked=delta(p.getInt("unknown_speaker_privilege_blocked",0)+p.getInt("known_speaker_privilege_blocked",0),p.getInt("effectiveness_base_privilege_blocked",0));
        int bargeIn=delta(p.getInt("spoken_barge_in_count",0),p.getInt("effectiveness_base_barge_in",0));
        int circuitBreaks=delta(p.getInt("recognizer_restart_circuit_breaks",0),p.getInt("effectiveness_base_circuit_breaks",0));
        String priorAudio=p.getString("audio_gate_last_category","none");
        boolean currentAudioSession=DeveloperFlightRecorder.currentSessionId().equals(p.getString("audio_gate_last_session_id",""));
        String lastAudio=currentAudioSession?priorAudio:"none observed in current recorder session (prior="+priorAudio+")";
        int lastSpeakerConfidence=currentAudioSession?p.getInt("audio_gate_last_confidence",0):0;
        return "Release baseline code: " + version + " • capturedAt=" + at + "\n"
                + "Last response latency: " + lastLatency + " ms • instrumented total turn: " + totalTurn + " ms\n"
                + "Since this release: speech rebuilds +" + speech + " • TTS watchdog recoveries +" + tts
                + " • runtime recoveries +" + runtime + " • echo suppressions +" + echoes + "\n"
                + "Fast Brain prompt-quality misses +" + promptMiss + "\n"
                + "Last functional route: " + functionalRoute + " • web sources=" + webSources
                + " • evidence confidence=" + String.format(Locale.US, "%.2f", webConfidence) + "\n"
                + "Information gathering: " + infoRoute
                + (infoReason.isEmpty()?"":" • "+infoReason)
                + (liveProvider.isEmpty()?"":" • liveProvider="+liveProvider)
                + (liveError.isEmpty()?"":" • lastLiveError="+bounded(liveError,220)) + "\n"
                + "Audio gate since this release: owner="+ownerAccepted+" • known="+knownAccepted+" • anonymous="+anonymousAccepted+" • owner-fallback="+ownerFallback
                + " • foreground-unverified accepted="+activeUnverified+" • foreground-unknown accepted="+activeUnknown
                + " • keyboard ambient rejected="+keyboardAmbientRejected+" • handoff blocked="+handoffBlocked
                + " • media/background rejected="+mediaRejected+" • unverified wake rejected="+wakeRejected
                + " • self-audio rejected="+selfRejected+" • privilege blocks="+privilegeBlocked+" • barge-ins="+bargeIn+"\n"
                + "Legacy pre-Code369 active accepts (lifetime history)="+legacyActive+"\n"
                + "Recognizer automatic-restart circuit breaks since release="+circuitBreaks+"\n"
                + "Last audio classification: "+lastAudio+" • speaker confidence="+lastSpeakerConfidence+"%\n"
                + "Last post-TTS listening handoff: " + postTts + " ms";
    }

    private static int delta(int current, int baseline) { return Math.max(0, current - baseline); }
    private static String bounded(String s,int max){ if(s==null)return ""; String v=s.replace('\n',' ').replace('\r',' ').trim(); return v.length()<=max?v:v.substring(0,max); }
}
