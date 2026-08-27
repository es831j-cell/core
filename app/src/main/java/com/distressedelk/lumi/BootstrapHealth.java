package com.distressedelk.lumi;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class BootstrapHealth {
    private BootstrapHealth() {}

    static Bundle healthBundle(Context context, SharedPreferences prefs) {
        Bundle out = new Bundle();
        ArrayList<String> issues = new ArrayList<>();
        long version = currentVersion(context);
        long now = System.currentTimeMillis();

        File modelRoot = context.getExternalFilesDir(null);
        File fast = new File(new File(modelRoot == null ? context.getFilesDir() : modelRoot, "models"), "Qwen3-0.6B-Q4_K_M.gguf");
        boolean fastVerified = fast.exists() && fast.length() > 330L*1024L*1024L && prefs.getBoolean("fast_model_verified", false);
        if (!fastVerified) issues.add("Fast Brain model is not verified");
        long quarantine = prefs.getLong("fast_brain_quarantine_until", 0L);
        if (quarantine > now) issues.add("Fast Brain is quarantined");
        String localStatus = prefs.getString("local_brain_status", "");
        if (localStatus.toLowerCase().contains("prompt mismatch")) issues.add("Fast Brain has a prompt-mismatch fault");
        long bootVersionForProof=prefs.getLong("bootstrap_last_boot_version",-1L);
        long bootAtForProof=prefs.getLong("bootstrap_last_boot_at",0L);
        long normalSuccess=prefs.getLong("fast_brain_last_success_at",0L);
        boolean normalInferenceProof=bootVersionForProof==version && bootAtForProof>0L && normalSuccess>=bootAtForProof;
        boolean certificationProbeProof=prefs.getBoolean("fast_brain_certification_probe_passed",false)
                && prefs.getLong("fast_brain_certification_probe_version",-1L)==version;
        // Code383: a worker probe proves engine readiness, but it is not a substitute for a real
        // post-install conversational inference. Certification now requires fresh normal-use proof.
        if(fastVerified && !normalInferenceProof)
            issues.add("Fast Brain has not completed a fresh normal inference after this core boot" +
                    (certificationProbeProof ? " (worker probe passed)" : ""));
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) issues.add("speech recognition is unavailable");
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) issues.add("microphone permission is missing");
        LumiSelfUpdateEngine.initialize(context,prefs);
        if (!prefs.getBoolean("native_self_update_engine_ready", false)) issues.add("Lumi native self-update engine is not ready");

        if (!prefs.getBoolean("lumi_1_0_foundation_ready", false)) issues.add("Lumi 1.0 foundation is not initialized");
        if (!prefs.getBoolean("direct_maintenance_host_ready", false)) issues.add("native maintenance host is not ready");
        String moduleHealth = RuntimeModuleRegistry.health(context, prefs);
        if (moduleHealth.startsWith("module registry error") || moduleHealth.startsWith("required module missing") || moduleHealth.startsWith("module registry check failed")) issues.add(moduleHealth);
        out.putString("module_health", moduleHealth);
        out.putLong("module_epoch", prefs.getLong("runtime_module_epoch", 0L));
        out.putInt("module_count", prefs.getInt("active_module_count", 0));
        try {
            JSONObject vault=LumiMemoryVault.get(context).stats();
            if (!vault.optBoolean("ready", false)) issues.add("Memory Vault is not ready");
        } catch (Throwable t) { issues.add("Memory Vault health check failed"); }

        File diag = new File(context.getFilesDir(), "lumi-diagnostics.log");
        try {
            File parent = diag.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
            if (!diag.exists()) diag.createNewFile();
            if (!diag.canWrite()) issues.add("diagnostic log is not writable");
        } catch (Exception e) { issues.add("diagnostic log check failed"); }

        long bootVersion = prefs.getLong("bootstrap_last_boot_version", -1L);
        long bootAt = prefs.getLong("bootstrap_last_boot_at", 0L);
        if (bootVersion != version || bootAt <= 0L) issues.add("current core has not completed its bootstrap heartbeat");

        // Static component presence is no longer enough for a "fully certified" claim.
        // The Black Box remediation contract requires observed end-to-end behavior on the installed phone.
        if(version>=381L && !prefs.getBoolean("full_remediation_acceptance_complete",false))
            issues.add("Code"+version+" Full Remediation behavioral acceptance is still pending");
        if(version>=381L){
            try{ if(!TrustedBuildRelayClient.status(prefs).optBoolean("configured",false))
                issues.add("trusted private build relay is not configured (external owner configuration required)");
            }catch(Throwable t){ issues.add("trusted private build relay status is unavailable"); }
        }

        boolean certified = issues.isEmpty();
        out.putBoolean("ok", true);
        out.putBoolean("certified", certified);
        out.putLong("version_code", version);
        out.putStringArrayList("issues", issues);
        out.putString("summary", certified ? "All Lumi core health checks passed for code " + version + "." : "Lumi is operational but not fully certified: " + join(issues));
        return out;
    }


    /**
     * Runs an actual Fast Brain instruction-following probe. A stale prompt-mismatch
     * quarantine is cleared only after the model produces the expected constrained
     * response. This is deliberately separate from healthBundle(), which is read-only.
     */
    static Bundle certificationBundle(Context context, SharedPreferences prefs) {
        LocalBrain.initialize(context);
        File modelRoot = context.getExternalFilesDir(null);
        File fast = new File(new File(modelRoot == null ? context.getFilesDir() : modelRoot, "models"), "Qwen3-0.6B-Q4_K_M.gguf");
        if (!(fast.exists() && fast.length() > 330L*1024L*1024L && prefs.getBoolean("fast_model_verified", false))) {
            Bundle b = healthBundle(context, prefs);
            b.putBoolean("probe_ran", false);
            b.putString("probe_result", "Fast Brain model is not verified");
            return b;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> rawReply = new AtomicReference<>(null);
        final AtomicReference<String> error = new AtomicReference<>(null);
        int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));

        // Code350 uses the same tiny request-scoped probe as the supervisor. Certification
        // must prove the real worker/result path without asking Qwen3 for a long completion.
        try {
            LocalBrain.probe(fast.getAbsolutePath(), 512, threads, new LocalBrain.Callback() {
                @Override public void onReply(String text, double tokensPerSecond) { rawReply.set(text == null ? "" : text.trim()); latch.countDown(); }
                @Override public void onError(String message) { error.set(message == null ? "unknown local engine error" : message); latch.countDown(); }
            });
            boolean completed = latch.await(50, TimeUnit.SECONDS);
            String raw = rawReply.get();
            String visible = LocalBrain.sanitizeVisibleReply(raw);
            String normalized = visible == null ? "" : visible.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
            boolean passed = completed && error.get() == null && "ready".equals(normalized);
            long now = System.currentTimeMillis();
            String rawPreview = preview(raw, 180);
            String visiblePreview = preview(visible, 180);

            if (passed) {
                prefs.edit()
                        .remove("fast_brain_quarantine_until")
                        .putString("local_brain_status", "ready • certification probe passed")
                        .putBoolean("fast_brain_certification_probe_passed", true)
                        .putLong("fast_brain_certification_probe_version", currentVersion(context))
                        .putLong("fast_brain_certification_probe_at", now)
                        .putString("fast_brain_certification_probe_reply", visible == null ? "" : visible)
                        .putString("fast_brain_certification_probe_raw", rawPreview)
                        .remove("fast_brain_certification_probe_error")
                        .apply();
                Bundle b = healthBundle(context, prefs);
                b.putBoolean("probe_ran", true);
                b.putBoolean("probe_passed", true);
                b.putString("probe_result", "Fast Brain live worker probe passed");
                return b;
            }

            String why;
            if (!completed) why = "timed out waiting for the local engine";
            else if (error.get() != null) why = "local engine error: " + error.get();
            else if (raw == null || raw.trim().isEmpty()) why = "engine returned an empty raw completion";
            else if (visible == null || visible.trim().isEmpty()) why = "engine returned thinking/control text only; raw=" + rawPreview;
            else why = "unexpected visible reply: " + visiblePreview + "; raw=" + rawPreview;

            prefs.edit()
                    .putBoolean("fast_brain_certification_probe_passed", false)
                    .putLong("fast_brain_certification_probe_version", currentVersion(context))
                    .putLong("fast_brain_certification_probe_at", now)
                    .putString("fast_brain_certification_probe_reply", visible == null ? "" : visible)
                    .putString("fast_brain_certification_probe_raw", rawPreview)
                    .putString("fast_brain_certification_probe_error", why)
                    .apply();
            Bundle b = healthBundle(context, prefs);
            ArrayList<String> issues = b.getStringArrayList("issues");
            if (issues == null) issues = new ArrayList<>();
            issues.add("Fast Brain certification probe failed (" + why + ")");
            b.putStringArrayList("issues", issues);
            b.putBoolean("certified", false);
            b.putBoolean("probe_ran", true);
            b.putBoolean("probe_passed", false);
            b.putString("probe_result", why);
            b.putString("summary", "Lumi is operational but not fully certified: " + join(issues));
            return b;
        } catch (Exception e) {
            Bundle b = healthBundle(context, prefs);
            ArrayList<String> issues = b.getStringArrayList("issues");
            if (issues == null) issues = new ArrayList<>();
            issues.add("Fast Brain certification probe could not run (" + e.getClass().getSimpleName() + ")");
            b.putStringArrayList("issues", issues);
            b.putBoolean("certified", false);
            b.putBoolean("probe_ran", true);
            b.putBoolean("probe_passed", false);
            b.putString("probe_result", String.valueOf(e.getMessage()));
            b.putString("summary", "Lumi is operational but not fully certified: " + join(issues));
            return b;
        }
    }

    private static String preview(String value, int max) {
        if (value == null) return "<null>";
        String s = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.isEmpty()) return "<empty>";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String humanSummary(Context context, SharedPreferences prefs) {
        Bundle b = healthBundle(context, prefs); return b.getString("summary", "Health status unavailable.");
    }

    private static long currentVersion(Context context) {
        try { PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0); return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode; }
        catch(Exception e){ return -1L; }
    }

    private static String join(List<String> items) {
        StringBuilder s=new StringBuilder(); for(int i=0;i<items.size();i++){ if(i>0)s.append("; "); s.append(items.get(i)); } return s.toString();
    }
}
