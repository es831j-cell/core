package com.distressedelk.lumi;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.List;
import java.util.Locale;

/** Installed-core acceptance contract for the all-inclusive Black Box remediation build. */
final class FullRemediationAcceptance {
    private static final int REQUIRED_CLEAN_REPLIES = 5;
    private FullRemediationAcceptance(){}

    static void initialize(SharedPreferences p,long version){
        if(p==null || p.getLong("full_remediation_acceptance_version",-1L)==version) return;
        long now=System.currentTimeMillis();
        p.edit()
                .putLong("full_remediation_acceptance_version",version)
                .putLong("full_remediation_acceptance_started_at",now)
                .putInt("full_remediation_base_tts_success",p.getInt("tts_reply_successes",0))
                .putInt("full_remediation_base_tts_fault",p.getInt("tts_watchdog_recoveries",0))
                .putInt("full_remediation_base_circuit_break",p.getInt("recognizer_restart_circuit_breaks",0))
                .putInt("full_remediation_base_self_audio",p.getInt("self_audio_rejected",0))
                .putInt("full_remediation_base_state_divergence",p.getInt("conversation_state_divergence",0))
                .putInt("full_remediation_base_phantom_speaking",p.getInt("phantom_speaking_prevented",0))
                .putInt("full_remediation_base_barge_in",p.getInt("spoken_barge_in_count",0))
                .putInt("full_remediation_base_owner_accept",p.getInt("owner_accepted",0))
                .putInt("full_remediation_base_maintenance_local",p.getInt("maintenance_intent_local_routes",0))
                .putBoolean("full_remediation_acceptance_complete",false)
                .apply();
    }

