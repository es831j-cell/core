#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv)<2:
    raise SystemExit('CODE614_FAIL missing worktree')
root=Path(sys.argv[1]).resolve()

def rd(r): return (root/r).read_text(encoding='utf-8')
def wr(r,s):
    p=root/r; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(s,encoding='utf-8')
def one(s,a,b,label):
    n=s.count(a)
    if n!=1: raise SystemExit(f'CODE614_FAIL {label}: expected 1 found {n}')
    return s.replace(a,b,1)

# Exact Code613 installed/published baseline only.
g=rd('app/build.gradle')
if 'versionCode 613' not in g or "versionName '4.50.13-conversation-stall-closure-r331'" not in g:
    raise SystemExit('CODE614_FAIL exact Code613 baseline missing')

m='app/src/main/java/com/distressedelk/lumi/MainActivity.java'
ms=rd(m)
anchor='''    void appendConversation(String q){\n'''
if ms.count(anchor)!=1: raise SystemExit('CODE614_FAIL appendConversation anchor')
probe='''    // Code614: developer-authorized silent live conversation probe. This deliberately
    // enters the same appendConversation production router used by typed interaction.
    // It suppresses TTS only for the probe, waits for the real turn to reach a terminal
    // state, and reports the actual rendered reply plus route/latency/stall evidence.
    JSONObject remoteQuestionProbe(String question,long timeoutMs){
        final String q=question==null?"":question.trim();
        if(q.isEmpty()) return new JSONObject().put("ok",false).put("state","EMPTY_QUESTION");
        if(q.length()>500) return new JSONObject().put("ok",false).put("state","QUESTION_TOO_LONG");
        final long timeout=Math.max(3000L,Math.min(45000L,timeoutMs));
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<JSONObject> result=new java.util.concurrent.atomic.AtomicReference<>();
        final long issuedAt=System.currentTimeMillis();
        runOnUiThread(()->{
            final long beforeSerial=requestSerial;
            final int beforeStalls=prefs.getInt("runtime_stall_recoveries",0);
            final boolean oldSpeak=speakReplies;
            speakReplies=false;
            try{
                currentTurnWasVoice=false;
                traceStage("REMOTE_TEST","QUESTION_DISPATCH","silent production-router probe");
                appendConversation(q);
                conversationHandler.post(new Runnable(){
                    @Override public void run(){
                        long now=System.currentTimeMillis();
                        boolean advanced=requestSerial>beforeSerial;
                        boolean terminal=advanced && !aiBusy && !LocalBrain.isBusy() && !lumiAudioOutputActive;
                        if(terminal){
                            String reply="";
                            try{reply=avatarSubtitle==null?"":String.valueOf(avatarSubtitle.getText());}catch(Throwable ignored){}
                            int afterStalls=prefs.getInt("runtime_stall_recoveries",0);
                            JSONObject o=new JSONObject()
                                    .put("ok",true).put("state","COMPLETE")
                                    .put("question",q).put("reply",reply)
                                    .put("turnSerial",requestSerial)
                                    .put("route",activeRequestRoute==null?"":activeRequestRoute)
                                    .put("model",activeRequestModel==null?"":activeRequestModel)
                                    .put("stage",activeRequestStage==null?"":activeRequestStage)
                                    .put("responseLatencyMs",lastResponseLatencyMs)
                                    .put("elapsedMs",Math.max(0L,now-issuedAt))
                                    .put("stallRecoveriesDelta",Math.max(0,afterStalls-beforeStalls));
                            result.set(o); speakReplies=oldSpeak; done.countDown(); return;
                        }
                        if(now-issuedAt>=timeout){
                            int afterStalls=prefs.getInt("runtime_stall_recoveries",0);
                            result.set(new JSONObject().put("ok",false).put("state","TIMEOUT")
                                    .put("question",q).put("turnSerial",requestSerial)
                                    .put("route",activeRequestRoute==null?"":activeRequestRoute)
                                    .put("model",activeRequestModel==null?"":activeRequestModel)
                                    .put("stage",activeRequestStage==null?"":activeRequestStage)
                                    .put("elapsedMs",Math.max(0L,now-issuedAt))
                                    .put("stallRecoveriesDelta",Math.max(0,afterStalls-beforeStalls)));
                            speakReplies=oldSpeak; done.countDown(); return;
                        }
                        conversationHandler.postDelayed(this,120L);
                    }
                });
            }catch(Throwable t){
                speakReplies=oldSpeak;
                result.set(new JSONObject().put("ok",false).put("state","DISPATCH_ERROR").put("error",safeDiagText(String.valueOf(t))));
                done.countDown();
            }
        });
        try{
            if(!done.await(timeout+2500L,java.util.concurrent.TimeUnit.MILLISECONDS))
                return new JSONObject().put("ok",false).put("state","BRIDGE_WAIT_TIMEOUT").put("question",q);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return new JSONObject().put("ok",false).put("state","INTERRUPTED");}
        JSONObject out=result.get();
        return out==null?new JSONObject().put("ok",false).put("state","NO_RESULT"):out;
    }

'''
ms=ms.replace(anchor,probe+anchor,1)
wr(m,ms)

