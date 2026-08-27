package com.distressedelk.lumi;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Code376 identity + contact-card authority boundary.
 * Recognition is identity evidence, never administrator authorization by itself.
 * Contact #001 is the single primary administrator. Introduced people always begin with NONE permissions.
 */
final class IdentityHierarchy {
    static final long ADMIN_SESSION_MS = 10L * 60L * 1000L;
    static final String PRIMARY_CONTACT_ID = "contact-001";
    private static final String ADMIN_PHRASE = "there can be only one";
    private IdentityHierarchy(){}

    static boolean isAdminPhrase(String raw){
        String s=raw==null?"":raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]"," ").replaceAll("\\s+"," ").trim();
        return s.equals(ADMIN_PHRASE) || s.equals("lumi "+ADMIN_PHRASE);
    }

    static String redactAdminPhrase(String raw,String replacement){
        if(raw==null) return "";
        String safeReplacement=(replacement==null || replacement.trim().isEmpty())?"[administrator passphrase]":replacement;
        return raw.replaceAll("(?i)\\bthere\\s+can\\s+be\\s+only\\s+one\\b",java.util.regex.Matcher.quoteReplacement(safeReplacement));
    }

    /** Cold-start boundary. A prior app process can never leave root authority propped open. */
    static void beginUnauthenticatedSession(SharedPreferences prefs){
        if(prefs==null) return;
        prefs.edit()
                .putLong("root_admin_session_until",0L)
                .putBoolean("session_identity_verified",false)
                .remove("session_identity_verified_id")
                .remove("session_identity_verified_name")
                .remove("session_identity_verified_at")
                .remove("active_voice_speaker_id")
                .remove("active_voice_speaker_name")
                .putString("session_security_state","UNKNOWN_UNAUTHENTICATED")
                .apply();
    }

    static boolean openAdminSession(SharedPreferences prefs){
        if(!prefs.getBoolean("admin_enrollment_complete",false)) return false;
        long until=System.currentTimeMillis()+ADMIN_SESSION_MS;
        prefs.edit().putLong("root_admin_session_until",until)
                .putLong("root_admin_last_verified_at",System.currentTimeMillis())
                .putString("root_admin_authority","SOLE_ROOT_ADMIN")
                .putString("session_security_state","ADMIN_AUTHORIZED")
                .apply();
        return true;
    }

    static boolean strongAdminSessionActive(SharedPreferences prefs){
        return prefs.getBoolean("admin_enrollment_complete",false)
                && System.currentTimeMillis()<=prefs.getLong("root_admin_session_until",0L);
    }

    /** Voice is retained as evidence for speaker recognition, but never opens authority on its own. */
    static boolean voiceAdminSessionActive(SharedPreferences prefs){
        if(!prefs.getBoolean("admin_enrollment_complete",false) || !prefs.getBoolean("admin_profile_verified",false)) return false;
        if(!"probable-owner".equals(prefs.getString("speaker_last_state",""))) return false;
        if(prefs.getInt("speaker_last_confidence",0)<95 || !prefs.getBoolean("speaker_liveness_passed",false)) return false;
        long liveChecked=prefs.getLong("speaker_liveness_checked_at",0L);
        long now=System.currentTimeMillis();
        return liveChecked>0L && now-liveChecked<=2L*60L*60L*1000L;
    }

    static boolean adminSessionActive(SharedPreferences prefs){
        return strongAdminSessionActive(prefs);
    }

    static boolean ownerVoiceRecentlyVerified(SharedPreferences prefs){
        if(prefs==null) return false;
        if(!"OWNER_ACCEPTED".equals(prefs.getString("audio_gate_last_category",""))) return false;
        if(prefs.getInt("audio_gate_last_confidence",0)<76) return false;
        long at=prefs.getLong("audio_gate_last_owner_match_at",0L);
        return at>0L && System.currentTimeMillis()-at<=20L*60L*1000L;
    }

    static void markRecognizedSessionIdentity(SharedPreferences prefs,String id,String name,int confidence){
        if(prefs==null || id==null || id.trim().isEmpty()) return;
        prefs.edit().putBoolean("session_identity_verified",true)
                .putString("session_identity_verified_id",id)
                .putString("session_identity_verified_name",name==null?"":name)
                .putInt("session_identity_confidence",Math.max(0,Math.min(100,confidence)))
                .putLong("session_identity_verified_at",System.currentTimeMillis())
                .putString("session_security_state","RECOGNIZED_NOT_ADMIN")
                .apply();
    }

    static String primaryAdminContactId(SharedPreferences prefs){ return PRIMARY_CONTACT_ID; }

    /** Creates/repairs Contact Card #001 without granting authority to anyone else. */
    static void ensurePrimaryAdminContact(SharedPreferences prefs){
        if(prefs==null) return;
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            JSONObject primary=null;
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i);
                if(c!=null && PRIMARY_CONTACT_ID.equals(c.optString("id"))){ primary=c; break; }
            }
            if(primary==null){ primary=new JSONObject(); a.put(primary); }
            String name=prefs.getString("owner_call_name",prefs.getString("owner_name","Primary Administrator")).trim();
            if(name.isEmpty()) name="Primary Administrator";
            primary.put("id",PRIMARY_CONTACT_ID);
            primary.put("contactNumber",1);
            primary.put("displayName",name);
            primary.put("name",name);
            primary.put("state",prefs.getBoolean("admin_enrollment_complete",false)?"PRIMARY_ADMIN":"PRIMARY_ADMIN_PENDING_ENROLLMENT");
            primary.put("relationship","Primary Administrator");
            primary.put("permissionLevel","ROOT_ADMIN");
            primary.put("privileged",true);
            primary.put("source","owner-enrollment");
            primary.put("firstMetAt",prefs.getLong("admin_enrollment_completed_at",System.currentTimeMillis()));
            primary.put("needsPrivateReview",false);
            primary.put("voiceProfile",prefs.getBoolean("admin_voice_enrolled",false)?"OWNER_ENROLLED":"PENDING");
            primary.put("voiceConsentState","OWNER_ENROLLMENT");
            primary.put("updatedAt",System.currentTimeMillis());
            prefs.edit().putString("identity_contacts_json",a.toString()).apply();
        }catch(Exception ignored){}
    }

    static String createProvisionalContact(SharedPreferences prefs,String displayName,String source){
        return createIntroducedContact(prefs,displayName,"UNREVIEWED",source);
    }

    static String createIntroducedContact(SharedPreferences prefs,String displayName,String relationship,String source){
        String name=displayName==null?"":displayName.trim();
        if(name.isEmpty()) name="New person";
        String rel=relationship==null?"":relationship.trim();
        if(rel.isEmpty()) rel="UNREVIEWED";
        try{
            ensurePrimaryAdminContact(prefs);
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            JSONObject c=new JSONObject();
            String id="contact-"+UUID.randomUUID().toString();
            c.put("id",id);
            c.put("contactNumber",nextContactNumber(a));
            c.put("displayName",name);
            c.put("name",name);
            c.put("state","INTRODUCED");
            c.put("relationship",rel);
            c.put("permissionLevel","NONE");
            c.put("privileged",false);
            c.put("source",source==null?"formal-introduction":source);
            c.put("introducedBy",PRIMARY_CONTACT_ID);
            c.put("firstMetAt",System.currentTimeMillis());
            c.put("lastInteractionAt",System.currentTimeMillis());
            c.put("needsPrivateReview",true);
            c.put("voiceProfile","NOT_ENROLLED");
            c.put("voiceConsentState","REQUIRED_FOR_PERSISTENT_PROFILE");
            a.put(c);
            prefs.edit().putString("identity_contacts_json",a.toString())
                    .putBoolean("identity_private_review_pending",true)
                    .putString("identity_private_review_contact_id",id)
                    .putString("identity_private_review_name",name)
                    .apply();
            return id;
        }catch(Exception e){ return ""; }
    }

    static void noteTransientVoiceSample(SharedPreferences prefs,String contactId,int byteCount){
        if(prefs==null || contactId==null || contactId.isEmpty()) return;
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c==null || !contactId.equals(c.optString("id"))) continue;
                c.put("voiceSampleObservedAt",System.currentTimeMillis());
                c.put("voiceSampleObservedBytes",Math.max(0,byteCount));
                c.put("voiceSampleRetention","SESSION_ONLY_UNTIL_CONSENT");
                c.put("lastInteractionAt",System.currentTimeMillis());
                break;
            }
            prefs.edit().putString("identity_contacts_json",a.toString()).apply();
        }catch(Exception ignored){}
    }

    static String contactName(SharedPreferences prefs,String contactId){
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c!=null && contactId.equals(c.optString("id"))) return c.optString("displayName",c.optString("name","New person"));
            }
        }catch(Exception ignored){}
        return "New person";
    }

    static JSONArray contactCardsForUi(SharedPreferences prefs){
        ensurePrimaryAdminContact(prefs);
        JSONArray out=new JSONArray();
        Set<String> names=new HashSet<>();
        try{
            JSONArray ids=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<ids.length();i++){
                JSONObject c=ids.optJSONObject(i); if(c==null) continue;
                out.put(new JSONObject(c.toString()));
                names.add(c.optString("displayName",c.optString("name","")).toLowerCase(Locale.US));
            }
            JSONArray legacy=new JSONArray(prefs.getString("people_cards_json","[]"));
            for(int i=0;i<legacy.length();i++){
                JSONObject c=legacy.optJSONObject(i); if(c==null) continue;
                String n=c.optString("name","").toLowerCase(Locale.US);
                if(!n.isEmpty() && names.contains(n)) continue;
                out.put(new JSONObject(c.toString()));
            }
        }catch(Exception ignored){}
        return out;
    }

    private static int nextContactNumber(JSONArray a){
        int max=1;
        for(int i=0;i<a.length();i++){
            JSONObject c=a.optJSONObject(i); if(c!=null) max=Math.max(max,c.optInt("contactNumber",0));
        }
        return max+1;
    }

    static String pendingPrivateReviewPrompt(SharedPreferences prefs){
        if(!prefs.getBoolean("identity_private_review_pending",false)) return null;
        if(!adminSessionActive(prefs)) return null;
        String n=prefs.getString("identity_private_review_name","that person");
        return "When we're alone, I still need to privately review "+n+" with you: trust level and permissions. Until then, they have no privileged access.";
    }

    static boolean updatePendingReview(SharedPreferences prefs,String relationship,String permission){
        String id=prefs.getString("identity_private_review_contact_id","");
        if(id.isEmpty() || !adminSessionActive(prefs)) return false;
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c==null || !id.equals(c.optString("id"))) continue;
                if(relationship!=null && !relationship.trim().isEmpty()) c.put("relationship",relationship.trim());
                if(permission!=null && !permission.trim().isEmpty()){
                    String level=permission.trim().toUpperCase(Locale.US).replace(' ','_');
                    if(level.equals("ROOT") || level.equals("ADMIN") || level.equals("ROOT_ADMIN")) level="NONE";
                    c.put("permissionLevel",level);
                    c.put("privileged",!level.equals("NONE"));
                }
                if(!"UNREVIEWED".equals(c.optString("relationship")) && permission!=null){
                    c.put("state","CONFIRMED"); c.put("needsPrivateReview",false);
                    prefs.edit().putBoolean("identity_private_review_pending",false)
                            .remove("identity_private_review_contact_id").remove("identity_private_review_name").apply();
                }
                prefs.edit().putString("identity_contacts_json",a.toString()).apply();
                return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    static String contactSummary(SharedPreferences prefs){
        ensurePrimaryAdminContact(prefs);
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            int introduced=0,privileged=0;
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c==null)continue;
                if("INTRODUCED".equals(c.optString("state"))) introduced++;
                if(c.optBoolean("privileged",false) && !PRIMARY_CONTACT_ID.equals(c.optString("id"))) privileged++;
            }
            return "Identity hierarchy: Contact #001 is the sole root administrator; "+a.length()+" total contact card"+(a.length()==1?"":"s")+", "+introduced+" introduced awaiting review, "+privileged+" non-root privileged contacts.";
        }catch(Exception e){ return "Identity hierarchy is initialized with Contact #001 as the sole root administrator."; }
    }
}
