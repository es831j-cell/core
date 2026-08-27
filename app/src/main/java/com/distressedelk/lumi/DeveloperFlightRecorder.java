package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Code374 Black Box R90 operational recorder.
 *
 * Records observable behavior and causality only: visible transcript, state transitions,
 * routes, tool calls/results, network/recovery events and timing. It intentionally does not
 * record hidden model chain-of-thought or credentials.
 */
final class DeveloperFlightRecorder {
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_DETAIL_CHARS = 64 * 1024;
    private static final String CURRENT = "lumi-developer-flight-recorder.jsonl";
    private static final String PREVIOUS = "lumi-developer-flight-recorder.previous.jsonl";
    private static final String SESSION_ID = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-p" + Process.myPid();
    private static final AtomicLong SEQUENCE = new AtomicLong(0L);
    private static final AtomicLong WRITE_FAILURES = new AtomicLong(0L);
    private static final AtomicLong DROPPED_EVENTS = new AtomicLong(0L);
    private static final AtomicLong ROTATIONS = new AtomicLong(0L);
    private static final ConcurrentHashMap<String,Long> FIRST_EVENT_BY_CORRELATION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Long> LAST_EVENT_BY_CORRELATION = new ConcurrentHashMap<>();
    private static volatile String lastWriteError = "none";

    private DeveloperFlightRecorder(){}

