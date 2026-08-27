package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Code383 forensic synthesis. Converts raw Black Box puzzle pieces into causal incidents instead
 * of merely counting warnings. The report is deterministic and based only on observable events.
 */
final class FullRemediationDiagnostics {
    private FullRemediationDiagnostics(){}

    static String report(Context c,SharedPreferences p){
        String raw=DeveloperFlightRecorder.readTail(c,2*1024*1024);
        String current=DeveloperFlightRecorder.currentSessionId();
        int blocked=countCurrent(raw,current,"BLOCKED_DURING_TTS");
        int speakerRejected=countCurrent(raw,current,"SPEAKER_REJECTED");
        int bgRejected=countCurrent(raw,current,"MEDIA_OR_BACKGROUND_REJECTED");
        int stateDivergence=countCurrent(raw,current,"STATE_DIVERGENCE");
        int readyDeaf=countCurrent(raw,current,"READY_BUT_DEAF");
        int circuit=countCurrent(raw,current,"RECOVERY_CIRCUIT_OPEN");
        int ttsWatch=countCurrent(raw,current,"WATCHDOG_START_TIMEOUT")+countCurrent(raw,current,"WATCHDOG_COMPLETION_TIMEOUT");
        int selfEchoRejected=countCurrent(raw,current,"SELF_AUDIO_REJECTED");
        int selfEchoAccepted=countCurrent(raw,current,"SELF_AUDIO_ACCEPTED");
        int pcmInsufficient=countCurrent(raw,current,"SPEAKER_PCM_INSUFFICIENT");
        int sttEvents=countCurrent(raw,current,"\"category\":\"STT\"");
        int ttsEvents=countCurrent(raw,current,"\"category\":\"TTS\"");
        int replyProof=p==null?0:Math.max(0,p.getInt("tts_reply_successes",0)-p.getInt("full_remediation_base_tts_success",0));
        int bargeProof=p==null?0:Math.max(0,p.getInt("spoken_barge_in_count",0)-p.getInt("full_remediation_base_barge_in",0));
        int ownerProof=p==null?0:Math.max(0,p.getInt("owner_accepted",0)-p.getInt("full_remediation_base_owner_accept",0));
        long latency=p==null?-1L:p.getLong("last_response_latency_ms",-1L);
        boolean relay=false; boolean relayPreflight=p!=null&&p.getBoolean("build_relay_last_preflight_ok",false);
        try{ relay=p!=null && TrustedBuildRelayClient.status(p).optBoolean("configured",false); }catch(Throwable ignored){}
        String provider=p==null?"unknown":CloudBrainRouter.healthSummary(p);
        String assistant="unknown";
        try{ assistant=android.provider.Settings.Secure.getString(c.getContentResolver(),"assistant"); if(assistant==null||assistant.trim().isEmpty()) assistant="not set"; }catch(Throwable ignored){}

        StringBuilder s=new StringBuilder();
        s.append("FORENSIC INCIDENT ASSEMBLY\n");
        observedIncident(s,"S1","Conversation/TTS-STT race",blocked>=3,replyProof>0 || ttsEvents>0,
                "BLOCKED_DURING_TTS current-session events="+blocked,
                "single-generation state machine + non-recursive post-TTS handoff");
        observedIncident(s,"S1","Owner/barge-in admission failure",speakerRejected>0,bargeProof>0 || speakerRejected>0,
                "speaker-rejected barge-in events="+speakerRejected+" successfulBargeInsThisAcceptanceWindow="+bargeProof+" lifetimeAccepted="+(p==null?0:p.getInt("spoken_barge_in_count",0)),
                "non-privileged STOP interrupts immediately; protected actions authenticate after TTS stops");
        observedIncident(s,"S1","Owner voice evidence unavailable from recognizer",pcmInsufficient>0,ownerProof>0 || pcmInsufficient>0,
                "turns with insufficient live PCM="+pcmInsufficient+" ownerAccepts="+ownerProof+" lastBytes="+(p==null?0:p.getInt("speaker_last_live_pcm_bytes",0)),
                "explicitly surfaced; owner biometric certification remains pending instead of silently treating 0% confidence as owner");
        verificationIncident(s,"S2","Speech classified as background/media",bgRejected>0,
                "background/media rejections in active session="+bgRejected,
                "Rejection is correct for TV/background audio. Correlate rejected transcript + direct-address lease + owner evidence before calling it a lease defect.");
        observedIncident(s,"S1","Recognizer READY-but-deaf",readyDeaf>0,sttEvents>0,
                "confirmed READY-but-deaf incidents="+readyDeaf+" STT events="+sttEvents,
                "RMS/activity-backed detection and recognizer rebuild; silence alone is no longer mislabeled deafness");
        observedIncident(s,"S1","Recognizer recovery circuit opened",circuit>0,sttEvents>0,
                "recovery circuit events="+circuit+" STT events="+sttEvents,
                "bounded restart circuit remains fail-safe and is surfaced as a real fault");
        observedIncident(s,"S1","Conversation state divergence",stateDivergence>0,sttEvents>0 || ttsEvents>0,
                "state divergence events="+stateDivergence,
                "visuals are driven from the authoritative runtime state and Speaking requires active TTS");
        observedIncident(s,"S2","TTS watchdog instability",ttsWatch>0,replyProof>0 || ttsEvents>0,
                "TTS watchdog events="+ttsWatch+" cleanReplies="+replyProof,
                "watchdog retained; state generation prevents stale retries from resurrecting output");
        observedIncident(s,"S1","Self-audio/echo promoted as user input",selfEchoAccepted>0,replyProof>0 || selfEchoRejected>0,
                "self-audio accepts="+selfEchoAccepted+" safelyRejected="+selfEchoRejected,
                "self audio must be rejected before turn promotion");
        s.append("INFO PROTECTED • self-audio safely rejected=").append(selfEchoRejected).append("\n");
        observedIncident(s,"S2","External AI latency",latency>5200L,latency>=0L,
                "last response latency="+latency+" ms",
                "Fast Brain hedge shortened to 700 ms; provider failure cooldown/ordering retained and unhealthy providers demoted");
        incident(s,"S1","Trusted build relay incomplete",!relay||!relayPreflight,
                "trusted relay configured="+relay+" load-test="+relayPreflight+" stage="+(p==null?"unknown":p.getString("trusted_core_build_stage","IDLE")),
                "one-time commissioning proves private repo, push, Actions dispatch/signing-secret presence, Lumi verification and storage before any verified source ZIP crosses the relay");
        boolean relayTxExercised=p!=null && (p.getBoolean("trusted_core_build_active",false) || p.getLong("trusted_core_build_completed_at",0L)>0L
                || !p.getString("update_tx_last_stage","").isEmpty());
        observedIncident(s,"S1","Bridge-core update transaction stalled",p!=null&&p.getBoolean("trusted_core_build_active",false)&&p.getInt("trusted_core_build_poll_failures",0)>=3,relayTxExercised,
                "stage="+(p==null?"unknown":p.getString("trusted_core_build_stage","IDLE"))+" retries="+(p==null?0:p.getInt("trusted_core_build_poll_failures",0)),
                "durable stage/commit/run reconciliation plus bounded exponential retry; authentication/policy faults fail closed");
        boolean lumiAssistant=assistant.toLowerCase(Locale.US).contains("com.distressedelk.lumi");
        incident(s,"S2","Android default-assistant ownership",!lumiAssistant,
                "secure assistant setting="+assistant,
                "external Android role/configuration; Lumi will not silently steal the system assistant role");
        if(provider.toLowerCase(Locale.US).contains("failed")) s.append("S2 ACTIVE • Provider degradation • ").append(provider).append("\n");
        s.append("Acceptance gate\n").append(FullRemediationAcceptance.report(c,p)).append("\n");
        return s.toString().trim();
    }

