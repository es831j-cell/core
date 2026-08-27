package com.distressedelk.lumi;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Code349 cash-safe multi-provider conversation fallback.
 *
 * Ordinary conversation may fail over across user-configured OpenAI-compatible providers.
 * Maintenance/update execution is deliberately NOT delegated here: local deterministic
 * maintenance routing and Lumi local authorization remain authoritative, so losing cloud credits cannot
 * disable Lumi's update controls.
 *
 * Provider secrets are read only from SecretStore and are sent only in HTTPS auth headers.
 * They are never included in diagnostics, prompts, Memory Vault, or exported state.
 */
final class CloudBrainRouter {
    interface Callback {
        void onSuccess(String reply, String providerId, String model);
        void onFailure(String summary);
    }

    static final class Provider {
        final String id;
        final String label;
        final String endpoint;
        final String key;
        final String model;

        Provider(String id, String label, String endpoint, String key, String model) {
            this.id=id; this.label=label; this.endpoint=endpoint; this.key=key; this.model=model;
        }
    }

    private CloudBrainRouter() {}

    static boolean anyConfigured(SharedPreferences prefs) {
        return !providers(prefs).isEmpty();
    }

    static boolean hasProvider(SharedPreferences prefs, String id) {
        if (prefs == null || id == null) return false;
        for (Provider p : providers(prefs)) if (id.equals(p.id)) return true;
        return false;
    }

    static String configuredProviderNames(SharedPreferences prefs) {
        List<Provider> ps=providers(prefs);
        if(ps.isEmpty()) return "none";
        StringBuilder b=new StringBuilder();
        for(Provider p:ps){ if(b.length()>0)b.append(" → "); b.append(p.label); }
        return b.toString();
    }

    static List<Provider> providers(SharedPreferences prefs) {
        ArrayList<Provider> out=new ArrayList<>();
        if(prefs==null) return out;
        boolean strict=prefs.getBoolean("ai_strict_zero_cash",true);

        // OpenRouter's explicit /free router is the first cash-safe cloud option.
        String openrouter=SecretStore.get(prefs,"openrouter_api_key").trim();
        String openrouterModel=model(prefs,"openrouter_model","openrouter/free");
        boolean openrouterZeroPrice="openrouter/free".equalsIgnoreCase(openrouterModel) || openrouterModel.toLowerCase(Locale.US).endsWith(":free");
        if(!openrouter.isEmpty() && prefs.getBoolean("openrouter_enabled",true) && (!strict || openrouterZeroPrice)){
            out.add(new Provider("openrouter","OpenRouter Free",
                    "https://openrouter.ai/api/v1/chat/completions",openrouter,openrouterModel));
        }

        // Direct free-tier providers can be used in strict mode only after the owner explicitly
        // confirms that the connected account/project will not incur cash charges.
        String groq=SecretStore.get(prefs,"groq_api_key").trim();
        if(!groq.isEmpty() && prefs.getBoolean("groq_enabled",true)
                && (!strict || prefs.getBoolean("groq_zero_cash_confirmed",false) || prefs.getBoolean("groq_usage_authorized",false))){
            out.add(new Provider("groq","Groq",
                    "https://api.groq.com/openai/v1/chat/completions",groq,
                    model(prefs,"groq_model","openai/gpt-oss-20b")));
        }

        String gemini=SecretStore.get(prefs,"gemini_api_key").trim();
        if(!gemini.isEmpty() && prefs.getBoolean("gemini_enabled",true)
                && (!strict || prefs.getBoolean("gemini_zero_cash_confirmed",false) || prefs.getBoolean("gemini_usage_authorized",false))){
            out.add(new Provider("gemini","Gemini",
                    "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",gemini,
                    model(prefs,"gemini_model","gemini-3.7-flash")));
        }

        String cf=SecretStore.get(prefs,"cloudflare_api_key").trim();
        String account=prefs.getString("cloudflare_account_id","").trim();
        if(!cf.isEmpty() && !account.isEmpty() && prefs.getBoolean("cloudflare_enabled",true)
                && (!strict || prefs.getBoolean("cloudflare_zero_cash_confirmed",false) || prefs.getBoolean("cloudflare_usage_authorized",false))){
            String endpoint="https://api.cloudflare.com/client/v4/accounts/"+urlSegment(account)+"/ai/v1/chat/completions";
            out.add(new Provider("cloudflare","Cloudflare Workers AI",endpoint,cf,
                    model(prefs,"cloudflare_model","@cf/openai/gpt-oss-20b")));
        }
        // Code370: once providers have been validated, prefer the healthy/fastest connection
        // instead of permanently pinning every turn to the first configured service.
        out.sort((a,b)->{
            String ah=prefs.getString("fallback_"+a.id+"_health","");
            String bh=prefs.getString("fallback_"+b.id+"_health","");
            boolean ar="ready".equals(ah), br="ready".equals(bh);
            if(ar!=br) return ar?-1:1;
            long al=prefs.getLong("fallback_"+a.id+"_latency_ms",Long.MAX_VALUE);
            long bl=prefs.getLong("fallback_"+b.id+"_latency_ms",Long.MAX_VALUE);
            if(al!=bl) return Long.compare(al,bl);
            return 0;
        });
        return out;
    }

