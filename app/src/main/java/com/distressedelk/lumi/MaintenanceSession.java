package com.distressedelk.lumi;

import android.content.SharedPreferences;

import java.util.Locale;
import java.util.UUID;

/**
 * Code336: durable maintenance routing plus one-approval-per-update-transaction continuity.
 *
 * A root-authenticated explicit owner approval can arm one bounded transaction grant. Ordinary
 * steps in that SAME transaction may continue through source patch, trusted relay build, verified
 * artifact staging, Lumi APK verification, Android installer launch and post-install validation without asking
 * the owner to repeat approval at every doorway. The grant never bypasses Android's installer UI,
 * signature checks, Android installer approval, Lumi post-install validation, rollback, or protected source-file rules.
 *
 * High-risk authority/security changes are not covered by continuity and still require fresh
 * administrator approval. Approval is cleared on cancel, certified completion, failure or reset.
 */
final class MaintenanceSession {
    private static final String ACTIVE="maintenance_session_active";
    private static final String STARTED="maintenance_session_started_at";
    private static final String LAST="maintenance_session_last_activity_at";
    private static final String REASON="maintenance_session_reason";
    private static final String END_REASON="maintenance_session_end_reason";
    private static final String SESSION_ID="maintenance_session_id";

    private static final String WRITE_APPROVAL_ACTIVE="maintenance_write_approval_active";
    private static final String WRITE_APPROVAL_SESSION_ID="maintenance_write_approval_session_id";
    private static final String WRITE_APPROVAL_GRANTED_AT="maintenance_write_approval_granted_at";
    private static final String WRITE_APPROVAL_EXPIRES_AT="maintenance_write_approval_expires_at";
    private static final String WRITE_APPROVAL_SOURCE="maintenance_write_approval_source";
    private static final String WRITE_APPROVAL_REQUEST_ID="maintenance_write_approval_request_id";
    private static final String WRITE_APPROVAL_CHANGE_TYPE="maintenance_write_approval_change_type";
    private static final String WRITE_APPROVAL_SCOPE="maintenance_write_approval_scope";

    private static final long IDLE_TTL_MS=2L*60L*60L*1000L;
    private static final long APPROVAL_TTL_MS=2L*60L*60L*1000L;

    private MaintenanceSession(){}

    static boolean active(SharedPreferences p){
        if(p==null || !p.getBoolean(ACTIVE,false)) return false;
        long now=System.currentTimeMillis();
        long last=p.getLong(LAST,p.getLong(STARTED,0L));
        if(!p.getBoolean("trusted_core_build_active",false) && (last<=0L || now-last>IDLE_TTL_MS)){
            clear(p,"expired after inactivity");
            return false;
        }
        return true;
    }

    static void begin(SharedPreferences p,String reason){
        if(p==null) return;
        boolean wasActive=p.getBoolean(ACTIVE,false);
        if(!wasActive){
            MaintenanceWorkflowState.clear(p);
            clearWriteApproval(p);
        }
        long now=System.currentTimeMillis();
        String sessionId=p.getString(SESSION_ID,"");
        if(!wasActive || sessionId.isEmpty()) sessionId="ms-"+now+"-"+UUID.randomUUID().toString().substring(0,8);
        SharedPreferences.Editor e=p.edit().putBoolean(ACTIVE,true)
                .putString(SESSION_ID,sessionId)
                .putLong(LAST,now).putString(REASON,safe(reason));
        if(!wasActive) e.putLong(STARTED,now);
        e.remove(END_REASON).apply();
    }

    static void touch(SharedPreferences p){
        if(p!=null && p.getBoolean(ACTIVE,false)) p.edit().putLong(LAST,System.currentTimeMillis()).apply();
    }

    /** Caller must already have passed the full fresh administrator authorization gate. */
    static boolean grantWriteApproval(SharedPreferences p,String source){
        if(p==null || !active(p) || !IdentityHierarchy.adminSessionActive(p)) return false;
        String sessionId=p.getString(SESSION_ID,"");
        if(sessionId.isEmpty()) return false;
        long now=System.currentTimeMillis();
        p.edit().putBoolean(WRITE_APPROVAL_ACTIVE,true)
                .putString(WRITE_APPROVAL_SESSION_ID,sessionId)
                .putLong(WRITE_APPROVAL_GRANTED_AT,now)
                .putLong(WRITE_APPROVAL_EXPIRES_AT,now+APPROVAL_TTL_MS)
                .putString(WRITE_APPROVAL_SOURCE,safe(source))
                .remove(WRITE_APPROVAL_REQUEST_ID)
                .remove(WRITE_APPROVAL_CHANGE_TYPE)
                .remove(WRITE_APPROVAL_SCOPE)
                .apply();
        return true;
    }

    /** Bind the current approval to the Lumi request that represents this update transaction. */
    static void bindWriteApprovalToRequest(SharedPreferences p,String requestId,String changeType,String scope){
        if(p==null || !writeApprovalActive(p)) return;
        String id=safe(requestId);
        if(id.isEmpty()) return;
        p.edit().putString(WRITE_APPROVAL_REQUEST_ID,id)
                .putString(WRITE_APPROVAL_CHANGE_TYPE,safe(changeType).toLowerCase(Locale.US))
                .putString(WRITE_APPROVAL_SCOPE,safe(scope))
                .putLong(LAST,System.currentTimeMillis())
                .apply();
    }

