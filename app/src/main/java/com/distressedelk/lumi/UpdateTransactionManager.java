package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Durable owner-approved Lumi update transaction.
 * Code388 R105 removes Guardian authority. The transaction is now owned by Lumi, while Android's
 * package installer remains the non-bypassable user/security approval boundary for APK changes.
 */
final class UpdateTransactionManager {
    private static final String ACTIVE="update_tx_active";
    private static final String REQUEST_ID="update_tx_request_id";
    private static final String CHANGE_TYPE="update_tx_change_type";
    private static final String SCOPE="update_tx_scope";
    private static final String APPROVED_AT="update_tx_approved_at";
    private static final String LAST_ACTIVITY="update_tx_last_activity_at";
    private static final String STAGE="update_tx_stage";
    private static final String TARGET_VERSION="update_tx_target_version";
    private static final String LAST_RESULT="update_tx_last_result";
    private static final String COMPLETED_AT="update_tx_completed_at";
    private static final String LAST_REQUEST_ID="update_tx_last_request_id";
    private static final String LAST_STAGE="update_tx_last_stage";
    private static final String SUPERSEDED_ID="update_tx_superseded_request_id";
    private static final String SUPERSEDED_AT="update_tx_superseded_at";
    private static final String SUPERSEDED_REASON="update_tx_superseded_reason";

    private UpdateTransactionManager(){}

    static final class ReconcileResult {
        final boolean readyForNew;
        final boolean resumeExisting;
        final String requestId;
        final String stage;
        final String message;
        ReconcileResult(boolean readyForNew,boolean resumeExisting,String requestId,String stage,String message){
            this.readyForNew=readyForNew; this.resumeExisting=resumeExisting;
            this.requestId=safe(requestId); this.stage=safe(stage); this.message=safe(message);
        }
    }

    static boolean active(SharedPreferences p){ return p!=null && p.getBoolean(ACTIVE,false) && !p.getString(REQUEST_ID,"").isEmpty(); }
    static String requestId(SharedPreferences p){ return p==null?"":safe(p.getString(REQUEST_ID,"")); }
    static String stage(SharedPreferences p){ return p==null?"":safe(p.getString(STAGE,"")); }
    static long targetVersion(SharedPreferences p){ return p==null?-1L:p.getLong(TARGET_VERSION,-1L); }
    static long approvedAt(SharedPreferences p){ return p==null?0L:p.getLong(APPROVED_AT,0L); }
    static long lastActivityAt(SharedPreferences p){ return p==null?0L:p.getLong(LAST_ACTIVITY,0L); }

    static boolean beginBound(SharedPreferences p,String requestId,String changeType,String scope){
        if(p==null) return false;
        String id=safe(requestId); if(id.isEmpty()) return false;
        if(active(p) && !id.equals(requestId(p))) return false;
        writeBound(p,id,changeType,scope); return true;
    }

    static ReconcileResult reconcileBeforeNewBridge(Context c,SharedPreferences p){
        if(p==null) return new ReconcileResult(false,false,"","","preferences unavailable");
        if(!active(p)) return new ReconcileResult(true,false,"","","no active transaction");
        String oldId=requestId(p), oldStage=stage(p);
        String buildId=safe(p.getString("trusted_core_build_request_id",""));
        String buildStage=safe(p.getString("trusted_core_build_stage",""));
        boolean buildActive=p.getBoolean("trusted_core_build_active",false) && oldId.equals(buildId) && !terminalStage(buildStage);
        try{
            long target=targetVersion(p), installed=currentVersionCode(c);
            if(target>0L && installed>=target){
                android.os.Bundle v=LumiSelfUpdateEngine.postInstallValidation(c,p);
                if(v.getBoolean("certified",false)){
                    p.edit().putBoolean("trusted_core_build_active",false)
                            .putString("trusted_core_build_stage","POST_INSTALL_VALIDATION_COMPLETE")
                            .putBoolean("zero_chat_android_approval_pending",false).apply();
                    finish(p,oldId,"INSTALLED_VALIDATED","Recovered completed Lumi self-update during transaction reconciliation");
                    return new ReconcileResult(true,false,oldId,oldStage,"recovered already-installed transaction");
                }
            }
            boolean waitingApproval=p.getBoolean("zero_chat_android_approval_pending",false)
                    || "ANDROID_INSTALL_APPROVAL_REQUIRED".equals(buildStage)
                    || "ANDROID_INSTALL_APPROVAL_PRESENTED".equals(buildStage);
            if(buildActive || waitingApproval){
                return new ReconcileResult(false,true,oldId,empty(oldStage)?buildStage:oldStage,"existing Lumi update is still live and will be resumed");
            }
            if(terminalStage(oldStage) || terminalStage(buildStage)){
                retireStale(p,oldId,"terminal transaction record remained marked active");
                return new ReconcileResult(true,false,oldId,oldStage,"retired terminal transaction record");
            }
            long age=Math.max(0L,System.currentTimeMillis()-lastActivityAt(p));
            if(age>10L*60L*1000L && !LumiUpdateManager.hasPendingCoreUpdate(c,p)){
                retireStale(p,oldId,"inactive native update transaction exceeded recovery window");
                return new ReconcileResult(true,false,oldId,oldStage,"retired inactive transaction");
            }
            return new ReconcileResult(false,true,oldId,oldStage,"existing Lumi transaction is preserved and will be resumed");
        }catch(Throwable t){
            return new ReconcileResult(false,true,oldId,oldStage,"could not prove the existing transaction stale: "+t.getClass().getSimpleName());
        }
    }