    static String healthSummary(SharedPreferences prefs){
        String[] ids={"openrouter","groq","gemini","cloudflare"};
        StringBuilder b=new StringBuilder();
        for(String id:ids){
            if(b.length()>0)b.append(" | ");
            String state=prefs.getString("fallback_"+id+"_health","");
            if(state.isEmpty()){
                boolean saved=!SecretStore.get(prefs,id+"_api_key").trim().isEmpty();
                state=saved?"saved/not yet tested":"not configured";
            }
            long cool=prefs.getLong("fallback_"+id+"_cooldown_until",0L);
            b.append(id).append("=").append(state);
            if(cool>System.currentTimeMillis()) b.append("(cooldown ").append(Math.max(1L,(cool-System.currentTimeMillis())/1000L)).append("s)");
        }
        return b.toString();
    }

    static void validateConfigured(SharedPreferences prefs){
        if(prefs==null) return;
        long now=System.currentTimeMillis();
        for(Provider p:providers(prefs)){
            long last=prefs.getLong("fallback_"+p.id+"_last_test_at",0L);
            String health=prefs.getString("fallback_"+p.id+"_health","");
            if("ready".equals(health) && last>0L && now-last<6L*60L*60L*1000L) continue;
            new Thread(()->{
                long started=System.currentTimeMillis();
                prefs.edit().putString("fallback_"+p.id+"_health","testing")
                        .putLong("fallback_"+p.id+"_last_test_at",started).apply();
                try{
                    String reply=call(p,"Reply with exactly: Lumi provider ready","","Lumi provider test");
                    if(reply==null || reply.trim().isEmpty()) throw new IllegalStateException("empty provider test response");
                    long latency=Math.max(1L,System.currentTimeMillis()-started);
                    prefs.edit().putString("fallback_"+p.id+"_health","ready")
                            .putLong("fallback_"+p.id+"_latency_ms",latency)
                            .putLong("fallback_"+p.id+"_last_success_at",System.currentTimeMillis())
                            .remove("fallback_"+p.id+"_cooldown_until").apply();
                }catch(Throwable t){
                    String safe=safeFailure(t);
                    prefs.edit().putString("fallback_"+p.id+"_health","failed: "+bounded(safe,90))
                            .putString("fallback_"+p.id+"_last_test_error",bounded(safe,160))
                            .putLong("fallback_"+p.id+"_last_test_failed_at",System.currentTimeMillis()).apply();
                }
            },"LumiProviderTest-"+p.id).start();
        }
    }

