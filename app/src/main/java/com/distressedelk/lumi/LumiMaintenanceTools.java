package com.distressedelk.lumi;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Code388 owner-approved maintenance surface. Core changes use staged source -> trusted build relay -> Lumi verification -> Android installer. */
final class LumiMaintenanceTools {
    private LumiMaintenanceTools(){}

    static JSONArray definitions() throws Exception {
        JSONArray a=new JSONArray();
        a.put(tool("get_lumi_status","Read Lumi, native self-update, Memory Vault, canonical-source, update and release-policy health. Read-only.",emptyObjectSchema()));
        a.put(tool("check_maintenance_bridge","Verify Lumi native self-update, trusted relay, and pending maintenance state. Read-only.",emptyObjectSchema()));
        a.put(tool("read_lumi_diagnostics","Read a bounded, redacted tail of Lumi diagnostics.",new JSONObject().put("type","object").put("properties",new JSONObject().put("max_chars",new JSONObject().put("type","integer").put("minimum",1000).put("maximum",16000))).put("additionalProperties",false)));
        a.put(tool("read_maintenance_history","Read recent append-only technical maintenance ledger entries.",new JSONObject().put("type","object").put("properties",new JSONObject().put("limit",new JSONObject().put("type","integer").put("minimum",1).put("maximum",30))).put("additionalProperties",false)));

        JSONObject sourceSearchProps=new JSONObject().put("query",new JSONObject().put("type","string")).put("max_results",new JSONObject().put("type","integer").put("minimum",1).put("maximum",12));
        a.put(tool("search_canonical_source","Search Lumi's current non-secret canonical source snapshot. Read-only.",new JSONObject().put("type","object").put("properties",sourceSearchProps).put("required",new JSONArray().put("query")).put("additionalProperties",false)));
        JSONObject sourceReadProps=new JSONObject().put("path",new JSONObject().put("type","string"))
                .put("anchor",new JSONObject().put("type","string").put("description","Optional exact term used to center a bounded source window."))
                .put("max_chars",new JSONObject().put("type","integer").put("minimum",1000).put("maximum",24000));
        a.put(tool("read_canonical_source_file","Read one allow-listed non-secret file from Lumi's canonical source snapshot. Read-only.",new JSONObject().put("type","object").put("properties",sourceReadProps).put("required",new JSONArray().put("path")).put("additionalProperties",false)));

        JSONObject requestProps=new JSONObject();
        requestProps.put("requested_change",new JSONObject().put("type","string").put("description","Short owner-approved maintenance request."));
        requestProps.put("change_type",new JSONObject().put("type","string").put("enum",new JSONArray().put("diagnose").put("runtime_tuning").put("core_update").put("rollback")));
        a.put(tool("submit_maintenance_request","Queue a bounded request inside Lumi. core_update starts an owner-approved durable build transaction.",new JSONObject().put("type","object").put("properties",requestProps).put("required",new JSONArray().put("requested_change").put("change_type")).put("additionalProperties",false)));

        JSONObject editSchema=new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("operation",new JSONObject().put("type","string").put("enum",new JSONArray().put("replace").put("create")))
                .put("path",new JSONObject().put("type","string"))
                .put("find",new JSONObject().put("type","string"))
                .put("replace",new JSONObject().put("type","string"))
                .put("content",new JSONObject().put("type","string")))
                .put("required",new JSONArray().put("operation").put("path")).put("additionalProperties",false);
        JSONObject patchProps=new JSONObject().put("request_id",new JSONObject().put("type","string"))
                .put("requested_change",new JSONObject().put("type","string"))
                .put("edits",new JSONObject().put("type","array").put("minItems",1).put("maxItems",10).put("items",editSchema));
        a.put(tool("apply_core_source_patch","Stage bounded exact source edits for the queued Lumi core_update. Cannot edit manifests, signing policy, Gradle policy, or arbitrary binaries.",new JSONObject().put("type","object").put("properties",patchProps).put("required",new JSONArray().put("request_id").put("requested_change").put("edits")).put("additionalProperties",false)));
        JSONObject buildProps=new JSONObject().put("request_id",new JSONObject().put("type","string")).put("requested_change",new JSONObject().put("type","string"));
        a.put(tool("start_trusted_relay_build","Send the staged source to the owner's configured private GitHub Actions build relay. The relay signs; Lumi independently verifies the APK and opens Android's installer.",new JSONObject().put("type","object").put("properties",buildProps).put("required",new JSONArray().put("request_id").put("requested_change")).put("additionalProperties",false)));
        a.put(tool("get_trusted_build_status","Read trusted build relay configuration and active transaction status. Never returns credentials.",emptyObjectSchema()));
        a.put(tool("get_pending_bridge_update_status","Read whether a verified bridge-core source ZIP is waiting and its target version. Never returns credentials.",emptyObjectSchema()));
        a.put(tool("start_pending_bridge_update","Start the already verified source update after fresh administrator verification and explicit owner approval. Lumi load-tests the relay, creates a local recovery checkpoint, builds/signs in private CI, verifies the APK, then opens Android's installer.",emptyObjectSchema()));

