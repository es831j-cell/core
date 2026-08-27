package com.distressedelk.lumi;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Code380 Black Box R96 completeness layer.
 * Adds resource telemetry, observability coverage, turn-level causal chains, regression checks,
 * and visual mount truth. It records only observable state, never credentials or hidden reasoning.
 */
final class BlackBoxCompleteness {
    private BlackBoxCompleteness(){}

    static String report(Context context, SharedPreferences prefs){
        StringBuilder s=new StringBuilder();
        s.append("OBSERVABILITY COVERAGE\n").append(coverage(context)).append("\n\n");
        s.append("RESOURCE / PROCESS SNAPSHOT\n").append(resourceSnapshot(context)).append("\n\n");
        s.append("CAUSAL TURN ANALYSIS\n").append(causalSummary(context)).append("\n\n");
        s.append("REGRESSION / EXPECTATION CHECKS\n").append(regressionSummary(context,prefs)).append("\n\n");
        s.append("VISUAL MOUNT TRUTH\n").append(visualMountSummary(prefs));
        return s.toString();
    }

    static String resourceSnapshot(Context context){
        if(context==null) return "context unavailable";
        StringBuilder s=new StringBuilder();
        try{
            Runtime r=Runtime.getRuntime();
            long used=(r.totalMemory()-r.freeMemory())/1024L;
            s.append("Java heap: used=").append(used).append(" KB • committed=").append(r.totalMemory()/1024L)
                    .append(" KB • max=").append(r.maxMemory()/1024L).append(" KB\n");
            s.append("Native heap: allocated=").append(Debug.getNativeHeapAllocatedSize()/1024L)
                    .append(" KB • size=").append(Debug.getNativeHeapSize()/1024L)
                    .append(" KB • free=").append(Debug.getNativeHeapFreeSize()/1024L).append(" KB\n");
            ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if(am!=null){
                ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                s.append("System memory: avail=").append(mi.availMem/1024L/1024L).append(" MB • lowMemory=").append(mi.lowMemory)
                        .append(" • threshold=").append(mi.threshold/1024L/1024L).append(" MB\n");
            }
            File files=context.getFilesDir(); StatFs fs=new StatFs(files.getAbsolutePath());
            s.append("App storage: free=").append(fs.getAvailableBytes()/1024L/1024L).append(" MB • total=")
                    .append(fs.getTotalBytes()/1024L/1024L).append(" MB\n");
            Intent b=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(b!=null){
                int level=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=b.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                int pct=scale>0?level*100/scale:-1; int temp=b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0);
                int plugged=b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);
                s.append("Battery: ").append(pct).append("% • ").append(plugged==0?"on battery":"charging")
                        .append(" • ").append(String.format(Locale.US,"%.1f°C",temp/10f)).append("\n");
            }
            s.append("Network: ").append(network(context)).append("\n");
            long processStart=android.os.Process.getStartElapsedRealtime();
            long processUptime=Math.max(0L,SystemClock.elapsedRealtime()-processStart);
            s.append("Process uptime: ").append(processUptime).append(" ms • threads=")
                    .append(Thread.getAllStackTraces().size()).append("\n");
        }catch(Throwable t){ s.append("Resource snapshot partial: ").append(clean(String.valueOf(t.getMessage()),240)).append("\n"); }
        return s.toString().trim();
    }

    static String visualMountSummary(SharedPreferences p){
        if(p==null) return "preferences unavailable";
        long now=System.currentTimeMillis(); long at=p.getLong("pyramid_mount_event_at",0L);
        long frameAt=p.getLong("pyramid_last_frame_wall_at",0L);
        String screen=p.getString("ui_current_screen","unknown");
        boolean expected="Home".equalsIgnoreCase(screen);
        return "Current screen="+screen+" • expectedOnThisScreen="+expected+"\n"
                +"Mount state="+p.getString("pyramid_mount_state","never-observed")
                +" • attached="+p.getBoolean("pyramid_mount_attached",false)
                +" • visible="+p.getBoolean("pyramid_mount_visible",false)
                +" • size="+p.getInt("pyramid_surface_width",0)+"x"+p.getInt("pyramid_surface_height",0)
                +" • mountAgeMs="+(at<=0?-1:Math.max(0,now-at))+"\n"
                +"Last GL frame ageMs="+(frameAt<=0?-1:Math.max(0,now-frameAt))
                +" • frameCount="+p.getLong("pyramid_frame_count",0L)
                +" • fps="+String.format(Locale.US,"%.1f",p.getFloat("pyramid_last_fps",0f))
                +" • state="+p.getString("pyramid_visual_state","unknown")
                +" • renderer="+p.getString("visual_core_renderer","unknown")+"\n"
                +"Last mount event="+p.getString("pyramid_mount_detail","none");
    }

    private static String coverage(Context context){
        String raw=DeveloperFlightRecorder.readTail(context,4*1024*1024);
        String session=DeveloperFlightRecorder.currentSessionId();
        LinkedHashMap<String,String[]> domains=new LinkedHashMap<>();
        domains.put("APP_LIFECYCLE",new String[]{"\"category\":\"APP\"","PROCESS_START","FOREGROUND","BACKGROUND"});
        domains.put("UI_ACTIONS",new String[]{"\"category\":\"UI\"","SCREEN","ACTION","NAV"});
        domains.put("VOICE_STT_TTS",new String[]{"\"category\":\"VOICE\"","\"category\":\"STT\"","\"category\":\"TTS\"","speech"});
        domains.put("AUDIO_FOCUS",new String[]{"AUDIO_FOCUS","AUDIO_GATE","BARGE_IN"});
        domains.put("IDENTITY_SECURITY",new String[]{"\"category\":\"IDENTITY\"","\"category\":\"SECURITY\"","speaker","ADMIN_"});
        domains.put("AI_ROUTING",new String[]{"\"category\":\"ROUTE\"","\"category\":\"AI\"","FAST_BRAIN","provider","LOCAL_MODEL","EXTERNAL_AI"});
        domains.put("LIVE_WEB_TOOLS",new String[]{"\"category\":\"WEB\"","\"category\":\"TOOL\"","LIVE_","news","weather","market"});
        domains.put("VISUAL_RENDER",new String[]{"\"category\":\"VISUAL\"","PYRAMID","GL_","renderer"});
        domains.put("NATIVE_UPDATE_RELAY",new String[]{"NATIVE_SELF_UPDATE","SELF_UPDATE","MAINTENANCE","BRIDGE","TRANSPORT","TRUSTED_RELAY"});
        domains.put("UPDATE_RECOVERY",new String[]{"UPDATE","POST_UPDATE","RECOVERY","SELF_HEAL","rollback","install"});
        domains.put("EXPORT_RECORDER",new String[]{"EXPORT/","FILE_WRITE","SAVE_PICKER","flight-recorder"});
        domains.put("MEMORY_STATE",new String[]{"MEMORY","VAULT","CONTACT","relationship"});
        int seen=0; StringBuilder s=new StringBuilder();
        for(Map.Entry<String,String[]> e:domains.entrySet()){
            boolean hit=false;
            for(String line:raw.split("\\r?\\n")){
                if(!line.contains("\"sessionId\":\""+session+"\"")) continue;
                String u=line.toUpperCase(Locale.US);
                for(String k:e.getValue()) if(u.contains(k.toUpperCase(Locale.US))){ hit=true; break; }
                if(hit) break;
            }
            if(hit) seen++;
            s.append(hit?"PASS":"NOT YET EXERCISED").append(" • ").append(e.getKey()).append("\n");
        }
        s.insert(0,"Current-session instrumentation observed="+seen+"/"+domains.size()+" domains (unexercised is not automatically a fault).\n");
        s.append("Recorder integrity: ").append(DeveloperFlightRecorder.healthSummary(context));
        return s.toString().trim();
    }

    private static String causalSummary(Context context){
        String raw=DeveloperFlightRecorder.readTail(context,4*1024*1024);
        String session=DeveloperFlightRecorder.currentSessionId();
        LinkedHashMap<String,List<Event>> turns=new LinkedHashMap<>();
        for(String line:raw.split("\\r?\\n")){
            if(line==null || !line.startsWith("{")) continue;
            try{
                JSONObject j=new JSONObject(line);
                if(!session.equals(j.optString("sessionId",""))) continue;
                String corr=j.optString("correlationId",""); if(corr.isEmpty()) continue;
                List<Event> list=turns.get(corr); if(list==null){list=new ArrayList<>();turns.put(corr,list);}
                list.add(new Event(j.optLong("epochMs",0L),j.optLong("correlationGapMs",0L),j.optString("severity","INFO"),j.optString("category",""),j.optString("action",""),j.optString("stage",""),j.optString("detail","")));
            }catch(Throwable ignored){}
        }
        if(turns.isEmpty()) return "No correlated current-session turns yet.";
        List<Map.Entry<String,List<Event>>> entries=new ArrayList<>(turns.entrySet());
        int start=Math.max(0,entries.size()-8); StringBuilder s=new StringBuilder();
        for(int i=start;i<entries.size();i++){
            Map.Entry<String,List<Event>> en=entries.get(i); List<Event> ev=en.getValue();
            Event max=null, fault=null, prior=null, recovery=null; int faultIndex=-1;
            for(int n=0;n<ev.size();n++){
                Event x=ev.get(n); if(max==null||x.gap>max.gap) max=x;
                if(fault==null && ("WARN".equalsIgnoreCase(x.sev)||"ERROR".equalsIgnoreCase(x.sev))){fault=x;faultIndex=n;}
            }
            if(faultIndex>0) prior=ev.get(faultIndex-1);
            if(faultIndex>=0) for(int n=faultIndex+1;n<ev.size();n++){ Event x=ev.get(n); String q=(x.cat+" "+x.act+" "+x.detail).toLowerCase(Locale.US); if(q.contains("recover")||q.contains("complete")||q.contains("success")||q.contains("ready")||q.contains("pass")){recovery=x;break;} }
            s.append(en.getKey()).append(" • events=").append(ev.size());
            if(max!=null) s.append(" • longestGap=").append(max.gap).append("ms at ").append(max.cat).append('/').append(max.act).append(" stage=").append(max.stage);
            s.append("\n");
            if(fault!=null){
                if(prior!=null) s.append("  before: ").append(prior.cat).append('/').append(prior.act).append(" • ").append(clean(prior.detail,150)).append("\n");
                s.append("  fault: ").append(fault.sev).append(' ').append(fault.cat).append('/').append(fault.act).append(" • ").append(clean(fault.detail,190)).append("\n");
                s.append("  recovery: ").append(recovery==null?"none observed in this turn":recovery.cat+"/"+recovery.act+" • "+clean(recovery.detail,160)).append("\n");
            }
        }
        return s.toString().trim();
    }

    private static String regressionSummary(Context context,SharedPreferences p){
        if(p==null)return "preferences unavailable";
        StringBuilder s=new StringBuilder(); int watch=0;
        long latency=p.getLong("last_response_latency_ms",-1L);
        if(latency>5200){watch++;s.append("WATCH • response latency ").append(latency).append(" ms\n");} else s.append("PASS • response latency ").append(latency).append(" ms\n");
        int prompt=Math.max(0,p.getInt("fast_brain_prompt_quality_misses",0)-p.getInt("fast_brain_prompt_quality_misses_baseline_code379",0));
        if(prompt>0){watch++;s.append("WATCH • new Fast Brain output-quality misses=").append(prompt).append("\n");} else s.append("PASS • no new Fast Brain output-quality misses\n");
        int anon=Math.max(0,p.getInt("active_anonymous_speaker_accepted",0)-p.getInt("effectiveness_base_anonymous_accepted",0));
        int unverified=Math.max(0,p.getInt("active_foreground_unverified_accepted",0)-p.getInt("effectiveness_base_foreground_unverified",0));
        if(anon+unverified>0){watch++;s.append("WATCH • unverified/anonymous speech accepts since release=").append(anon+unverified).append("\n");} else s.append("PASS • no unverified/anonymous speech accepts since release\n");
        if(DeveloperFlightRecorder.writeFailureCount()>0 || DeveloperFlightRecorder.droppedEventCount()>0){watch++;s.append("WATCH • recorder integrity ").append(DeveloperFlightRecorder.healthSummary(context)).append("\n");} else s.append("PASS • recorder write/drop counters clean\n");
        String screen=p.getString("ui_current_screen","unknown"); boolean home="Home".equalsIgnoreCase(screen); long frameAt=p.getLong("pyramid_last_frame_wall_at",0L); long age=frameAt<=0?-1:Math.max(0,System.currentTimeMillis()-frameAt);
        if(home && (age<0||age>2500)){watch++;s.append("WATCH • Home expects live pyramid but last GL frame age=").append(age).append(" ms\n");} else s.append("PASS • visual mount expectation consistent with current screen (frameAgeMs=").append(age).append(")\n");
        int stateDiv=p.getInt("conversation_state_divergence",0)-p.getInt("full_remediation_base_state_divergence",0);
        if(stateDiv>0){watch++;s.append("WATCH • authoritative conversation-state divergences=").append(stateDiv).append("\n");} else s.append("PASS • no authoritative conversation-state divergence recorded\n");
        int circuit=Math.max(0,p.getInt("recognizer_restart_circuit_breaks",0)-p.getInt("full_remediation_base_circuit_break",0));
        if(circuit>0){watch++;s.append("WATCH • recognizer recovery circuit opened=").append(circuit).append("\n");} else s.append("PASS • recognizer recovery circuit remained closed\n");
        int owner=Math.max(0,p.getInt("owner_accepted",0)-p.getInt("full_remediation_base_owner_accept",0));
        s.append(owner>0?"PASS":"PENDING").append(" • enrolled-owner voice acceptance test count=").append(owner).append("\n");
        int barge=Math.max(0,p.getInt("spoken_barge_in_count",0)-p.getInt("full_remediation_base_barge_in",0));
        s.append(barge>0?"PASS":"PENDING").append(" • spoken barge-in acceptance test count=").append(barge).append("\n");
        long acceptanceCode=p.getLong("full_remediation_acceptance_version",-1L);
        s.append("Acceptance: ").append(p.getBoolean("full_remediation_acceptance_complete",false)?"PASS":"PENDING").append(" • Code");
        if(acceptanceCode>0L) s.append(acceptanceCode); else s.append("current");
        s.append(" 5-turn runtime gate\n");
        s.insert(0,"Regression status="+(watch==0?"PASS":"WATCH")+" • watchItems="+watch+"\n");
        return s.toString().trim();
    }

    private static String network(Context c){
        try{ ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE); if(cm==null)return "unknown"; Network n=cm.getActiveNetwork(); NetworkCapabilities x=n==null?null:cm.getNetworkCapabilities(n); if(x==null)return "offline"; if(x.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Wi-Fi"; if(x.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "cellular"; if(x.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet"; return "connected"; }catch(Throwable t){return "unknown";}
    }
    private static String clean(String v,int n){ if(v==null)return ""; String x=SecretStore.redact(v).replace('\n',' ').replace('\r',' ').trim(); return x.length()<=n?x:x.substring(0,n)+"…"; }
    private static final class Event{ final long at,gap; final String sev,cat,act,stage,detail; Event(long at,long gap,String sev,String cat,String act,String stage,String detail){this.at=at;this.gap=gap;this.sev=sev;this.cat=cat;this.act=act;this.stage=stage;this.detail=detail;} }
}