    static void request(SharedPreferences prefs, String instructions, String recentTranscript,
                        String userText, Callback callback) {
        new Thread(() -> {
            List<Provider> list=providers(prefs);
            if(list.isEmpty()) { callback.onFailure("No free fallback provider is configured."); return; }
            StringBuilder failures=new StringBuilder();
            long now=System.currentTimeMillis();
            boolean attempted=false;
            for(Provider p:list){
                long cooldownUntil=prefs.getLong("fallback_"+p.id+"_cooldown_until",0L);
                if(cooldownUntil>now){
                    prefs.edit().putString("fallback_"+p.id+"_health","cooldown").apply();
                    if(failures.length()>0) failures.append(" | ");
                    failures.append(p.id).append(": cooling down");
                    continue;
                }
                attempted=true;
                long providerStarted=System.currentTimeMillis();
                prefs.edit().putString("fallback_"+p.id+"_health","trying")
                        .putLong("fallback_"+p.id+"_last_attempt_at",providerStarted).apply();
                try{
                    String reply=call(p,instructions,recentTranscript,userText);
                    if(reply==null || reply.trim().isEmpty()) throw new IllegalStateException("empty response");
                    prefs.edit()
                            .putString("fallback_last_provider",p.id)
                            .putString("fallback_last_model",p.model)
                            .putLong("fallback_last_success_at",System.currentTimeMillis())
                            .putLong("fallback_"+p.id+"_latency_ms",Math.max(1L,System.currentTimeMillis()-providerStarted))
                            .putLong("fallback_"+p.id+"_last_success_at",System.currentTimeMillis())
                            .remove("fallback_last_error")
                            .remove("fallback_"+p.id+"_cooldown_until")
                            .putString("fallback_"+p.id+"_health","ready")
                            .apply();
                    callback.onSuccess(reply.trim(),p.id,p.model);
                    return;
                }catch(Throwable t){
                    String safe=safeFailure(t);
                    if(failures.length()>0) failures.append(" | ");
                    failures.append(p.id).append(": ").append(safe);
                    long cooldown=cooldownMs(t);
                    prefs.edit().putString("fallback_last_failed_provider",p.id)
                            .putString("fallback_last_error",safe)
                            .putLong("fallback_last_failure_at",System.currentTimeMillis())
                            .putLong("fallback_"+p.id+"_cooldown_until",System.currentTimeMillis()+cooldown)
                            .putString("fallback_"+p.id+"_health","failed: "+bounded(safe,90)).apply();
                }
            }
            if(!attempted && failures.length()==0) failures.append("Every configured free provider is cooling down.");
            callback.onFailure(failures.length()==0?"Every configured free fallback provider failed.":failures.toString());
        },"LumiCloudFallback").start();
    }

    static void requestConsensus(SharedPreferences prefs,String instructions,String recentTranscript,String userText,Callback callback){
        new Thread(() -> {
            List<Provider> list=providers(prefs);
            if(list.isEmpty()){callback.onFailure("No free AI provider is configured.");return;}
            ArrayList<String> answers=new ArrayList<>(); ArrayList<String> labels=new ArrayList<>(); StringBuilder failures=new StringBuilder();
            int limit=Math.min(3,list.size());
            for(int i=0;i<limit;i++){
                Provider p=list.get(i);
                try{
                    String answer=call(p,instructions,recentTranscript,userText);
                    if(answer!=null && !answer.trim().isEmpty()){answers.add(answer.trim());labels.add(p.label);prefs.edit().putString("fallback_"+p.id+"_health","ready").apply();}
                }catch(Throwable t){if(failures.length()>0)failures.append(" | ");failures.append(p.id).append(": ").append(safeFailure(t));}
            }
            if(answers.isEmpty()){callback.onFailure(failures.length()==0?"All free AI providers failed.":failures.toString());return;}
            if(answers.size()==1){callback.onSuccess(answers.get(0),labels.get(0),"single-source");return;}
            StringBuilder evidence=new StringBuilder("Independent free-AI answers:\n");
            for(int i=0;i<answers.size();i++)evidence.append("SOURCE ").append(i+1).append(" (").append(labels.get(i)).append("):\n").append(bounded(answers.get(i),2200)).append("\n\n");
            Provider synth=list.get(0);
            try{
                String merged=call(synth,"Compare the independent answers below. Prefer claims that agree across sources, explicitly flag disagreements, and answer the user's question concisely. Do not invent consensus.\n\n"+bounded(evidence.toString(),6500),"",userText);
                prefs.edit().putInt("free_ai_consensus_sources",answers.size()).putString("free_ai_consensus_labels",labels.toString()).putLong("free_ai_consensus_at",System.currentTimeMillis()).apply();
                callback.onSuccess(merged,synth.id+"+consensus","consensus-"+answers.size());
            }catch(Throwable t){
                StringBuilder fallback=new StringBuilder("I checked ").append(answers.size()).append(" free AI sources. ");
                fallback.append(answers.get(0));
                if(answers.size()>1 && !answers.get(1).equalsIgnoreCase(answers.get(0))) fallback.append("\n\nAnother source differed: ").append(bounded(answers.get(1),900));
                callback.onSuccess(fallback.toString(),"multi-free-ai","consensus-fallback");
            }
        },"LumiFreeAIConsensus").start();
    }