        JSONObject repairProps=new JSONObject();
        repairProps.put("request_id",new JSONObject().put("type","string").put("description","Queued Lumi diagnose/runtime_tuning request id."));
        repairProps.put("action",new JSONObject().put("type","string").put("enum",new JSONArray().put("speech_rebuild").put("bridge_reinitialize").put("fast_brain_recover").put("mobius_recover").put("runtime_health_recheck")));
        a.put(tool("apply_runtime_fix","Apply one bounded owner-authorized local runtime repair. This cannot modify Lumi's compiled core.",new JSONObject().put("type","object").put("properties",repairProps).put("required",new JSONArray().put("request_id").put("action")).put("additionalProperties",false)));
        a.put(tool("create_recovery_checkpoint","Ask Lumi to create a protected local state recovery checkpoint. Requires owner authorization.",new JSONObject().put("type","object").put("properties",new JSONObject().put("reason",new JSONObject().put("type","string"))).put("additionalProperties",false)));
        a.put(tool("rollback_last_update","Rollback the last reversible Lumi content update and re-run Lumi validation. Requires owner authorization.",new JSONObject().put("type","object").put("properties",new JSONObject().put("reason",new JSONObject().put("type","string"))).put("additionalProperties",false)));
        return a;
    }

    private static JSONObject emptyObjectSchema() throws Exception {
        return new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false);
    }

    private static JSONObject tool(String name,String description,JSONObject parameters)throws Exception{
        return new JSONObject().put("type","function").put("name",name).put("description",description).put("parameters",parameters).put("strict",false);
    }

    static String execute(Activity activity, SharedPreferences prefs, String name, JSONObject args, String currentUserText) {
        String tx="tx-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        LumiMemoryVault vault=LumiMemoryVault.get(activity);
        try{
            if(activity instanceof MainActivity){
                ((MainActivity)activity).diag("maintenance-tool","invoke name="+safe(name)+" tx="+tx);
                ((MainActivity)activity).traceStage("MAINTENANCE_TOOL","START","name="+safe(name)+" tx="+tx);
                ((MainActivity)activity).flightRecord("MAINTENANCE_TOOL","INVOKE","name="+safe(name)+" tx="+tx+" args="+safe(args==null?"{}":args.toString()));
            }
            if("get_lumi_status".equals(name)) return getStatus(activity,prefs).toString();
            if("check_maintenance_bridge".equals(name)) return bridgeStatus(activity,prefs).toString();
            if("read_lumi_diagnostics".equals(name)) return new JSONObject().put("ok",true).put("diagnostics",readDiagnostics(activity,args.optInt("max_chars",12000))).toString();
            if("read_maintenance_history".equals(name)) return new JSONObject().put("ok",true).put("ledger",vault.recentLedger(args.optInt("limit",12))).toString();
            if("search_canonical_source".equals(name)) return SourcePatchManager.search(activity,prefs,args.optString("query",""),args.optInt("max_results",6)).toString();
            if("read_canonical_source_file".equals(name)) return SourcePatchManager.read(activity,prefs,args.optString("path",""),args.optInt("max_chars",12000),args.optString("anchor","")).toString();
            if("get_trusted_build_status".equals(name)) return TrustedBuildRelayClient.status(prefs).toString();
            if("get_pending_bridge_update_status".equals(name)) return BridgeUpdatePackage.status(activity,prefs).toString();

            if(RuntimePolicy.blocksMaintenanceTool(name)) return new JSONObject().put("ok",false).put("state","EXTERNAL_RELEASE_REQUIRED").put("transactionId",tx).put("reason",RuntimePolicy.blockedSelfModificationReply()).toString();

            MaintenanceAuthorization.Decision auth=MaintenanceAuthorization.authorizeWrite(activity,prefs,currentUserText,name,args);
            if(!auth.allowed){
                vault.ledger("maintenance-denied",name,auth.reason,tx);
                return new JSONObject().put("ok",false).put("state","AUTHORIZATION_REQUIRED").put("transactionId",tx).put("reason",auth.reason).toString();
            }
            if(activity instanceof MainActivity){
                ((MainActivity)activity).flightRecord("MAINTENANCE_AUTH",auth.carried?"CONTINUITY_USED":"CURRENT_TURN_APPROVAL",
                        "tool="+safe(name)+" tx="+tx+" approvalExpiresAt="+MaintenanceSession.writeApprovalExpiresAt(prefs));
            }

            if("submit_maintenance_request".equals(name)) return submitMaintenanceRequest(activity,args,tx).toString();
            if("apply_core_source_patch".equals(name)){
                JSONObject out=SourcePatchManager.apply(activity,prefs,args.optString("request_id",""),args.optString("requested_change",""),args.optJSONArray("edits"));
                out.put("transactionId",tx); vault.ledger("source-staged",args.optString("requested_change",""),"Trusted bridge source staged request="+args.optString("request_id",""),tx); return out.toString();
            }
            if("start_trusted_relay_build".equals(name)){
                JSONObject out=TrustedBuildRelayClient.start(activity,prefs,args.optString("request_id",""),args.optString("requested_change",""));
                out.put("transactionId",tx); vault.ledger("relay-build",args.optString("requested_change",""),"Trusted GitHub Actions relay queued request="+args.optString("request_id",""),tx); return out.toString();
            }
            if("start_pending_bridge_update".equals(name)){
                JSONObject out=BridgeUpdatePackage.start(activity,prefs);
                out.put("transactionId",tx); vault.ledger("bridge-core-start",prefs.getString("pending_bridge_update_name","verified bridge-core update"),"Verified source ZIP entered trusted build/sign/install transaction request="+out.optString("request_id",""),tx); return out.toString();
            }
            if("apply_runtime_fix".equals(name)){
                Bundle e=new Bundle();
                e.putString("request_id",safe(args.optString("request_id","")));
                e.putString("action",safe(args.optString("action","")));
                Bundle r=LumiSelfUpdateEngine.call(activity,"execute_runtime_repair",e);
                JSONObject out=bundleResult(r,tx); out.put("requestedRepair",safe(args.optString("action",""))); return out.toString();
            }
            if("create_recovery_checkpoint".equals(name)){
                Bundle b=LumiSelfUpdateEngine.call(activity,"create_checkpoint");
                boolean ok=b.getBoolean("ok",false);
                vault.ledger(ok?"checkpoint":"checkpoint-failed","Lumi recovery checkpoint",ok?b.getString("path",""):b.getString("error","unknown recovery error"),tx);
                return bundleResult(b,tx).toString();
            }
            if("rollback_last_update".equals(name)) return rollback(activity,prefs,args.optString("reason","owner requested rollback"),tx).toString();
            return new JSONObject().put("ok",false).put("state","FORBIDDEN_OR_UNKNOWN_TOOL").put("transactionId",tx).put("error","Tool is not in Lumi's maintenance allow-list").toString();
        }catch(Exception e){
            vault.ledger("maintenance-failed",name,e.getClass().getSimpleName()+": "+safe(e.getMessage()),tx);
            try{return new JSONObject().put("ok",false).put("state","FAILED").put("transactionId",tx).put("failedStage",name).put("error",e.getClass().getSimpleName()+": "+safe(e.getMessage())).toString();}
            catch(Exception ignored){return "{\"ok\":false,\"state\":\"FAILED\"}";}
        }
    }

    private static JSONObject submitMaintenanceRequest(Activity a,JSONObject args,String tx)throws Exception{
        String requested=safe(args.optString("requested_change","")).trim();
        String type=safe(args.optString("change_type","")).trim().toLowerCase(Locale.US);
        if(requested.length()<3 || requested.length()>500) throw new SecurityException("Maintenance request must be 3-500 characters");
        if(!("diagnose".equals(type)||"runtime_tuning".equals(type)||"core_update".equals(type)||"rollback".equals(type))) throw new SecurityException("Unsupported Lumi maintenance request type");
        long created=System.currentTimeMillis(); String nonce=UUID.randomUUID().toString();
        String canonical=tx+"|"+type+"|"+created+"|"+nonce+"|"+requested;
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        StringBuilder hs=new StringBuilder(); for(byte b:md.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))) hs.append(String.format(Locale.US,"%02x",b));
        Bundle e=new Bundle(); e.putString("transaction_id",tx); e.putString("change_type",type); e.putString("requested_change",requested); e.putLong("created_at",created); e.putString("nonce",nonce); e.putString("request_hash",hs.toString());
        Bundle b=LumiSelfUpdateEngine.call(a,"submit_maintenance_request",e);
        JSONObject o=bundleResult(b,tx);
        if(b.getBoolean("ok",false)){
            String requestId=b.getString("request_id",b.getString("transaction_id",tx));
            SharedPreferences prefs=a.getSharedPreferences("lumi",Activity.MODE_PRIVATE);
            MaintenanceSession.bindWriteApprovalToRequest(prefs,requestId,type,requested);
            if("core_update".equals(type)) UpdateTransactionManager.beginBound(prefs,requestId,type,requested);
            LumiMemoryVault.get(a).ledger("maintenance-request",requested,"Lumi accepted bounded request type="+type+"; approval bound="+requestId,tx);
        }
        return o;
    }

    private static JSONObject bridgeStatus(Activity a,SharedPreferences p)throws Exception{ return bridgeStatus(a,p,true); }
    static JSONObject diagnosticBridgeStatus(Activity a,SharedPreferences p)throws Exception{ return bridgeStatus(a,p,false); }

    private static JSONObject bridgeStatus(Activity a,SharedPreferences p,boolean writeTrace)throws Exception{
        String tx="self-update-probe-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        long started=System.currentTimeMillis();
        LumiSelfUpdateEngine.initialize(a,p);
        Bundle probe=LumiSelfUpdateEngine.call(a,"bridge_probe");
        boolean host=p.getBoolean("direct_maintenance_host_ready",false);
        boolean engineReady=p.getBoolean("native_self_update_engine_ready",false) && probe.getBoolean("ok",false);
        boolean connected=host && engineReady;
        JSONObject o=new JSONObject();
        o.put("ok",connected).put("state",connected?"SELF_UPDATE_READY":"SELF_UPDATE_NOT_READY");
        o.put("transactionId",tx).put("releaseManagement",RuntimePolicy.summary()).put("maintenanceToolHostReady",host);
        o.put("nativeSelfUpdateReady",engineReady).put("companionAppRequired",false);
        o.put("androidInstallerPermissionReady",probe.getBoolean("installerPermissionReady",false));
        o.put("lastValidationPass",probe.getBoolean("last_certification_pass",probe.getBoolean("certified",false)));
        o.put("pendingRequestState",probe.getString("pending_request_state","NONE")).put("pendingRequestId",probe.getString("pending_request_id","")).put("pendingRequestType",probe.getString("pending_request_type","")).put("pendingRequestChange",probe.getString("pending_request_change",""));
        o.put("durableTransaction",UpdateTransactionManager.summary(p));
        o.put("completedAt",System.currentTimeMillis()).put("roundTripMs",System.currentTimeMillis()-started);
        o.put("failedStage",probe.getString("failedStage","NONE"));
        o.put("diagnostic",connected?"Lumi native self-update engine and maintenance host are ready. No Guardian companion is installed or required.":probe.getString("error","Native self-update engine is not ready."));
        if(writeTrace && a instanceof MainActivity){
            ((MainActivity)a).diag("maintenance-tool","complete name=check_maintenance_bridge tx="+tx+" ok="+connected+" state="+o.getString("state"));
            ((MainActivity)a).flightRecord("MAINTENANCE_TOOL","RESULT","name=check_maintenance_bridge tx="+tx+" result="+o.toString());
        }
        return o;
    }

    private static JSONObject getStatus(Activity a,SharedPreferences p)throws Exception{
        Bundle lumi=BootstrapHealth.healthBundle(a,p);
        Bundle update=LumiSelfUpdateEngine.call(a,"maintenance_status");
        JSONObject o=new JSONObject();
        o.put("ok",true).put("releaseManagement",RuntimePolicy.summary());
        o.put("lumiCertified",lumi.getBoolean("certified",false)).put("lumiSummary",lumi.getString("summary",""));
        o.put("nativeSelfUpdateReady",p.getBoolean("native_self_update_engine_ready",false));
        o.put("selfUpdateValidated",update.getBoolean("last_certification_pass",update.getBoolean("certified",false)));
        o.put("selfUpdateSummary",update.getString("summary",update.getString("error","")));
        o.put("companionAppRequired",false);
        o.put("memoryVault",LumiMemoryVault.get(a).stats()).put("nativeMaintenanceHost",p.getBoolean("direct_maintenance_host_ready",false)).put("maintenanceRevoked",p.getBoolean("remote_maintenance_revoked",false));
        CanonicalSourceManager.initialize(a,p);
        o.put("canonicalSourceHealthy",CanonicalSourceManager.isHealthy(a,p)).put("canonicalSourceVersionCode",p.getLong("canonical_source_version_code",-1L)).put("canonicalSourceSha256",p.getString("canonical_source_sha256","")).put("canonicalSourceStagedTargetVersion",p.getLong("canonical_source_staged_target_version",-1L));
        o.put("pendingCoreUpdate",LumiUpdateManager.hasPendingCoreUpdate(a,p));
        o.put("durableUpdateTransactionActive",UpdateTransactionManager.active(p)).put("durableUpdateTransactionId",UpdateTransactionManager.requestId(p)).put("durableUpdateTransactionStage",UpdateTransactionManager.stage(p)).put("durableUpdateTransactionTarget",UpdateTransactionManager.targetVersion(p)).put("durableUpdateTransactionSummary",UpdateTransactionManager.summary(p));
        o.put("pendingRequest",update.getString("pending_request_change","")).put("pendingRequestType",update.getString("pending_request_type","")).put("pendingRequestId",update.getString("pending_request_id",""));
        o.put("trustedBuildRelay",TrustedBuildRelayClient.status(p));
        o.put("blackBoxEffectiveness",BlackBoxEffectiveness.summary(p));
        return o;
    }

    private static JSONObject rollback(Activity a,SharedPreferences p,String reason,String tx)throws Exception{
        if(!LumiUpdateManager.hasRollbackPoint(p)) return new JSONObject().put("ok",false).put("state","NO_ROLLBACK_POINT").put("transactionId",tx);
        String id=LumiUpdateManager.rollbackLastContentUpdate(a,p); Bundle cert=LumiSelfUpdateEngine.call(a,"certify"); boolean ok=cert.getBoolean("certified",cert.getBoolean("last_certification_pass",false));
        LumiMemoryVault.get(a).ledger("rollback",reason,"Rolled back update="+id+"; certified="+ok,tx);
        return new JSONObject().put("ok",ok).put("state",ok?"SUCCESS":"ROLLED_BACK_BUT_CERTIFICATION_FAILED").put("transactionId",tx).put("rolledBackUpdate",id).put("postRollbackCertification",ok).put("validationSummary",cert.getString("summary",cert.getString("error","")));
    }

    private static JSONObject bundleResult(Bundle b,String tx)throws Exception{
        JSONObject o=new JSONObject().put("ok",b.getBoolean("ok",false)).put("transactionId",tx);
        for(String k:b.keySet()){Object v=b.get(k); if(v instanceof String||v instanceof Boolean||v instanceof Integer||v instanceof Long||v instanceof Double||v instanceof Float)o.put(k,v);} return o;
    }

    private static String readDiagnostics(Activity a,int maxChars)throws Exception{
        File f=new File(a.getFilesDir(),"lumi-diagnostics.log"); if(!f.isFile())return "No diagnostics have been recorded yet.";
        int cap=Math.max(1000,Math.min(16000,maxChars));
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){long start=Math.max(0,r.length()-cap*2L); r.seek(start); byte[] b=new byte[(int)Math.min(r.length()-start,cap*2L)]; r.readFully(b); String s=new String(b,java.nio.charset.StandardCharsets.UTF_8); if(s.length()>cap)s=s.substring(s.length()-cap); return redact(s);}
    }
    private static String redact(String s){if(s==null)return "";return s.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{12,}","Bearer [REDACTED]").replaceAll("(?i)(api[_ -]?key|token|password)\\s*[:=]\\s*[^\\s,;]{8,}","$1=[REDACTED]").replaceAll("github_pat_[A-Za-z0-9_]{10,}","[REDACTED_GITHUB_TOKEN]");}
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
