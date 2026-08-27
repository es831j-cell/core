package com.distressedelk.lumi;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Code338: bounded maintenance continuation with canonical-path grounding.
 *
 * Only successful canonical-source paths are retained. Failed or hallucinated reads are never
 * promoted into continuation state. The model is explicitly grounded to Lumi's real Java package
 * and high-value source locations so reconnaissance cannot drift into invented com/lumi Kotlin
 * paths and consume the patch/build budget.
 */
final class MaintenanceWorkflowState {
    private static final String QUERIES="maintenance_workflow_source_queries";
    private static final String PATHS="maintenance_workflow_source_paths";
    private static final String READS="maintenance_workflow_source_reads";
    private static final String LAST="maintenance_workflow_last_activity_at";
    private static final int MAX_QUERIES=8;
    private static final int MAX_PATHS=16;
    private static final int MAX_READS=8;

    private static final String APP_JAVA_ROOT="app/src/main/java/com/distressedelk/lumi/";
    private static final String APP_RES_ROOT="app/src/main/res/";
    private static final String APP_ASSET_ROOT="app/src/main/assets/";

    private MaintenanceWorkflowState(){}

    static void clear(SharedPreferences p){
        if(p==null)return;
        p.edit().remove(QUERIES).remove(PATHS).remove(READS).remove(LAST).apply();
    }

    static void rememberToolResult(SharedPreferences p,String name,JSONObject args,String result){
        if(p==null || name==null)return;
        try{
            if("search_canonical_source".equals(name)){
                add(p,QUERIES,norm(args==null?"":args.optString("query","")),MAX_QUERIES);
                JSONObject r=new JSONObject(result==null?"{}":result);
                JSONArray a=r.optJSONArray("results");
                if(r.optBoolean("ok",false) && a!=null)for(int i=0;i<a.length();i++){
                    JSONObject x=a.optJSONObject(i);
                    if(x!=null){
                        String path=safeCanonicalPath(x.optString("path",""));
                        if(!path.isEmpty())add(p,PATHS,path,MAX_PATHS);
                    }
                }
            }else if("read_canonical_source_file".equals(name)){
                // Code338: only a successful canonical read can teach the continuation state a path.
                // This scrubs the exact Code337 failure where a failed invented MainActivity.kt path
                // was remembered and later treated as authoritative.
                JSONObject r=new JSONObject(result==null?"{}":result);
                if(r.optBoolean("ok",false)){
                    String returned=safeCanonicalPath(r.optString("path",""));
                    String requested=safeCanonicalPath(args==null?"":args.optString("path",""));
                    String path=!returned.isEmpty()?returned:requested;
                    if(!path.isEmpty()){
                        add(p,READS,path,MAX_READS);
                        add(p,PATHS,path,MAX_PATHS);
                    }
                }
            }
            p.edit().putLong(LAST,System.currentTimeMillis()).apply();
        }catch(Throwable ignored){}
    }

    static boolean seenSearch(SharedPreferences p,String query){ return contains(load(p,QUERIES,false),norm(query)); }
    static boolean seenRead(SharedPreferences p,String path){ return contains(load(p,READS,true),safeCanonicalPath(path)); }
    static int searchCount(SharedPreferences p){ return load(p,QUERIES,false).size(); }

    static String canonicalGrounding(String userText){
        String u=norm(userText);
        StringBuilder s=new StringBuilder();
        s.append("Canonical source grounding (authoritative): Lumi's Java package is com.distressedelk.lumi. ")
                .append("The main activity is ").append(APP_JAVA_ROOT).append("MainActivity.java. ")
                .append("Do not invent app/src/main/java/com/lumi or MainActivity.kt. ")
                .append("Only a path returned by search_canonical_source or a successful read_canonical_source_file is discovered source.");
        if(u.contains("mobius") || u.contains("möbius") || u.contains("animation"))
            s.append(" Animation/Möbius code is primarily ").append(APP_JAVA_ROOT).append("Mobius3DView.java and MainActivity.java.");
        if(u.contains("button") || u.contains("color") || u.contains("colour") || u.contains("ui") || u.contains("style") || u.contains("screen"))
            s.append(" App UI/button work should inspect ").append(APP_JAVA_ROOT).append("MainActivity.java and ").append(APP_RES_ROOT).append(" resources.");
        if(u.contains("speech") || u.contains("voice") || u.contains("listen") || u.contains("recogn"))
            s.append(" Speech/listening orchestration is primarily ").append(APP_JAVA_ROOT).append("MainActivity.java.");
        if(u.contains("improvement") || u.contains("suggest"))
            s.append(" Improvement suggestions are primarily ").append(APP_JAVA_ROOT).append("ImprovementAdvisor.java.");
        if(u.contains("memory") || u.contains("vault"))
            s.append(" Memory-vault code is primarily ").append(APP_JAVA_ROOT).append("LumiMemoryVault.java.");
        s.append(" Resources live under ").append(APP_RES_ROOT).append(" and assets under ").append(APP_ASSET_ROOT)
                .append(" Code388 has no Guardian companion; executable Lumi source is app-owned.");
        return s.toString();
    }

