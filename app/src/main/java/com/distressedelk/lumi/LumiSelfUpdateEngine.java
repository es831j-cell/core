package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.File;

/**
 * Code388 R105 native Lumi update/recovery engine.
 *
 * Guardian is no longer part of the operational architecture. Lumi owns verification,
 * protected checkpoints, durable transaction state, installer handoff and post-install
 * validation. Android remains the independent security boundary for APK installation.
 */
final class LumiSelfUpdateEngine {
    private LumiSelfUpdateEngine() {}

    static void initialize(Context context, SharedPreferences prefs) {
        if (context == null || prefs == null) return;
        prefs.edit()
                .putBoolean("native_self_update_engine_ready", true)
                .putString("native_self_update_engine", "lumi-r105")
                .putLong("native_self_update_engine_initialized_at", System.currentTimeMillis())
                .apply();
    }

    static Bundle call(Context context, String method) { return call(context, method, null); }

    static Bundle call(Context context, String method, Bundle extras) {
        Bundle out = new Bundle();
        if (context == null) {
            out.putBoolean("ok", false);
            out.putString("error", "Lumi self-update context unavailable");
            return out;
        }
        SharedPreferences prefs = context.getSharedPreferences("lumi", Context.MODE_PRIVATE);
        initialize(context, prefs);
        String m = method == null ? "" : method.trim();
        try {
            if ("health".equals(m) || "maintenance_status".equals(m)) return statusBundle(context, prefs);
            if ("maintenance_request_status".equals(m)) return requestStatus(prefs);
            if ("bridge_probe".equals(m)) return probe(context, prefs, extras);
            if ("submit_maintenance_request".equals(m)) return acceptRequest(prefs, extras);
            if ("create_checkpoint".equals(m)) {
                JSONObject cp = RecoverySnapshotManager.create(context, prefs, "lumi-native-self-update-pre-change");
                out.putBoolean("ok", true);
                out.putString("state", "CHECKPOINT_CREATED");
                out.putString("path", RecoverySnapshotManager.latestPath(prefs));
                out.putLong("version_code", cp.optLong("versionCode", -1L));
                return out;
            }
            if ("restore_latest_checkpoint".equals(m)) {
                boolean ok = RecoverySnapshotManager.restoreLatest(context, prefs);
                out.putBoolean("ok", ok);
                out.putString("state", ok ? "CHECKPOINT_RESTORED" : "RESTORE_FAILED");
                return out;
            }
            if ("execute_runtime_repair".equals(m)) {
                String action = extras == null ? "" : extras.getString("action", "").trim();
                String requestId = extras == null ? "" : extras.getString("request_id", "").trim();
                if (!("speech_rebuild".equals(action) || "bridge_reinitialize".equals(action) ||
                        "fast_brain_recover".equals(action) || "mobius_recover".equals(action) ||
                        "runtime_health_recheck".equals(action))) {
                    throw new SecurityException("Runtime repair action is outside the allow-list");
                }
                boolean dispatched = MainActivity.requestMaintenanceRuntimeRepair(action, requestId);
                if (!dispatched) {
                    prefs.edit().putString("maintenance_runtime_repair_action", action)
                            .putString("maintenance_runtime_repair_id", requestId)
                            .putString("maintenance_runtime_repair_state", "QUEUED")
                            .putLong("maintenance_runtime_repair_at", System.currentTimeMillis()).apply();
                }
                out.putBoolean("ok", true);
                out.putString("state", dispatched ? "REPAIR_DISPATCHED" : "REPAIR_QUEUED_FOR_NEXT_FOREGROUND");
                return out;
            }
            if ("resume_pending_install".equals(m) || "pending_install_approval".equals(m)) {
                boolean waiting = LumiUpdateManager.hasPendingCoreUpdate(context, prefs)
                        && prefs.getBoolean("zero_chat_android_approval_pending", false);
                out.putBoolean("ok", true);
                out.putBoolean("waiting", waiting);
                out.putBoolean("install_waiting_user_action", waiting);
                out.putString("state", waiting ? "ANDROID_INSTALL_APPROVAL_REQUIRED" : "NO_PENDING_INSTALL");
                return out;
            }
            if ("certify".equals(m)) return BootstrapHealth.certificationBundle(context, prefs);
            if ("install_core_from_uri".equals(m)) {
                out.putBoolean("ok", false);
                out.putString("error", "Legacy companion installer removed; Lumi opens Android installer directly");
                return out;
            }
            out.putBoolean("ok", false);
            out.putString("error", "Unsupported Lumi self-update method: " + m);
            return out;
        } catch (Exception e) {
            out.putBoolean("ok", false);
            out.putString("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            return out;
        }
    }

    static Bundle statusBundle(Context context, SharedPreferences prefs) {
        Bundle out = new Bundle();
        try {
            long current = currentVersionCode(context);
            long target = prefs.getLong("pending_core_version_code", prefs.getLong("trusted_core_build_target_version", -1L));
            boolean pending = LumiUpdateManager.hasPendingCoreUpdate(context, prefs);
            boolean waiting = pending && prefs.getBoolean("zero_chat_android_approval_pending", false);
            boolean installPermission = Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
            boolean validationPass = prefs.getBoolean("self_update_last_validation_pass", false)
                    && prefs.getLong("self_update_last_validation_version", -1L) == current;
            out.putBoolean("ok", true);
            out.putBoolean("certified", validationPass);
            out.putBoolean("last_certification_pass", validationPass);
            out.putBoolean("certification_pending", false);
            out.putBoolean("installerPermissionReady", installPermission);
            out.putBoolean("install_waiting_user_action", waiting);
            out.putBoolean("core_recovery_required", false);
            out.putLong("last_install_target", target);
            out.putInt("last_install_status", 0);
            out.putString("last_install_message", waiting ? "Android installer approval required" : (validationPass ? "Installed core validated" : "Native self-update engine ready"));
            out.putString("pending_request_id", UpdateTransactionManager.requestId(prefs));
            out.putString("pending_request_type", prefs.getString("update_tx_change_type", ""));
            out.putString("pending_request_change", prefs.getString("update_tx_scope", ""));
            out.putString("pending_request_state", UpdateTransactionManager.stage(prefs));
            out.putString("state", waiting ? "ANDROID_INSTALL_APPROVAL_REQUIRED" : "SELF_UPDATE_READY");
            out.putLong("version_code", current);
            out.putString("summary", waiting ? "Verified Lumi APK is waiting for Android installation approval." : "Lumi native self-update engine is ready.");
        } catch (Exception e) {
            out.putBoolean("ok", false);
            out.putString("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
        return out;
    }

    static Bundle postInstallValidation(Context context, SharedPreferences prefs) {
        Bundle out = new Bundle();
        try {
            CanonicalSourceManager.initialize(context, prefs);
            long current = currentVersionCode(context);
            boolean canonical = CanonicalSourceManager.isHealthy(context, prefs);
            boolean foundation = prefs.getBoolean("lumi_1_0_foundation_ready", false);
            boolean host = prefs.getBoolean("direct_maintenance_host_ready", false);
            boolean speech = android.speech.SpeechRecognizer.isRecognitionAvailable(context);
            File pending = LumiUpdateManager.pendingCoreApkFile(prefs);
            long target = prefs.getLong("pending_core_version_code", prefs.getLong("trusted_core_build_target_version", -1L));
            boolean versionOk = target <= 0L || current >= target;
            boolean pass = canonical && foundation && host && speech && versionOk;
            prefs.edit()
                    .putBoolean("self_update_last_validation_pass", pass)
                    .putLong("self_update_last_validation_version", current)
                    .putLong("self_update_last_validation_at", System.currentTimeMillis())
                    .putString("self_update_last_validation_detail", "canonical=" + canonical + " foundation=" + foundation + " host=" + host + " speech=" + speech + " versionOk=" + versionOk)
                    .putBoolean("zero_chat_android_approval_pending", false)
                    .apply();
            if (pass && pending != null && pending.isFile()) pending.delete();
            out.putBoolean("ok", true);
            out.putBoolean("certified", pass);
            out.putBoolean("canonical", canonical);
            out.putBoolean("foundation", foundation);
            out.putBoolean("maintenance_host", host);
            out.putBoolean("speech", speech);
            out.putBoolean("version_ok", versionOk);
            out.putLong("version_code", current);
            out.putString("summary", pass ? "Native post-install validation passed for Lumi code " + current + "." : "Post-install validation found a problem; export Black Box before another core update.");
            return out;
        } catch (Exception e) {
            prefs.edit().putBoolean("self_update_last_validation_pass", false)
                    .putLong("self_update_last_validation_at", System.currentTimeMillis())
                    .putString("self_update_last_validation_detail", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())).apply();
            out.putBoolean("ok", false);
            out.putBoolean("certified", false);
            out.putString("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            return out;
        }
    }

    static void onPackageReplaced(Context context, SharedPreferences prefs) {
        initialize(context, prefs);
        Bundle validation = postInstallValidation(context, prefs);
        long current = -1L;
        try { current = currentVersionCode(context); } catch (Exception ignored) {}
        String requestId = prefs.getString("trusted_core_build_request_id", "");
        long target = prefs.getLong("trusted_core_build_target_version", -1L);
        if (target > 0L && current >= target && validation.getBoolean("certified", false)) {
            prefs.edit().putBoolean("trusted_core_build_active", false)
                    .putString("trusted_core_build_stage", "POST_INSTALL_VALIDATION_COMPLETE")
                    .putLong("trusted_core_build_completed_at", System.currentTimeMillis())
                    .putBoolean("zero_chat_android_approval_pending", false)
                    .remove("trusted_core_build_error").apply();
            if (!requestId.isEmpty()) UpdateTransactionManager.finish(prefs, requestId, "INSTALLED_VALIDATED", "Trusted relay core installed and Lumi post-install validation passed");
        }
    }

    private static Bundle requestStatus(SharedPreferences prefs) {
        Bundle b = new Bundle();
        b.putBoolean("ok", true);
        b.putString("request_id", UpdateTransactionManager.requestId(prefs));
        b.putString("transaction_id", UpdateTransactionManager.requestId(prefs));
        b.putString("change_type", prefs.getString("update_tx_change_type", ""));
        b.putString("requested_change", prefs.getString("update_tx_scope", ""));
        b.putString("state", UpdateTransactionManager.stage(prefs));
        return b;
    }

    private static Bundle acceptRequest(SharedPreferences prefs, Bundle extras) {
        Bundle b = new Bundle();
        String tx = extras == null ? "" : extras.getString("transaction_id", "").trim();
        String type = extras == null ? "" : extras.getString("change_type", "").trim();
        String requested = extras == null ? "" : extras.getString("requested_change", "").trim();
        if (tx.isEmpty() || requested.isEmpty()) {
            b.putBoolean("ok", false); b.putString("error", "Maintenance request is incomplete"); return b;
        }
        b.putBoolean("ok", true);
        b.putString("state", "LUMI_REQUEST_ACCEPTED");
        b.putString("request_id", tx);
        b.putString("transaction_id", tx);
        b.putString("change_type", type);
        return b;
    }

    private static Bundle probe(Context context, SharedPreferences prefs, Bundle extras) {
        Bundle b = statusBundle(context, prefs);
        String tx = extras == null ? "" : extras.getString("transaction_id", "");
        long now = System.currentTimeMillis();
        long vc = -1L; String vn = "";
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            vn = pi.versionName == null ? "" : pi.versionName;
        } catch (Exception ignored) {}
        b.putBoolean("ok", true);
        b.putBoolean("lumiRoundTrip", true);
        b.putBoolean("transportPingOk", true);
        b.putBoolean("transactionEchoOk", true);
        b.putBoolean("lumiIdentityOk", true);
        b.putBoolean("maintenanceHostReady", prefs.getBoolean("direct_maintenance_host_ready", false));
        b.putLong("lumiVersionCode", vc);
        b.putString("lumiVersionName", vn);
        b.putString("transaction_id", tx);
        b.putLong("completed_at", now);
        b.putLong("round_trip_ms", 0L);
        b.putString("failedStage", "NONE");
        return b;
    }

    private static long currentVersionCode(Context c) throws Exception {
        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }
}