    private static String call(Provider p, String instructions, String recentTranscript, String userText) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(p.endpoint).openConnection();
        try{
            c.setRequestMethod("POST");
            c.setConnectTimeout(4000); c.setReadTimeout(10000); c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            c.setRequestProperty("Authorization","Bearer "+p.key);
            c.setRequestProperty("User-Agent","Lumi/4.4.1 FullRemediation/3");
            if("openrouter".equals(p.id)){
                c.setRequestProperty("X-Title","Lumi");
            }

            JSONArray messages=new JSONArray();
            String system=(instructions==null?"":instructions.trim());
            if(system.length()>5000) system=system.substring(0,5000);
            if(!system.isEmpty()) messages.put(new JSONObject().put("role","system").put("content",system));
            String recent=recentTranscript==null?"":recentTranscript.trim();
            if(recent.length()>3500) recent=recent.substring(recent.length()-3500);
            if(!recent.isEmpty()) messages.put(new JSONObject().put("role","system").put("content","Recent conversation context:\n"+recent));
            messages.put(new JSONObject().put("role","user").put("content",userText==null?"":userText));

            JSONObject body=new JSONObject();
            body.put("model",p.model);
            body.put("messages",messages);
            body.put("stream",false);
            body.put("temperature",0.45);
            body.put("max_tokens",240);

            try(OutputStream os=c.getOutputStream()){
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code=c.getResponseCode();
            InputStream is=(code>=200&&code<300)?c.getInputStream():c.getErrorStream();
            String raw=readAll(is);
            if(code<200||code>=300) throw new ProviderException(code,friendlyError(raw));
            JSONObject root=new JSONObject(raw);
            JSONArray choices=root.optJSONArray("choices");
            if(choices==null||choices.length()==0) throw new IllegalStateException("no choices returned");
            JSONObject choice=choices.optJSONObject(0);
            JSONObject message=choice==null?null:choice.optJSONObject("message");
            String finishReason=choice==null?"":choice.optString("finish_reason","");
            String text=message==null?"":message.optString("content","");
            if("length".equalsIgnoreCase(finishReason)) throw new IllegalStateException("truncated response: provider hit output token limit");
            if(text.trim().isEmpty()){
                // Some compatibility layers may expose a plain response string.
                text=root.optString("response",root.optString("result",""));
            }
            return text;
        }finally{ c.disconnect(); }
    }

    private static String model(SharedPreferences prefs,String key,String fallback){
        String v=prefs.getString(key,fallback); return v==null||v.trim().isEmpty()?fallback:v.trim();
    }

    private static String urlSegment(String value){
        // Cloudflare account IDs are opaque URL path tokens. Keep only conservative token chars.
        return value==null?"":value.replaceAll("[^A-Za-z0-9_-]","");
    }

    private static String readAll(InputStream is) throws Exception {
        if(is==null)return "";
        StringBuilder b=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){
            String line; while((line=r.readLine())!=null){ b.append(line); if(b.length()>16000)break; }
        }
        return b.toString();
    }

    private static String friendlyError(String raw){
        if(raw==null||raw.trim().isEmpty())return "provider returned no error body";
        try{
            JSONObject j=new JSONObject(raw);
            Object e=j.opt("error");
            if(e instanceof JSONObject){
                String m=((JSONObject)e).optString("message",""); if(!m.isEmpty())return bounded(m,280);
            }
            if(e instanceof String && !((String)e).isEmpty())return bounded((String)e,280);
            String m=j.optString("message",""); if(!m.isEmpty())return bounded(m,280);
        }catch(Throwable ignored){}
        return bounded(raw.replace('\n',' ').replace('\r',' '),280);
    }

    private static long cooldownMs(Throwable t){
        if(t instanceof ProviderException){
            int c=((ProviderException)t).code;
            if(c==401 || c==403) return 60L*60L*1000L;
            if(c==429) return 10L*60L*1000L;
            if(c>=500) return 3L*60L*1000L;
        }
        String m=t==null?"":String.valueOf(t.getMessage()).toLowerCase();
        if(m.contains("empty response") || m.contains("no error body") || m.contains("truncated response")) return 5L*60L*1000L;
        if(m.contains("timeout") || m.contains("timed out")) return 2L*60L*1000L;
        return 60L*1000L;
    }

    private static String safeFailure(Throwable t){
        String m=t==null?"unknown error":String.valueOf(t.getMessage());
        if(t instanceof ProviderException) m="HTTP "+((ProviderException)t).code+": "+m;
        m=m.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]+","Bearer [redacted]");
        return bounded(m,320);
    }

    private static String bounded(String s,int max){
        String v=s==null?"":s.trim(); return v.length()<=max?v:v.substring(0,max);
    }

    private static final class ProviderException extends Exception {
        final int code;
        ProviderException(int code,String message){ super(message); this.code=code; }
    }
}
