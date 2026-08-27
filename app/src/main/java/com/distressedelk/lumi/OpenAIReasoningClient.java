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
import java.util.ArrayList;

/**
 * Lumi 1.0 OpenAI Responses client with a bounded local tool loop.
 * Secrets are used only in the HTTPS Authorization header and are never placed in prompts,
 * Memory Vault entries, function outputs, diagnostics, or Technical Ledger records.
 */
final class OpenAIReasoningClient {
    private static final int MAINTENANCE_TOOL_ROUND_LIMIT=10;
    private static final int SOURCE_SEARCH_LIMIT_PER_SESSION=3;
    interface Callback { void onSuccess(String reply,String responseId); void onFailure(String error); }
    private OpenAIReasoningClient(){}

    static void request(Activity activity, SharedPreferences prefs, String apiKey, String model,
                        String instructions, String presence, String recentTranscript,
                        String userText, String previousResponseId, Callback callback) {
        new Thread(()->{
            try{
                boolean maintenance=maintenanceIntent(activity,userText,prefs);
                flight(activity,"BRAIN_REQUEST","OPENAI_START","model="+model+" maintenance="+maintenance+" user="+userText);
                String memory=LumiMemoryVault.get(activity).contextPacket(userText,maintenance?6500:2600);
                String recent=recentTranscript==null?"":recentTranscript;
                if(recent.length()>(maintenance?4200:1600))recent=recent.substring(recent.length()-(maintenance?4200:1600));
                String input=presence+"\n"+memory+"\n"+(recent.trim().isEmpty()?"":"Recent active-session transcript:\n"+recent+"\n")+"Current user message: "+userText;
                if(maintenance){
                    // Code338 grounds the model to Lumi's real canonical package before any source tool
                    // call, then adds only successfully discovered continuation paths.
                    input += "\n\n"+MaintenanceWorkflowState.canonicalGrounding(userText);
                    String continuation=MaintenanceWorkflowState.promptSummary(prefs);
                    if(!continuation.isEmpty()) input += "\n\n"+continuation;
                }
                boolean deterministicSpeech=maintenance && deterministicSpeechExecutionIntent(userText,prefs);
                // Code322: an explicitly approved speech change executes through Lumi local maintenance
                // before the model drafts a reply, removing model tool-choice as a failure point.
                if(deterministicSpeech){
                    JSONObject qArgs=new JSONObject().put("requested_change","Apply the owner-approved conversational speech smoothing profile and rebuild the bounded speech path.").put("change_type","runtime_tuning");
                    String queued=LumiMaintenanceTools.execute(activity,prefs,"submit_maintenance_request",qArgs,userText);
                    String repair="";
                    try{
                        JSONObject qj=new JSONObject(queued);
                        if(qj.optBoolean("ok",false)){
                            String requestId=qj.optString("request_id",qj.optString("transactionId",""));
                            if(!requestId.isEmpty()){
                                repair=LumiMaintenanceTools.execute(activity,prefs,"apply_runtime_fix",new JSONObject().put("request_id",requestId).put("action","speech_rebuild"),userText);
                                repair=repair+" | verified="+awaitRuntimeRepairResult(prefs,requestId);
                            }
                        }
                    }catch(Throwable ignored){}
                    flight(activity,"MAINTENANCE_TOOL","DETERMINISTIC_SPEECH_EXECUTION","queued="+queued+" repair="+repair);
                    input += "\nDeterministic speech-maintenance execution result (authoritative; do not duplicate this write): queued="+queued+" repair="+repair;
                }
                // Code302: bridge-status questions perform a deterministic local self-update probe
                // before OpenAI is asked to explain the result. This removes reliance on the model
                // choosing the check_maintenance_bridge tool merely because the tool is available.
                if(maintenance && bridgeStatusIntent(userText)){
                    String bridgeProbe=LumiMaintenanceTools.execute(activity,prefs,"check_maintenance_bridge",new JSONObject(),userText);
                    flight(activity,"MAINTENANCE_TOOL","AUTO_BRIDGE_PROBE",bridgeProbe);
                    input += "\nLive same-phone maintenance bridge probe (authoritative): "+bridgeProbe;
                }
                JSONObject first=new JSONObject();first.put("model",model);first.put("instructions",instructions);first.put("input",input);first.put("max_output_tokens",maintenance?1400:280);
                if(maintenance && !deterministicSpeech){
                    first.put("tools",LumiMaintenanceTools.definitions());

                }
                if(previousResponseId!=null&&!previousResponseId.trim().isEmpty())first.put("previous_response_id",previousResponseId);
                JSONObject response=post(apiKey,first);String responseId=response.optString("id",previousResponseId==null?"":previousResponseId);

                // Bound tool recursion. Code382 keeps maintenance finite while allowing the verified owner-approved bridge transaction.
                if(!maintenance){String text=outputText(response);if(text.trim().isEmpty())text="I got a response, but there wasn't any readable text in it.";callback.onSuccess(text.trim(),responseId);return;}
                java.util.LinkedHashMap<String,String> turnReadCache=new java.util.LinkedHashMap<>();

                for(int round=0;round<MAINTENANCE_TOOL_ROUND_LIMIT;round++){
                    JSONArray calls=functionCalls(response);
                    if(calls.length()==0){
                        String text=outputText(response);if(text.trim().isEmpty())text="I got a response, but there wasn't any readable text in it.";callback.onSuccess(text.trim(),responseId);return;
                    }
                    JSONArray outputs=new JSONArray();
                    for(int i=0;i<calls.length();i++){
                        JSONObject call=calls.getJSONObject(i);String callId=call.optString("call_id","");String name=call.optString("name","");String rawArgs=call.optString("arguments","{}");JSONObject args;try{args=new JSONObject(rawArgs);}catch(Exception e){args=new JSONObject();}
                        flight(activity,"MAINTENANCE_TOOL","MODEL_TOOL_CALL","round="+round+" name="+name+" callId="+callId+" args="+rawArgs);
                        String result;
                        String cacheKey=name+"|"+args.toString();
                        boolean readOnlyCacheable="search_canonical_source".equals(name)||"read_canonical_source_file".equals(name)||"check_maintenance_bridge".equals(name)||"get_lumi_status".equals(name)||"get_trusted_build_status".equals(name)||"get_pending_bridge_update_status".equals(name);
                        if(readOnlyCacheable && turnReadCache.containsKey(cacheKey)){
                            result=turnReadCache.get(cacheKey);
                            flight(activity,"MAINTENANCE_TOOL","CACHE_HIT","round="+round+" name="+name+" args="+rawArgs);
                        }else if("search_canonical_source".equals(name) && (MaintenanceSession.approvalBound(prefs) || MaintenanceWorkflowState.seenSearch(prefs,args.optString("query","")) || MaintenanceWorkflowState.searchCount(prefs)>=SOURCE_SEARCH_LIMIT_PER_SESSION || round>=4)){
                            String why=MaintenanceSession.approvalBound(prefs)
                                    ?"A Lumi runtime-maintenance request is already bound to this approved transaction; additional source reconnaissance is unnecessary."
                                    :MaintenanceWorkflowState.seenSearch(prefs,args.optString("query",""))
                                    ?"This exact source search already completed in the current maintenance workflow."
                                    :(round>=4?"Source discovery is closed for this bounded diagnostic turn; report the diagnosed external-release change instead of continuing reconnaissance.":"The bounded source-search budget for this maintenance workflow is exhausted.");
                            result=MaintenanceWorkflowState.duplicateSearchResult(prefs,args.optString("query",""),why).toString();
                            flight(activity,"MAINTENANCE_TOOL","SOURCE_SEARCH_SKIPPED","round="+round+" query="+safe(args.optString("query",""))+" reason="+safe(why));
                        }else{
                            result=LumiMaintenanceTools.execute(activity,prefs,name,args,userText);
                            if(readOnlyCacheable)turnReadCache.put(cacheKey,result);
                            MaintenanceWorkflowState.rememberToolResult(prefs,name,args,result);
                        }
                        MaintenanceSession.touch(prefs);
                        // Code322 closes the queued-but-never-executed speech maintenance gap.
                        if("submit_maintenance_request".equals(name) && "runtime_tuning".equalsIgnoreCase(args.optString("change_type","")) && speechRuntimeTuningIntent(userText+" "+args.optString("requested_change",""))){
                            try{
                                JSONObject queued=new JSONObject(result);
                                if(queued.optBoolean("ok",false)){
                                    String requestId=queued.optString("request_id",queued.optString("transactionId",""));
                                    if(!requestId.isEmpty()){
                                        JSONObject repairArgs=new JSONObject().put("request_id",requestId).put("action","speech_rebuild");
                                        String repair=LumiMaintenanceTools.execute(activity,prefs,"apply_runtime_fix",repairArgs,userText);
                                        String verified=awaitRuntimeRepairResult(prefs,requestId);
                                        JSONObject repairJson; try{ repairJson=new JSONObject(repair); }catch(Exception ignored){ repairJson=new JSONObject().put("raw",repair); }
                                        repairJson.put("verified_runtime_state",verified);
                                        queued.put("auto_runtime_repair",repairJson); result=queued.toString();
                                        flight(activity,"MAINTENANCE_TOOL","AUTO_RUNTIME_REPAIR","request="+requestId+" action=speech_rebuild result="+repair+" verified="+verified);
                                    }
                                }
                            }catch(Throwable t){ flight(activity,"MAINTENANCE_TOOL","AUTO_RUNTIME_REPAIR_FAILED",safe(String.valueOf(t.getMessage()))); }
                        }
                        flight(activity,"MAINTENANCE_TOOL","MODEL_TOOL_RESULT","round="+round+" name="+name+" callId="+callId+" result="+result);
                        outputs.put(new JSONObject().put("type","function_call_output").put("call_id",callId).put("output",result));
                    }
                    String roundGuidance="Code382 bridge maintenance: tool round "+(round+1)+" of "+MAINTENANCE_TOOL_ROUND_LIMIT+" is complete. Use only the exposed diagnostics, canonical-source inspection, verified bridge-core update intake/status, bounded source staging, trusted build relay and owner-authorized runtime tools. A compiled core change may proceed only inside an exact owner-approved durable transaction with fresh authorization where required, source/hash/version verification, relay preflight, Lumi local checkpoint, private CI signing, APK provenance verification, Android install and post-install certification. Never invent credentials or claim success before certification.";
                    JSONObject follow=new JSONObject();follow.put("model",model);follow.put("instructions",instructions+"\n\n"+roundGuidance);follow.put("input",outputs);follow.put("max_output_tokens",1400);follow.put("tools",LumiMaintenanceTools.definitions());
                    if(responseId!=null&&!responseId.isEmpty())follow.put("previous_response_id",responseId);
                    response=post(apiKey,follow);responseId=response.optString("id",responseId);
                }
                callback.onFailure("OpenAI maintenance workflow exceeded Lumi's bounded "+MAINTENANCE_TOOL_ROUND_LIMIT+"-round continuation limit before reaching a final response.");
            }catch(Exception e){callback.onFailure(e.getClass().getSimpleName()+": "+safe(e.getMessage()));}
        },"LumiOpenAIReasoning").start();
    }

