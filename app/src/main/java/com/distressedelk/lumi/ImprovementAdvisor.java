package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Stabilization-era deterministic Improvement Advisor.
 *
 * The advisor observes health and diagnostics, verifies bounded runtime repairs, and records
 * recommendations for the next owner-approved bridge-core release transaction. It does not bypass core-build
 * requests, patch source, or install a compiled Lumi release.
 */
public final class ImprovementAdvisor {
    private static final int MAX_VISIBLE = 5;
    private ImprovementAdvisor() {}

    /** Code350: invalidate the owner-visible command snapshot whenever runtime facts change. */
    public static void invalidate(SharedPreferences p, String reason){
        if(p==null) return;
        p.edit().remove("improvement_advisor_last_json")
                .remove("improvement_advisor_last_report")
                .remove("improvement_advisor_snapshot_id")
                .putLong("improvement_advisor_last_scan_at",0L)
                .putLong("improvement_advisor_invalidated_at",System.currentTimeMillis())
                .putString("improvement_advisor_invalidation_reason",reason==null?"runtime-state-changed":reason)
                .apply();
    }

    private static final class Candidate {
        final String key, title, evidence, benefit, risk, action;
        final int priority, impact, riskScore, confidence;
        final boolean writeRequired;
        Candidate(String key,String title,int priority,String evidence,String benefit,String risk,String action,boolean writeRequired){
            this.key=key; this.title=title; this.priority=priority; this.evidence=evidence;
            this.benefit=benefit; this.risk=risk; this.action=action; this.writeRequired=writeRequired;
            this.impact=Math.max(0,Math.min(100,priority+(writeRequired?2:-4)));
            this.riskScore=Math.max(1,Math.min(100,(writeRequired?34:8)+(risk.toLowerCase(Locale.US).contains("high")?38:risk.toLowerCase(Locale.US).contains("low")?-8:0)));
            this.confidence=Math.max(50,Math.min(99,priority+(evidence.length()>40?3:0)-(risk.toLowerCase(Locale.US).contains("unknown")?12:0)));
        }
        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("key",key).put("title",title).put("priority",priority).put("impact",impact).put("riskScore",riskScore).put("confidence",confidence)
                    .put("evidence",evidence).put("benefit",benefit).put("risk",risk)
                    .put("action",action).put("writeRequired",writeRequired);
        }
    }

    public static String scan(Context c, SharedPreferences p){
        return scanInternal(c,p,true);
    }

    /** Background/diagnostic scans may observe and advance verification state, but must not
     * silently renumber the suggestion list the owner is currently acting on. */
    public static String scanDiagnostic(Context c, SharedPreferences p){
        return scanInternal(c,p,false);
    }

    private static String scanInternal(Context c, SharedPreferences p, boolean publishCommandSnapshot){
        final long now=System.currentTimeMillis();
        List<Candidate> out=new ArrayList<>();

        try{
            if(!CanonicalSourceManager.isHealthy(c,p)){
                out.add(new Candidate("canonical-source-health","Restore canonical source-of-truth integrity",100,
                        CanonicalSourceManager.statusSummary(c,p).replace('\n',' '),
                        "Prevents a future core update from overwriting changes whose source was not preserved.",
                        "Core updates remain blocked until the source snapshot matches the installed APK.","canonical_source_recheck",false));
            }
        }catch(Throwable ignored){}

        long acceptanceVersion=p.getLong("full_remediation_acceptance_version",-1L);
        if(acceptanceVersion>=381L && !p.getBoolean("full_remediation_acceptance_complete",false)){
            String acceptance=FullRemediationAcceptance.report(c,p).replace('\n',' ');
            if(acceptance.length()>520) acceptance=acceptance.substring(0,520);
            out.add(new Candidate("full-remediation-acceptance","Complete Code"+acceptanceVersion+" full-remediation acceptance",99,
                    acceptance,
                    "Prevents a source-only or one-shot test from being called fixed before the installed phone proves conversation, identity, barge-in, maintenance, latency, memory and the trusted self-update path.",
                    "Verification/configuration only; no hidden self-modification.","full_remediation_verify",false));
        }
        int stateDiv=Math.max(0,p.getInt("conversation_state_divergence",0)-p.getInt("full_remediation_base_state_divergence",0));
        if(stateDiv>0) out.add(new Candidate("conversation-state-divergence","Repair authoritative conversation-state divergence",100,
                "Code"+acceptanceVersion+" recorded "+stateDiv+" state divergence(s).",
                "Stops visuals, TTS and STT from disagreeing about whether Lumi is listening, thinking or speaking.",
                "Requires source-level diagnosis before another signed core build.","conversation_state_review",true));

        int ttsRecoveries=p.getInt("tts_watchdog_recoveries",0);
        long lastTtsFault=p.getLong("last_tts_watchdog_at",0L);
        int speechRebuilds=p.getInt("speech_recognizer_rebuilds",0);
        int callbackStalls=p.getInt("speech_recognizer_callback_stalls",0);
        int postTtsRebuilds=p.getInt("post_tts_recognizer_rebuilds",0);
        long lastRepairAt=p.getLong("evolution_last_repair_at",0L);
        String lastRepairState=p.getString("evolution_last_repair_state","");

        boolean speechVerificationArmed=p.getBoolean("improvement_speech_verification_armed",false);
        int verifyBaseReplies=p.getInt("improvement_speech_verify_base_replies",0);
        int verifyBaseFaults=p.getInt("improvement_speech_verify_base_faults",0);
        int replySuccesses=p.getInt("tts_reply_successes",0);
        if(speechVerificationArmed){
            int cleanReplies=Math.max(0,replySuccesses-verifyBaseReplies);
            int newFaults=Math.max(0,ttsRecoveries-verifyBaseFaults);
            if(newFaults>0){
                p.edit().putBoolean("improvement_speech_verification_armed",false)
                        .putString("improvement_speech_verification_state","FAILED")
                        .putLong("improvement_speech_verification_completed_at",now).apply();
                if(!publishCommandSnapshot) invalidate(p,"speech-verification-failed");
                out.add(new Candidate("speech-repair-after-verification-failure","Repair speech after verification failure",100,
                        "Post-repair verification saw "+newFaults+" new TTS watchdog recovery/recoveries after "+cleanReplies+" clean reply/replies.",
                        "Rebuild the bounded speech runtime and restore a clean conversational voice path.",
                        "Low runtime risk; local rollback/authorization boundaries remain in force.","speech_rebuild",true));
            }else if(cleanReplies>=5){
                p.edit().putBoolean("improvement_speech_verification_armed",false)
                        .putString("improvement_speech_verification_state","PASSED")
                        .putLong("improvement_speech_verification_completed_at",now).apply();
                if(!publishCommandSnapshot) invalidate(p,"speech-verification-passed");
            }else{
                out.add(new Candidate("speech-post-repair-verification","Finish speech repair verification",96,
                        "Speech repair verification is active with "+cleanReplies+" of 5 clean spoken replies and no new watchdog recovery.",
                        "Proves the repair survives normal conversation instead of trusting a one-shot repair result.",
                        "No modification; observation only.","speech_verify",false));
            }
        } else {
            String speechVerifyState=p.getString("improvement_speech_verification_state","");
            long speechVerifyCompleted=p.getLong("improvement_speech_verification_completed_at",0L);
            boolean latestRepairAlreadyVerified="PASSED".equals(speechVerifyState)
                    && lastRepairAt>0L && speechVerifyCompleted>=lastRepairAt;
            if(!latestRepairAlreadyVerified && "APPLIED".equals(lastRepairState) && lastRepairAt>0L && lastTtsFault<=lastRepairAt){
                out.add(new Candidate("speech-post-repair-verification","Verify the new speech repair under conversation",94,
                        "The last bounded speech repair is APPLIED and no newer TTS watchdog fault is recorded, but a clean multi-reply verification has not been completed.",
                        "Confirms smoother speech and catches a regression before calling the repair finished.",
                        "No modification; observation only.","speech_verify",false));
            } else if(!latestRepairAlreadyVerified && (ttsRecoveries>=3 || callbackStalls>0 || postTtsRebuilds>0 || speechRebuilds>=3)){
                out.add(new Candidate("speech-runtime-resilience","Improve speech runtime resilience",98,
                        "TTS watchdog recoveries="+ttsRecoveries+", recognizer rebuilds="+speechRebuilds+", callback stalls="+callbackStalls+", post-TTS rebuilds="+postTtsRebuilds+".",
                        "Reduce clipped/choppy speech, dead handoffs, and recovery loops.",
                        "Low runtime risk; applies only the existing local allow-listed speech rebuild.","speech_rebuild",true));
            }
        }
        long lastLocalSuccess=p.getLong("fast_brain_last_success_at",0L);
        long currentBootAt=p.getLong("bootstrap_last_boot_at",0L);
        long currentBootVersion=p.getLong("bootstrap_last_boot_version",-1L);
        boolean freshNormalInference=currentBootVersion==acceptanceVersion && currentBootAt>0L && lastLocalSuccess>=currentBootAt;
        boolean fastProofArmed=p.getBoolean("improvement_fast_brain_proof_armed",false);
        long fastProofBaseline=p.getLong("improvement_fast_brain_proof_baseline",0L);
        if(fastProofArmed && lastLocalSuccess>fastProofBaseline){
            p.edit().putBoolean("improvement_fast_brain_proof_armed",false)
                    .putString("improvement_fast_brain_proof_state","PASSED")
                    .putLong("improvement_fast_brain_proof_completed_at",now).apply();
            if(!publishCommandSnapshot) invalidate(p,"fast-brain-proof-passed");
        } else if(LocalBrain.isLoaded() && !freshNormalInference){
            out.add(new Candidate("fast-brain-live-proof","Prove Fast Brain with a fresh real inference",91,
                    "Fast Brain is loaded, but no successful normal local inference has been recorded after the current core boot.",
                    "Separates 'model loaded/probed' from 'model actually answered a real post-install turn' and makes certification more trustworthy.",
                    "No code change; verification only.","fast_brain_proof",false));
        } else if(fastProofArmed){
            out.add(new Candidate("fast-brain-live-proof","Complete Fast Brain live proof",88,
                    "A live-inference proof is armed and is waiting for a successful local Fast Brain answer.",
                    "Closes the gap between readiness probes and real conversation performance.",
                    "No code change; verification only.","fast_brain_proof",false));
        }

        boolean openRouterConfigured=!SecretStore.get(p,"openrouter_api_key").trim().isEmpty();
        boolean groqConfigured=!SecretStore.get(p,"groq_api_key").trim().isEmpty();
        boolean geminiConfigured=!SecretStore.get(p,"gemini_api_key").trim().isEmpty();
        boolean cloudflareConfigured=!SecretStore.get(p,"cloudflare_api_key").trim().isEmpty()
                && !p.getString("cloudflare_account_id","").trim().isEmpty();
        if(!openRouterConfigured && !groqConfigured && !geminiConfigured && !cloudflareConfigured){
            out.add(new Candidate("cash-safe-free-provider","Connect a cash-safe free AI fallback",93,
                    "No free fallback provider is configured. Paid OpenAI remains explicit-turn-only.",
                    "Gives Lumi a stronger backup when Fast Brain cannot answer without silently spending OpenAI credits.",
                    "No provider is contacted until you supply that provider's own credential/account information.","free_provider_setup",false));
        }




        long latency=p.getLong("last_response_latency_ms",-1L);
        if(latency>3500L && !p.getBoolean("speed_priority",false)){
            out.add(new Candidate("conversation-speed-profile","Enable the reversible speed-first conversation profile",74,
                    "Last measured response latency is "+latency+" ms and speed priority is not enabled.",
                    "Shortens ordinary replies and prefers the faster conversation path.",
                    "Low and reversible; answers may become shorter.","speed_profile",true));
        } else if(latency>5200L){
            out.add(new Candidate("conversation-route-latency","Investigate remaining conversation latency",72,
                    "Last measured response latency is "+latency+" ms even with the current speed policy.",
                    "Targets routing/connection delay rather than masking it with shorter wording.",
                    "Diagnostic recommendation only until a specific bottleneck is identified.","latency_diagnose",false));
        }

        int promptMissTotal=p.getInt("fast_brain_prompt_quality_misses",0);
        int promptMissBaseline=p.getInt("fast_brain_prompt_quality_misses_baseline_code379",0);
        int promptMisses=Math.max(0,promptMissTotal-promptMissBaseline);
        if(promptMisses>=2){
            out.add(new Candidate("fast-brain-prompt-guardrails","Harden Fast Brain prompt/output validation",68,
                    "Fast Brain prompt-quality misses since Code379="+promptMisses+" (historical baseline="+promptMissBaseline+").",
                    "Reduces malformed or unusable local replies before they reach the conversation.",
                    "Requires a signed Lumi core update after the exact failure pattern is reviewed.","prompt_guardrails",true));
        }



        String currentScreen=p.getString("ui_current_screen","unknown");
        long pyramidFrameAt=p.getLong("pyramid_last_frame_wall_at",0L);
        long pyramidFrameAge=pyramidFrameAt<=0L?-1L:Math.max(0L,now-pyramidFrameAt);
        if("Home".equalsIgnoreCase(currentScreen)
                && (pyramidFrameAge<0L || pyramidFrameAge>2500L)){
            out.add(new Candidate("pyramid-runtime-mount","Repair live pyramid mount/render loop",92,
                    "Home expects the live approved pyramid, but the last recorded GL frame age is "+pyramidFrameAge+" ms and mount state is "+p.getString("pyramid_mount_state","unknown")+".",
                    "Makes a visible visual failure diagnosable and restores the live renderer instead of silently falling back.",
                    "Requires a signed core change only if runtime recovery cannot restart the existing renderer.","visual_mount_diagnose",false));
        }

        if(DeveloperFlightRecorder.writeFailureCount()>0L || DeveloperFlightRecorder.droppedEventCount()>0L){
            out.add(new Candidate("blackbox-recorder-integrity","Repair Black Box recorder integrity",95,
                    "Recorder writeFailures="+DeveloperFlightRecorder.writeFailureCount()+" and droppedEvents="+DeveloperFlightRecorder.droppedEventCount()+".",
                    "Prevents diagnostic blind spots so future root-cause analysis is trustworthy.",
                    "Recorder repair must preserve credentials redaction and existing history.","blackbox_integrity_diagnose",false));
        }

        Collections.sort(out,new Comparator<Candidate>(){
            @Override public int compare(Candidate a,Candidate b){ return Integer.compare(b.priority,a.priority); }
        });
        if(out.size()>MAX_VISIBLE) out=new ArrayList<>(out.subList(0,MAX_VISIBLE));

        JSONArray arr=new JSONArray();
        for(Candidate x:out){ try{arr.put(x.json());}catch(Exception ignored){} }
        String report=formatReport(arr,now);
        if(publishCommandSnapshot){
            p.edit().putString("improvement_advisor_last_json",arr.toString())
                    .putString("improvement_advisor_last_report",report)
                    .putLong("improvement_advisor_last_scan_at",now)
                    .putInt("improvement_advisor_last_count",arr.length())
                    .putString("improvement_advisor_snapshot_id","adv-"+now)
                    .apply();
        } else {
            p.edit().putString("improvement_advisor_diagnostic_json",arr.toString())
                    .putString("improvement_advisor_diagnostic_report",report)
                    .putLong("improvement_advisor_diagnostic_scan_at",now).apply();
        }
        if(c instanceof MainActivity){
            ((MainActivity)c).flightRecord("IMPROVEMENT_ADVISOR",publishCommandSnapshot?"SCAN":"SCAN_DIAGNOSTIC","suggestions="+arr.length());
        }
        return report;
    }

    public static String currentOrScan(Context c, SharedPreferences p){
        // Code350: owner requests always rebuild from current facts; no five-minute stale snapshot.
        return scan(c,p);
    }

    public static String applySuggestion(MainActivity a, SharedPreferences p, int oneBasedIndex, String userText){
        try{
            JSONArray arr=new JSONArray(p.getString("improvement_advisor_last_json","[]"));
            if(oneBasedIndex<1 || oneBasedIndex>arr.length()) return "I don't have suggestion "+oneBasedIndex+" in my current improvement list. Ask me to suggest improvements again.";
            JSONObject s=arr.getJSONObject(oneBasedIndex-1);
            String title=s.optString("title","that improvement");
            String action=s.optString("action","");
            String key=s.optString("key","suggestion-"+oneBasedIndex);
            p.edit().putInt("improvement_advisor_last_selected_index",oneBasedIndex)
                    .putString("improvement_advisor_last_selected_key",key)
                    .putString("improvement_advisor_last_selected_title",title)
                    .putString("improvement_advisor_last_selected_action",action)
                    .putLong("improvement_advisor_last_selected_at",System.currentTimeMillis()).apply();

            if("speech_verify".equals(action)){
                int replies=p.getInt("tts_reply_successes",0);
                int baseReplies=p.getInt("improvement_speech_verify_base_replies",replies);
                int faults=p.getInt("tts_watchdog_recoveries",0);
                int baseFaults=p.getInt("improvement_speech_verify_base_faults",faults);
                int clean=Math.max(0,replies-baseReplies);
                int newFaults=Math.max(0,faults-baseFaults);
                boolean speechArmed=p.getBoolean("improvement_speech_verification_armed",false);
                String speechState=p.getString("improvement_speech_verification_state","");
                long speechCompleted=p.getLong("improvement_speech_verification_completed_at",0L);
                long latestRepair=p.getLong("evolution_last_repair_at",0L);
                if(!speechArmed && speechCompleted>=latestRepair && latestRepair>0L && "PASSED".equals(speechState)){
                    return "Suggestion "+oneBasedIndex+" is already complete. Speech verification passed after the latest repair. Ask me to suggest improvements again for the current list.";
                }
                if(!speechArmed && speechCompleted>=latestRepair && latestRepair>0L && "FAILED".equals(speechState)){
                    return "Suggestion "+oneBasedIndex+" already finished with a failed speech verification. Ask me to suggest improvements again so I can offer the repair action instead of restarting the old proof window.";
                }
                if(speechArmed){
                    if(newFaults>0){
                        p.edit().putBoolean("improvement_speech_verification_armed",false)
                                .putString("improvement_speech_verification_state","FAILED")
                                .putLong("improvement_speech_verification_completed_at",System.currentTimeMillis()).apply();
                        invalidate(p,"speech-verification-failed");
                        a.flightRecord("IMPROVEMENT_ADVISOR","VERIFY_FAILED","speech clean="+clean+" newFaults="+newFaults);
                        return "Speech verification failed after "+clean+" clean replies because a new TTS watchdog recovery occurred. The verification was stopped instead of silently restarting.";
                    }
                    if(clean>=5){
                        p.edit().putBoolean("improvement_speech_verification_armed",false)
                                .putString("improvement_speech_verification_state","PASSED")
                                .putLong("improvement_speech_verification_completed_at",System.currentTimeMillis()).apply();
                        invalidate(p,"speech-verification-passed");
                        a.flightRecord("IMPROVEMENT_ADVISOR","VERIFY_PASSED","speech clean="+clean);
                        return "Speech verification passed: 5 clean spoken replies with no new TTS watchdog recovery.";
                    }
                    a.flightRecord("IMPROVEMENT_ADVISOR","VERIFY_CONTINUE","speech clean="+clean+" target=5");
                    return "Suggestion "+oneBasedIndex+" is already running. Speech verification is "+clean+" of 5 clean replies with no new watchdog recovery. I kept the existing proof window instead of restarting it.";
                }
                p.edit().putBoolean("improvement_speech_verification_armed",true)
                        .putInt("improvement_speech_verify_base_replies",replies)
                        .putInt("improvement_speech_verify_base_faults",faults)
                        .putString("improvement_speech_verification_state","RUNNING")
                        .putLong("improvement_speech_verification_started_at",System.currentTimeMillis()).apply();
                a.flightRecord("IMPROVEMENT_ADVISOR","VERIFY_ARMED","speech clean-reply target=5");
                return "Speech verification is armed. I'll use the next five clean spoken replies as the proof window and fail it if a new TTS watchdog recovery occurs.";
            }
            if("fast_brain_proof".equals(action)){
                long lastSuccess=p.getLong("fast_brain_last_success_at",0L);
                long baseline=p.getLong("improvement_fast_brain_proof_baseline",lastSuccess);
                boolean fastArmed=p.getBoolean("improvement_fast_brain_proof_armed",false);
                String fastState=p.getString("improvement_fast_brain_proof_state","");
                long fastCompleted=p.getLong("improvement_fast_brain_proof_completed_at",0L);
                if(!fastArmed && "PASSED".equals(fastState) && fastCompleted>0L){
                    return "Suggestion "+oneBasedIndex+" is already complete. Fast Brain produced the required real local inference. Ask me to suggest improvements again for the current list.";
                }
                if(fastArmed){
                    if(lastSuccess>baseline){
                        p.edit().putBoolean("improvement_fast_brain_proof_armed",false)
                                .putString("improvement_fast_brain_proof_state","PASSED")
                                .putLong("improvement_fast_brain_proof_completed_at",System.currentTimeMillis()).apply();
                        invalidate(p,"fast-brain-proof-passed");
                        a.flightRecord("IMPROVEMENT_ADVISOR","VERIFY_PASSED","Fast Brain real inference proof");
                        return "Fast Brain live proof passed. A real local inference completed after the proof window started.";
                    }
                    a.flightRecord("IMPROVEMENT_ADVISOR","VERIFY_CONTINUE","Fast Brain proof already armed");
                    return "Suggestion "+oneBasedIndex+" is already running. I'm keeping the original Fast Brain proof baseline and waiting for a real local inference instead of restarting the test.";
                }
                p.edit().putBoolean("improvement_fast_brain_proof_armed",true)
                        .putLong("improvement_fast_brain_proof_baseline",lastSuccess)
                        .putString("improvement_fast_brain_proof_state","RUNNING")
                        .putLong("improvement_fast_brain_proof_started_at",System.currentTimeMillis()).apply();
                a.flightRecord("IMPROVEMENT_ADVISOR","VERIFY_ARMED","Fast Brain real inference proof");
                return "Fast Brain live proof is armed. The next successful normal local Fast Brain answer will satisfy it; a readiness probe alone will not.";
            }
            if("latency_diagnose".equals(action)){
                return "That recommendation is diagnostic-only. I need another slow real turn in the flight recorder so I can isolate whether the delay is routing, OpenAI connection, local inference, or speech handoff.";
            }
            if("canonical_source_recheck".equals(action)){
                CanonicalSourceManager.initialize(a,p);
                return CanonicalSourceManager.isHealthy(a,p)
                        ? "Canonical source integrity is healthy again and matches the installed Lumi core."
                        : "Canonical source is still unhealthy. I blocked core modification rather than risk losing source-of-truth.";
            }
            if("free_provider_setup".equals(action)){
                a.runOnUiThread(a::showCashSafeProviderSetupDialog);
                a.flightRecord("IMPROVEMENT_ADVISOR","OPEN_FREE_PROVIDER_SETUP","secure provider picker opened directly");
                return "I opened the secure provider picker directly. Choose a provider and paste the credential into its protected password field; never speak or paste API keys into conversation.";
            }

            if(!IdentityHierarchy.adminSessionActive(p)){
                return "Suggestion "+oneBasedIndex+" can change Lumi. Root administrator authority is not active. Authenticate locally, then say ‘apply suggestion "+oneBasedIndex+".’";
            }
            if(!currentApproval(userText)){
                return "I have the recommendation, but I still need current approval. Say ‘apply suggestion "+oneBasedIndex+"’ or ‘I approve suggestion "+oneBasedIndex+".’";
            }

            if("speech_rebuild".equals(action)){
                long lastApply=p.getLong("improvement_advisor_last_apply_at",0L);
                long snapshotAt=p.getLong("improvement_advisor_last_scan_at",0L);
                if(lastApply>=snapshotAt && title.equals(p.getString("improvement_advisor_last_applied_title",""))
                        && "APPLIED".equals(p.getString("evolution_last_repair_state",""))){
                    String previous=p.getString("improvement_advisor_last_apply_result","");
                    a.flightRecord("IMPROVEMENT_ADVISOR","APPLY_IDEMPOTENT","suggestion="+oneBasedIndex+" key="+key+" runtime repair already applied");
                    return previous.isEmpty()?"Suggestion "+oneBasedIndex+" was already applied after this suggestion list was created. I did not run the repair twice.":previous+" I did not run it twice.";
                }
                String r=a.executeSpeechOptimizationRepair(userText);
                recordApply(p,a,oneBasedIndex,title,r);
                return r;
            }
            if("speed_profile".equals(action)){
                if(p.getBoolean("speed_priority",false) && "brief".equals(p.getString("reply_style",""))){
                    String r="Suggestion "+oneBasedIndex+" is already applied: speed-first conversation profile is enabled. I did not reapply it.";
                    recordApply(p,a,oneBasedIndex,title,r);
                    return r;
                }
                p.edit().putBoolean("speed_priority",true).putString("reply_style","brief").apply();
                String r="Applied suggestion "+oneBasedIndex+": speed-first conversation profile enabled. It's reversible from conversation settings.";
                recordApply(p,a,oneBasedIndex,title,r);
                return r;
            }
            if("trusted_build_proof".equals(action)){
                JSONObject pf=TrustedBuildRelayClient.preflight(a,p,true);
                String r=pf.optBoolean("ok",false)?"Trusted build relay load-test PASS. Lumi and private CI are ready for verified source updates.":"Trusted build relay load-test failed: "+pf.optString("error",pf.optString("state","unknown failure")); recordApply(p,a,oneBasedIndex,title,r); return r;
            }
            if("prompt_guardrails".equals(action)){
                p.edit().putBoolean("improvement_prompt_guardrail_requested",true).apply();
                String r="Recorded suggestion "+oneBasedIndex+" for the next verified owner-approved bridge-core remediation package. No core update was started without an imported verified package and authorization.";
                recordApply(p,a,oneBasedIndex,title,r); return r;
            }
            return "I can explain suggestion "+oneBasedIndex+", but it does not have a bounded apply action yet, so I did not change anything.";
        }catch(Throwable t){
            return "I couldn't apply that suggestion safely: "+t.getClass().getSimpleName()+".";
        }
    }


    static String lastSuggestionStatus(SharedPreferences p){

        if(p.getBoolean("improvement_speech_verification_armed",false)){
            int clean=Math.max(0,p.getInt("tts_reply_successes",0)-p.getInt("improvement_speech_verify_base_replies",0));
            int faults=Math.max(0,p.getInt("tts_watchdog_recoveries",0)-p.getInt("improvement_speech_verify_base_faults",0));
            return faults>0?"The active speech verification has detected a new watchdog fault and will fail on the next advisor check.":"Speech verification is still running at "+clean+" of 5 clean replies with no new watchdog recovery.";
        }
        if(p.getBoolean("improvement_fast_brain_proof_armed",false)) return "Fast Brain live proof is still running and waiting for a successful real local inference.";
        long applyAt=p.getLong("improvement_advisor_last_apply_at",0L);
        long speechAt=p.getLong("improvement_speech_verification_completed_at",0L);
        long fastAt=p.getLong("improvement_fast_brain_proof_completed_at",0L);
        if(applyAt>=speechAt && applyAt>=fastAt && applyAt>0L){
            String r=p.getString("improvement_advisor_last_apply_result","").trim();
            if(!r.isEmpty()) return r;
        }
        if(speechAt>=fastAt && speechAt>0L){
            String speechState=p.getString("improvement_speech_verification_state","");
            if("PASSED".equals(speechState)) return "The last speech verification passed.";
            if("FAILED".equals(speechState)) return "The last speech verification failed. Ask me to suggest improvements again for the repair action.";
        }
        if(fastAt>0L && "PASSED".equals(p.getString("improvement_fast_brain_proof_state",""))) return "The last Fast Brain live proof passed.";
        String r=p.getString("improvement_advisor_last_apply_result","").trim();
        return r.isEmpty()?"I don't have a completed suggestion action to report yet.":r;
    }

    static String retryLastSuggestion(MainActivity a, SharedPreferences p, String userText){
        int index=p.getInt("improvement_advisor_last_selected_index",-1);
        long age=System.currentTimeMillis()-p.getLong("improvement_advisor_last_selected_at",0L);
        if(index<1 || age<0L || age>10L*60L*1000L) return "I don't have a recent numbered suggestion to retry. Ask me to suggest improvements again.";
        return applySuggestion(a,p,index,userText);
    }

    static int parseSuggestionIndex(String text){
        String l=text==null?"":text.toLowerCase(Locale.US).trim();
        // Code344: shorthand such as "apply #3" or "apply 3" stays local instead of
        // escaping into a metered cloud model just because the word "suggestion" was omitted.
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:\\bsuggestion\\s+|#|\\b(?:apply|approve|approved|do|run|install)\\s+)(\\d+|one|two|three|four|five|won|too|to|tree|for|fore)\\b").matcher(l);
        if(!m.find()) return -1;
        String n=m.group(1);
        try{return Integer.parseInt(n);}catch(Exception ignored){}
        if("one".equals(n)||"won".equals(n)) return 1;
        if("two".equals(n)||"too".equals(n)||"to".equals(n)) return 2;
        if("three".equals(n)||"tree".equals(n)) return 3;
        if("four".equals(n)||"for".equals(n)||"fore".equals(n)) return 4;
        if("five".equals(n)) return 5;
        return -1;
    }

    private static void recordApply(SharedPreferences p, MainActivity a, int index, String title, String result){
        p.edit().putInt("improvement_advisor_last_applied_index",index)
                .putString("improvement_advisor_last_applied_title",title)
                .putString("improvement_advisor_last_apply_result",result)
                .putLong("improvement_advisor_last_apply_at",System.currentTimeMillis()).apply();
        a.flightRecord("IMPROVEMENT_ADVISOR","APPLY","suggestion="+index+" title="+title+" result="+result);
    }

    private static boolean currentApproval(String userText){
        String l=userText==null?"":userText.toLowerCase(Locale.US).trim();
        int suggestion=parseSuggestionIndex(l);
        boolean suggestionVerb=l.matches(".*\\b(apply|approve|approved|do|run|install)\\b.*") || l.contains("i approve");
        boolean retry=(l.equals("retry")||l.equals("try again")||l.equals("retry suggestion"));
        return (suggestion>0 && suggestionVerb)
                || l.matches(".*\\b(do it|go ahead|proceed|i approve|approved)\\b.*")
                || retry;
    }

    private static JSONArray exactlyThree(JSONArray source){
        JSONArray out=new JSONArray();
        try{for(int i=0;i<Math.min(3,source.length());i++)out.put(source.getJSONObject(i));}catch(Exception ignored){}
        String[][] defaults={
                {"next-build-runtime","Recheck runtime performance after this update","64","Confirm the new build remains responsive under normal conversation.","Catches regressions before they accumulate.","No modification; observation only.","latency_diagnose"},
                {"next-build-web","Audit web and free-AI routing quality","62","Compare source agreement, provider failures, and answer latency after real use.","Improves online answer quality and failover.","No modification; observation only.","latency_diagnose"},
                {"next-build-ux","Review high-frequency UI actions","58","Use Black Box button/action events to identify any remaining interface friction.","Keeps Lumi's interface aligned to real usage.","No modification; observation only.","latency_diagnose"}};
        int di=0;
        while(out.length()<3 && di<defaults.length){
            try{
                String[] d=defaults[di++]; int pr=Integer.parseInt(d[2]);
                JSONObject x=new JSONObject().put("key",d[0]).put("title",d[1]).put("priority",pr).put("impact",pr).put("riskScore",5).put("confidence",82)
                        .put("evidence",d[3]).put("benefit",d[4]).put("risk",d[5]).put("action",d[6]).put("writeRequired",false);
                boolean duplicate=false; for(int i=0;i<out.length();i++)if(d[0].equals(out.optJSONObject(i).optString("key")))duplicate=true;
                if(!duplicate)out.put(x);
            }catch(Exception ignored){}
        }
        return out;
    }

    public static String nextBuildRecommendations(Context c,SharedPreferences p){
        String saved=p.getString("next_build_recommendations_report","");
        long forCode=p.getLong("next_build_recommendations_for_code",-1L);
        if(!saved.isEmpty()) return "For installed code "+forCode+"\n"+saved;
        scanInternal(c,p,false);
        try{
            JSONArray arr=new JSONArray(p.getString("improvement_advisor_diagnostic_json","[]"));
            JSONArray top=exactlyThree(arr);
            String report=formatReport(top,System.currentTimeMillis()).replace("Say ‘apply suggestion 1’ (or another number) when you want me to act on one.","These three are automatically carried into the next build scope when this Black Box is reviewed.");
            return report;
        }catch(Exception e){return "No next-build recommendations are available yet.";}
    }

    public static void capturePostUpdateRecommendations(Context c,SharedPreferences p,long installedCode){
        try{
            scanInternal(c,p,false);
            JSONArray arr=new JSONArray(p.getString("improvement_advisor_diagnostic_json","[]"));
            JSONArray top=exactlyThree(arr);
            String report=formatReport(top,System.currentTimeMillis()).replace("Say ‘apply suggestion 1’ (or another number) when you want me to act on one.","These three are automatically carried into the next build scope when this Black Box is reviewed.");
            p.edit().putString("next_build_recommendations_json",top.toString()).putString("next_build_recommendations_report",report)
                    .putLong("next_build_recommendations_for_code",installedCode).putLong("next_build_recommendations_at",System.currentTimeMillis()).apply();
            if(c instanceof MainActivity)((MainActivity)c).flightRecord("NEXT_BUILD","RECOMMENDATIONS_CAPTURED","count="+top.length()+" installedCode="+installedCode);
        }catch(Exception ignored){}
    }

    private static String formatReport(JSONArray arr,long now){
        String stamp=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(now));
        if(arr.length()==0) return "Improvement Advisor • "+stamp+"\nI don't see a high-confidence improvement to recommend right now. I'll keep watching the diagnostics instead of inventing work.";
        StringBuilder b=new StringBuilder("Improvement Advisor • ").append(stamp).append("\n");
        for(int i=0;i<arr.length();i++){
            JSONObject s=arr.optJSONObject(i); if(s==null) continue;
            b.append("\n").append(i+1).append(") ").append(s.optString("title","Improvement"))
                    .append(" • Priority ").append(s.optInt("priority",0)).append("/100")
                    .append(" • Impact ").append(s.optInt("impact",0)).append("/100")
                    .append(" • Risk ").append(s.optInt("riskScore",0)).append("/100")
                    .append(" • Confidence ").append(s.optInt("confidence",0)).append("/100\n")
                    .append("Evidence: ").append(s.optString("evidence","")).append("\n")
                    .append("Benefit: ").append(s.optString("benefit","")).append("\n")
                    .append("Risk: ").append(s.optString("risk","")).append("\n")
                    .append(s.optBoolean("writeRequired",false)?"Approval: required before any change.":"Approval: not required because this is verification/observation only.").append("\n");
        }
        b.append("\nSay ‘apply suggestion 1’ (or another number) when you want me to act on one.");
        return b.toString();
    }
}