    static boolean matches(SharedPreferences p,String requestId){ return active(p) && !safe(requestId).isEmpty() && requestId(p).equals(safe(requestId)); }

    static boolean allows(SharedPreferences p,String tool,JSONObject args){
        if(!active(p)) return false;
        String t=safe(tool); JSONObject a=args==null?new JSONObject():args; String bound=requestId(p);
        if("apply_core_source_patch".equals(t)||"apply_runtime_fix".equals(t)||"start_trusted_relay_build".equals(t))
            return bound.equals(safe(a.optString("request_id","")));
        if("launch_pending_core_install".equals(t)){
            String activeBuild=p.getString("trusted_core_build_request_id",""); String pendingCore=p.getString("pending_core_update_id","");
            return bound.equals(activeBuild)||bound.equals(pendingCore)||pendingCore.startsWith("relay-"+bound);
        }
        if("create_recovery_checkpoint".equals(t)) return true;
        return false;
    }

    static void markStage(SharedPreferences p,String requestId,String stage,long targetVersion){
        if(p==null || !matches(p,requestId)) return;
        SharedPreferences.Editor e=p.edit().putString(STAGE,safe(stage)).putLong(LAST_ACTIVITY,System.currentTimeMillis());
        if(targetVersion>0L)e.putLong(TARGET_VERSION,targetVersion); e.apply();
    }

    static void finish(SharedPreferences p,String requestId,String finalStage,String result){
        if(p==null)return; String id=safe(requestId);
        if(active(p)&&!id.isEmpty()&&!id.equals(requestId(p)))return;
        long now=System.currentTimeMillis(); String lastId=id.isEmpty()?requestId(p):id;
        p.edit().putBoolean(ACTIVE,false).putString(LAST_REQUEST_ID,lastId).putString(LAST_STAGE,safe(finalStage))
                .putString(LAST_RESULT,safe(result)).putLong(COMPLETED_AT,now).putLong(LAST_ACTIVITY,now)
                .remove(REQUEST_ID).remove(CHANGE_TYPE).remove(SCOPE).remove(APPROVED_AT).remove(STAGE).remove(TARGET_VERSION).apply();
    }

    static String summary(SharedPreferences p){
        if(active(p))return "Update transaction "+requestId(p)+" is in stage "+stage(p)+" toward code "+targetVersion(p)+".";
        String last=p==null?"":p.getString(LAST_RESULT,""); return last==null?"":last;
    }

    private static void writeBound(SharedPreferences p,String id,String changeType,String scope){
        long now=System.currentTimeMillis();
        p.edit().putBoolean(ACTIVE,true).putString(REQUEST_ID,id).putString(CHANGE_TYPE,safe(changeType).toLowerCase(Locale.US))
                .putString(SCOPE,safe(scope)).putLong(APPROVED_AT,now).putLong(LAST_ACTIVITY,now)
                .putString(STAGE,"LUMI_REQUEST_ACCEPTED").putLong(TARGET_VERSION,-1L).remove(LAST_RESULT).remove(COMPLETED_AT).apply();
    }

    private static void retireStale(SharedPreferences p,String oldId,String reason){
        long now=System.currentTimeMillis(); String oldStage=stage(p);
        SharedPreferences.Editor e=p.edit().putBoolean(ACTIVE,false).putString(SUPERSEDED_ID,safe(oldId)).putString(SUPERSEDED_REASON,safe(reason))
                .putLong(SUPERSEDED_AT,now).putString(LAST_REQUEST_ID,safe(oldId)).putString(LAST_STAGE,empty(oldStage)?"SUPERSEDED_STALE":oldStage)
                .putString(LAST_RESULT,"Superseded stale Lumi update transaction: "+safe(reason)).putLong(COMPLETED_AT,now).putLong(LAST_ACTIVITY,now)
                .remove(REQUEST_ID).remove(CHANGE_TYPE).remove(SCOPE).remove(APPROVED_AT).remove(STAGE).remove(TARGET_VERSION);
        if(safe(oldId).equals(safe(p.getString("trusted_core_build_request_id","")))){
            e.putBoolean("trusted_core_build_active",false).putString("trusted_core_build_stage","SUPERSEDED_STALE")
                    .putString("trusted_core_build_error",safe(reason)).putBoolean("zero_chat_android_approval_pending",false);
        }
        e.apply();
    }

    private static long currentVersionCode(Context c)throws Exception{
        android.content.pm.PackageInfo pi=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        return android.os.Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode;
    }
    private static boolean terminalStage(String s){
        String v=safe(s).toUpperCase(Locale.US);
        return v.equals("FAILED")||v.equals("CERTIFIED")||v.equals("INSTALLED_VALIDATED")||v.equals("POST_INSTALL_VALIDATION_COMPLETE")||
                v.equals("CANCELLED")||v.equals("SUPERSEDED_STALE")||v.endsWith("_FAILED");
    }
    private static boolean empty(String s){return safe(s).isEmpty();}
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
