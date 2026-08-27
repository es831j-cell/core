package com.distressedelk.lumi;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Code336 write authorization.
 *
 * Fresh owner approval + live root authentication starts a normal update transaction. Low-risk
 * steps inside that SAME transaction may then use the bounded continuity grant even after the
 * short conversational root session expires. High-risk security/authority operations never use
 * continuity and always require fresh root authentication and fresh explicit approval.
 */
final class MaintenanceAuthorization {
    static final class Decision {
        final boolean allowed; final String reason; final boolean carried;
        Decision(boolean allowed,String reason){this(allowed,reason,false);}
        Decision(boolean allowed,String reason,boolean carried){this.allowed=allowed;this.reason=reason;this.carried=carried;}
    }
    private MaintenanceAuthorization(){}

    static Decision authorizeWrite(Activity activity, SharedPreferences prefs, String userText, String toolName, JSONObject args) {
        if (prefs.getBoolean("remote_maintenance_revoked", false)) return new Decision(false,"Remote/AI maintenance has been revoked in Lumi settings.");
        if (!prefs.getBoolean("admin_enrollment_complete", false)) return new Decision(false,"Administrator enrollment must be completed before Lumi accepts maintenance writes.");

        String tool=toolName==null?"":toolName.trim();
        JSONObject safeArgs=args==null?new JSONObject():args;
        boolean highRisk=isHighRisk(tool,safeArgs);

        // Code388: durable approval belongs to the exact Lumi update transaction, not to
        // a conversational session. It survives process/app restarts until that transaction
        // certifies, fails, is cancelled, or is superseded.
        if(!highRisk && UpdateTransactionManager.allows(prefs,tool,safeArgs)){
            return new Decision(true,"Existing owner approval is bound to this exact durable update transaction.",true);
        }
        // Keep legacy session continuity only for non-transactional maintenance paths.
        if(!highRisk && MaintenanceSession.writeApprovalActive(prefs) && continuityMatches(prefs,tool,safeArgs)){
            return new Decision(true,"Existing owner approval is valid for this bounded maintenance session.",true);
        }
        if("submit_maintenance_request".equals(tool) && UpdateTransactionManager.active(prefs)){
            return new Decision(false,"Update transaction "+UpdateTransactionManager.requestId(prefs)+" is still active. Finish, cancel, or fail that transaction before starting another core request.");
        }

        if (highRisk && !IdentityHierarchy.strongAdminSessionActive(prefs)) return new Decision(false,"This sensitive security change requires a fresh administrator-authorized session. Voice recognition alone never grants administrator authority.");
        if (!highRisk && !IdentityHierarchy.adminSessionActive(prefs)) return new Decision(false,"Administrator authority is not active. Authenticate through the protected administrator flow before beginning this update transaction. Voice recognition alone is identity evidence, not authority.");
        try {
            KeyguardManager km=(KeyguardManager)activity.getSystemService(Context.KEYGUARD_SERVICE);
            if(km!=null && km.isDeviceLocked()) return new Decision(false,"The phone is locked. Unlock it before approving a maintenance change.");
        } catch(Throwable ignored) {}

        String l=normalize(userText);
        long selectedAge=System.currentTimeMillis()-prefs.getLong("improvement_advisor_last_selected_at",0L);
        boolean boundedSuggestionRetry=(l.equals("retry")||l.equals("try again")||l.equals("retry suggestion"))
                && prefs.getInt("improvement_advisor_last_selected_index",-1)>0 && selectedAge>=0L && selectedAge<=10L*60L*1000L;
        boolean explicit=boundedSuggestionRetry || explicitOwnerApproval(l);
        if(!explicit){
            return new Decision(false,highRisk
                    ?"This high-risk security/authority change requires fresh explicit owner approval."
                    :"Approve the update once with a natural command such as ‘do it’, ‘apply the fixes’, or ‘install it’. After that, ordinary steps continue automatically.");
        }

        if(highRisk){
            return new Decision(true,"Fresh root-authenticated owner approval accepted for high-risk operation.",false);
        }
        boolean armed=MaintenanceSession.grantWriteApproval(prefs,"owner approved bounded update transaction");
        return new Decision(true,armed
                ?"Owner approval accepted and transaction continuity armed through patch, build, staging, Android install approval and post-install validation."
                :"Owner approval accepted for this write.",false);
    }

