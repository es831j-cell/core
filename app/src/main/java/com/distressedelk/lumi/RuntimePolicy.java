package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Code388 release policy: Lumi owns normal updates; Factory is recovery-only. */
final class RuntimePolicy {
    private RuntimePolicy() {}

    static boolean chatManagedReleases() { return false; }
    static boolean autonomousCoreBuildsAllowed() { return false; }
    static boolean ownerApprovedBridgeBuildsAllowed() { return true; }
    static boolean overnightSelfOptimizationAllowed() { return false; }

    static boolean blocksMaintenanceTool(String name) {
        if(name==null) return false;
        String n=name.trim();
        // Keep direct arbitrary/sideload install paths out of the model surface. Owner-approved
        // source staging -> trusted relay -> Lumi verification -> Android installer is the only normal core mutation route.
        return "install_signed_update".equals(n);
    }

    static void applyStartupPolicy(Context context, SharedPreferences prefs) {
        if (prefs == null) return;
        prefs.edit()
                .putString("release_management_mode", "OWNER_APPROVED_BRIDGE_BUILD")
                .putBoolean("overnight_maintenance", false)
                .putBoolean("evolution_overnight_active", false)
                .putBoolean("autonomous_core_updates_enabled", false)
                .putBoolean("autonomous_self_optimization_enabled", false)
                .putBoolean("bridge_builds_enabled", true)
                .apply();
        // Code371 deliberately preserves trusted_core_build_* transaction state across process
        // restarts so the JobScheduler relay can finish while the screen is off.
    }

    static boolean isSelfModificationPhrase(String raw) {
        if (raw == null) return false;
        String s = raw.toLowerCase(Locale.US).replace('-', ' ').trim();
        return s.equals("update yourself") || s.equals("lumi update yourself")
                || s.equals("self update") || s.equals("self update now")
                || s.equals("build update") || s.equals("build the update")
                || s.equals("build my update") || s.equals("optimize yourself")
                || s.equals("lumi optimize yourself") || s.equals("optimize your system")
                || s.equals("install optimization") || s.equals("install the optimization")
                || s.equals("lumi install optimization");
    }

    static String blockedSelfModificationReply() {
        return "Autonomous unapproved self-modification is disabled. Owner-approved core changes use the trusted relay when a build is needed; Lumi verifies the signed APK, creates a local recovery checkpoint, and opens Android's normal installer approval.";
    }

    static String summary() {
        return "OWNER-APPROVED NATIVE SELF-UPDATE • source patch → private GitHub Actions relay → signed APK → Lumi verify/checkpoint → Android installer → Lumi post-install validation • Factory is recovery-only.";
    }
}