    static int healthSeverity(Context c,SharedPreferences p){
        String raw=DeveloperFlightRecorder.readTail(c,2*1024*1024); String current=DeveloperFlightRecorder.currentSessionId();
        if(countCurrent(raw,current,"BLOCKED_DURING_TTS")>=3 || countCurrent(raw,current,"SPEAKER_REJECTED")>0
                || countCurrent(raw,current,"SPEAKER_PCM_INSUFFICIENT")>0 || countCurrent(raw,current,"READY_BUT_DEAF")>0
                || countCurrent(raw,current,"RECOVERY_CIRCUIT_OPEN")>0 || countCurrent(raw,current,"STATE_DIVERGENCE")>0
                || countCurrent(raw,current,"SELF_AUDIO_ACCEPTED")>0) return 2;
        boolean relay=false,preflight=p!=null&&p.getBoolean("build_relay_last_preflight_ok",false);
        try{relay=p!=null&&TrustedBuildRelayClient.status(p).optBoolean("configured",false);}catch(Throwable ignored){}
        if(!relay||!preflight) return 2;
        if(p!=null&&p.getBoolean("trusted_core_build_active",false)&&p.getInt("trusted_core_build_poll_failures",0)>=3) return 2;
        long latency=p==null?-1L:p.getLong("last_response_latency_ms",-1L);
        if(countCurrent(raw,current,"WATCHDOG_START_TIMEOUT")+countCurrent(raw,current,"WATCHDOG_COMPLETION_TIMEOUT")>0 || latency>5200L) return 1;
        return 0;
    }

    private static void verificationIncident(StringBuilder s,String severity,String title,boolean observed,String evidence,String next){
        s.append(severity).append(' ').append(observed?"VERIFY":"CLEAR").append(" • ").append(title)
                .append(" • evidence: ").append(evidence).append(" • next: ").append(next).append("\n");
    }

    private static void observedIncident(StringBuilder s,String severity,String title,boolean active,boolean exercised,String evidence,String remediation){
        String state=active?"ACTIVE":(exercised?"CLEAR":"UNTESTED");
        s.append(severity).append(' ').append(state).append(" • ").append(title)
                .append(" • evidence: ").append(evidence).append(" • remediation: ").append(remediation).append("\n");
    }

    private static void incident(StringBuilder s,String severity,String title,boolean active,String evidence,String remediation){
        s.append(severity).append(' ').append(active?"ACTIVE":"CLEAR").append(" • ").append(title)
                .append(" • evidence: ").append(evidence).append(" • remediation: ").append(remediation).append("\n");
    }

    private static int countCurrent(String raw,String session,String token){
        if(raw==null || raw.isEmpty() || token==null || token.isEmpty()) return 0;
        int n=0;
        for(String line:raw.split("\\r?\\n")) if(line.contains(token) && (session==null || session.isEmpty() || line.contains("\"sessionId\":\""+session+"\""))) n++;
        return n;
    }
}