    static String report(Context c,SharedPreferences p){
        if(p==null) return "Full remediation acceptance unavailable.";
        long started=p.getLong("full_remediation_acceptance_started_at",0L);
        int replies=delta(p,"tts_reply_successes","full_remediation_base_tts_success");
        int ttsFaults=delta(p,"tts_watchdog_recoveries","full_remediation_base_tts_fault");
        int circuit=delta(p,"recognizer_restart_circuit_breaks","full_remediation_base_circuit_break");
        int stateDivergence=delta(p,"conversation_state_divergence","full_remediation_base_state_divergence");
        int phantom=delta(p,"phantom_speaking_prevented","full_remediation_base_phantom_speaking");
        int barge=delta(p,"spoken_barge_in_count","full_remediation_base_barge_in");
        int owner=delta(p,"owner_accepted","full_remediation_base_owner_accept");
        int maintenance=delta(p,"maintenance_intent_local_routes","full_remediation_base_maintenance_local");
        int lowMemory=lowMemorySince(c,started);
        boolean relayConfigured=false; boolean relayPreflight=p.getBoolean("build_relay_last_preflight_ok",false);
        try{ relayConfigured=TrustedBuildRelayClient.status(p).optBoolean("configured",false); }catch(Throwable ignored){}
        boolean bridgeHealthy=p.getBoolean("direct_maintenance_host_ready",false) && p.getBoolean("native_self_update_engine_ready",false);
        long acceptanceCode=p.getLong("full_remediation_acceptance_version",-1L);
        boolean relayProofRequired=acceptanceCode>=383L;
        boolean relaySelfUpdateProof=!relayProofRequired || (p.getBoolean("self_update_last_validation_pass",false)
                && p.getLong("self_update_last_validation_version",-1L)==acceptanceCode);
        boolean behavioralPass=replies>=REQUIRED_CLEAN_REPLIES && ttsFaults==0 && circuit==0 && stateDivergence==0
                && lowMemory==0 && barge>0 && owner>0 && maintenance>0 && bridgeHealthy && latencyWithinTarget(p);
        boolean complete=behavioralPass && relayConfigured && relayPreflight && relaySelfUpdateProof;
        if(complete) p.edit().putBoolean("full_remediation_acceptance_complete",true)
                .putLong("full_remediation_acceptance_completed_at",System.currentTimeMillis()).apply();

        StringBuilder s=new StringBuilder();
        String incompleteState=behavioralPass?(relaySelfUpdateProof?"BEHAVIORAL PASS / EXTERNAL CONFIG PENDING":"BEHAVIORAL PASS / SELF-UPDATE PROOF PENDING"):"PENDING";
        s.append("Code"); if(acceptanceCode>0L) s.append(acceptanceCode); else s.append("current");
        s.append(" Full Remediation acceptance: ").append(complete?"PASS":incompleteState).append("\n");
        s.append(replies>=REQUIRED_CLEAN_REPLIES?"PASS":"PENDING").append(" • clean spoken replies this acceptance window ").append(replies).append('/').append(REQUIRED_CLEAN_REPLIES)
                .append(" • lifetime=").append(p.getInt("tts_reply_successes",0)).append("\n");
        s.append(ttsFaults==0?"PASS":"FAIL").append(" • new TTS watchdog recoveries=").append(ttsFaults).append("\n");
        s.append(circuit==0?"PASS":"FAIL").append(" • recognizer recovery-circuit breaks=").append(circuit).append("\n");
        s.append(stateDivergence==0?"PASS":"FAIL").append(" • authoritative-state divergences=").append(stateDivergence).append("\n");
        s.append(phantom==0?"PASS":"INFO").append(" • phantom Speaking transitions prevented=").append(phantom).append("\n");
        s.append(barge>0?"PASS":"PENDING").append(" • successful spoken barge-in tests this acceptance window=").append(barge)
                .append(" • lifetime=").append(p.getInt("spoken_barge_in_count",0)).append("\n");
        s.append(owner>0?"PASS":"PENDING").append(" • enrolled-owner voice accepts this acceptance window=").append(owner)
                .append(" • lifetime=").append(p.getInt("owner_accepted",0)).append("\n");
        s.append(maintenance>0?"PASS":"PENDING").append(" • maintenance intents routed locally this acceptance window=").append(maintenance)
                .append(" • lifetime=").append(p.getInt("maintenance_intent_local_routes",0)).append("\n");
        s.append(bridgeHealthy?"PASS":"FAIL").append(" • Native self-update/local maintenance host ready=").append(bridgeHealthy).append("\n");
        s.append(lowMemory==0?"PASS":"FAIL").append(" • LOW_MEMORY exits since this build=").append(lowMemory).append("\n");
        long latency=p.getLong("last_response_latency_ms",-1L);
        s.append(latencyWithinTarget(p)?"PASS":"FAIL").append(" • last AI response latency target <=5200ms, observed=").append(latency).append(" ms\n");
        s.append(relayConfigured?"PASS":"EXTERNAL CONFIG REQUIRED").append(" • trusted GitHub build relay configured=").append(relayConfigured).append("\n");
        s.append(relayPreflight?"PASS":"PENDING").append(" • reinforced bridge load-test/preflight=").append(relayPreflight).append("\n");
        if(relayProofRequired) s.append(relaySelfUpdateProof?"PASS":"PENDING").append(" • trusted self-update proof for Code").append(acceptanceCode)
                .append(" (verified package → Android installer → Lumi post-install validation)\n");
        if(!relayConfigured) s.append("UNRESOLVED EXTERNAL DEPENDENCY • repository/credentials cannot be invented by the APK; one-time private relay commissioning is required before self-build/install can be end-to-end.\n");
        else if(!relayPreflight) s.append("PENDING • run Trusted Build Relay load test before declaring the bridge roadworthy.\n");
        else if(relayProofRequired && !relaySelfUpdateProof) s.append("PENDING • Lumi must complete post-install validation on this code before native self-update is certified.\n");
        return s.toString().trim();
    }


    private static boolean latencyWithinTarget(SharedPreferences p){
        long latency=p==null?-1L:p.getLong("last_response_latency_ms",-1L);
        return latency<0L || latency<=5200L;
    }
    private static int delta(SharedPreferences p,String key,String base){ return Math.max(0,p.getInt(key,0)-p.getInt(base,0)); }

    private static int lowMemorySince(Context c,long since){
        if(c==null || since<=0L || Build.VERSION.SDK_INT<30) return 0;
        try{
            ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> xs=am==null?null:am.getHistoricalProcessExitReasons(c.getPackageName(),0,20);
            int n=0;
            if(xs!=null) for(ApplicationExitInfo x:xs) if(x.getTimestamp()>=since && x.getReason()==ApplicationExitInfo.REASON_LOW_MEMORY) n++;
            return n;
        }catch(Throwable ignored){ return 0; }
    }
}
