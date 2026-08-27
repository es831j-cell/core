package com.distressedelk.lumi;

import android.app.Activity;
import android.content.SharedPreferences;

/**
 * Code388 R105 zero-chat update coordinator.
 * The verified APK is prepared by Lumi; when Lumi is foreground, Android's installer is opened
 * directly. No ChatGPT interaction and no companion application is required.
 */
final class ZeroChatUpdateCoordinator {
    private static final long APPROVAL_RELAUNCH_GUARD_MS=30_000L;
    private ZeroChatUpdateCoordinator(){}

    static void resume(Activity activity, SharedPreferences p, String reason){
        if(activity==null || p==null || !p.getBoolean("zero_chat_update_flow",true)) return;
        if(p.getBoolean("trusted_core_build_active",false)) TrustedBuildRelayJobService.schedule(activity,1000L);
        try{
            if(LumiUpdateManager.hasPendingCoreUpdate(activity,p) && p.getBoolean("zero_chat_android_approval_pending",false)){
                long now=System.currentTimeMillis();
                long lastAt=p.getLong("zero_chat_last_approval_launch_at",0L);
                long target=p.getLong("pending_core_version_code",-1L);
                long lastTarget=p.getLong("zero_chat_last_approval_target",-1L);
                if(lastTarget!=target || now-lastAt>=APPROVAL_RELAUNCH_GUARD_MS){
                    p.edit().putLong("zero_chat_last_approval_target",target)
                            .putLong("zero_chat_last_approval_launch_at",now)
                            .putString("zero_chat_last_resume_reason",reason==null?"":reason).apply();
                    LumiUpdateManager.launchPendingCoreInstaller(activity,p);
                }
            }
        }catch(Throwable t){
            p.edit().putString("zero_chat_update_last_error",SecretStore.redact(t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()))).apply();
        }
        maybeChainWaitingBridge(activity,p);
    }

    /** Once an older live transaction finishes, start a verified newer package automatically when the local admin session is still valid. */
    private static void maybeChainWaitingBridge(Activity activity,SharedPreferences p){
        if(!p.getBoolean("zero_chat_newer_bridge_waiting",false)) return;
        if(p.getBoolean("trusted_core_build_active",false) || UpdateTransactionManager.active(p)) return;
        if(!BridgeUpdatePackage.hasPending(activity,p)){
            p.edit().remove("zero_chat_newer_bridge_waiting").remove("zero_chat_newer_bridge_update_id")
                    .remove("zero_chat_newer_bridge_reason").remove("zero_chat_chain_starting").apply();
            return;
        }
        if(!IdentityHierarchy.strongAdminSessionActive(p)){
            p.edit().putBoolean("resume_pending_bridge_after_admin",true).apply();
            return;
        }
        if(p.getBoolean("zero_chat_chain_starting",false)) return;
        p.edit().putBoolean("zero_chat_chain_starting",true).apply();
        new Thread(() -> {
            try{
                BridgeUpdatePackage.start(activity,p);
                p.edit().remove("zero_chat_newer_bridge_waiting").remove("zero_chat_newer_bridge_update_id")
                        .remove("zero_chat_newer_bridge_reason").remove("zero_chat_chain_starting").apply();
            }catch(Throwable t){
                p.edit().putBoolean("zero_chat_chain_starting",false)
                        .putString("zero_chat_update_last_error",SecretStore.redact(t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()))).apply();
            }
        },"LumiZeroChatRelayChain").start();
    }
}