r='app/src/main/java/com/distressedelk/lumi/RemoteDeveloperMaintenance.java'
rs=rd(r)
status_anchor='''        if("SEND_BLACK_BOX".equals(action)){\n'''
if rs.count(status_anchor)!=1: raise SystemExit('CODE614_FAIL remote action anchor')
remote_action='''        if("ASK_LUMI_TEST".equals(action)){
            if(a==null) return new JSONObject().put("ok",false).put("state","ACTIVITY_UNAVAILABLE");
            String q=args==null?"":args.optString("question","").trim();
            long timeout=args==null?30000L:args.optLong("timeoutMs",30000L);
            JSONObject probe=a.remoteQuestionProbe(q,timeout);
            probe.put("action","ASK_LUMI_TEST").put("versionCode",614).put("versionName","4.50.14-remote-conversation-probe-r332");
            return probe;
        }
'''
rs=rs.replace(status_anchor,remote_action+status_anchor,1)
old_allowed='''new JSONArray().put("PING").put("STATUS").put("VOICE_STATUS").put("SEND_BLACK_BOX")'''
new_allowed='''new JSONArray().put("PING").put("STATUS").put("VOICE_STATUS").put("ASK_LUMI_TEST").put("SEND_BLACK_BOX")'''
if old_allowed not in rs: raise SystemExit('CODE614_FAIL allowlist anchor')
rs=rs.replace(old_allowed,new_allowed,1)
# Keep all current remote identity fields truthful for the installed build.
rs=rs.replace('.put("versionCode",613)', '.put("versionCode",614)')
rs=rs.replace('"4.50.13-conversation-stall-closure-r331"','"4.50.14-remote-conversation-probe-r332"')
rs=rs.replace('periodic-online-code613','periodic-online-code614')
wr(r,rs)

# Advance whole-self release identity without changing frozen acceptance gates.
a='app/src/main/java/com/distressedelk/lumi/LumiWholeSelfAudit.java'
asrc=rd(a)
asrc=asrc.replace('RUNNING_CODE613','RUNNING_CODE614').replace('FAILED_CODE613','FAILED_CODE614').replace('COMPLETE_CODE613','COMPLETE_CODE614')
asrc=asrc.replace('code613_conversation_baseline_initialized','code614_conversation_baseline_initialized')
asrc=asrc.replace('code613_conversation_baseline_at','code614_conversation_baseline_at')
asrc=asrc.replace('code613_base_','code614_base_').replace('Code613 ','Code614 ')
wr(a,asrc)

g=one(g,'versionCode 613','versionCode 614','version code')
g=one(g,"versionName '4.50.13-conversation-stall-closure-r331'","versionName '4.50.14-remote-conversation-probe-r332'",'version name')
wr('app/build.gradle',g)
wr('CODE614-REMOTE-CONVERSATION-PROBE-R332.txt','''Lumi Code614 • Remote Conversation Probe • R332

Base: exact installed/published Code613 Conversation Stall Closure.

Purpose:
- Add developer-authorized ASK_LUMI_TEST to the existing remote maintenance bridge.
- Inject a silent question through the exact production appendConversation router used by typed interaction.
- Return Lumi's rendered reply, route, model, terminal stage, response latency, total elapsed time, and stall-recovery delta.
- Suppress TTS only during the probe so remote testing does not unexpectedly speak from the phone.
- Bound each probe to 3-45 seconds and preserve production serial/winner/stall behavior.

This is test plumbing, not a replacement conversation engine. Frozen Lumi 1.0 gates remain unchanged.
''')

check='\n'.join([rd('app/build.gradle'),rd(m),rd(r),rd(a)])
for x in ['versionCode 614','4.50.14-remote-conversation-probe-r332','ASK_LUMI_TEST','remoteQuestionProbe','stallRecoveriesDelta','COMPLETE_CODE614']:
    if x not in check: raise SystemExit('CODE614_FAIL missing '+x)
print('CODE614_REMOTE_CONVERSATION_PROBE_PATCH_OK')
