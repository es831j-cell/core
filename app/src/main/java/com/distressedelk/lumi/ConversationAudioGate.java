package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

/** Code376: explicit-address speech admission. Recognition identifies a person; it does not prove they are addressing Lumi. */
final class ConversationAudioGate {
    static final class Decision {
        final boolean accept; final String category; final String speakerId; final String speakerName; final int confidence; final String reason;
        Decision(boolean accept,String category,String speakerId,String speakerName,int confidence,String reason){
            this.accept=accept;this.category=category;this.speakerId=speakerId;this.speakerName=speakerName;this.confidence=confidence;this.reason=reason;
        }
    }
    private ConversationAudioGate(){}

    static Decision decide(Context context, SharedPreferences prefs, String text, byte[] pcm16, int sampleRate,
                           boolean selfEcho, boolean wakePhrase, boolean conversationMode, boolean foregroundEligible,
                           boolean explicitSpeakerAcquisition, boolean keyboardOwned, boolean privilegedIntent){
        if(selfEcho) return new Decision(false,"SELF_AUDIO_REJECTED","lumi","Lumi",100,"recognized text matched Lumi recent TTS");

        SpeakerProfileRegistry.Match m=SpeakerProfileRegistry.identify(context,prefs,pcm16,sampleRate);
        boolean directlyAddressed=wakePhrase || foregroundEligible;
        boolean secureWake=prefs.getBoolean("secure_wake_requires_owner_voice",true);
        String activeId=prefs.getString("active_voice_speaker_id","");
        long introUntil=prefs.getLong("formal_intro_handoff_until",0L);
        boolean formalIntroOpen=prefs.getBoolean("formal_intro_voice_sample_pending",false)
                && introUntil>0L && System.currentTimeMillis()<=introUntil;

        // Keyboard ownership is intentionally sticky. Ambient speech, including a TV/person that happens
        // to say the wake phrase, cannot steal a typed session. Only the enrolled owner may explicitly
        // move a keyboard-owned session back to voice with the wake phrase.
        if(keyboardOwned){
            if(wakePhrase && m.owner()){
                return new Decision(true,"OWNER_ACCEPTED",m.contactId,m.displayName,m.confidence,
                        "enrolled owner wake phrase deliberately released keyboard ownership");
            }
            return new Decision(false,"KEYBOARD_AMBIENT_REJECTED",m.contactId,m.displayName,m.confidence,
                    "keyboard session owns input; non-owner/background speech cannot take the turn");
        }

        // Secure hands-free activation: a room/TV voice saying the wake phrase is not enough.
        // When secure wake is enabled, only the enrolled primary owner's voice may open a wake-only session.
        if(wakePhrase && secureWake && !m.owner())
            return new Decision(false,"UNVERIFIED_WAKE_REJECTED",m.contactId,m.displayName,m.confidence,
                    "wake phrase heard but primary-owner voice was not verified; use explicit Listen/keyboard or enroll owner voice");

        // A recognized owner is identity evidence, not proof that a room conversation is for Lumi.
        if(m.owner()){
            if(!activeId.isEmpty() && !IdentityHierarchy.PRIMARY_CONTACT_ID.equals(activeId) && !formalIntroOpen)
                return new Decision(false,"SPEAKER_HANDOFF_BLOCKED",m.contactId,m.displayName,m.confidence,
                        "another speaker owns the active voice session; explicit handoff is required");
            if(!directlyAddressed) return new Decision(false,"OWNER_BACKGROUND_REJECTED",m.contactId,m.displayName,m.confidence,"recognized owner speech occurred outside a direct-address window");
            return new Decision(true,"OWNER_ACCEPTED",m.contactId,m.displayName,m.confidence,"recognized owner inside explicit wake/foreground conversation window");
        }
        if(m.known()){
            if(!activeId.isEmpty() && !activeId.equals(m.contactId) && !formalIntroOpen)
                return new Decision(false,"SPEAKER_HANDOFF_BLOCKED",m.contactId,m.displayName,m.confidence,
                        "active speaker lock refused an automatic speaker transfer");
            if(!directlyAddressed) return new Decision(false,"KNOWN_BACKGROUND_REJECTED",m.contactId,m.displayName,m.confidence,"recognized contact speech occurred outside a direct-address window");
            if(privilegedIntent) return new Decision(false,"KNOWN_SPEAKER_PRIVILEGE_BLOCKED",m.contactId,m.displayName,m.confidence,"non-owner may converse but cannot execute owner-only commands");
            return new Decision(true,"KNOWN_SPEAKER_ACCEPTED",m.contactId,m.displayName,m.confidence,"remembered contact inside explicit conversation window");
        }

        // A formal introduction deliberately hands exactly one foreground response to the new person.
        if(formalIntroOpen && directlyAddressed){
            String id=prefs.getString("formal_intro_contact_id","");
            String name=prefs.getString("formal_intro_contact_name","New person");
            return new Decision(true,"FORMAL_INTRO_SPEAKER_SAMPLE",id,name,m.confidence,"formal introduction opened a one-turn foreground handoff");
        }

        if(!activeId.isEmpty()){
            return new Decision(false,"SPEAKER_HANDOFF_BLOCKED","","Unknown/background speaker",m.confidence,
                    "active speaker lock refused an automatic transfer to an unrecognized voice");
        }
        if(privilegedIntent){
            return new Decision(false,"UNKNOWN_SPEAKER_PRIVILEGE_BLOCKED","","Unknown speaker",m.confidence,"owner-only command requires verified owner identity plus administrator authorization");
        }
        if(prefs.getBoolean("identity_waiting_for_new_name",false) && directlyAddressed){
            return new Decision(true,"UNKNOWN_SPEAKER_INTRO","","Unknown speaker",m.confidence,"Lumi explicitly requested an introduction response");
        }

        if(conversationMode && directlyAddressed){
            if(SessionSpeakerLock.hasAnchor()){
                SpeakerVerifier.Result continuity=SessionSpeakerLock.compare(pcm16,sampleRate);
                if(continuity.usable && continuity.probableMatch)
                    return new Decision(true,"ACTIVE_ANONYMOUS_SPEAKER_ACCEPTED","session-anonymous","Current speaker",continuity.confidence,
                            "session-only anonymous speaker anchor matched; no biometric profile persisted");
                return new Decision(false,"SPEAKER_HANDOFF_BLOCKED","","Different/unverified nearby speaker",continuity.confidence,
                        "session-only speaker continuity lock refused automatic transfer to another unrecognized voice");
            }
            if(explicitSpeakerAcquisition)
                return new Decision(true,m.usable?"ACTIVE_FOREGROUND_UNKNOWN_ACCEPTED":"ACTIVE_FOREGROUND_UNVERIFIED_ACCEPTED","session-anonymous","Current speaker",m.confidence,
                        wakePhrase?"explicit verified wake opened speaker acquisition":"explicit Listen/UI action opened speaker acquisition");
            return new Decision(false,"UNKNOWN_NO_SPEAKER_ANCHOR_REJECTED","","Background/unknown speech",m.confidence,
                    "foreground continuation lease cannot acquire a new unrecognized speaker without explicit activation");
        }

        return new Decision(false,"MEDIA_OR_BACKGROUND_REJECTED","","Background/unknown speech",m.confidence,
                conversationMode?"microphone may be listening for the wake phrase, but no current direct-address lease exists":
                        (m.usable?"idle speech did not match a directly addressed conversation":"idle recognition had no usable per-turn speaker audio"));
    }
}
