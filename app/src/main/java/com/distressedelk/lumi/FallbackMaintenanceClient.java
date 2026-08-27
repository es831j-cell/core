package com.distressedelk.lumi;

import android.app.Activity;
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
import java.util.List;

/**
 * Code344 OpenAI-compatible maintenance reasoning fallback.
 *
 * This client does not grant authority. It can only request LumiMaintenanceTools, whose local
 * authorization/Android installer gates remain authoritative. Provider failover is allowed until a
 * mutating maintenance tool executes. After a mutation, the transaction stays pinned to the
 * provider that initiated it so a network retry cannot duplicate a write.
 */
final class FallbackMaintenanceClient {
    private static final int ROUND_LIMIT=10;
    interface Callback { void onSuccess(String reply,String provider,String model); void onFailure(String error); }
    private FallbackMaintenanceClient() {}

    static void request(Activity activity, SharedPreferences prefs, String instructions, String presence,
                        String recentTranscript, String userText, Callback callback){
        new Thread(() -> {
            List<CloudBrainRouter.Provider> providers=CloudBrainRouter.providers(prefs);
            if(providers.isEmpty()){ callback.onFailure("No free fallback provider is configured for maintenance reasoning."); return; }
            StringBuilder failures=new StringBuilder();
            long now=System.currentTimeMillis();
            for(CloudBrainRouter.Provider provider:providers){
                long cooldownUntil=prefs.getLong("fallback_"+provider.id+"_cooldown_until",0L);
                if(cooldownUntil>now){
                    if(failures.length()>0)failures.append(" | ");
                    failures.append(provider.id).append(": cooling down");
                    continue;
                }
                boolean mutationExecuted=false;
                try{
                    JSONArray messages=baseMessages(instructions,presence,recentTranscript,userText);
                    JSONArray tools=chatToolDefinitions();
                    for(int round=0; round<ROUND_LIMIT; round++){
                        JSONObject response=post(provider,messages,tools);
                        JSONObject message=firstMessage(response);
                        JSONArray calls=message.optJSONArray("tool_calls");
                        if(calls==null || calls.length()==0){
                            String text=contentText(message);
                            if(text.isEmpty()) text="I completed the maintenance reasoning turn, but the provider returned no readable final text.";
                            prefs.edit().putString("maintenance_reasoning_provider",provider.id)
                                    .putString("maintenance_reasoning_model",provider.model)
                                    .putLong("maintenance_reasoning_success_at",System.currentTimeMillis())
                                    .remove("fallback_"+provider.id+"_cooldown_until").apply();
                            callback.onSuccess(text,provider.id,provider.model);
                            return;
                        }

                        JSONObject assistant=new JSONObject().put("role","assistant");
                        Object c=message.opt("content");
                        assistant.put("content",c==null?JSONObject.NULL:c);
                        assistant.put("tool_calls",calls);
                        messages.put(assistant);

                        for(int i=0;i<calls.length();i++){
                            JSONObject call=calls.optJSONObject(i); if(call==null)continue;
                            String callId=call.optString("id","call-"+round+"-"+i);
                            JSONObject fn=call.optJSONObject("function");
                            String name=fn==null?"":fn.optString("name","");
                            String rawArgs=fn==null?"{}":fn.optString("arguments","{}");
                            JSONObject args; try{args=new JSONObject(rawArgs);}catch(Throwable ignored){args=new JSONObject();}
                            if(!readOnly(name)) mutationExecuted=true;
                            if(activity instanceof MainActivity){
                                ((MainActivity)activity).flightRecord("MAINTENANCE_TOOL","FALLBACK_MODEL_TOOL_CALL",
                                        "provider="+provider.id+" round="+round+" name="+name+" callId="+callId+" args="+rawArgs);
                            }
                            String result=LumiMaintenanceTools.execute(activity,prefs,name,args,userText);
                            messages.put(new JSONObject().put("role","tool").put("tool_call_id",callId).put("content",result));
                        }
                    }
                    throw new IllegalStateException("maintenance tool loop exceeded "+ROUND_LIMIT+" rounds");
                }catch(Throwable t){
                    String safe=safe(t);
                    if(failures.length()>0)failures.append(" | ");
                    failures.append(provider.id).append(": ").append(safe);
                    prefs.edit().putString("maintenance_reasoning_last_error",safe)
                            .putString("maintenance_reasoning_failed_provider",provider.id)
                            .putLong("maintenance_reasoning_failure_at",System.currentTimeMillis())
                            .putLong("fallback_"+provider.id+"_cooldown_until",System.currentTimeMillis()+failureCooldownMs(t)).apply();
                    // A write may already have been accepted by Lumi. Never replay the same owner
                    // instruction through a second model after that point.
                    if(mutationExecuted){
                        callback.onFailure("Provider "+provider.id+" failed after the maintenance transaction began: "+safe+". I kept the existing transaction and did not replay it through another provider.");
                        return;
                    }
                }
            }
            callback.onFailure(failures.length()==0?"Every configured free maintenance provider failed.":failures.toString());
        },"LumiFallbackMaintenance").start();
    }