    static synchronized void record(Context context, SharedPreferences prefs, long turn,
                                    String category, String action, String detail,
                                    String route, String model, String stage,
                                    boolean aiBusy, boolean conversationMode, boolean manualStop) {
        if(context==null){ DROPPED_EVENTS.incrementAndGet(); return; }
        try{
            rotateIfNeeded(context);
            long now=System.currentTimeMillis();
            String correlationId=SESSION_ID+"-t"+Math.max(0L,turn);
            Long first=FIRST_EVENT_BY_CORRELATION.putIfAbsent(correlationId,now);
            long firstAt=first==null?now:first;
            Long prior=LAST_EVENT_BY_CORRELATION.put(correlationId,now);
            long gap=prior==null?0L:Math.max(0L,now-prior);

            JSONObject event=new JSONObject();
            event.put("timestamp",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.US).format(new Date(now)));
            event.put("epochMs",now);
            event.put("sessionId",SESSION_ID);
            event.put("correlationId",correlationId);
            event.put("sequence",SEQUENCE.incrementAndGet());
            event.put("turn",turn);
            event.put("severity",severity(category,action,detail,manualStop));
            event.put("category",clean(category,120));
            event.put("action",clean(action,240));
            event.put("detail",clean(detail,MAX_DETAIL_CHARS));
            event.put("route",clean(route,240));
            event.put("model",clean(model,240));
            event.put("stage",clean(stage,240));
            event.put("correlationGapMs",gap);
            event.put("turnElapsedMs",Math.max(0L,now-firstAt));
            event.put("aiBusy",aiBusy);
            event.put("conversationMode",conversationMode);
            event.put("manualStop",manualStop);
            event.put("thread",Thread.currentThread().getName());
            event.put("pid",Process.myPid());
            event.put("uptimeMs",SystemClock.elapsedRealtime());
            Runtime runtime=Runtime.getRuntime();
            event.put("heapUsedKb",Math.max(0L,(runtime.totalMemory()-runtime.freeMemory())/1024L));
            event.put("heapCommittedKb",runtime.totalMemory()/1024L);
            event.put("heapMaxKb",runtime.maxMemory()/1024L);
            if(prefs!=null){
                event.put("lastRoute",clean(prefs.getString("last_route",""),240));
                event.put("lastActionReason",clean(prefs.getString("last_action_reason",""),1200));
                event.put("screen",clean(prefs.getString("ui_current_screen","unknown"),160));
                event.put("inputMode",clean(prefs.getString("identity_input_mode","unknown"),80));
                event.put("securityState",clean(prefs.getString("identity_security_state","unknown"),120));
                event.put("visualRenderer",clean(prefs.getString("visual_core_renderer","unknown"),120));
                event.put("pyramidMount",clean(prefs.getString("pyramid_mount_state","unknown"),120));
                event.put("pyramidState",clean(prefs.getString("pyramid_visual_state","unknown"),80));
                event.put("speakerLock",clean(SessionSpeakerLock.status(),160));
                String repairAction=prefs.getString("maintenance_runtime_repair_action",prefs.getString("last_runtime_repair_action",""));
                event.put("nativeUpdateLastRepair",clean(repairAction,240));
                event.put("nativeUpdateRepairState",clean(prefs.getString("maintenance_runtime_repair_state",""),120));
                event.put("nativeUpdateRepairResult",clean(prefs.getString("maintenance_runtime_repair_result",""),1200));
            }
            appendLine(new File(context.getFilesDir(),CURRENT),event.toString()+"\n");
            lastWriteError="none";
        }catch(Throwable t){
            WRITE_FAILURES.incrementAndGet();
            lastWriteError=clean(t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()),320);
        }
    }

    static String readTail(Context context,int maxChars){
        if(context==null) return "";
        int cap=Math.max(4000,maxChars);
        StringBuilder out=new StringBuilder();
        File previous=new File(context.getFilesDir(),PREVIOUS);
        File current=new File(context.getFilesDir(),CURRENT);
        int previousBudget=Math.min(cap/3,2*1024*1024);
        if(previous.exists() && previousBudget>0) out.append(readFileTail(previous,previousBudget));
        int remaining=Math.max(0,cap-out.length());
        if(current.exists() && remaining>0) out.append(readFileTail(current,remaining));
        String all=SecretStore.redact(out.toString());
        return all.length()>cap?"[older flight-recorder events omitted by export guard]\n"+SecretStore.redact(all.substring(all.length()-cap)):all;
    }

    static String currentSessionId(){ return SESSION_ID; }

    static String healthSummary(){
        return "session="+SESSION_ID
                +" • sequence="+SEQUENCE.get()
                +" • writeFailures="+WRITE_FAILURES.get()
                +" • droppedEvents="+DROPPED_EVENTS.get()
                +" • rotations="+ROTATIONS.get()
                +" • lastWriteError="+lastWriteError;
    }

    static String healthSummary(Context context){
        String base=healthSummary();
        if(context==null) return base;
        try{
            File current=new File(context.getFilesDir(),CURRENT), previous=new File(context.getFilesDir(),PREVIOUS);
            return base+" • currentBytes="+(current.exists()?current.length():0L)+" • previousBytes="+(previous.exists()?previous.length():0L);
        }catch(Throwable t){ return base; }
    }

    static long writeFailureCount(){ return WRITE_FAILURES.get(); }
    static long droppedEventCount(){ return DROPPED_EVENTS.get(); }

    static void clear(Context context){
        if(context==null) return;
        try{ new File(context.getFilesDir(),CURRENT).delete(); }catch(Throwable ignored){}
        try{ new File(context.getFilesDir(),PREVIOUS).delete(); }catch(Throwable ignored){}
        FIRST_EVENT_BY_CORRELATION.clear();
        LAST_EVENT_BY_CORRELATION.clear();
    }

    private static void rotateIfNeeded(Context context){
        File current=new File(context.getFilesDir(),CURRENT);
        if(!current.exists() || current.length()<=MAX_BYTES) return;
        File previous=new File(context.getFilesDir(),PREVIOUS);
        try{ if(previous.exists()) previous.delete(); }catch(Throwable ignored){}
        if(current.renameTo(previous)){ ROTATIONS.incrementAndGet(); return; }
        // Rename can fail on some Android storage/filesystem states. Copy, then truncate,
        // rather than silently losing the recorder or allowing unbounded growth.
        try(FileInputStream in=new FileInputStream(current); FileOutputStream out=new FileOutputStream(previous,false)){
            byte[] b=new byte[65536]; int n;
            while((n=in.read(b))>0) out.write(b,0,n);
            out.flush(); out.getFD().sync();
        }catch(Throwable t){
            WRITE_FAILURES.incrementAndGet();
            lastWriteError="rotation copy: "+clean(t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()),260);
            return;
        }
        try(FileOutputStream truncate=new FileOutputStream(current,false)){ truncate.getFD().sync(); ROTATIONS.incrementAndGet(); }
        catch(Throwable t){ WRITE_FAILURES.incrementAndGet(); lastWriteError="rotation truncate: "+clean(String.valueOf(t.getMessage()),260); }
    }

    private static void appendLine(File file,String line) throws Exception{
        byte[] bytes=line.getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(file,true)){
            out.write(bytes);
            out.flush();
        }
    }

    private static String readFileTail(File file,int maxChars){
        if(file==null || !file.exists() || maxChars<=0) return "";
        long byteBudget=Math.max(8192L,Math.min((long)maxChars*2L,4L*1024L*1024L));
        try(RandomAccessFile raf=new RandomAccessFile(file,"r")){
            long len=raf.length(); long start=Math.max(0L,len-byteBudget);
            raf.seek(start);
            byte[] bytes=new byte[(int)(len-start)];
            raf.readFully(bytes);
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(start>0){ int nl=text.indexOf('\n'); if(nl>=0 && nl+1<text.length()) text=text.substring(nl+1); }
            if(text.length()>maxChars) text=text.substring(text.length()-maxChars);
            return text;
        }catch(Throwable t){ return "[flight recorder read failed: "+clean(String.valueOf(t.getMessage()),240)+"]\n"; }
    }

    private static String severity(String category,String action,String detail,boolean manualStop){
        String x=((category==null?"":category)+" "+(action==null?"":action)+" "+(detail==null?"":detail)).toLowerCase(Locale.US);

        // Code381: functional failures are errors even when Android delivered a normal callback.
        // This prevents a deaf recognizer or impossible conversation state from hiding behind errors=0.
        if(x.contains("ready_but_deaf") || x.contains("recovery_circuit_open") || x.contains("state_divergence")
                || x.contains("watchdog_start_timeout") || x.contains("watchdog_completion_timeout")) return "ERROR";

        // Explicit failures stay loud. Healthy counters such as writeFailures=0 are not failures.
        boolean healthyFailureCounter=x.contains("writefailures=0") || x.contains("failure streak=0")
                || x.contains("no failure") || x.contains("no failures");
        boolean explicitFailure=x.contains("fatal") || x.contains("uncaught") || x.contains("crash")
                || x.contains("exception") || x.contains("status=fail") || x.contains("fail •")
                || x.contains(" failed") || x.contains(" error") || (x.contains("failure") && !healthyFailureCounter);
        if(explicitFailure) return "ERROR";

        // R89: expected control-flow must not poison health/warning counts.
        if(isExpectedControlFlow(x,manualStop)) return "INFO";

        boolean actualTimeout=x.contains("timed out") || x.contains("-timeout") || x.contains("timeout waiting")
                || x.contains("timeout after") || x.contains("timeout occurred") || x.contains("watchdog timeout")
                || x.contains("timeout reason");
        boolean activeQuarantine=x.contains("quarantined=true") || x.contains("quarantine active")
                || x.contains("quarantined after") || x.contains("entered quarantine");
        boolean actualMismatch=x.contains("mismatch") && !x.contains("mismatch=false") && !x.contains("no mismatch");
        if(actualTimeout || x.contains("retry") || x.contains("recovered") || x.contains("reject")
                || activeQuarantine || actualMismatch || x.contains("blocked")) return "WARN";
        return "INFO";
    }

    private static boolean isExpectedControlFlow(String x,boolean manualStop){
        if(x.contains("quarantined=false") || x.contains("quarantine clear")) return true;
        if(x.contains("may hedge after timeout")) return true;
        if(x.contains("status=complete") && x.contains("pass •") && !x.contains("fail •")) return true;
        if(x.contains("blocked by hard manual-stop latch") || x.contains("blocked by manual stop latch")
                || x.contains("auto-listen blocked by manual stop") || x.contains("suppressed by manual stop latch")
                || x.contains("startup auto-listen suppressed by manual stop latch")) return true;
        return manualStop && x.contains("speech") && x.contains("manual-stop latch");
    }

    private static String clean(String value,int max){
        String x=SecretStore.redact(value==null?"":value);
        if(x.length()>max) x=x.substring(0,max)+"…[truncated]";
        return x;
    }
}