    static boolean writeApprovalActive(SharedPreferences p){
        if(p==null || !active(p) || !p.getBoolean(WRITE_APPROVAL_ACTIVE,false)) return false;
        String sessionId=p.getString(SESSION_ID,"");
        String approvalSession=p.getString(WRITE_APPROVAL_SESSION_ID,"");
        long expires=p.getLong(WRITE_APPROVAL_EXPIRES_AT,0L);
        if(sessionId.isEmpty() || !sessionId.equals(approvalSession) || System.currentTimeMillis()>expires){
            clearWriteApproval(p);
            return false;
        }
        return true;
    }

    static boolean approvalBound(SharedPreferences p){
        return writeApprovalActive(p) && !p.getString(WRITE_APPROVAL_REQUEST_ID,"").isEmpty();
    }

    static String approvedRequestId(SharedPreferences p){ return p==null?"":p.getString(WRITE_APPROVAL_REQUEST_ID,""); }
    static String approvedScope(SharedPreferences p){ return p==null?"":p.getString(WRITE_APPROVAL_SCOPE,""); }
    static long writeApprovalExpiresAt(SharedPreferences p){ return p==null?0L:p.getLong(WRITE_APPROVAL_EXPIRES_AT,0L); }

    static boolean requestMatchesApproval(SharedPreferences p,String requestId){
        if(!writeApprovalActive(p)) return false;
        String bound=p.getString(WRITE_APPROVAL_REQUEST_ID,"");
        if(bound.isEmpty()) return true; // First queued Lumi request will bind the approval.
        return !safe(requestId).isEmpty() && bound.equals(safe(requestId));
    }

    static void clearWriteApproval(SharedPreferences p){
        if(p==null) return;
        p.edit().remove(WRITE_APPROVAL_ACTIVE)
                .remove(WRITE_APPROVAL_SESSION_ID)
                .remove(WRITE_APPROVAL_GRANTED_AT)
                .remove(WRITE_APPROVAL_EXPIRES_AT)
                .remove(WRITE_APPROVAL_SOURCE)
                .remove(WRITE_APPROVAL_REQUEST_ID)
                .remove(WRITE_APPROVAL_CHANGE_TYPE)
                .remove(WRITE_APPROVAL_SCOPE)
                .apply();
    }

    static void clear(SharedPreferences p,String reason){
        if(p==null) return;
        MaintenanceWorkflowState.clear(p);
        clearWriteApproval(p);
        p.edit().putBoolean(ACTIVE,false).putLong(LAST,System.currentTimeMillis())
                .remove(SESSION_ID)
                .putString(END_REASON,safe(reason)).apply();
    }

    static boolean cancelIntent(String raw){
        String s=norm(raw);
        return s.matches("^(cancel|cancel it|cancel update|cancel the update|cancel maintenance|stop update|stop the update|stop maintenance|abort|abort update|abort the update|never mind|nevermind)[.!?]*$");
    }

    static boolean selfImprovementIntent(String raw){
        String s=norm(raw);
        if(s.isEmpty()) return false;
        boolean target=s.contains("lumi") || s.contains("your app") || s.contains("your code")
                || s.contains("your source") || s.contains("canonical source") || s.contains("source code")
                || s.contains("your listening") || s.contains("your conversation")
                || s.contains("voice recognition") || s.contains("speech recognition")
                || s.contains("your voice") || s.contains("your speech") || s.contains("your speaking")
                || s.contains("tts") || s.contains("pronunciation") || s.contains("pacing") || s.contains("prosody")
                || s.contains("your brain") || s.contains("your routing") || s.contains("your animation")
                || s.contains("your button") || s.contains("your ui") || s.contains("your interface")
                || s.contains("home screen") || s.contains("button color") || s.contains("button colour")
                || s.contains("mobius") || s.contains("möbius") || s.contains("your update") || s.contains("self update")
                || s.contains("self-update") || s.contains("update yourself") || s.contains("improve yourself")
                || s.equals("build update") || s.equals("build the update");
        boolean action=s.contains("fix") || s.contains("repair") || s.contains("change") || s.contains("update")
                || s.contains("patch") || s.contains("improve") || s.contains("tune") || s.contains("modify")
                || s.contains("apply") || s.contains("build") || s.contains("install") || s.contains("optimize")
                || s.contains("inspect") || s.contains("edit") || s.contains("rewrite");
        return target && action;
    }

    static boolean ellipticalAction(String raw){
        String s=norm(raw);
        return s.matches("^(do it|go ahead|proceed|continue|keep going|finish it|finish update|finish the update|complete it|complete update|complete the update|apply it|apply the fix|apply fix|apply fixes|apply suggested fix|apply suggested fixes|fix it|fix error|patch it|build it|build update|build the update|install it|install update|install the update|install code [0-9]+|retry|try again|reconnect|connect|connect it|resume|yes|yes please|approved|i approve)[.!?]*$");
    }

    private static String norm(String s){ return s==null?"":s.toLowerCase(Locale.US).replace('-',' ').replace('–',' ').replace('—',' ').trim(); }
    private static String safe(String s){ return s==null?"":s.replace('\n',' ').replace('\r',' ').trim(); }
}