    private static JSONArray baseMessages(String instructions,String presence,String recentTranscript,String userText) throws Exception{
        JSONArray messages=new JSONArray();
        String system=(instructions==null?"":instructions.trim())+"\n"+(presence==null?"":presence.trim());
        if(system.length()>8500)system=system.substring(0,8500);
        messages.put(new JSONObject().put("role","system").put("content",system));
        String recent=recentTranscript==null?"":recentTranscript.trim();
        if(recent.length()>5000)recent=recent.substring(recent.length()-5000);
        if(!recent.isEmpty())messages.put(new JSONObject().put("role","system").put("content","Recent active-session transcript:\n"+recent));
        messages.put(new JSONObject().put("role","user").put("content",userText==null?"":userText));
        return messages;
    }

    private static JSONArray chatToolDefinitions() throws Exception{
        JSONArray responses=LumiMaintenanceTools.definitions();
        JSONArray out=new JSONArray();
        for(int i=0;i<responses.length();i++){
            JSONObject d=responses.optJSONObject(i); if(d==null)continue;
            JSONObject fn=new JSONObject().put("name",d.optString("name",""))
                    .put("description",d.optString("description",""))
                    .put("parameters",d.optJSONObject("parameters") == null ? new JSONObject().put("type","object") : d.optJSONObject("parameters"));
            out.put(new JSONObject().put("type","function").put("function",fn));
        }
        return out;
    }

    private static JSONObject post(CloudBrainRouter.Provider provider,JSONArray messages,JSONArray tools)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(provider.endpoint).openConnection();
        try{
            c.setRequestMethod("POST"); c.setConnectTimeout(9000); c.setReadTimeout(40000); c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            c.setRequestProperty("Authorization","Bearer "+provider.key);
            c.setRequestProperty("User-Agent","Lumi/4.0 FallbackMaintenance/1");
            if("openrouter".equals(provider.id)) c.setRequestProperty("X-Title","Lumi");
            JSONObject body=new JSONObject().put("model",provider.model).put("messages",messages)
                    .put("tools",tools).put("tool_choice","auto").put("stream",false)
                    .put("temperature",0.15).put("max_tokens",1500);
            try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            int code=c.getResponseCode(); InputStream is=(code>=200&&code<300)?c.getInputStream():c.getErrorStream();
            String raw=readAll(is);
            if(code<200||code>=300)throw new ProviderException(code,friendly(raw));
            return new JSONObject(raw);
        }finally{c.disconnect();}
    }

    private static JSONObject firstMessage(JSONObject root)throws Exception{
        JSONArray choices=root.optJSONArray("choices");
        if(choices==null||choices.length()==0)throw new IllegalStateException("provider returned no choices");
        JSONObject choice=choices.optJSONObject(0); JSONObject message=choice==null?null:choice.optJSONObject("message");
        if(message==null)throw new IllegalStateException("provider returned no assistant message");
        return message;
    }

    private static String contentText(JSONObject message){
        Object c=message.opt("content");
        if(c instanceof String)return ((String)c).trim();
        if(c instanceof JSONArray){
            StringBuilder b=new StringBuilder(); JSONArray a=(JSONArray)c;
            for(int i=0;i<a.length();i++){
                JSONObject part=a.optJSONObject(i); if(part==null)continue;
                String t=part.optString("text",part.optString("content",""));
                if(!t.isEmpty()){if(b.length()>0)b.append('\n');b.append(t);}
            }
            return b.toString().trim();
        }
        return "";
    }

    private static boolean readOnly(String name){
        return "get_lumi_status".equals(name) || "check_maintenance_bridge".equals(name)
                || "read_lumi_diagnostics".equals(name) || "read_maintenance_history".equals(name)
                || "search_canonical_source".equals(name) || "read_canonical_source_file".equals(name)
                || "get_trusted_build_status".equals(name) || "get_pending_bridge_update_status".equals(name);
    }

    private static String readAll(InputStream is)throws Exception{
        if(is==null)return ""; StringBuilder b=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){b.append(line);if(b.length()>24000)break;}}
        return b.toString();
    }

    private static String friendly(String raw){
        if(raw==null||raw.trim().isEmpty())return "no error body";
        try{JSONObject j=new JSONObject(raw);Object e=j.opt("error");if(e instanceof JSONObject){String m=((JSONObject)e).optString("message","");if(!m.isEmpty())return bound(m);}if(e instanceof String)return bound((String)e);String m=j.optString("message","");if(!m.isEmpty())return bound(m);}catch(Throwable ignored){}
        return bound(raw.replace('\n',' ').replace('\r',' '));
    }

    private static long failureCooldownMs(Throwable t){
        if(t instanceof ProviderException){
            int c=((ProviderException)t).code;
            if(c==401 || c==403)return 60L*60L*1000L;
            if(c==429)return 10L*60L*1000L;
            if(c>=500)return 90L*1000L;
        }
        String m=t==null?"":String.valueOf(t.getMessage()).toLowerCase();
        if(m.contains("timeout") || m.contains("timed out"))return 90L*1000L;
        return 45L*1000L;
    }

    private static String safe(Throwable t){
        String m=t==null?"unknown error":String.valueOf(t.getMessage());
        if(t instanceof ProviderException)m="HTTP "+((ProviderException)t).code+": "+m;
        return bound(m.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]+","Bearer [redacted]"));
    }
    private static String bound(String s){String v=s==null?"":s.trim();return v.length()>360?v.substring(0,360):v;}
    private static final class ProviderException extends Exception{final int code;ProviderException(int c,String m){super(m);code=c;}}
}
