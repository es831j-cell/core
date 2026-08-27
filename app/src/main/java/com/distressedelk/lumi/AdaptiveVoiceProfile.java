package com.distressedelk.lumi;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Code353 persistent administrator voice-profile history.
 * Acoustic enrollment remains the seed. Verified live comparison samples extend confidence history;
 * ordinary trusted turns record environment continuity without pretending transcript text is biometric audio.
 */
final class AdaptiveVoiceProfile {
    private AdaptiveVoiceProfile(){}
    static void recordVerification(SharedPreferences p,int confidence,double similarity,String recognizer,String audio){
        try{
            JSONArray a=new JSONArray(p.getString("admin_voice_profile_history","[]"));
            JSONObject x=new JSONObject().put("at",System.currentTimeMillis()).put("confidence",confidence).put("similarity",similarity)
                    .put("recognizer",safe(recognizer)).put("audio",safe(audio)).put("kind","LIVE_VERIFICATION");
            a.put(x); while(a.length()>24)a.remove(0);
            int high=p.getInt("admin_voice_high_confidence_samples",0)+(confidence>=95?1:0);
            p.edit().putString("admin_voice_profile_history",a.toString()).putInt("admin_voice_high_confidence_samples",high)
                    .putLong("admin_voice_profile_updated_at",System.currentTimeMillis()).apply();
        }catch(Exception ignored){}
    }
    static void noteTrustedConversation(SharedPreferences p,String recognizer,String audio){
        long now=System.currentTimeMillis(); long last=p.getLong("admin_voice_last_trusted_context_at",0L);
        if(now-last<60000L)return;
        p.edit().putLong("admin_voice_last_trusted_context_at",now).putString("admin_voice_last_recognizer",safe(recognizer)).putString("admin_voice_last_audio_path",safe(audio)).apply();
    }
    static String summary(SharedPreferences p){
        try{
            JSONArray a=new JSONArray(p.getString("admin_voice_profile_history","[]"));
            return a.length()+" verified sample(s) • high-confidence="+p.getInt("admin_voice_high_confidence_samples",0)+" • last confidence="+p.getInt("speaker_last_confidence",0)+"%";
        }catch(Exception e){return "seed enrollment only";}
    }
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