    private static JSONObject post(String apiKey,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.openai.com/v1/responses").openConnection();
        try{
            c.setRequestMethod("POST");c.setConnectTimeout(9000);c.setReadTimeout(35000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer "+apiKey);
            try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            int code=c.getResponseCode();InputStream is=(code>=200&&code<300)?c.getInputStream():c.getErrorStream();String raw=readAll(is);
            if(code<200||code>=300)throw new java.io.IOException("OpenAI returned HTTP "+code+": "+friendlyApiError(raw));
            return new JSONObject(raw);
        }finally{c.disconnect();}
    }




    private static String awaitRuntimeRepairResult(SharedPreferences prefs,String requestId){
        if(prefs==null || requestId==null || requestId.isEmpty()) return "UNKNOWN";
        long until=System.currentTimeMillis()+1600L;
        while(System.currentTimeMillis()<until){
            String completed=prefs.getString("maintenance_runtime_repair_completed_id","");
            String state=prefs.getString("maintenance_runtime_repair_state","UNKNOWN");
            if(requestId.equals(completed) && ("APPLIED".equals(state)||"FAILED".equals(state)))
                return state+":"+prefs.getString("maintenance_runtime_repair_result","");
            if("FAILED".equals(state)) return state+":"+prefs.getString("maintenance_runtime_repair_result","");
            try{Thread.sleep(55L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        return prefs.getString("maintenance_runtime_repair_state","UNKNOWN")+":"+prefs.getString("maintenance_runtime_repair_result","");
    }



    private static boolean deterministicSpeechExecutionIntent(String userText,SharedPreferences prefs){
        String s=userText==null?"":userText.toLowerCase(java.util.Locale.US).trim();
        boolean direct=s.matches(".*\\b(fix|change|improve|tune|smooth)\\s+your\\s+(speech|voice|speaking|pronunciation|pacing)\\b.*");
        boolean approval=s.matches(".*\\b(do it|go ahead|apply it|fix it|make the change|proceed|approved|approve it|i approve|yes please|you have my approval|you have my permission)\\b.*");
        String last=prefs==null?"":prefs.getString("last_lumi_reply","").toLowerCase(java.util.Locale.US);
        boolean speechContext=last.contains("speech")||last.contains("voice")||last.contains("pronunciation")||last.contains("prosody")||last.contains("pacing")||last.contains("smooth")||last.contains("choppy");
        return direct || (approval && speechContext);
    }

    private static boolean speechRuntimeTuningIntent(String userText){
        String s=userText==null?"":userText.toLowerCase(java.util.Locale.US);
        return s.contains("speech") || s.contains("speaking") || s.contains("voice") || s.contains("pronunciation") || s.contains("choppy") || s.contains("prosody") || s.contains("pacing") || s.contains("sound smoother") || s.contains("sounds sharp");
    }

    private static boolean bridgeStatusIntent(String userText){
        String s=userText==null?"":userText.toLowerCase(java.util.Locale.US).trim();
        boolean bridge=s.contains("maintenance bridge")||s.contains("native update engine")||s.contains("bridge connection")||s.contains("guardian connection")||s.contains("make the connection")||s.contains("connect the bridge")||s.contains("check update engine");
        boolean status=s.contains("working")||s.contains("connected")||s.contains("connection")||s.contains("status")||s.contains("check")||s.contains("verify")||s.contains("test")||s.startsWith("is ")||s.startsWith("are ")||s.startsWith("can ");
        return bridge && status;
    }

    private static boolean maintenanceIntent(Activity activity, String userText, SharedPreferences prefs){
        if(MaintenanceSession.cancelIntent(userText)){
            MaintenanceSession.clear(prefs,"cancel intent observed by reasoning router");
            return false;
        }
        if(MaintenanceSession.active(prefs)){
            MaintenanceSession.touch(prefs);
            return true;
        }
        if(MaintenanceSession.selfImprovementIntent(userText)) MaintenanceSession.begin(prefs,userText);
        try{ if(activity instanceof MainActivity && ((MainActivity)activity).isConversationalMaintenanceRequest(userText)) return true; }catch(Throwable ignored){}
        String s=userText==null?"":userText.toLowerCase(java.util.Locale.US).trim();
        String[] keys={"diagnostic","update lumi","update yourself","improve yourself","self update","self-update","repair","fix lumi","voice recognition","speech recognition","canonical source","source code","apply the fix","apply fix","patch","maintenance","maintenance bridge","bridge","guardian","install update","rollback","certification","health check","technical ledger","permission","configure lumi","change setting","developer issue","check update engine","check the update engine","make the connection","make connection","connect the bridge","bridge connection"};
        for(String k:keys)if(s.contains(k))return true;

        // Code331: maintenance conversations are often elliptical follow-ups. The durable session
        // above is authoritative; this immediate-reply fallback covers the first transition turn.
        String last=prefs==null?"":prefs.getString("last_lumi_reply","").toLowerCase(java.util.Locale.US);
        boolean maintenanceContext=last.contains("maintenance")||last.contains("guardian")||last.contains("bridge")||last.contains("update")||last.contains("repair")||last.contains("fix")||last.contains("source")||last.contains("patch")||last.contains("build");
        if(maintenanceContext){
            if(MaintenanceSession.ellipticalAction(userText)){
                MaintenanceSession.begin(prefs,"inherited follow-up: "+userText);
                return true;
            }
            if(s.contains("connection")||s.contains("connect")) return true;
        }
        return false;
    }

    private static void flight(Activity activity,String category,String action,String detail){
        try{ if(activity instanceof MainActivity)((MainActivity)activity).flightRecord(category,action,detail); }catch(Throwable ignored){}
    }

    private static JSONArray functionCalls(JSONObject response)throws Exception{JSONArray out=new JSONArray();JSONArray items=response.optJSONArray("output");if(items==null)return out;for(int i=0;i<items.length();i++){JSONObject x=items.optJSONObject(i);if(x!=null&&"function_call".equals(x.optString("type")))out.put(x);}return out;}
    private static String outputText(JSONObject response){StringBuilder out=new StringBuilder();JSONArray arr=response.optJSONArray("output");if(arr==null)return "";for(int i=0;i<arr.length();i++){JSONObject item=arr.optJSONObject(i);if(item==null)continue;JSONArray c=item.optJSONArray("content");if(c==null)continue;for(int j=0;j<c.length();j++){JSONObject p=c.optJSONObject(j);if(p!=null&&"output_text".equals(p.optString("type"))){String t=p.optString("text","");if(!t.isEmpty()){if(out.length()>0)out.append('\n');out.append(t);}}}}return out.toString();}
    private static String friendlyApiError(String raw){try{JSONObject j=new JSONObject(raw);JSONObject e=j.optJSONObject("error");if(e!=null)return safe(e.optString("message",raw));}catch(Exception ignored){}return raw==null?"":(raw.length()>400?raw.substring(0,400):raw);}
    private static String readAll(InputStream is)throws Exception{if(is==null)return "";try(BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);return s.toString();}}
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