    private static boolean continuityMatches(SharedPreferences prefs,String tool,JSONObject args){
        String bound=MaintenanceSession.approvedRequestId(prefs);
        if(bound.isEmpty()) return "submit_maintenance_request".equals(tool) || "create_recovery_checkpoint".equals(tool);
        if("apply_core_source_patch".equals(tool) || "apply_runtime_fix".equals(tool) || "start_trusted_relay_build".equals(tool))
            return MaintenanceSession.requestMatchesApproval(prefs,args.optString("request_id",""));
        if("launch_pending_core_install".equals(tool)){
            String active=prefs.getString("trusted_core_build_request_id","");
            String pending=prefs.getString("pending_core_update_id","");
            return bound.equals(active) || bound.equals(pending);
        }
        if("create_recovery_checkpoint".equals(tool)) return true;
        // A second Lumi request is a new transaction unless the user explicitly approves it.
        if("submit_maintenance_request".equals(tool)) return false;
        return false;
    }

    private static boolean isHighRisk(String tool,JSONObject args){
        if("configure_trusted_build_relay".equals(tool) || "rollback_last_update".equals(tool)
                || "install_signed_update".equals(tool) || "start_pending_bridge_update".equals(tool)) return true;
        if("submit_maintenance_request".equals(tool) && "rollback".equalsIgnoreCase(args.optString("change_type",""))) return true;
        String text=(args.optString("requested_change","")+" "+args.toString()).toLowerCase(Locale.US);
        return text.matches(".*\\b(signing key|keystore|credential|secret|token|passphrase|administrator|admin authority|guardian security|permission escalation|expand authority|delete data|wipe data|erase data|revoke owner|identity hierarchy)\\b.*");
    }

    private static boolean explicitOwnerApproval(String l){
        if(l==null || l.isEmpty()) return false;
        if(l.matches("^(can|could|would|will|do)\\s+you\\b.*") || l.endsWith("?")) return false;
        return l.matches(".*\\b(do it|go ahead|build it|finish the build|finish build|finish update|complete update|create the update|install it|install the update|install code [0-9]+|apply it|apply the update|apply fixes|apply the fixes|apply suggested fix|apply suggested fixes|update lumi|fix it|fix error|repair it|repair the update handoff|make the change|proceed|approved|approve it|i approve|yes please|you have my approval|you have my permission)\\b.*")
                || l.matches("^(apply|install|fix|repair|build|implement|patch|update|complete|finish|continue|proceed)\\b.+")
                || l.matches(".*\\b(fix|change|improve|tune|smooth)\\s+your\\s+(speech|voice|speaking|pronunciation|pacing)\\b.*")
                || l.matches(".*\\boptimi[sz]e\\s+(your\\s+)?(speech|speach|voice|speaking|pronunciation|pacing)\\b.*")
                || l.matches(".*\\b(apply|approve|approved|do|run|install)\\s+(the\\s+)?(improvement\\s+)?suggestion\\s+(?:[0-9]+|one|two|three|four|five|won|too|to|tree|for|fore)\\b.*")
                || l.matches(".*\\bi\\s+approve\\s+(the\\s+)?(improvement\\s+)?suggestion\\s+(?:[0-9]+|one|two|three|four|five|won|too|to|tree|for|fore)\\b.*");
    }

    private static String normalize(String raw){
        return raw==null?"":raw.toLowerCase(Locale.US).replace('-',' ').replace('–',' ').replace('—',' ').replaceAll("\\s+"," ").trim();
    }
}
