package com.distressedelk.lumi;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns the raw JSONL recorder into a compact, owner-readable root-cause view. */
final class BlackBoxAnalyzer {
    private BlackBoxAnalyzer(){}

    static String executiveSummary(Context context,SharedPreferences prefs){
        Analysis a=analyze(context);
        StringBuilder s=new StringBuilder();
        int healthErrors=a.currentEvents>0?a.currentErrors:a.totalErrors;
        int healthWarnings=a.currentEvents>0?a.currentWarnings:a.totalWarnings;
        String health=healthErrors>0?"ATTENTION":healthWarnings>0?"WATCH":"HEALTHY";
        s.append("Black Box health: ").append(health)
                .append(" • currentEvents=").append(a.currentEvents)
                .append(" • errors=").append(a.currentErrors)
                .append(" • warnings=").append(a.currentWarnings).append("\n");
        s.append("Correlation coverage (current session): ")
                .append(percent(a.currentCorrelated,a.currentEvents)).append("%")
                .append(" • distinct turns=").append(a.currentTurns.size())
                .append(" • session=").append(DeveloperFlightRecorder.currentSessionId()).append("\n");
        s.append("Historical analysis window: events=").append(a.totalEvents)
                .append(" • correlation=").append(percent(a.totalCorrelated,a.totalEvents)).append("%")
                .append(" • errors=").append(a.totalErrors)
                .append(" • warnings=").append(a.totalWarnings).append("\n");
        if(a.totalEvents==0) s.append("No flight-recorder events were available in the analysis window.\n");
        if(!a.currentFaults.isEmpty()){
            s.append("Most frequent current-session fault signatures:\n");
            int shown=0;
            for(Map.Entry<String,Fault> e:sortedFaults(a.currentFaults)){
                Fault f=e.getValue();
                s.append("  ").append(++shown).append(") ").append(e.getKey()).append(" ×").append(f.count)
                        .append(" • last=").append(bound(f.lastDetail,180)).append("\n");
                if(shown>=5) break;
            }
        }else s.append("No ERROR-severity recorder events in the current session.\n");
        String lastCrash=prefs==null?"":prefs.getString("blackbox_last_uncaught","");
        long lastCrashAt=prefs==null?0L:prefs.getLong("blackbox_last_uncaught_at",0L);
        s.append("Last uncaught Java crash: ").append(lastCrashAt>0L?new java.util.Date(lastCrashAt)+" • "+bound(lastCrash,220):"none recorded").append("\n");
        s.append("Recorder integrity: ").append(DeveloperFlightRecorder.healthSummary()).append("\n");
        if(prefs!=null && prefs.getBoolean("blackbox_forensic_synthesis_enabled",false)){
            s.append("\n").append(FullRemediationDiagnostics.report(context,prefs)).append("\n");
        }
        return s.toString();
    }

    static String latencyProfile(Context context,SharedPreferences prefs){
        Analysis a=analyze(context);
        StringBuilder s=new StringBuilder();
        s.append("Last response latency: ").append(prefs==null?-1L:prefs.getLong("last_response_latency_ms",-1L)).append(" ms\n");
        s.append("Functional total turn: ").append(prefs==null?-1L:prefs.getLong("functional_core_last_total_turn_ms",-1L)).append(" ms")
                .append(" • post-TTS handoff: ").append(prefs==null?-1L:prefs.getLong("functional_core_last_post_tts_actual_handoff_ms",-1L)).append(" ms\n");
        s.append("Idle/user-wait gaps excluded: ").append(a.excludedIdleGaps)
                .append(" • maxExcludedMs=").append(a.maxExcludedIdleGapMs).append("\n");
        if(a.slowGaps.isEmpty()){
            s.append("No active-processing stage gaps >=250 ms recorded in the current session.\n");
            return s.toString();
        }
        Collections.sort(a.slowGaps,(x,y)->Long.compare(y.ms,x.ms));
        s.append("Slowest active-processing stage gaps in current session:\n");
        int n=Math.min(8,a.slowGaps.size());
        for(int i=0;i<n;i++){
            Gap g=a.slowGaps.get(i);
            s.append("  ").append(i+1).append(") ").append(g.ms).append(" ms • ")
                    .append(g.category).append("/").append(g.action)
                    .append(" • stage=").append(g.stage)
                    .append(" • ").append(g.correlation).append("\n");
        }
        return s.toString();
    }

