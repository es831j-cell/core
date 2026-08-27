package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/** Best-effort per-turn speaker identity registry.
 * Owner voice is compared against the enrolled administrator reference. Contact voice profiles
 * are persistent only after an explicit persistent-voice consent state has been recorded.
 * Recognition is identity evidence, never a replacement for protected administrator auth.
 */
final class SpeakerProfileRegistry {
    static final class Match {
        final boolean usable; final String kind; final String contactId; final String displayName; final int confidence; final String detail;
        Match(boolean usable,String kind,String contactId,String displayName,int confidence,String detail){
            this.usable=usable;this.kind=kind;this.contactId=contactId;this.displayName=displayName;this.confidence=confidence;this.detail=detail;
        }
        boolean owner(){ return "OWNER".equals(kind); }
        boolean known(){ return "KNOWN".equals(kind); }
    }
    private SpeakerProfileRegistry(){}

    static Match identify(Context context, SharedPreferences prefs, byte[] pcm16, int sampleRate){
        if(context==null || prefs==null || pcm16==null || pcm16.length<12000) return new Match(false,"UNAVAILABLE","","",0,"no usable per-turn audio buffer");
        File owner=new File(context.getFilesDir(),"owner_voice_reference.m4a");
        if(prefs.getBoolean("admin_voice_enrolled",false) && owner.isFile()){
            SpeakerVerifier.Result r=SpeakerVerifier.comparePcm(owner,pcm16,sampleRate);
            if(r.usable && r.probableMatch){ return new Match(true,"OWNER",IdentityHierarchy.PRIMARY_CONTACT_ID,prefs.getString("owner_call_name",prefs.getString("owner_name","Owner")),r.confidence,r.detail); }
        }
        Match best=new Match(true,"UNKNOWN","","Unknown speaker",0,"no enrolled contact matched");
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c==null)continue;
                String id=c.optString("id",""); if(id.isEmpty())continue;
                File f=profileFile(context,id);
                if(!f.isFile())continue;
                SpeakerVerifier.Result r=SpeakerVerifier.compareRawPcm(f,pcm16,sampleRate);
                if(r.usable && r.probableMatch && r.confidence>best.confidence){
                    best=new Match(true,"KNOWN",id,c.optString("displayName","Known person"),r.confidence,r.detail);
                }
            }
        }catch(Exception ignored){}
        return best;
    }

    static boolean bindContactVoice(Context context,SharedPreferences prefs,String contactId,byte[] pcm16,int sampleRate){
        if(context==null || prefs==null || contactId==null || contactId.isEmpty() || pcm16==null || pcm16.length<18000)return false;
        // Code376 privacy boundary: a raw biometric profile may not be persisted merely because
        // an introduction occurred. A separate explicit consent flow must first mark this card.
        boolean consent=false;
        try{
            JSONArray contacts=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<contacts.length();i++){
                JSONObject c=contacts.optJSONObject(i);
                if(c!=null && contactId.equals(c.optString("id"))){
                    consent="PERSISTENT_CONSENT_GRANTED".equals(c.optString("voiceConsentState",""));
                    break;
                }
            }
        }catch(Exception ignored){}
        if(!consent) return false;
        try{
            File f=profileFile(context,contactId); f.getParentFile().mkdirs();
            try(FileOutputStream out=new FileOutputStream(f)){ out.write(pcm16); }
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c==null || !contactId.equals(c.optString("id")))continue;
                c.put("voiceProfile","LOCAL_PCM16"); c.put("voiceSampleRate",sampleRate); c.put("voiceEnrolledAt",System.currentTimeMillis());
            }
            prefs.edit().putString("identity_contacts_json",a.toString()).apply();
            return true;
        }catch(Exception e){ return false; }
    }

    private static File profileFile(Context context,String id){
        String safe=id.replaceAll("[^A-Za-z0-9._-]","_");
        return new File(new File(context.getFilesDir(),"speaker_profiles"),safe+".pcm16le");
    }
}
