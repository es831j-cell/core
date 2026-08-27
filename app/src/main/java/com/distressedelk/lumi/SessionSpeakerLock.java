package com.distressedelk.lumi;

import java.util.Arrays;

/** Code377 session-only speaker continuity lock.
 * Keeps an ephemeral PCM anchor only in process memory so an unrecognized speaker who deliberately
 * starts a voice session cannot be silently replaced by another nearby unrecognized voice.
 * Never grants administrator authority and never writes biometric audio to disk.
 */
final class SessionSpeakerLock {
    private static byte[] anchorPcm = new byte[0];
    private static int anchorSampleRate = 0;
    private static String recorderSessionId = "";
    private static long anchoredAt = 0L;
    private SessionSpeakerLock(){}

    static synchronized void reset(String reason){
        if(anchorPcm.length>0) Arrays.fill(anchorPcm,(byte)0);
        anchorPcm=new byte[0]; anchorSampleRate=0; recorderSessionId=""; anchoredAt=0L;
    }

    static synchronized boolean hasAnchor(){ return anchorPcm.length>=12000 && anchorSampleRate>0; }

    static synchronized void anchor(byte[] pcm16,int sampleRate,String sessionId){
        reset("replace");
        if(pcm16==null || pcm16.length<12000 || sampleRate<=0) return;
        anchorPcm=Arrays.copyOf(pcm16,Math.min(pcm16.length,192000));
        anchorSampleRate=sampleRate;
        recorderSessionId=sessionId==null?"":sessionId;
        anchoredAt=System.currentTimeMillis();
    }

    static synchronized SpeakerVerifier.Result compare(byte[] candidate,int sampleRate){
        if(!hasAnchor() || candidate==null || candidate.length<12000)
            return new SpeakerVerifier.Result(false,0,0,false,"no usable session speaker anchor");
        if(sampleRate!=anchorSampleRate)
            return new SpeakerVerifier.Result(false,0,0,false,"sample-rate mismatch");
        return SpeakerVerifier.comparePcmBuffers(anchorPcm,candidate,sampleRate);
    }

    static synchronized String status(){
        return hasAnchor()?"ACTIVE ageMs="+Math.max(0L,System.currentTimeMillis()-anchoredAt)+" session="+recorderSessionId:"NONE";
    }
}