    static String changeLedger(Context context){
        Analysis a=analyze(context);
        if(a.changes.isEmpty()) return "No change/update/repair events in the analysis window.";
        StringBuilder s=new StringBuilder();
        int start=Math.max(0,a.changes.size()-30);
        for(int i=start;i<a.changes.size();i++){
            Change c=a.changes.get(i);
            s.append(c.time).append(" • ").append(c.category).append("/").append(c.action)
                    .append(" • ").append(bound(c.detail,260)).append("\n");
        }
        return s.toString();
    }

    static String processExitSummary(Context context){
        if(context==null) return "Unavailable.";
        if(Build.VERSION.SDK_INT<30) return "Android historical process-exit reasons require Android 11+.";
        try{
            ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if(am==null) return "ActivityManager unavailable.";
            List<ApplicationExitInfo> exits=am.getHistoricalProcessExitReasons(context.getPackageName(),0,6);
            if(exits==null || exits.isEmpty()) return "No historical process exits reported by Android.";
            StringBuilder s=new StringBuilder(); int i=0;
            for(ApplicationExitInfo e:exits){
                s.append(++i).append(") ").append(new java.util.Date(e.getTimestamp()))
                        .append(" • process=").append(bound(e.getProcessName(),120))
                        .append(" • pid=").append(e.getPid())
                        .append(" • reason=").append(exitReason(e.getReason()))
                        .append(" • importance=").append(e.getImportance())
                        .append(" • pssKB=").append(e.getPss())
                        .append(" • rssKB=").append(e.getRss());
                String d=e.getDescription(); if(d!=null && !d.trim().isEmpty()) s.append(" • ").append(bound(d,200));
                s.append("\n");
            }
            return s.toString();
        }catch(Throwable t){ return "Process-exit history unavailable: "+bound(String.valueOf(t.getMessage()),220); }
    }

    private static Analysis analyze(Context context){
        Analysis a=new Analysis();
        String currentSession=DeveloperFlightRecorder.currentSessionId();
        String raw=DeveloperFlightRecorder.readTail(context,2*1024*1024);
        if(raw==null || raw.trim().isEmpty()) return a;
        String[] lines=raw.split("\\r?\\n");
        for(String line:lines){
            if(line==null || line.trim().isEmpty() || !line.trim().startsWith("{")) continue;
            try{
                JSONObject j=new JSONObject(line);
                a.totalEvents++;
                String sev=j.optString("severity","INFO").toUpperCase(Locale.US);
                String cat=j.optString("category","unknown");
                String act=j.optString("action","unknown");
                String detail=j.optString("detail","");
                String corr=j.optString("correlationId","");
                String session=j.optString("sessionId","");
                String stage=j.optString("stage","");
                long turn=j.optLong("turn",-1L);
                long gap=j.optLong("correlationGapMs",0L);
                boolean current=!currentSession.isEmpty() && currentSession.equals(session);
                boolean aiBusy=j.optBoolean("aiBusy",false);
                boolean manualStop=j.optBoolean("manualStop",false);

                if(!corr.isEmpty()) a.totalCorrelated++;
                if("ERROR".equals(sev)) a.totalErrors++;
                else if("WARN".equals(sev)) a.totalWarnings++;

                if(current){
                    a.currentEvents++;
                    if(!corr.isEmpty()){
                        a.currentCorrelated++;
                        a.currentTurns.put(corr,Boolean.TRUE);
                    }
                    if("ERROR".equals(sev)){
                        a.currentErrors++;
                        String signature=cat+"/"+act;
                        Fault f=a.currentFaults.get(signature); if(f==null){ f=new Fault(); a.currentFaults.put(signature,f); }
                        f.count++; f.lastDetail=detail;
                    }else if("WARN".equals(sev)) a.currentWarnings++;

                    if(turn>0L && gap>=250L && isLatencyEvent(cat,act,stage)){
                        if(isIdleOrUserWait(cat,act,detail,stage,manualStop,aiBusy,sev)){
                            a.excludedIdleGaps++;
                            a.maxExcludedIdleGapMs=Math.max(a.maxExcludedIdleGapMs,gap);
                        }else{
                            a.slowGaps.add(new Gap(gap,cat,act,stage,corr.isEmpty()?"turn="+turn:corr));
                        }
                    }
                }
                if(isChange(cat,act)) a.changes.add(new Change(j.optString("timestamp",""),cat,act,detail));
            }catch(Throwable ignored){}
        }
        return a;
    }