    static String promptSummary(SharedPreferences p){
        Set<String> q=load(p,QUERIES,false), paths=load(p,PATHS,true), reads=load(p,READS,true);
        if(q.isEmpty() && paths.isEmpty() && reads.isEmpty())return "";
        StringBuilder s=new StringBuilder("Maintenance workflow continuation state (non-secret, authoritative for avoiding duplicate reconnaissance):");
        if(!q.isEmpty())s.append("\n- source searches already completed: ").append(join(q));
        if(!paths.isEmpty())s.append("\n- verified source paths already discovered: ").append(join(paths));
        if(!reads.isEmpty())s.append("\n- source files successfully read in this workflow: ").append(join(reads));
        s.append("\nFailed reads are intentionally excluded. Do not restart completed discovery. Re-read only a verified file needed for the exact bounded maintenance transaction. Code388 may stage a verified bridge-core source snapshot or bounded canonical-source patch, but build/sign must remain inside the owner-approved trusted relay; Lumi must verify the returned APK and Android must present its normal installer approval before post-install validation can be claimed complete.");
        return s.toString();
    }

    static JSONObject duplicateSearchResult(SharedPreferences p,String query,String reason){
        JSONArray paths=new JSONArray(); for(String x:load(p,PATHS,true))paths.put(x);
        JSONArray reads=new JSONArray(); for(String x:load(p,READS,true))reads.put(x);
        JSONObject out=new JSONObject();
        try{
            out.put("ok",false).put("state","SOURCE_SEARCH_SKIPPED")
                    .put("query",norm(query)).put("reason",reason==null?"duplicate or exhausted discovery":reason)
                    .put("known_paths",paths).put("already_read",reads)
                    .put("canonical_grounding",canonicalGrounding(query))
                    .put("next","Use a verified known path, or the grounded real Lumi package path if no verified path exists; perform the exact read needed, then queue/apply the bounded core patch instead of repeating source search.");
        }catch(Exception ignored){}
        return out;
    }

    private static void add(SharedPreferences p,String key,String value,int max){
        if(value==null || value.isEmpty())return;
        LinkedHashSet<String> set=new LinkedHashSet<>(load(p,key,PATHS.equals(key)||READS.equals(key)));
        set.remove(value); set.add(value);
        while(set.size()>max){ String first=set.iterator().next(); set.remove(first); }
        JSONArray a=new JSONArray(); for(String x:set)a.put(x);
        p.edit().putString(key,a.toString()).apply();
    }

    private static LinkedHashSet<String> load(SharedPreferences p,String key,boolean pathsOnly){
        LinkedHashSet<String> out=new LinkedHashSet<>(); if(p==null)return out;
        try{
            JSONArray a=new JSONArray(p.getString(key,"[]"));
            for(int i=0;i<a.length();i++){
                String x=a.optString(i,"");
                if(pathsOnly)x=safeCanonicalPath(x);
                if(!x.isEmpty())out.add(x);
            }
        }catch(Throwable ignored){}
        return out;
    }

    private static boolean contains(Set<String> s,String value){ return value!=null && !value.isEmpty() && s.contains(value); }
    private static String join(Set<String> s){ StringBuilder b=new StringBuilder(); for(String x:s){if(b.length()>0)b.append(", ");b.append(x);} return b.toString(); }
    private static String norm(String s){ return s==null?"":s.toLowerCase(Locale.US).replace('-',' ').trim(); }

    private static String safeCanonicalPath(String s){
        if(s==null)return "";
        String x=s.replace('\\','/').trim();
        while(x.startsWith("./"))x=x.substring(2);
        if(x.isEmpty() || x.startsWith("/") || x.contains("../") || x.indexOf('\0')>=0)return "";
        // Code338 legacy-state scrub: reject the hallucinated package root seen in the Code337 log.
        if(x.startsWith("app/src/main/java/com/lumi/") || x.startsWith("app/src/main/kotlin/com/lumi/"))return "";
        if(x.startsWith("app/src/main/java/") && !x.startsWith(APP_JAVA_ROOT))return "";
        if(x.startsWith("app/src/main/kotlin/") && !x.startsWith("app/src/main/kotlin/com/distressedelk/lumi/"))return "";
        boolean allowed=x.startsWith(APP_JAVA_ROOT) || x.startsWith("app/src/main/kotlin/com/distressedelk/lumi/")
                || x.startsWith(APP_RES_ROOT) || x.startsWith(APP_ASSET_ROOT)
                ;
        return allowed?x:"";
    }
}