    private static boolean isIdleOrUserWait(String category,String action,String detail,String stage,
                                            boolean manualStop,boolean aiBusy,String severity){
        String x=((category==null?"":category)+" "+(action==null?"":action)+" "+(detail==null?"":detail)+" "+(stage==null?"":stage)).toLowerCase(Locale.US);
        if(isExpectedManualStop(x)) return true;
        if(x.contains("keyboard active") || x.contains("save picker") || x.contains("document destination picker")) return true;
        String st=stage==null?"":stage.trim().toLowerCase(Locale.US);
        boolean idleStage=st.isEmpty() || "idle".equals(st) || "none".equals(st);
        // A ~5 s recognizer silence window often surfaces on the next AUDIO_FOCUS callback.
        // It is user/room wait, not active CPU/network processing.
        if(!aiBusy && idleStage && x.contains("audio_focus")) return true;
        return !aiBusy && idleStage && manualStop && !"ERROR".equals(severity);
    }

    private static boolean isExpectedManualStop(String x){
        return x.contains("blocked by hard manual-stop latch")
                || x.contains("blocked by manual stop latch")
                || x.contains("auto-listen blocked by manual stop")
                || x.contains("suppressed by manual stop latch")
                || x.contains("startup auto-listen suppressed by manual stop latch");
    }

    private static boolean isLatencyEvent(String category,String action,String stage){
        String x=((category==null?"":category)+" "+(action==null?"":action)+" "+(stage==null?"":stage)).toLowerCase(Locale.US);
        return x.contains("trace") || x.contains("stt") || x.contains("tts") || x.contains("speech")
                || x.contains("brain") || x.contains("ai") || x.contains("web") || x.contains("route")
                || x.contains("network") || x.contains("response") || x.contains("reply") || x.contains("transcript") || x.contains("functional");
    }

    private static boolean isChange(String category,String action){
        String x=((category==null?"":category)+" "+(action==null?"":action)).toLowerCase(Locale.US);
        return x.contains("change") || x.contains("reset") || x.contains("update") || x.contains("install")
                || x.contains("repair") || x.contains("profile") || x.contains("morph") || x.contains("rotation")
                || x.contains("migration") || x.contains("rollback") || x.contains("permission");
    }

    private static int percent(int numerator,int denominator){
        return denominator<=0?0:Math.round((numerator*100f)/denominator);
    }

    private static List<Map.Entry<String,Fault>> sortedFaults(Map<String,Fault> map){
        List<Map.Entry<String,Fault>> out=new ArrayList<>(map.entrySet());
        out.sort((a,b)->Integer.compare(b.getValue().count,a.getValue().count));
        return out;
    }

    private static String exitReason(int r){
        switch(r){
            case 0:return "UNKNOWN";
            case 1:return "EXIT_SELF";
            case 2:return "SIGNALED";
            case 3:return "LOW_MEMORY";
            case 4:return "CRASH";
            case 5:return "NATIVE_CRASH";
            case 6:return "ANR";
            case 7:return "INIT_FAILURE";
            case 8:return "PERMISSION_CHANGE";
            case 9:return "EXCESSIVE_RESOURCE";
            case 10:return "USER_REQUESTED";
            case 11:return "USER_STOPPED";
            case 12:return "DEPENDENCY_DIED";
            case 13:return "PACKAGE_STATE_CHANGE";
            case 14:return "PACKAGE_UPDATED_OR_FREEZER";
            default:return "CODE_"+r;
        }
    }

    private static String bound(String s,int max){
        String v=SecretStore.redact(s==null?"":s).replace('\n',' ').replace('\r',' ').trim();
        return v.length()<=max?v:v.substring(0,max)+"…";
    }

    private static final class Analysis{
        int totalEvents,totalErrors,totalWarnings,totalCorrelated;
        int currentEvents,currentErrors,currentWarnings,currentCorrelated;
        int excludedIdleGaps;
        long maxExcludedIdleGapMs;
        final Map<String,Boolean> currentTurns=new LinkedHashMap<>();
        final Map<String,Fault> currentFaults=new LinkedHashMap<>();
        final List<Gap> slowGaps=new ArrayList<>();
        final List<Change> changes=new ArrayList<>();
    }
    private static final class Fault{ int count; String lastDetail=""; }
    private static final class Gap{
        final long ms; final String category,action,stage,correlation;
        Gap(long ms,String category,String action,String stage,String correlation){this.ms=ms;this.category=category;this.action=action;this.stage=stage;this.correlation=correlation;}
    }
    private static final class Change{
        final String time,category,action,detail;
        Change(String time,String category,String action,String detail){this.time=time;this.category=category;this.action=action;this.detail=detail;}
    }
}
