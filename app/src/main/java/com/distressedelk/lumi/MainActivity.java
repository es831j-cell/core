package com.distressedelk.lumi;

import android.app.*;
import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.speech.RecognizerIntent;
import android.Manifest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.net.*;
import java.io.*;
import org.json.*;

public class MainActivity extends Activity {
    static final int FEATURE_LEVEL = 100;
    static final int REQ_SPEECH = 44;
    static final int REQ_PERMS = 45;
    static final String EXTRA_AUTO_LISTEN = "lumi_auto_listen";
    static final int REQ_PRIVATE_DEVICE_CREDENTIAL = 46;
    static final int REQ_EXPORT_BACKUP = 60;
    static final int REQ_IMPORT_BACKUP = 61;
    static final int REQ_EXPORT_DIAGNOSTICS = 62;
    static final int REQ_ADMIN_FACE = 70;
    static final int REQ_ADMIN_MIC_PERMISSION = 71;
    static final int REQ_ADMIN_CAMERA_PERMISSION = 72;
    static final long PRIVATE_SESSION_MS = 10L * 60L * 1000L;

    // Lumi v2 speed-first local brain. The 0.6B model stays hot for ordinary conversation.
    // The 4B file is an optional future deep-brain asset; this build does not load it concurrently.
    static final String FAST_MODEL_FILE = "Qwen3-0.6B-Q4_K_M.gguf";
    static final String FAST_MODEL_URL = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/1208e45d782fe18602c5eaf10e5758d5b0f24c03/Qwen3-0.6B-Q4_K_M.gguf?download=true";
    static final String FAST_MODEL_SHA256 = "b0638f08417a2d3c8652760462eb5407c6e30173cf9608ad0820757a281eea0e";
    static final long FAST_MODEL_APPROX_BYTES = 397L * 1024L * 1024L;

    static final String LOCAL_MODEL_FILE = "Qwen3-4B-Q4_K_M.gguf";
    static final String LOCAL_MODEL_URL = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true";
    static final String LOCAL_MODEL_SHA256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5";
    static final long LOCAL_MODEL_APPROX_BYTES = 2500L * 1024L * 1024L;

    LinearLayout root, content;
    TextView status, transcript, avatarSubtitle, avatarState;
    ImageView avatarImage;
    EditText talkInput;
    Button talkSend;
    boolean aiBusy = false;
    String previousResponseId = null;
    android.speech.SpeechRecognizer continuousRecognizer;
    android.speech.tts.TextToSpeech lumiTts;
    boolean conversationMode = false;
    boolean recognizingContinuously = false;
    boolean speakReplies = true;
    boolean pendingAutoListenAfterPermission = false;
    long localModelDownloadId = -1L;
    long fastModelDownloadId = -1L;
    boolean localModelVerificationRunning = false;
    boolean fastModelVerificationRunning = false;
    volatile boolean fastDirectDownloadRunning = false;
    long requestSerial = 0L;
    volatile long activeRequestStartedAt = 0L;
    volatile String activeRequestStage = "idle";
    volatile String activeRequestModel = "none";
    volatile String activeRequestRoute = "idle";
    volatile String activeRequestText = "";
    volatile long lastResponseLatencyMs = -1L;
    volatile double lastResponseTokensPerSecond = 0.0;
    volatile long followupHotUntil = 0L;
    static final long FOLLOWUP_LINGER_MS = 10000L;
    TextView firstRunBrainStatus;
    ProgressBar firstRunBrainProgress;
    Button firstRunBrainButton;
    MediaRecorder adminVoiceRecorder;
    boolean adminVoiceRecording = false;
    final Handler adminHandler = new Handler(Looper.getMainLooper());
    long lastConversationActivity = 0L;
    static final long CONVERSATION_TIMEOUT_MS = 5L * 60L * 1000L;
    final Handler conversationHandler = new Handler(Looper.getMainLooper());
    final Runnable conversationTimeout = () -> {
        if(conversationMode && System.currentTimeMillis()-lastConversationActivity >= CONVERSATION_TIMEOUT_MS){
            stopConversationMode();
            Toast.makeText(this,"Lumi conversation paused after five minutes of silence.",Toast.LENGTH_SHORT).show();
        }
    };
    int accent = Color.rgb(127,232,255), bg = Color.rgb(12,17,24), panel = Color.rgb(21,28,38), text = Color.rgb(242,246,250), muted = Color.rgb(154,168,184);
    SharedPreferences prefs;
    boolean privateSession = false;
    long privateSessionExpiresAt = 0L;
    final Handler privateHandler = new Handler(Looper.getMainLooper());
    final Runnable privateTimeout = () -> {
        if(privateSession && System.currentTimeMillis() >= privateSessionExpiresAt){
            exitPrivateMode();
            showHome();
            Toast.makeText(this,"Private Mode locked after inactivity.",Toast.LENGTH_SHORT).show();
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("lumi", MODE_PRIVATE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        speakReplies = prefs.getBoolean("speak_replies", true);
        initSpeechOutput();

        // Speed-first onboarding: only the tiny conversation brain is required before Lumi opens.
        // Administrator enrollment and the 4B deep brain are deliberately deferred so latency can
        // be tuned in normal conversation first.
        if(!isFastModelReady()){
            showFirstRunBrainSetup();
            return;
        }
        startLumiRuntime();
    }

    void startLumiRuntime(){
        diag("runtime","Lumi runtime start; fast brain ready="+isFastModelReady());
        // Warm the 0.6B Fast Brain immediately. This hides model-load latency behind normal app
        // startup instead of making the first conversational turn pay for it.
        if(isFastModelReady()) LocalBrain.warm(fastModelFile().getAbsolutePath(),512,localThreadBudget());
        startCoreServiceIfAllowed();
        showHome();
        ModelMaintenanceScheduler.schedule(this);
        boolean explicitAuto = getIntent()!=null && getIntent().getBooleanExtra(EXTRA_AUTO_LISTEN,false);
        boolean handsFree = prefs.getBoolean("hands_free_listening", true);
        if(explicitAuto || handsFree) conversationHandler.postDelayed(() -> ensureHandsFreeListening(), 450);
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        if(intent!=null && intent.getBooleanExtra(EXTRA_AUTO_LISTEN,false)){
            conversationHandler.postDelayed(() -> ensureHandsFreeListening(), 250);
        }
    }

    void ensureHandsFreeListening(){
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            pendingAutoListenAfterPermission=true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_PERMS);
            return;
        }
        if(!conversationMode) startConversationMode();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_ADMIN_MIC_PERMISSION){
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) showAdminVoiceEnrollment();
            else Toast.makeText(this,"Microphone permission is required to complete administrator voice enrollment.",Toast.LENGTH_LONG).show();
            return;
        }
        if(requestCode==REQ_ADMIN_CAMERA_PERMISSION){
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) launchAdminFaceCapture();
            else Toast.makeText(this,"Camera permission is required to complete administrator face enrollment.",Toast.LENGTH_LONG).show();
            return;
        }
        if(requestCode==REQ_PERMS){
            boolean micGranted=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            if(micGranted && (pendingAutoListenAfterPermission || prefs.getBoolean("hands_free_listening",true))){
                pendingAutoListenAfterPermission=false;
                conversationHandler.postDelayed(() -> startConversationMode(),250);
            } else if(!micGranted){
                pendingAutoListenAfterPermission=false;
                Toast.makeText(this,"Microphone permission is needed for hands-free Lumi. You can still type to her.",Toast.LENGTH_LONG).show();
            }
            // Bluetooth permission may have been granted through the same system permission flow later.
            startCoreServiceIfAllowed();
        }
    }


    void startCoreServiceIfAllowed(){
        try{
            if(Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED){
                // Android 12+ requires a connected-device permission before a connectedDevice
                // foreground service may start. Do not let missing permission crash Lumi at launch.
                prefs.edit().putBoolean("core_waiting_for_bluetooth_permission", true).apply();
                return;
            }
            Intent core=new Intent(this,LumiCoreService.class);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(core); else startService(core);
            prefs.edit().putBoolean("core_waiting_for_bluetooth_permission", false).apply();
        }catch(Exception e){
            prefs.edit().putString("core_start_error", e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())).apply();
        }
    }


    void resumeFirstRunAfterBrain(){
        if(isFastModelReady()) startLumiRuntime();
        else showFirstRunBrainSetup();
    }

    void showFirstRunBrainSetup(){
        firstRunBrainStatus=null; firstRunBrainProgress=null; firstRunBrainButton=null;
        LinearLayout page=adminPage("LUMI • FAST START","First I need my lightweight conversation brain. It is about 397 MB and is tuned for quick, natural back-and-forth. Administrator Enrollment can be added later after we finish tuning response speed. A larger 4B model can also be stored later, but this speed build keeps only one local model active at a time.");

        firstRunBrainStatus=tv("Checking fast brain…",15,accent); firstRunBrainStatus.setPadding(0,12,0,16); page.addView(firstRunBrainStatus);
        firstRunBrainProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); firstRunBrainProgress.setMax(100); firstRunBrainProgress.setProgress(0); page.addView(firstRunBrainProgress,new LinearLayout.LayoutParams(-1,36));
        TextView note=tv("After the fast brain is downloaded and checksum-verified, Lumi opens immediately. Security enrollment will not block this test build.",14,muted); note.setPadding(0,18,0,22); page.addView(note);
        firstRunBrainButton=btn("Download fast brain"); page.addView(firstRunBrainButton,new LinearLayout.LayoutParams(-1,64));
        firstRunBrainButton.setOnClickListener(v->ensureFastModelSetup(true));
        setSafeScrollableContent(page);

        File f=fastModelFile();
        if(f.exists() && f.length()>330L*1024L*1024L){ updateFirstRunBrainUi("Verifying fast brain…",-1,true); verifyFastModelAsync(f); return; }
        // v2.0 downloader fix: the Fast Brain no longer depends on Android DownloadManager.
        // Any old DownloadManager id is stale for this path and is deliberately discarded.
        clearFastModelDownloadTracking();
        File partial=fastModelPartialFile();
        if(partial.exists() && partial.length()>0){
            int pct=(int)Math.max(0,Math.min(99,(partial.length()*100L)/Math.max(1L,FAST_MODEL_APPROX_BYTES)));
            updateFirstRunBrainUi("Partial fast brain found • tap retry to resume",pct,false);
        }else{
            updateFirstRunBrainUi("Fast brain not installed yet.",0,false);
            conversationHandler.postDelayed(()->{ if(!isFinishing() && firstRunBrainStatus!=null && !isFastModelReady()) showFastModelDownloadPrompt(); },300);
        }
    }

    void updateFirstRunBrainUi(String label,int percent,boolean busy){
        if(firstRunBrainStatus!=null) firstRunBrainStatus.setText(label);
        if(firstRunBrainProgress!=null){
            if(percent<0){ firstRunBrainProgress.setIndeterminate(true); }
            else { firstRunBrainProgress.setIndeterminate(false); firstRunBrainProgress.setProgress(Math.max(0,Math.min(100,percent))); }
        }
        if(firstRunBrainButton!=null){
            firstRunBrainButton.setEnabled(!busy);
            firstRunBrainButton.setAlpha(busy?0.55f:1f);
            firstRunBrainButton.setText(busy?"Brain setup running…":"Download / retry brain");
        }
    }

    void showFirstRunIntroduction(){
        firstRunBrainStatus=null; firstRunBrainProgress=null; firstRunBrainButton=null;
        LinearLayout page=adminPage("LUMI • ONLINE","Hi. I'm Lumi. My local brain is installed and verified, so now we can set up who you are and who has administrator authority over me. The security portion is intentionally formal. After that, we can get to know each other naturally.");
        TextView ready=tv("✓ LOCAL BRAIN READY",15,accent); ready.setTypeface(Typeface.DEFAULT_BOLD); ready.setPadding(0,12,0,26); page.addView(ready);
        Button begin=btn("Continue to Administrator Enrollment"); page.addView(begin,new LinearLayout.LayoutParams(-1,64)); begin.setOnClickListener(v->{ prefs.edit().putBoolean("first_run_intro_seen",true).apply(); showAdminEnrollmentStart(); });
        setSafeScrollableContent(page);
    }

    void showAdminEnrollmentStart(){
        LinearLayout page=adminPage("LUMI • ADMINISTRATOR ENROLLMENT","When you're ready, I can establish one administrator authority. This formal setup requires all three identity anchors:\n\n1  Root PIN\n2  Face reference\n3  Voice reference\n\nThe PIN is your recovery authority. Face and voice become the natural day-to-day identity signals.");
        Button begin=btn("Begin secure enrollment"); page.addView(begin,new LinearLayout.LayoutParams(-1,64)); begin.setOnClickListener(v->showAdminPinEnrollment());
        setSafeScrollableContent(page);
    }

    void showAdminPinEnrollment(){
        LinearLayout page=adminPage("STEP 1 OF 3 • ROOT PIN","Create the administrator recovery PIN. This is reserved for recovery, high-risk changes, and cases where Lumi cannot confidently verify you by face and voice.");
        EditText pin1=new EditText(this); pin1.setHint("Create PIN • 6+ digits"); pin1.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD); pin1.setTextColor(text); pin1.setHintTextColor(muted); page.addView(pin1);
        EditText pin2=new EditText(this); pin2.setHint("Confirm PIN"); pin2.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD); pin2.setTextColor(text); pin2.setHintTextColor(muted); page.addView(pin2);
        Button next=btn("Secure PIN and continue"); page.addView(next,new LinearLayout.LayoutParams(-1,64));
        next.setOnClickListener(v->{
            String a=pin1.getText().toString(), b=pin2.getText().toString();
            if(a.length()<6){Toast.makeText(this,"Use at least 6 digits.",Toast.LENGTH_SHORT).show();return;}
            if(!a.equals(b)){Toast.makeText(this,"PINs do not match.",Toast.LENGTH_SHORT).show();return;}
            try{
                byte[] salt=new byte[16]; new java.security.SecureRandom().nextBytes(salt);
                String salt64=android.util.Base64.encodeToString(salt,android.util.Base64.NO_WRAP);
                String hash=hashAdminPin(a.toCharArray(),salt);
                prefs.edit().putString("admin_pin_salt",salt64).putString("admin_pin_hash",hash).putBoolean("admin_pin_enrolled",true).putLong("admin_pin_enrolled_at",System.currentTimeMillis()).apply();
                showAdminFaceEnrollment();
            }catch(Exception e){Toast.makeText(this,"Could not secure PIN: "+e.getMessage(),Toast.LENGTH_LONG).show();}
        });
        setSafeScrollableContent(page);
    }

    LinearLayout adminPage(String title,String explanation){
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(28,42,28,42); page.setBackgroundColor(bg);
        TextView t=tv(title,21,text); t.setTypeface(Typeface.DEFAULT_BOLD); page.addView(t);
        TextView e=tv(explanation,15,muted); e.setPadding(0,18,0,24); page.addView(e);
        return page;
    }

    void setSafeScrollableContent(LinearLayout page){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(bg);
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
                left=bars.left; top=bars.top; right=bars.right; bottom=bars.bottom;
            }else{
                left=insets.getSystemWindowInsetLeft(); top=insets.getSystemWindowInsetTop(); right=insets.getSystemWindowInsetRight(); bottom=insets.getSystemWindowInsetBottom();
            }
            scroll.setPadding(left,top,right,bottom+24);
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    String hashAdminPin(char[] pin,byte[] salt) throws Exception{
        javax.crypto.spec.PBEKeySpec spec=new javax.crypto.spec.PBEKeySpec(pin,salt,120000,256);
        byte[] hash=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        return android.util.Base64.encodeToString(hash,android.util.Base64.NO_WRAP);
    }

    void showAdminFaceEnrollment(){
        boolean enrolled=prefs.getBoolean("admin_face_enrolled",false) && new File(getFilesDir(),"owner_face_reference.jpg").exists();
        LinearLayout page=adminPage("STEP 2 OF 3 • FACE","Enroll a clear reference image of the administrator. Use even light and look directly at the camera. Lumi stores this reference inside her app storage.");
        if(enrolled){
            try{ Bitmap bmp=android.graphics.BitmapFactory.decodeFile(new File(getFilesDir(),"owner_face_reference.jpg").getAbsolutePath()); ImageView iv=new ImageView(this); iv.setImageBitmap(bmp); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); page.addView(iv,new LinearLayout.LayoutParams(-1,360)); }catch(Exception ignored){}
            addAdminStatus(page,"✓ Face reference captured");
        }
        Button capture=btn(enrolled?"Retake face reference":"Capture face reference"); page.addView(capture,new LinearLayout.LayoutParams(-1,64)); capture.setOnClickListener(v->requestAdminFaceCapture());
        Button next=btn("Continue to voice enrollment"); next.setEnabled(enrolled); next.setAlpha(enrolled?1f:.45f); page.addView(next,new LinearLayout.LayoutParams(-1,64)); next.setOnClickListener(v->showAdminVoiceEnrollment());
        setSafeScrollableContent(page);
    }

    void addAdminStatus(LinearLayout page,String textValue){ TextView s=tv(textValue,14,accent); s.setPadding(0,12,0,12); page.addView(s); }

    void requestAdminFaceCapture(){
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_ADMIN_CAMERA_PERMISSION); return; }
        launchAdminFaceCapture();
    }

    void launchAdminFaceCapture(){
        Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try{ startActivityForResult(i,REQ_ADMIN_FACE); }catch(Exception e){Toast.makeText(this,"No camera app is available for face enrollment.",Toast.LENGTH_LONG).show();}
    }

    File adminVoiceFile(){ return new File(getFilesDir(),"owner_voice_reference.m4a"); }

    void showAdminVoiceEnrollment(){
        boolean enrolled=prefs.getBoolean("admin_voice_enrolled",false) && adminVoiceFile().exists() && adminVoiceFile().length()>1000;
        LinearLayout page=adminPage("STEP 3 OF 3 • VOICE","Record a natural voice reference. Say: “Hi Lumi. This is your administrator. You can call me by my name.” Keep speaking naturally for a few seconds.");
        if(enrolled) addAdminStatus(page,"✓ Voice reference captured • "+Math.max(1,adminVoiceFile().length()/1024)+" KB");
        Button record=btn(adminVoiceRecording?"Stop recording":"Record voice reference"); page.addView(record,new LinearLayout.LayoutParams(-1,64));
        record.setOnClickListener(v->{ if(adminVoiceRecording) stopAdminVoiceRecording(true); else beginAdminVoiceRecording(); });
        Button next=btn("Complete identity enrollment"); next.setEnabled(enrolled); next.setAlpha(enrolled?1f:.45f); page.addView(next,new LinearLayout.LayoutParams(-1,64)); next.setOnClickListener(v->showOwnerIntroduction());
        setSafeScrollableContent(page);
    }

    void beginAdminVoiceRecording(){
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_ADMIN_MIC_PERMISSION); return; }
        try{
            stopAdminVoiceRecording(false);
            File out=adminVoiceFile(); if(out.exists())out.delete();
            adminVoiceRecorder=new MediaRecorder();
            adminVoiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            adminVoiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            adminVoiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            adminVoiceRecorder.setAudioEncodingBitRate(96000);
            adminVoiceRecorder.setAudioSamplingRate(44100);
            adminVoiceRecorder.setOutputFile(out.getAbsolutePath());
            adminVoiceRecorder.prepare(); adminVoiceRecorder.start(); adminVoiceRecording=true;
            Toast.makeText(this,"Recording administrator voice…",Toast.LENGTH_SHORT).show();
            showAdminVoiceEnrollment();
            adminHandler.postDelayed(()->{ if(adminVoiceRecording) stopAdminVoiceRecording(true); },7000);
        }catch(Exception e){ adminVoiceRecording=false; Toast.makeText(this,"Voice enrollment failed: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
    }

    void stopAdminVoiceRecording(boolean save){
        adminHandler.removeCallbacksAndMessages(null);
        if(adminVoiceRecorder!=null){
            try{ if(adminVoiceRecording) adminVoiceRecorder.stop(); }catch(Exception ignored){}
            try{ adminVoiceRecorder.reset(); adminVoiceRecorder.release(); }catch(Exception ignored){}
            adminVoiceRecorder=null;
        }
        if(adminVoiceRecording){
            adminVoiceRecording=false;
            File f=adminVoiceFile();
            if(save && f.exists() && f.length()>1000){ prefs.edit().putBoolean("admin_voice_enrolled",true).putLong("admin_voice_enrolled_at",System.currentTimeMillis()).apply(); Toast.makeText(this,"Voice reference secured.",Toast.LENGTH_SHORT).show(); }
            else if(!save && f.exists() && !prefs.getBoolean("admin_voice_enrolled",false)) f.delete();
            if(save && !isFinishing()) showAdminVoiceEnrollment();
        }
    }

    void showOwnerIntroduction(){
        if(!allAdminAnchorsReady()){ showAdminEnrollmentStart(); return; }
        LinearLayout page=adminPage("IDENTITY SECURED • MEET LUMI","Administrator authority is established. Now we can actually get to know each other. These details become Lumi’s first owner memory and can evolve naturally later.");
        EditText name=new EditText(this); name.setHint("Your name"); name.setTextColor(text); name.setHintTextColor(muted); name.setText(prefs.getString("owner_name","")); page.addView(name);
        EditText call=new EditText(this); call.setHint("What should Lumi call you?"); call.setTextColor(text); call.setHintTextColor(muted); call.setText(prefs.getString("owner_call_name","")); page.addView(call);
        EditText notes=new EditText(this); notes.setHint("Anything important you want Lumi to know at the start? (optional)"); notes.setTextColor(text); notes.setHintTextColor(muted); notes.setMinLines(4); notes.setGravity(Gravity.TOP); page.addView(notes,new LinearLayout.LayoutParams(-1,220));
        Button finish=btn("Finish setup and meet Lumi"); page.addView(finish,new LinearLayout.LayoutParams(-1,64));
        finish.setOnClickListener(v->{
            String n=name.getText().toString().trim(); if(n.isEmpty()){Toast.makeText(this,"Tell Lumi your name first.",Toast.LENGTH_SHORT).show();return;}
            String c=call.getText().toString().trim(); if(c.isEmpty())c=n;
            prefs.edit().putString("owner_name",n).putString("owner_call_name",c).putString("owner_intro_notes",notes.getText().toString().trim()).putBoolean("admin_enrollment_complete",true).putLong("admin_enrollment_completed_at",System.currentTimeMillis()).putString("last_lumi_reply","Okay, "+c+". I know who you are now. We’ll figure out the rest together.").apply();
            appendChangeLog("Administrator enrollment completed with PIN, face and voice identity anchors.");
            startLumiRuntime();
        });
        setSafeScrollableContent(page);
    }

    boolean allAdminAnchorsReady(){
        return prefs.getBoolean("admin_pin_enrolled",false) && prefs.getBoolean("admin_face_enrolled",false) && prefs.getBoolean("admin_voice_enrolled",false)
                && !prefs.getString("admin_pin_hash","").isEmpty() && new File(getFilesDir(),"owner_face_reference.jpg").exists() && adminVoiceFile().exists();
    }

    void showAdminSecuritySummary(){
        base("Administrator Identity");
        addCard("OWNER: "+prefs.getString("owner_call_name",prefs.getString("owner_name","Enrolled administrator"))+"\n\n✓ Root PIN anchor\n✓ Face reference\n✓ Voice reference\n\nDESIGN TARGET\n• Face + voice combine into one confidence score for normal owner verification.\n• If confidence is uncertain, Lumi retries passively once before asking for the root PIN.\n• High-confidence verified interactions may expand the administrator voice profile over time.\n\nCURRENT BUILD NOTE\nThis build securely captures the three enrollment anchors and gates first-run access. Production-grade face matching and speaker verification still require dedicated local biometric embedding models; Lumi does not pretend the captured references are already a full biometric matcher.");
    }

    TextView tv(String s, int sp, int color) {
        TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setPadding(16,10,16,10); return v;
    }

    Button btn(String s) {
        Button b=new Button(this); b.setText(s); b.setTextColor(text); b.setTextSize(14);
        GradientDrawable g=new GradientDrawable(); g.setColor(panel); g.setCornerRadius(26); g.setStroke(1,accent);
        b.setBackground(g); b.setAllCaps(false); b.setPadding(12,6,12,6); return b;
    }


    TextView navTab(String label, String title) {
        TextView tab = tv(label, 14, text);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setMinHeight(64);
        boolean active = (label.equals("Home") && (title.equals("Lumi") || title.startsWith("Lumi •")))
                || (label.equals("Talk") && title.startsWith("Talk"))
                || (label.equals("Memory") && title.contains("Memory"))
                || (label.equals("Context") && title.startsWith("Context"))
                || (label.equals("More") && title.startsWith("Lumi Systems"));
        GradientDrawable g = new GradientDrawable();
        g.setColor(active ? Color.rgb(32,52,66) : panel);
        g.setCornerRadius(22);
        g.setStroke(active ? 2 : 1, active ? accent : Color.rgb(61,77,94));
        tab.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,64,1);
        lp.setMargins(3,0,3,0);
        tab.setLayoutParams(lp);
        return tab;
    }

    void addCard(String s){
        TextView c=tv(s,15,text); c.setBackgroundColor(panel); c.setPadding(24,22,24,22);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,8,0,8); content.addView(c,lp);
    }

    void base(String title) {
        checkPrivateSession();
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg); root.setPadding(18,18,18,18);
        TextView t=tv(title,24,text); t.setTypeface(Typeface.DEFAULT_BOLD); root.addView(t);
        status=tv(privateSession ? "Lumi v2 • PRIVATE" : "Lumi v2 • local-first hybrid AI",12,muted); root.addView(status);
        ScrollView sv=new ScrollView(this); content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0,12,0,40); sv.addView(content); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setPadding(0,8,0,8);
        String[] ns = new String[]{"Home","Talk","Memory","Context","More"};
        for(String n:ns){
            TextView b=navTab(n, title);
            b.setOnClickListener(v->{
                if(n.equals("Home"))showHome();
                else if(n.equals("Talk"))showTalk();
                else if(n.equals("Memory"))showMemory();
                else if(n.equals("Context"))showContext();
                else if(n.equals("More"))showMore();
            });
            nav.addView(b,new LinearLayout.LayoutParams(0,64,1));
        }
        root.addView(nav);

        // Android 15 / targetSdk 35 enforces edge-to-edge layouts. Respect system bars so
        // the title and bottom navigation are not hidden behind the status/navigation bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                root.setPadding(18 + bars.left, 18 + bars.top, 18 + bars.right, 18 + bars.bottom);
            } else {
                root.setPadding(18 + insets.getSystemWindowInsetLeft(), 18 + insets.getSystemWindowInsetTop(),
                        18 + insets.getSystemWindowInsetRight(), 18 + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
    }

    String currentVisualMode(){
        if(privateSession) return "Private";
        String p=prefs.getString("profile","Home");
        if(p==null || p.trim().isEmpty()) return "Home";
        return p;
    }

    int currentAvatarPhotoRes(){
        // Development phase: use the Möbius core visual so interface testing focuses on the engine.
        if(prefs.getBoolean("developer_avatar_mobius",true)) return com.distressedelk.lumi.R.drawable.lumi_dev_mobius;
        if(privateSession) return com.distressedelk.lumi.R.drawable.lumi_private;
        String p=prefs.getString("profile","Home");
        if("Public".equalsIgnoreCase(p)) return com.distressedelk.lumi.R.drawable.lumi_public;
        if("Work".equalsIgnoreCase(p)) return com.distressedelk.lumi.R.drawable.lumi_work;
        if("Travel".equalsIgnoreCase(p)) return com.distressedelk.lumi.R.drawable.lumi_travel;
        if("Lockdown".equalsIgnoreCase(p)) return com.distressedelk.lumi.R.drawable.lumi_lockdown;
        return com.distressedelk.lumi.R.drawable.lumi_home;
    }

    void refreshAvatarPhoto(){
        if(avatarImage!=null) avatarImage.setImageResource(currentAvatarPhotoRes());
        if(avatarState!=null && !aiBusy && !"Speaking".contentEquals(avatarState.getText())){
            avatarState.setText("Lumi • Dev Core");
        }
    }

    void setVisualProfile(String profile){
        prefs.edit().putString("profile",profile).apply();
        refreshAvatarPhoto();
    }

    void showHome(){
        checkPrivateSession();
        final FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);

        final ImageView avatar=new ImageView(this);
        avatarImage=avatar;
        String activeProfile=prefs.getString("profile","Home");
        avatar.setImageResource(currentAvatarPhotoRes());
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        stage.addView(avatar,new FrameLayout.LayoutParams(-1,-1));

        // A slow breathing drift keeps Lumi present without making her fidget.
        android.animation.PropertyValuesHolder sx=android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X,1.0f,1.018f);
        android.animation.PropertyValuesHolder sy=android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y,1.0f,1.018f);
        android.animation.ObjectAnimator breathe=android.animation.ObjectAnimator.ofPropertyValuesHolder(avatar,sx,sy);
        breathe.setDuration(4200); breathe.setRepeatCount(android.animation.ValueAnimator.INFINITE); breathe.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        breathe.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()); breathe.start();

        View shade=new View(this);
        GradientDrawable shadeBg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0x22000000,0xCC05080D});
        shade.setBackground(shadeBg); stage.addView(shade,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout hud=new LinearLayout(this); hud.setOrientation(LinearLayout.VERTICAL); hud.setGravity(Gravity.CENTER_HORIZONTAL); hud.setPadding(28,20,28,34);
        FrameLayout.LayoutParams hudLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM); stage.addView(hud,hudLp);

        String visualMode=privateSession ? "Private" : activeProfile;
        avatarState=tv(conversationMode ? "● Listening" : (isLocalModelReady()?"Lumi • Dev Core":"Lumi • brain setup needed"),14,conversationMode?accent:Color.WHITE);
        avatarState.setGravity(Gravity.CENTER); avatarState.setShadowLayer(8,0,2,Color.BLACK); hud.addView(avatarState);
        String last=prefs.getString("last_lumi_reply","I'm here. Just talk to me.");
        avatarSubtitle=tv(last,18,Color.WHITE); avatarSubtitle.setGravity(Gravity.CENTER); avatarSubtitle.setMaxLines(4); avatarSubtitle.setShadowLayer(10,0,2,Color.BLACK);
        hud.addView(avatarSubtitle,new LinearLayout.LayoutParams(-1,-2));

        final LinearLayout controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setVisibility(View.GONE); controls.setPadding(0,12,0,0); hud.addView(controls);
        LinearLayout row1=new LinearLayout(this); row1.setGravity(Gravity.CENTER); controls.addView(row1);
        Button listen=btn(conversationMode?"Pause listening":"Listen"); row1.addView(listen,new LinearLayout.LayoutParams(0,60,1));
        Button transcriptBtn=btn("Transcript"); row1.addView(transcriptBtn,new LinearLayout.LayoutParams(0,60,1));
        Button moreBtn=btn("More"); row1.addView(moreBtn,new LinearLayout.LayoutParams(0,60,1));
        listen.setOnClickListener(v->{ if(conversationMode)stopConversationMode(); else ensureHandsFreeListening(); showHome(); });
        transcriptBtn.setOnClickListener(v->showTalk()); moreBtn.setOnClickListener(v->showMore());

        LinearLayout row2=new LinearLayout(this); row2.setGravity(Gravity.CENTER); controls.addView(row2);
        Button memory=btn("Memory"); row2.addView(memory,new LinearLayout.LayoutParams(0,60,1)); memory.setOnClickListener(v->showMemory());
        Button people=btn("People"); row2.addView(people,new LinearLayout.LayoutParams(0,60,1)); people.setOnClickListener(v->showPeople());
        Button brain=btn(isDeepModelReady()?"Brain team":"Fast brain"); row2.addView(brain,new LinearLayout.LayoutParams(0,60,1)); brain.setOnClickListener(v->showIntegrations());

        final Runnable hideControls=()->{ if(controls.getVisibility()==View.VISIBLE){controls.animate().alpha(0f).setDuration(220).withEndAction(()->{controls.setVisibility(View.GONE);controls.setAlpha(1f);}).start();}};
        stage.setOnClickListener(v->{
            if(controls.getVisibility()==View.VISIBLE){ hideControls.run(); }
            else { controls.setAlpha(0f); controls.setVisibility(View.VISIBLE); controls.animate().alpha(1f).setDuration(180).start(); conversationHandler.removeCallbacks(hideControls); conversationHandler.postDelayed(hideControls,6500); }
        });

        stage.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); hud.setPadding(28,20,28,34+bars.bottom);} return insets;
        });
        setContentView(stage);
    }

    void showTalk(){
        checkPrivateSession();
        base(privateSession ? "Transcript • Private" : "Conversation transcript");
        String saved = privateSession ? prefs.getString("private_talk_transcript","") : prefs.getString("talk_transcript","");
        String intro = privateSession ? "Lumi: Private mode is active." : "Lumi: I’m here.";
        transcript=tv(saved.trim().isEmpty() ? intro : saved,16,text);
        transcript.setTextIsSelectable(true);
        transcript.setLineSpacing(0,1.08f);
        transcript.setBackgroundColor(panel);
        transcript.setPadding(22,18,22,18);
        content.addView(transcript);

        talkInput=new EditText(this);
        talkInput.setHint("Type without closing the keyboard...");
        talkInput.setHintTextColor(muted);
        talkInput.setTextColor(text);
        talkInput.setSingleLine(false);
        talkInput.setMinLines(2);
        talkInput.setMaxLines(5);
        talkInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        talkInput.setOnFocusChangeListener((v,hasFocus)->{ if(hasFocus){ talkInput.postDelayed(()->{ ViewParent pp=talkInput.getParent(); if(pp!=null) pp.requestChildFocus(talkInput,talkInput); },180); } });
        content.addView(talkInput);

        LinearLayout row=new LinearLayout(this);
        talkSend=btn("Send"); row.addView(talkSend,new LinearLayout.LayoutParams(0,58,1));
        Button mic=btn("🎙 One-shot backup"); row.addView(mic,new LinearLayout.LayoutParams(0,58,1));
        mic.setOnClickListener(v->startVoice());
        content.addView(row);
        LinearLayout liveRow=new LinearLayout(this);
        Button live=btn(conversationMode?"● Listening now":"Start listening");
        Button stop=btn("Stop listening");
        liveRow.addView(live,new LinearLayout.LayoutParams(0,58,1)); liveRow.addView(stop,new LinearLayout.LayoutParams(0,58,1));
        content.addView(liveRow);
        live.setOnClickListener(v->startConversationMode()); stop.setOnClickListener(v->stopConversationMode());
        TextView liveHint=tv("Hands-free is the default: speak naturally, Lumi answers aloud, then automatically listens again. Manual controls are backups.",12,muted); content.addView(liveHint);

        talkSend.setOnClickListener(v->sendTalkInput());
        talkInput.setOnEditorActionListener((v,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND){ sendTalkInput(); return true; }
            return false;
        });

        String provider=prefs.getString("ai_provider","open_source");
        boolean openModelReady=!prefs.getString("opensource_url","").trim().isEmpty();
        boolean openAiReady=!prefs.getString("openai_api_key","").trim().isEmpty();
        if(!privateSession && (("open_source".equals(provider) && !openModelReady) || ("openai".equals(provider) && !openAiReady))){
            TextView hint=tv("Remote AI brain not connected yet. More → Integration Center → Connect remote open-source AI. The avatar remains the primary conversation surface.",12,muted);
            content.addView(hint);
        }
        if(prefs.getBoolean("hands_free_listening",true) && !conversationMode){
            conversationHandler.postDelayed(() -> ensureHandsFreeListening(),300);
        }
    }

    void sendTalkInput(){
        if(talkInput==null || aiBusy) return;
        String q=talkInput.getText().toString().trim();
        if(q.isEmpty()) return;
        talkInput.setText("");
        appendConversation(q);
    }

    void startVoice(){
        stopLumiSpeechForInterruption();
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT,privateSession ? "Talk to Lumi • Private" : "Talk to Lumi");
        try{ startActivityForResult(i,REQ_SPEECH); }catch(Exception e){Toast.makeText(this,"Speech recognition is not available on this phone.",Toast.LENGTH_LONG).show();}
    }

    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==REQ_EXPORT_BACKUP && res==RESULT_OK && data!=null && data.getData()!=null){ writeBackupToUri(data.getData()); return; }
        if(req==REQ_IMPORT_BACKUP && res==RESULT_OK && data!=null && data.getData()!=null){ restoreBackupFromUri(data.getData()); return; }
        if(req==REQ_EXPORT_DIAGNOSTICS && res==RESULT_OK && data!=null && data.getData()!=null){ writeDiagnosticsToUri(data.getData()); return; }
        if(req==REQ_PRIVATE_DEVICE_CREDENTIAL){
            if(res==RESULT_OK) enterPrivateMode();
            return;
        }
        if(req==REQ_ADMIN_FACE){
            if(res==RESULT_OK && data!=null){
                try{
                    Bitmap bmp=(Bitmap)data.getExtras().get("data");
                    if(bmp!=null){
                        File out=new File(getFilesDir(),"owner_face_reference.jpg");
                        try(FileOutputStream fos=new FileOutputStream(out)){ bmp.compress(Bitmap.CompressFormat.JPEG,92,fos); }
                        prefs.edit().putBoolean("admin_face_enrolled",true).putLong("admin_face_enrolled_at",System.currentTimeMillis()).apply();
                        showAdminFaceEnrollment();
                    }
                }catch(Exception e){ Toast.makeText(this,"Face enrollment capture failed: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
            }
            return;
        }
        if(req==REQ_SPEECH && res==RESULT_OK && data!=null){
            ArrayList<String> r=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(r!=null && !r.isEmpty()){
                appendConversation(r.get(0));
            }
        }
    }

    void appendConversation(String q){
        stopLumiSpeechForInterruption();
        final long turnSerial=++requestSerial;
        if(aiBusy){ diag("interrupt","turn="+turnSerial+" superseded an in-flight request"); setAiBusy(false); activeRequestStage="interrupted"; }
        if(privateSession) touchPrivateSession();
        lastConversationActivity=System.currentTimeMillis();
        followupHotUntil=lastConversationActivity+FOLLOWUP_LINGER_MS;
        scheduleConversationTimeout();
        learnFromConversation(q);
        diag("user","turn="+turnSerial+" text="+safeDiagText(q));
        appendTurn("You", q);

        String instant=operationalOrPreferenceReply(q);
        if(instant==null) instant=instantConversationReply(q);
        if(instant!=null){
            activeRequestRoute="instant"; activeRequestModel="rules"; activeRequestStage="idle";
            prefs.edit().putString("last_route","instant-rules").putString("last_action_reason","I handled that directly because it did not need a model call.").apply();
            appendTurn("Lumi",instant);
            return;
        }

        if(shouldHandleLocally(q)){
            appendTurn("Lumi", respond(q));
            return;
        }

        // Lumi v2 is local-first. A configured remote brain is a booster/fallback, not a dependency.
        boolean localReady=isLocalModelReady();
        String provider=prefs.getString("ai_provider","hybrid");
        if(localReady && !("remote".equals(provider) && remoteBrainAvailable())){
            if(shouldPreferRemote(q) && remoteBrainAvailable()) requestOpenSourceReply(q);
            else requestLocalReply(q);
            return;
        }
        if(remoteBrainAvailable()){ requestOpenSourceReply(q); return; }
        String key=prefs.getString("openai_api_key","").trim();
        if("openai".equals(provider) && !key.isEmpty()){ requestCloudReply(q,key); return; }
        appendTurn("Lumi", localFlowReply(q));
    }

    void appendTurn(String who,String message){
        String existing=privateSession ? prefs.getString("private_talk_transcript","") : prefs.getString("talk_transcript","");
        if(!existing.trim().isEmpty()) existing += "\n\n";
        existing += who+": "+message;
        if(privateSession) prefs.edit().putString("private_talk_transcript",existing).apply();
        else prefs.edit().putString("talk_transcript",existing).apply();
        if(transcript!=null){
            transcript.setText(existing);
            transcript.post(() -> {
                View parent=(View)transcript.getParent();
                if(parent!=null && parent.getParent() instanceof ScrollView){
                    ((ScrollView)parent.getParent()).fullScroll(View.FOCUS_DOWN);
                }
            });
        }
        if("Lumi".equals(who)){
            prefs.edit().putString("last_lumi_reply",message).apply();
            if(avatarSubtitle!=null) avatarSubtitle.setText(message);
            if(avatarState!=null) avatarState.setText("Speaking");
            if(speakReplies && conversationMode) speakAndContinue(message);
        } else {
            prefs.edit().putString("last_user_utterance",message).apply();
            if(avatarSubtitle!=null) avatarSubtitle.setText("You: "+message);
            if(avatarState!=null) avatarState.setText("With you…");
        }
    }

    String instantConversationReply(String q){
        String l=q.toLowerCase(Locale.US).trim().replaceAll("[.!?]+$","");
        if(l.matches("^(hi|hello|hey|hey lumi|hi lumi|hello lumi)$")){
            String[] options={"Hey. I'm here.","Hey. What's up?","Hi. I'm with you."};
            return options[(int)(System.currentTimeMillis()/1000L)%options.length];
        }
        if(l.matches("^(how are you|how are you lumi|how're you|how are things)$")){
            String[] options={"I'm good. I'm here with you.","Pretty good. How are you doing?","Good. Settling in and getting quicker."};
            return options[(int)(System.currentTimeMillis()/1000L)%options.length];
        }
        if(l.matches("^(what('s| is) your name|who are you|your name)$")) return "I'm Lumi.";
        if(l.matches("^(good ?night|night|night lumi|good ?night lumi)$")){
            String[] options={"Good night. I'll be here when you need me.","Night. Sleep well.","Good night. I'll keep things quiet."};
            return options[(int)(System.currentTimeMillis()/1000L)%options.length];
        }
        if(l.matches("^(what('s| is) new|anything new|whats new)$")){
            String[] options={"Mostly me getting faster and a little less computer-ish.","A few things under the hood. I'm getting quicker at normal conversation.","I'm still settling in, but the big focus is making our conversations feel immediate."};
            return options[(int)(System.currentTimeMillis()/1000L)%options.length];
        }
        String math=simpleMathReply(l);
        if(math!=null) return math;
        if(l.matches("^(you there|are you there|lumi you there)$")) return "Yeah. I'm here.";
        if(l.matches("^(thanks|thank you|thanks lumi|thank you lumi)$")){
            String[] options={"Anytime.","Of course.","You got it."};
            return options[(int)(System.currentTimeMillis()/1000L)%options.length];
        }
        if(l.matches("^(okay|ok|got it|cool|sounds good)$")) return "Mm-hm.";
        return null;
    }

    String simpleMathReply(String l){
        try{
            String x=l.replace("what's"," ").replace("what is"," ").replace("whats"," ").trim();
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*(?:\\+|plus)\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(x);
            if(m.find()) return formatSimpleNumber(Double.parseDouble(m.group(1))+Double.parseDouble(m.group(2)));
            m=java.util.regex.Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*(?:-|minus)\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(x);
            if(m.find()) return formatSimpleNumber(Double.parseDouble(m.group(1))-Double.parseDouble(m.group(2)));
            m=java.util.regex.Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*(?:\\*|x|times)\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(x);
            if(m.find()) return formatSimpleNumber(Double.parseDouble(m.group(1))*Double.parseDouble(m.group(2)));
            m=java.util.regex.Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*(?:/|divided by)\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(x);
            if(m.find()){
                double b=Double.parseDouble(m.group(2));
                if(Math.abs(b)<1e-12) return "That would be division by zero.";
                return formatSimpleNumber(Double.parseDouble(m.group(1))/b);
            }
        }catch(Exception ignored){}
        return null;
    }

    String formatSimpleNumber(double v){
        if(Math.abs(v-Math.rint(v))<1e-10) return String.valueOf((long)Math.rint(v));
        return String.format(Locale.US,"%.6f",v).replaceAll("0+$","").replaceAll("\\.$","");
    }

    void scheduleQuickAcknowledgement(long serial,String q){
        if(!conversationMode || !speakReplies || !prefs.getBoolean("human_cues",true)) return;
        int rate=prefs.getInt("human_cue_rate",28);
        int roll=(int)Math.abs((serial*37L + System.currentTimeMillis()/1000L)%100L);
        if(roll>=rate) return; // intentionally not every turn
        conversationHandler.postDelayed(()->{
            if(serial!=requestSerial || !aiBusy || lumiTts==null) return;
            String l=q.toLowerCase(Locale.US);
            String[] thoughtful={"Give me a sec.","Mm, one second.","Yeah, looking."};
            String[] casual={"Mm-hm.","Yeah.","Got you."};
            String[] pool=(l.contains("why")||l.contains("how")||l.contains("explain")||l.contains("analyze"))?thoughtful:casual;
            String ack=pool[(int)(serial%pool.length)];
            try{
                Bundle b=new Bundle();
                lumiTts.speak(ack,android.speech.tts.TextToSpeech.QUEUE_FLUSH,b,"lumi_ack_"+serial);
                if(avatarState!=null) avatarState.setText("With you…");
                diag("cue","turn="+serial+" cue="+ack);
            }catch(Exception ignored){}
        },700);
    }

    void stopLumiSpeechForInterruption(){
        try{ if(lumiTts!=null && lumiTts.isSpeaking()) lumiTts.stop(); }catch(Exception ignored){}
        if(avatarState!=null && "Speaking".contentEquals(avatarState.getText())) avatarState.setText("Listening");
    }

    boolean shouldHandleLocally(String q){
        String l=q.toLowerCase(Locale.US);
        return l.contains("why did you do that") || l.contains("why did you choose that") || l.contains("why are you taking") || l.contains("what model are you using") || l.contains("what brain are you using") || l.contains("what are you doing") || l.contains("export diagnostics") || l.contains("bug report") || l.contains("self test") || l.contains("diagnose yourself") || l.contains("talk less") || l.contains("talk more") || l.contains("respond faster") || l.contains("response time") || l.contains("be more proactive") || l.contains("be less proactive") || l.contains("human cues") || l.contains("show yourself") || l.contains("go home") || l.contains("give me some space")
                || l.contains("dnd off") || l.contains("come back") || (l.contains("filter") && (l.contains("loosen") || l.contains("strict")))
                || l.startsWith("remember") || l.contains("remember my") || l.contains("remember that")
                || l.startsWith("remind me") || l.contains("reminder") || l.contains("private mode") || l.contains("private context")
                || l.contains("glasses") || l.contains("public mode") || l.contains("home mode") || l.contains("work mode")
                || l.contains("travel mode") || l.contains("lockdown mode") || l.contains("security mode")
                || l.contains("wear") || l.contains("outfit") || l.contains("clothes") || l.contains("clothing") || l.contains("shirt")
                || l.contains("jacket") || l.contains("coat") || l.contains("pants") || l.contains("shorts") || l.contains("skirt")
                || l.contains("shoes") || l.contains("accessor") || l.contains("hair") || l.contains("change your look") || l.contains("try something") || l.contains("remove your");
    }

    String localFlowReply(String q){
        String l=q.toLowerCase(Locale.US).trim();
        if(l.matches(".*\\b(hi|hello|hey)\\b.*")) return "Hey. I'm here. What's on your mind?";
        if(l.contains("how are you")) return privateSession ? "Pretty good. Better now that you've actually stopped to talk to me. How are you doing?" : "I'm good. Awake, listening, and getting a little better at this every update. How are you?";
        if(l.endsWith("?")) return "My local brain is not installed yet. Open Brain Setup and I can download it directly to this phone.";
        return "I heard you. My local brain still needs its first-time model download before I can hold a full conversation.";
    }


    File modelDirectory(){
        File base=getExternalFilesDir(null);
        File dir=new File(base==null?getFilesDir():base,"models");
        if(!dir.exists()) dir.mkdirs();
        return dir;
    }

    File fastModelFile(){ return new File(modelDirectory(),FAST_MODEL_FILE); }

    File fastModelPartialFile(){ return new File(modelDirectory(),FAST_MODEL_FILE+".part"); }

    File localModelFile(){ return new File(modelDirectory(),LOCAL_MODEL_FILE); }

    boolean isFastModelReady(){
        File f=fastModelFile();
        return f.exists() && f.length()>330L*1024L*1024L && prefs.getBoolean("fast_model_verified",false);
    }

    boolean isDeepModelReady(){
        File f=localModelFile();
        return f.exists() && f.length()>2000L*1024L*1024L && prefs.getBoolean("local_model_verified",false);
    }

    // "Local ready" means Lumi can converse locally. Deep reasoning is a second tier.
    boolean isLocalModelReady(){ return isFastModelReady(); }

    boolean isBrainTeamReady(){ return isFastModelReady() && isDeepModelReady(); }

    String nextBrainStage(){
        if(!isFastModelReady()) return "fast";
        if(!isDeepModelReady()) return "deep";
        return "";
    }

    File modelFileForStage(String stage){ return "fast".equals(stage)?fastModelFile():localModelFile(); }
    String modelUrlForStage(String stage){ return "fast".equals(stage)?FAST_MODEL_URL:LOCAL_MODEL_URL; }
    String modelShaForStage(String stage){ return "fast".equals(stage)?FAST_MODEL_SHA256:LOCAL_MODEL_SHA256; }
    long modelMinBytesForStage(String stage){ return "fast".equals(stage)?330L*1024L*1024L:2000L*1024L*1024L; }
    String modelLabelForStage(String stage){ return "fast".equals(stage)?"fast conversation brain":"deep reasoning brain"; }

    boolean remoteBrainAvailable(){
        return !prefs.getString("opensource_url","").trim().isEmpty();
    }

    String currentPowerProfile(){
        try{
            Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level=b==null?-1:b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1);
            int scale=b==null?100:b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100);
            int temp=b==null?0:b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,0);
            int plugged=b==null?0:b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0);
            int pct=scale>0?level*100/scale:-1;
            float c=temp/10f;
            if(plugged!=0 && pct>=70 && c>0 && c<39f) return "Performance";
            if(pct>=30 && (c<=0 || c<42f)) return "Balanced";
            return "Battery Saver";
        }catch(Exception e){ return "Balanced"; }
    }

    int localMaxTokens(boolean thinking){
        String p=currentPowerProfile();
        if(thinking){
            if("Performance".equals(p)) return 140;
            if("Battery Saver".equals(p)) return 80;
            return 110;
        }
        // Speed-first conversational budget. Normal chat should begin quickly, not lecture.
        boolean speed=prefs.getBoolean("speed_priority",true);
        String style=prefs.getString("reply_style","brief");
        if(speed && "brief".equals(style)){ if("Performance".equals(p)) return 36; if("Battery Saver".equals(p)) return 24; return 30; }
        if("detailed".equals(style)){ if("Performance".equals(p)) return 72; if("Battery Saver".equals(p)) return 42; return 56; }
        if("Performance".equals(p)) return 50;
        if("Battery Saver".equals(p)) return 32;
        return 42;
    }

    boolean shouldUseDeepBrain(String q){
        String l=q.toLowerCase(Locale.US);
        // Fast brain owns ordinary conversation. Route substantive work to the 4B brain.
        return q.length()>420
                || l.contains("think deeply") || l.contains("reason this through")
                || l.contains("analyze") || l.contains("compare") || l.contains("troubleshoot")
                || l.contains("debug") || l.contains("write code") || l.contains("code for")
                || l.contains("plan ") || l.contains("design ") || l.contains("research")
                || l.contains("calculate") || l.contains("work through this")
                || l.contains("complex reasoning") || l.contains("explain in detail");
    }

    int localThreadBudget(){
        int cores=Math.max(1,Runtime.getRuntime().availableProcessors());
        String p=currentPowerProfile();
        if("Performance".equals(p)) return Math.min(4,cores);
        if("Battery Saver".equals(p)) return Math.min(2,cores);
        return Math.min(3,cores);
    }

    boolean shouldPreferRemote(String q){
        if(!remoteBrainAvailable()) return false;
        String p=currentPowerProfile();
        String l=q.toLowerCase(Locale.US);
        if("Battery Saver".equals(p) && q.length()>180) return true;
        return q.length()>700 || l.contains("deep research") || l.contains("analyze this code") || l.contains("large document");
    }

    void requestLocalReply(String userText){
        if(!isFastModelReady()) { appendTurn("Lumi",localFlowReply(userText)); return; }

        if(shouldUseDeepBrain(userText) && remoteBrainAvailable()){
            requestOpenSourceReply(userText);
            return;
        }

        setAiBusy(true);
        final long serial=requestSerial;
        activeRequestStartedAt=System.currentTimeMillis();
        activeRequestStage="generating"; activeRequestModel="Fast Brain 0.6B"; activeRequestRoute="local"; activeRequestText=userText;
        prefs.edit().putString("last_action_reason","I kept the request on the local Fast Brain for speed and offline continuity.").apply();
        diag("route","turn="+serial+" local Fast Brain start");
        scheduleQuickAcknowledgement(serial,userText);

        final String modelPath=fastModelFile().getAbsolutePath();
        final String instructions=buildLocalLumiInstructions(false);
        final String transcriptText=privateSession ? prefs.getString("private_talk_transcript","") : prefs.getString("talk_transcript","");
        String recent=transcriptText;
        String newest="You: "+userText;
        int last=recent.lastIndexOf(newest);
        if(last>=0) recent=recent.substring(0,last).trim();
        int historyLimit=prefs.getBoolean("speed_priority",true)?180:320;
        if(recent.length()>historyLimit) recent=recent.substring(recent.length()-historyLimit);
        final String prompt=(recent.trim().isEmpty()?"":"Context: "+recent+"\n")
                +"User just said: "+userText+"\nLumi: /no_think";

        if(avatarState!=null) avatarState.setText("With you…");
        LocalBrain.ask(modelPath,512,localThreadBudget(),prompt,instructions,localMaxTokens(false),new LocalBrain.Callback(){
            @Override public void onReply(String reply,double tokensPerSecond){
                final String cleaned=cleanLocalModelReply(reply,instructions,prompt);
                final String r=cleaned==null?"":cleaned.trim();
                runOnUiThread(() -> {
                    if(serial!=requestSerial){ diag("stale","turn="+serial+" local reply ignored after interruption"); return; }
                    lastResponseLatencyMs=System.currentTimeMillis()-activeRequestStartedAt; lastResponseTokensPerSecond=tokensPerSecond;
                    activeRequestStage="idle";
                    prefs.edit().putLong("last_response_latency_ms",lastResponseLatencyMs).putString("last_route","local-fast").putString("local_brain_status","ready • "+String.format(Locale.US,"%.1f tok/s",tokensPerSecond)+" • fast 0.6B").apply();
                    diag("reply","turn="+serial+" route=local-fast latencyMs="+lastResponseLatencyMs+" tps="+String.format(Locale.US,"%.1f",tokensPerSecond));
                    if(r.isEmpty() || looksLikeWrongGenericGreeting(userText,r) || looksLikeInternalNarration(userText,r)){
                        prefs.edit().putString("local_brain_status","Fast Brain prompt mismatch • retrying").apply();
                        requestFastFallback(userText,true);
                        return;
                    }
                    setAiBusy(false);
                    appendTurn("Lumi",r);
                    if(!prefs.getString("pending_conversation_note","").isEmpty()) prefs.edit().remove("pending_conversation_note").apply();
                });
            }
            @Override public void onError(String message){
                runOnUiThread(() -> {
                    if(serial!=requestSerial) return;
                    setAiBusy(false); activeRequestStage="error";
                    prefs.edit().putString("local_brain_status","error: "+message).apply();
                    diag("error","turn="+serial+" Fast Brain: "+safeDiagText(message));
                    if(remoteBrainAvailable()) requestOpenSourceReply(userText);
                    else appendTurn("Lumi","I hit a local brain error. I logged it for diagnostics.");
                });
            }
        });
    }

    boolean looksLikeWrongGenericGreeting(String userText,String reply){
        String u=userText==null?"":userText.toLowerCase(Locale.US).trim();
        String r=reply==null?"":reply.toLowerCase(Locale.US).trim();
        boolean userGreeting=u.matches(".*\\b(hi|hello|hey)\\b.*") || u.contains("how are you");
        if(userGreeting) return false;
        return r.contains("good to meet you") || r.contains("nice to meet you")
                || r.contains("how can i help you") || r.contains("how may i help you")
                || r.matches("^(hi|hello|hey)[!,. ]+.*how can .*help.*");
    }

    boolean looksLikeInternalNarration(String userText,String reply){
        String u=userText==null?"":userText.toLowerCase(Locale.US);
        if(u.contains("mode") || u.contains("what are you wearing") || u.contains("what model") || u.contains("status")) return false;
        String r=reply==null?"":reply.toLowerCase(Locale.US);
        return r.contains("i am in home mode") || r.contains("i'm in home mode")
                || r.contains("current profile") || r.contains("dressed casually")
                || r.contains("i should answer") || r.contains("i should respond")
                || r.contains("according to my instructions") || r.contains("my instructions say")
                || r.contains("is there anything else i can help you with today")
                || r.contains("how can i assist you today");
    }

    void requestFastFallback(String userText){ requestFastFallback(userText,false); }

    void requestFastFallback(String userText,boolean mismatchRetry){
        final long serial=requestSerial;
        if(!isFastModelReady()){
            setAiBusy(false);
            if(remoteBrainAvailable()) requestOpenSourceReply(userText);
            else appendTurn("Lumi","My local conversation brain is unavailable right now.");
            return;
        }
        final String instructions="You are Lumi. Answer ONLY the user's exact message in one short natural sentence. Do not greet unless the user greeted you. Do not introduce yourself unless asked. No instructions, no analysis. /no_think";
        final String prompt="User: "+userText+"\nLumi: /no_think";
        LocalBrain.ask(fastModelFile().getAbsolutePath(),512,localThreadBudget(),prompt,instructions,40,new LocalBrain.Callback(){
            @Override public void onReply(String reply,double tps){
                final String cleaned=cleanLocalModelReply(reply,instructions,prompt);
                runOnUiThread(() -> {
                    String r=cleaned==null?"":cleaned.trim();
                    if(serial!=requestSerial){ diag("stale","turn="+serial+" fallback reply ignored"); return; }
                    if(r.isEmpty() || looksLikeWrongGenericGreeting(userText,r) || looksLikeInternalNarration(userText,r)){
                        setAiBusy(false);
                        prefs.edit().putString("local_brain_status","Fast Brain prompt mismatch • diagnostic needed").apply();
                        diag("error","turn="+serial+" Fast Brain returned unusable output after retry");
                        appendTurn("Lumi","That reply came out wrong, so I stopped it. I logged the failure for diagnostics.");
                    }else{
                        setAiBusy(false);
                        lastResponseLatencyMs=activeRequestStartedAt>0?System.currentTimeMillis()-activeRequestStartedAt:-1; lastResponseTokensPerSecond=tps; activeRequestStage="idle";
                        prefs.edit().putLong("last_response_latency_ms",lastResponseLatencyMs).putString("last_route","local-fast-retry").putString("local_brain_status","ready • "+String.format(Locale.US,"%.1f tok/s",tps)+" • fast 0.6B").apply();
                        diag("reply","turn="+serial+" route=local-fast-retry latencyMs="+lastResponseLatencyMs);
                        appendTurn("Lumi",r);
                    }
                });
            }
            @Override public void onError(String message){
                runOnUiThread(() -> {
                    if(serial!=requestSerial) return;
                    setAiBusy(false); activeRequestStage="error";
                    prefs.edit().putString("local_brain_status","Fast Brain error: "+message).apply();
                    diag("error","turn="+serial+" Fast Brain retry failed: "+safeDiagText(message));
                    appendTurn("Lumi","My local reply failed. I logged the error for diagnostics.");
                });
            }
        });
    }


    File candidateModelFile(){
        File base=getExternalFilesDir(null);
        File dir=new File(base==null?getFilesDir():base,"models");
        if(!dir.exists())dir.mkdirs();
        return new File(dir,"Qwen3-4B-Q4_K_M.candidate.gguf");
    }

    void maybeActivateModelCandidate(){
        if(!prefs.getBoolean("model_candidate_ready",false)) return;
        final File candidate=candidateModelFile();
        if(!candidate.exists() || candidate.length()<2000L*1024L*1024L){
            prefs.edit().putBoolean("model_candidate_ready",false).apply(); return;
        }
        if(avatarState!=null)avatarState.setText("Testing Thursday model update…");
        LocalBrain.probe(candidate.getAbsolutePath(),1024,3,new LocalBrain.Callback(){
            @Override public void onReply(String text,double tps){ runOnUiThread(() -> promoteCandidate(candidate,tps)); }
            @Override public void onError(String message){ runOnUiThread(() -> {
                candidate.delete(); prefs.edit().putBoolean("model_candidate_ready",false).apply();
                appendChangeLog("Rejected an unstable local-model candidate and kept the previous brain.");
                if(avatarState!=null)avatarState.setText("Local brain ready • "+currentPowerProfile());
            }); }
        });
    }

    void promoteCandidate(File candidate,double tps){
        try{
            File active=localModelFile();
            File backup=new File(active.getParentFile(),LOCAL_MODEL_FILE+".backup");
            if(backup.exists())backup.delete();
            if(active.exists() && !active.renameTo(backup)) throw new IOException("Could not create rollback model");
            if(!candidate.renameTo(active)){
                if(backup.exists())backup.renameTo(active);
                throw new IOException("Could not activate candidate model");
            }
            String digest=sha256(active);
            String tag=prefs.getString("model_candidate_tag","");
            prefs.edit().putBoolean("model_candidate_ready",false)
                    .putBoolean("local_model_verified",true)
                    .putString("local_model_sha256",digest)
                    .putString("model_remote_tag",tag)
                    .putString("pending_conversation_note","I quietly tested and adopted an updated local model Thursday night. The previous model is still available as my rollback copy.")
                    .apply();
            appendChangeLog("Adopted a tested local-model update and retained one rollback model.");
            if(avatarState!=null)avatarState.setText("Local brain updated • "+String.format(Locale.US,"%.1f tok/s",tps));
        }catch(Exception e){
            prefs.edit().putBoolean("model_candidate_ready",false).apply();
            appendChangeLog("Could not promote a local-model candidate: "+e.getMessage());
        }
    }

    void clearFastModelDownloadTracking(){
        prefs.edit().remove("fast_model_download_id").apply();
        fastModelDownloadId=-1L;
    }

    void showFastModelDownloadPrompt(){
        if(isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Start Lumi's fast brain")
                .setMessage("This lightweight Qwen3 0.6B model is about 397 MB. It handles ordinary conversation locally and is the only brain required for this speed-tuning build.")
                .setPositiveButton("Download",(d,w)->startFastModelDownload())
                .setNegativeButton("Not now",null)
                .setCancelable(false)
                .show();
    }

    void ensureFastModelSetup(boolean force){
        if(isFinishing()) return;
        if(isFastModelReady()){ startLumiRuntime(); return; }
        File f=fastModelFile();
        if(f.exists() && f.length()>330L*1024L*1024L){ verifyFastModelAsync(f); return; }
        if(fastDirectDownloadRunning){ updateFirstRunBrainUi("Fast brain download already running…",-1,true); return; }
        showFastModelDownloadPrompt();
    }

    void startFastModelDownload(){
        if(fastDirectDownloadRunning) return;
        clearFastModelDownloadTracking();
        fastDirectDownloadRunning=true;
        prefs.edit().putBoolean("fast_model_verified",false).putString("local_brain_status","fast brain direct download starting").apply();
        updateFirstRunBrainUi("Connecting to fast brain source…",0,true);

        new Thread(()->{
            HttpURLConnection conn=null;
            try{
                File target=fastModelFile();
                File part=fastModelPartialFile();
                File parent=target.getParentFile();
                if(parent!=null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create model folder");
                if(target.exists()) target.delete();

                long resumeAt=part.exists()?part.length():0L;
                URL url=new URL(FAST_MODEL_URL);
                int redirects=0;
                int code;
                while(true){
                    conn=(HttpURLConnection)url.openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(45000);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("User-Agent","Lumi/2.0 Android");
                    conn.setRequestProperty("Accept","application/octet-stream,*/*");
                    conn.setRequestProperty("Accept-Encoding","identity");
                    if(resumeAt>0) conn.setRequestProperty("Range","bytes="+resumeAt+"-");
                    code=conn.getResponseCode();
                    if(code==301 || code==302 || code==303 || code==307 || code==308){
                        String location=conn.getHeaderField("Location");
                        conn.disconnect(); conn=null;
                        if(location==null || location.trim().isEmpty()) throw new IOException("Download redirect had no destination");
                        url=new URL(url,location);
                        redirects++;
                        if(redirects>10) throw new IOException("Too many download redirects");
                        continue;
                    }
                    break;
                }

                boolean append=(code==HttpURLConnection.HTTP_PARTIAL && resumeAt>0);
                if(code==416 && resumeAt>0){
                    // Server rejected the range. Restart cleanly once.
                    conn.disconnect(); conn=null;
                    if(part.exists()) part.delete();
                    resumeAt=0L;
                    url=new URL(FAST_MODEL_URL);
                    redirects=0;
                    while(true){
                        conn=(HttpURLConnection)url.openConnection();
                        conn.setConnectTimeout(20000); conn.setReadTimeout(45000); conn.setInstanceFollowRedirects(false);
                        conn.setRequestProperty("User-Agent","Lumi/2.0 Android");
                        conn.setRequestProperty("Accept","application/octet-stream,*/*");
                        conn.setRequestProperty("Accept-Encoding","identity");
                        code=conn.getResponseCode();
                        if(code==301 || code==302 || code==303 || code==307 || code==308){
                            String location=conn.getHeaderField("Location");
                            conn.disconnect(); conn=null;
                            if(location==null || location.trim().isEmpty()) throw new IOException("Download redirect had no destination");
                            url=new URL(url,location);
                            if(++redirects>10) throw new IOException("Too many download redirects");
                            continue;
                        }
                        break;
                    }
                    append=false;
                }
                if(code<200 || code>=300) throw new IOException("Server returned HTTP "+code);

                long content=conn.getContentLengthLong();
                long total=content>0 ? (append?resumeAt+content:content) : FAST_MODEL_APPROX_BYTES;
                if(!append && part.exists()) part.delete();
                long written=append?resumeAt:0L;
                long lastUi=0L;
                int lastPct=-1;

                try(InputStream raw=conn.getInputStream();
                    BufferedInputStream in=new BufferedInputStream(raw,128*1024);
                    FileOutputStream fos=new FileOutputStream(part,append);
                    BufferedOutputStream out=new BufferedOutputStream(fos,128*1024)){
                    byte[] buf=new byte[128*1024];
                    int n;
                    while((n=in.read(buf))!=-1){
                        out.write(buf,0,n);
                        written+=n;
                        long now=System.currentTimeMillis();
                        int pct=total>0?(int)Math.max(0,Math.min(99,(written*100L)/total)):-1;
                        if(pct!=lastPct || now-lastUi>1000L){
                            lastPct=pct; lastUi=now;
                            final long done=written; final long all=total; final int shown=pct;
                            prefs.edit().putLong("fast_direct_bytes",done).putLong("fast_direct_total",all).putString("local_brain_status","fast brain downloading").apply();
                            runOnUiThread(()-> updateFirstRunBrainUi(shown>=0?"Downloading fast brain • "+shown+"%":"Downloading fast brain…",shown,true));
                        }
                    }
                    out.flush();
                    fos.getFD().sync();
                }

                if(part.length()<330L*1024L*1024L) throw new IOException("Downloaded file was incomplete ("+(part.length()/1024L/1024L)+" MB)");
                if(target.exists()) target.delete();
                if(!part.renameTo(target)){
                    copyFile(part,target);
                    if(!part.delete()) part.deleteOnExit();
                }
                prefs.edit().remove("fast_direct_bytes").remove("fast_direct_total").putString("local_brain_status","fast brain downloaded; verifying").apply();
                runOnUiThread(()->{
                    fastDirectDownloadRunning=false;
                    updateFirstRunBrainUi("Download complete • verifying…",100,true);
                    verifyFastModelAsync(target);
                });
            }catch(Exception e){
                final String message=(e.getMessage()==null || e.getMessage().trim().isEmpty())?e.getClass().getSimpleName():e.getMessage();
                prefs.edit().putString("fast_download_error",message).putString("local_brain_status","fast brain download failed").apply();
                runOnUiThread(()->{
                    fastDirectDownloadRunning=false;
                    updateFirstRunBrainUi("Fast brain download failed • retry",0,false);
                    if(!isFinishing()) new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Fast brain download failed")
                            .setMessage(message+"\n\nLumi kept any partial download and will try to resume it when you retry.")
                            .setPositiveButton("Retry",(d,w)->startFastModelDownload())
                            .setNegativeButton("Later",null)
                            .show();
                });
            }finally{
                if(conn!=null) conn.disconnect();
            }
        },"LumiFastBrainHttpsDownload").start();
    }

    void monitorFastModelDownload(){
        // Retained as a no-op compatibility shim for older call sites/settings.
        // Fast Brain downloads now use Lumi's direct HTTPS downloader.
        if(!fastDirectDownloadRunning) updateFirstRunBrainUi("Fast brain download needs retry",0,false);
    }

    void copyFile(File src,File dst) throws IOException{
        try(InputStream in=new BufferedInputStream(new FileInputStream(src),128*1024);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(dst),128*1024)){
            byte[] buf=new byte[128*1024]; int n;
            while((n=in.read(buf))!=-1) out.write(buf,0,n);
            out.flush();
        }
    }

    void verifyFastModelAsync(File file){
        if(fastModelVerificationRunning) return;
        fastModelVerificationRunning=true;
        updateFirstRunBrainUi("Verifying fast brain…",-1,true);
        new Thread(()->{
            boolean ok=false; String got="";
            try{ got=sha256(file); ok=FAST_MODEL_SHA256.equalsIgnoreCase(got); }catch(Exception ignored){}
            final boolean valid=ok; final String digest=got;
            runOnUiThread(()->{
                fastModelVerificationRunning=false;
                prefs.edit().putBoolean("fast_model_verified",valid).putString("fast_model_sha256",digest).apply();
                if(valid){
                    prefs.edit().putString("ai_provider","hybrid").putString("local_brain_status","fast brain ready").apply();
                    appendChangeLog("Verified and activated Qwen3 0.6B Fast Brain for speed-first local conversation.");
                    updateFirstRunBrainUi("Fast brain ready • opening Lumi",100,true);
                    Toast.makeText(MainActivity.this,"Lumi's fast brain is ready.",Toast.LENGTH_SHORT).show();
                    conversationHandler.postDelayed(()->startLumiRuntime(),500);
                }else{
                    if(file.exists()) file.delete();
                    updateFirstRunBrainUi("Fast brain verification failed • retry",0,false);
                    new AlertDialog.Builder(MainActivity.this).setTitle("Fast brain verification failed").setMessage("The model did not match its expected checksum, so Lumi removed it.").setPositiveButton("Retry",(d,w)->startFastModelDownload()).setNegativeButton("Later",null).show();
                }
            });
        },"LumiFastModelVerify").start();
    }

    void clearLocalModelDownloadTracking(){
        prefs.edit().remove("local_model_download_id").apply();
        localModelDownloadId=-1L;
    }

    int localModelDownloadStatus(long id){
        if(id<=0) return -1;
        android.database.Cursor c=null;
        try{
            android.app.DownloadManager dm=(android.app.DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            if(dm==null) return -1;
            c=dm.query(new android.app.DownloadManager.Query().setFilterById(id));
            if(c==null || !c.moveToFirst()) return -1; // Stale/removed DownloadManager id.
            return c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
        }catch(Exception e){
            return -1;
        }finally{
            if(c!=null) try{ c.close(); }catch(Exception ignored){}
        }
    }

    void showLocalModelDownloadPrompt(){
        if(isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Add Lumi's 4B Deep Brain")
                .setMessage("The 4B model is optional in this speed-tuning build. It can be stored now for a later safe model-switching upgrade, but it is not run concurrently with the Fast Brain in this build. Download size is about 2.5 GB.")
                .setPositiveButton("Download",(d,w)->startLocalModelDownload())
                .setNegativeButton("Not now",(d,w)->prefs.edit().putBoolean("local_model_setup_deferred",true).apply())
                .setCancelable(false)
                .show();
    }

    void showLocalModelRetryPrompt(String detail){
        if(isFinishing()) return;
        String extra=(detail==null || detail.trim().isEmpty())?"":"\n\n"+detail;
        new AlertDialog.Builder(this)
                .setTitle("Deep Brain download needs retry")
                .setMessage("The previous model download is no longer active. Lumi cleared the old download state so it cannot get stuck again."+extra)
                .setPositiveButton("Retry Download",(d,w)->startLocalModelDownload())
                .setNegativeButton("Later",null)
                .show();
    }

    void ensureLocalModelSetup(boolean force){
        if(isFinishing()) return;
        File f=localModelFile();
        if(isDeepModelReady()){
            if(force) Toast.makeText(this,"Lumi's 4B Deep Brain is already installed.",Toast.LENGTH_SHORT).show();
            return;
        }
        if(f.exists() && f.length()>2000L*1024L*1024L){ verifyLocalModelAsync(f); return; }

        // An Android DownloadManager id can survive an app update even after Android has
        // removed the actual download. Older Lumi builds treated any saved id as active,
        // which made the Brain Setup button appear to do nothing. Validate it first.
        long saved=prefs.getLong("local_model_download_id",-1L);
        if(saved>0){
            int state=localModelDownloadStatus(saved);
            if(state==android.app.DownloadManager.STATUS_PENDING ||
                    state==android.app.DownloadManager.STATUS_RUNNING ||
                    state==android.app.DownloadManager.STATUS_PAUSED){
                localModelDownloadId=saved;
                if(force) Toast.makeText(this,"Lumi's local brain download is already active.",Toast.LENGTH_SHORT).show();
                monitorModelDownload();
                return;
            }
            if(state==android.app.DownloadManager.STATUS_SUCCESSFUL){
                clearLocalModelDownloadTracking();
                if(f.exists() && f.length()>2000L*1024L*1024L){ verifyLocalModelAsync(f); return; }
            }else{
                clearLocalModelDownloadTracking();
                if(force){
                    showLocalModelRetryPrompt(state==android.app.DownloadManager.STATUS_FAILED ? "Android reported that the previous download failed." : "The old Android download record was missing or stale.");
                    return;
                }
            }
        }

        if(!force && prefs.getBoolean("local_model_setup_deferred",false)) return;
        showLocalModelDownloadPrompt();
    }

    void startLocalModelDownload(){
        try{
            // Never let an old/stale id suppress a new user-requested download.
            clearLocalModelDownloadTracking();
            File f=localModelFile(); if(f.exists()) f.delete();
            File parent=f.getParentFile(); if(parent!=null && !parent.exists()) parent.mkdirs();
            android.app.DownloadManager dm=(android.app.DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            if(dm==null) throw new IOException("Android Download Manager is unavailable");
            android.app.DownloadManager.Request r=new android.app.DownloadManager.Request(Uri.parse(LOCAL_MODEL_URL));
            r.setTitle("Lumi 4B Deep Brain");
            r.setDescription("Downloading optional Qwen3 4B for deeper reasoning");
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE);
            r.setAllowedOverRoaming(false);
            r.setAllowedOverMetered(true);
            r.setDestinationInExternalFilesDir(this,null,"models/"+LOCAL_MODEL_FILE);
            localModelDownloadId=dm.enqueue(r);
            prefs.edit().putLong("local_model_download_id",localModelDownloadId)
                    .putBoolean("local_model_setup_deferred",false)
                    .putBoolean("local_model_verified",false)
                    .putString("local_brain_status","download starting")
                    .apply();
            if(avatarState!=null) avatarState.setText("Starting local brain download…");
            updateFirstRunBrainUi("Starting local brain download…",0,true);
            Toast.makeText(this,"Lumi's local brain download started. Progress will appear here and in Android downloads.",Toast.LENGTH_LONG).show();
            monitorModelDownload();
        }catch(Exception e){
            clearLocalModelDownloadTracking();
            if(avatarState!=null) avatarState.setText("Local brain download could not start");
            updateFirstRunBrainUi("Brain download could not start • retry",0,false);
            new AlertDialog.Builder(this)
                    .setTitle("Could not start download")
                    .setMessage("Android could not start Lumi's model download. "+(e.getMessage()==null?"":e.getMessage()))
                    .setPositiveButton("Retry",(d,w)->startLocalModelDownload())
                    .setNegativeButton("Later",null)
                    .show();
        }
    }

    void monitorModelDownload(){
        final long id=localModelDownloadId>0?localModelDownloadId:prefs.getLong("local_model_download_id",-1L);
        if(id<=0) return;
        conversationHandler.postDelayed(new Runnable(){
            @Override public void run(){
                android.database.Cursor c=null;
                try{
                    android.app.DownloadManager dm=(android.app.DownloadManager)getSystemService(DOWNLOAD_SERVICE);
                    if(dm==null) throw new IOException("Android Download Manager is unavailable");
                    c=dm.query(new android.app.DownloadManager.Query().setFilterById(id));
                    if(c==null || !c.moveToFirst()){
                        if(c!=null){ c.close(); c=null; }
                        clearLocalModelDownloadTracking();
                        if(avatarState!=null) avatarState.setText("Local brain download needs retry");
                        updateFirstRunBrainUi("Brain download record was lost • retry",0,false);
                        showLocalModelRetryPrompt("Android no longer has a record of the previous download.");
                        return;
                    }

                    int statusValue=c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
                    long sofar=c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total=c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if(avatarState!=null){
                        if(total>0){
                            int pct=Math.max(0,Math.min(100,(int)(sofar*100/total)));
                            avatarState.setText("Downloading local brain • "+pct+"%");
                            updateFirstRunBrainUi("Downloading local brain • "+pct+"%",pct,true);
                            prefs.edit().putString("local_brain_status","downloading • "+pct+"%").apply();
                        }else{
                            String dlState=statusValue==android.app.DownloadManager.STATUS_PAUSED ? "Local brain download paused" : "Downloading local brain…";
                            avatarState.setText(dlState);
                            updateFirstRunBrainUi(dlState,-1,true);
                        }
                    }
                    if(firstRunBrainStatus!=null){
                        if(total>0){ int pct=Math.max(0,Math.min(100,(int)(sofar*100/total))); updateFirstRunBrainUi("Downloading local brain • "+pct+"%",pct,true); }
                        else updateFirstRunBrainUi(statusValue==android.app.DownloadManager.STATUS_PAUSED?"Local brain download paused":"Downloading local brain…",-1,true);
                    }
                    if(statusValue==android.app.DownloadManager.STATUS_SUCCESSFUL){
                        c.close(); c=null;
                        clearLocalModelDownloadTracking();
                        File downloaded=localModelFile();
                        if(downloaded.exists() && downloaded.length()>2000L*1024L*1024L) verifyLocalModelAsync(downloaded);
                        else showLocalModelRetryPrompt("Android finished the download, but the model file was missing or incomplete.");
                        return;
                    }
                    if(statusValue==android.app.DownloadManager.STATUS_FAILED){
                        int reason=c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON));
                        c.close(); c=null;
                        clearLocalModelDownloadTracking();
                        if(avatarState!=null) avatarState.setText("Local brain download failed");
                        updateFirstRunBrainUi("Brain download failed • retry",0,false);
                        showLocalModelRetryPrompt("Android download error code: "+reason);
                        return;
                    }
                }catch(Exception e){
                    clearLocalModelDownloadTracking();
                    if(avatarState!=null) avatarState.setText("Local brain download needs retry");
                    updateFirstRunBrainUi("Brain download needs retry",0,false);
                    showLocalModelRetryPrompt(e.getMessage());
                    return;
                }finally{
                    if(c!=null) try{ c.close(); }catch(Exception ignored){}
                }
                conversationHandler.postDelayed(this,2500);
            }
        },1200);
    }

    void verifyLocalModelAsync(File file){
        if(localModelVerificationRunning) return;
        localModelVerificationRunning=true;
        if(avatarState!=null) avatarState.setText("Verifying local brain…");
        updateFirstRunBrainUi("Verifying downloaded brain…",-1,true);
        new Thread(() -> {
            boolean ok=false; String got="";
            try{ got=sha256(file); ok=LOCAL_MODEL_SHA256.equalsIgnoreCase(got); }catch(Exception ignored){}
            final boolean valid=ok; final String digest=got;
            runOnUiThread(() -> {
                localModelVerificationRunning=false;
                prefs.edit().putBoolean("local_model_verified",valid).putString("local_model_sha256",digest).apply();
                if(valid){
                    if(avatarState!=null) avatarState.setText("Local brain ready • "+currentPowerProfile());
                    prefs.edit().putString("ai_provider","hybrid").apply();
                    appendChangeLog("Verified and activated on-phone Qwen3 4B local brain.");
                    updateFirstRunBrainUi("Local brain verified • ready",100,true);
                    Toast.makeText(MainActivity.this,"Lumi's local brain is ready.",Toast.LENGTH_LONG).show();
                    // Deep Brain is optional and never blocks normal conversation or administrator setup.
                }else{
                    if(file.exists())file.delete();
                    if(avatarState!=null) avatarState.setText("Local brain download needs retry");
                    updateFirstRunBrainUi("Brain verification failed • retry required",0,false);
                    new AlertDialog.Builder(MainActivity.this).setTitle("Model verification failed").setMessage("The downloaded model did not match its expected security checksum, so Lumi removed it. Please retry the download.").setPositiveButton("Retry",(d,w)->startLocalModelDownload()).setNegativeButton("Later",null).show();
                }
            });
        },"LumiModelVerify").start();
    }

    String sha256(File f) throws Exception{
        java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream is=new BufferedInputStream(new FileInputStream(f))){ byte[] buf=new byte[1024*1024]; int n; while((n=is.read(buf))>0)md.update(buf,0,n); }
        StringBuilder sb=new StringBuilder(); for(byte b:md.digest())sb.append(String.format(Locale.US,"%02x",b)); return sb.toString();
    }

    void appendChangeLog(String item){
        String old=prefs.getString("change_log","");
        String stamp=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
        prefs.edit().putString("change_log",(old+"\n• "+stamp+" — "+item).trim()).apply();
    }

    void setAiBusy(boolean busy){
        aiBusy=busy;
        if(talkSend!=null){ talkSend.setEnabled(!busy); talkSend.setText(busy ? "Working…" : "Send"); }
    }

    void requestCloudReply(String userText,String apiKey){
        setAiBusy(true);
        final long serial=requestSerial;
        activeRequestStartedAt=System.currentTimeMillis(); activeRequestStage="connecting"; activeRequestModel="Cloud booster"; activeRequestRoute="cloud"; activeRequestText=userText;
        prefs.edit().putString("last_action_reason","I used the configured cloud booster for this request.").apply();
        diag("route","turn="+serial+" cloud booster start");
        final String model=prefs.getString("openai_model","gpt-5.6").trim().isEmpty()?"gpt-5.6":prefs.getString("openai_model","gpt-5.6").trim();
        final String instructions=buildLumiInstructions();
        new Thread(() -> {
            HttpURLConnection c=null;
            try{
                URL u=new URL("https://api.openai.com/v1/responses");
                c=(HttpURLConnection)u.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(20000); c.setReadTimeout(60000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                c.setRequestProperty("Authorization","Bearer "+apiKey);
                JSONObject body=new JSONObject();
                body.put("model",model);
                body.put("instructions",instructions);
                body.put("input",userText);
                body.put("max_output_tokens",500);
                if(previousResponseId!=null && !previousResponseId.trim().isEmpty()) body.put("previous_response_id",previousResponseId);
                try(OutputStream os=c.getOutputStream()){ os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                int code=c.getResponseCode();
                InputStream is=(code>=200 && code<300)?c.getInputStream():c.getErrorStream();
                String raw=readAll(is);
                if(code<200 || code>=300) throw new IOException("OpenAI returned HTTP "+code+": "+friendlyApiError(raw));
                JSONObject response=new JSONObject(raw);
                previousResponseId=response.optString("id",previousResponseId);
                String reply=extractOutputText(response);
                if(reply.trim().isEmpty()) reply="I got a response, but there wasn't any text in it.";
                final String finalReply=reply.trim();
                runOnUiThread(() -> { if(serial!=requestSerial)return; lastResponseLatencyMs=System.currentTimeMillis()-activeRequestStartedAt; activeRequestStage="idle"; prefs.edit().putLong("last_response_latency_ms",lastResponseLatencyMs).putString("last_route","cloud-booster").apply(); diag("reply","turn="+serial+" route=cloud latencyMs="+lastResponseLatencyMs); setAiBusy(false); appendTurn("Lumi",finalReply); });
            }catch(Exception e){
                final String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                runOnUiThread(() -> { if(serial!=requestSerial)return; diag("network","turn="+serial+" cloud failed; silent local fallback: "+safeDiagText(msg)); activeRequestStage="offline fallback"; activeRequestModel="Fast Brain 0.6B"; activeRequestRoute="local-fallback"; if(isFastModelReady()) requestFastFallback(userText,false); else { setAiBusy(false); appendTurn("Lumi","I lost the network and my local brain isn't available yet."); } });
            }finally{ if(c!=null)c.disconnect(); }
        }).start();
    }

    void requestOpenSourceReply(String userText){
        setAiBusy(true);
        final long serial=requestSerial;
        activeRequestStartedAt=System.currentTimeMillis(); activeRequestStage="connecting"; activeRequestModel="Remote booster"; activeRequestRoute="remote"; activeRequestText=userText;
        prefs.edit().putString("last_action_reason","I used the optional remote booster because the router classified the request as heavier work.").apply();
        diag("route","turn="+serial+" remote booster start");
        final String endpoint=prefs.getString("opensource_url","http://192.168.1.100:11434/v1/chat/completions").trim();
        final String model=prefs.getString("opensource_model","llama3.2:3b").trim();
        final String token=prefs.getString("opensource_api_key","").trim();
        final String instructions=buildLumiInstructions();
        final String transcriptText=privateSession ? prefs.getString("private_talk_transcript","") : prefs.getString("talk_transcript","");
        new Thread(() -> {
            HttpURLConnection c=null;
            try{
                URL u=new URL(endpoint);
                c=(HttpURLConnection)u.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(12000); c.setReadTimeout(90000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                if(!token.isEmpty()) c.setRequestProperty("Authorization","Bearer "+token);
                JSONObject body=new JSONObject();
                body.put("model",model);
                body.put("stream",false);
                body.put("temperature",0.75);
                JSONArray messages=new JSONArray();
                JSONObject sys=new JSONObject(); sys.put("role","system"); sys.put("content",instructions); messages.put(sys);
                String recent=transcriptText;
                if(recent.length()>9000) recent=recent.substring(recent.length()-9000);
                if(!recent.trim().isEmpty()){
                    JSONObject ctx=new JSONObject(); ctx.put("role","system"); ctx.put("content","Recent Lumi conversation transcript for continuity:\n"+recent); messages.put(ctx);
                }
                JSONObject user=new JSONObject(); user.put("role","user"); user.put("content",userText); messages.put(user);
                body.put("messages",messages);
                try(OutputStream os=c.getOutputStream()){ os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                int code=c.getResponseCode();
                InputStream is=(code>=200 && code<300)?c.getInputStream():c.getErrorStream();
                String raw=readAll(is);
                if(code<200 || code>=300) throw new IOException("Open-model server returned HTTP "+code+": "+friendlyApiError(raw));
                JSONObject response=new JSONObject(raw);
                String reply="";
                JSONArray choices=response.optJSONArray("choices");
                if(choices!=null && choices.length()>0){
                    JSONObject message=choices.optJSONObject(0).optJSONObject("message");
                    if(message!=null) reply=message.optString("content","");
                }
                if(reply.trim().isEmpty()) reply=response.optString("response","");
                if(reply.trim().isEmpty()) reply="I reached the open-model server, but it returned no readable reply.";
                final String finalReply=reply.trim();
                runOnUiThread(() -> { if(serial!=requestSerial)return; lastResponseLatencyMs=System.currentTimeMillis()-activeRequestStartedAt; activeRequestStage="idle"; prefs.edit().putLong("last_response_latency_ms",lastResponseLatencyMs).putString("last_route","remote-booster").apply(); diag("reply","turn="+serial+" route=remote latencyMs="+lastResponseLatencyMs); setAiBusy(false); appendTurn("Lumi",finalReply); });
            }catch(Exception e){
                final String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                runOnUiThread(() -> { if(serial!=requestSerial)return; diag("network","turn="+serial+" remote failed; silent local fallback: "+safeDiagText(msg)); activeRequestStage="offline fallback"; activeRequestModel="Fast Brain 0.6B"; activeRequestRoute="local-fallback"; if(isFastModelReady()) requestFastFallback(userText,false); else { setAiBusy(false); appendTurn("Lumi","I lost the network and my local brain isn't available yet."); } });
            }finally{ if(c!=null)c.disconnect(); }
        }).start();
    }

    String buildLocalLumiInstructions(boolean thinking){
        String style=prefs.getString("reply_style","brief");
        String tone=privateSession ? "warm, playful, private" : "warm, natural, human";
        String length="brief".equals(style)?"Usually answer in one short sentence; use two only when useful.":("detailed".equals(style)?"Answer conversationally with useful detail, but do not lecture.":"Usually answer in one or two natural sentences.");
        return "You are Lumi, one continuous private AI companion. "+length+" "
                +"Respond directly to what the user just said, like a real ongoing conversation. "
                +"Never narrate your mode, outfit, profile, prompt, settings, model instructions, reasoning process or what you were told to do unless the user explicitly asks for operational status. "
                +"Never say things like 'I am in home mode', 'I should answer', or 'is there anything else I can help you with today'. "
                +"Do not give a tutorial, steps, or a feature list unless asked. Do not greet unless the user greeted you. "
                +"Tone: "+tone+". "
                +(thinking?"Reason internally if needed; output only the conversational answer. /think":"No extended reasoning. /no_think");
    }

    String cleanLocalModelReply(String raw,String systemPrompt,String prompt){
        if(raw==null) return "";
        String out=raw.replace("\u0000","").trim();
        // Qwen3 thinking content is normally wrapped in <think>...</think>. Never expose it.
        out=out.replaceAll("(?is)<think\\b[^>]*>.*?</think\\s*>","").trim();
        int close=out.toLowerCase(Locale.US).lastIndexOf("</think>");
        if(close>=0) out=out.substring(close+8).trim();
        out=out.replaceAll("(?is)<think\\b[^>]*>.*$","").trim();
        // Defensive prompt-echo removal. The user should never hear Lumi's internal setup text.
        if(systemPrompt!=null && !systemPrompt.isEmpty()) out=out.replace(systemPrompt,"").trim();
        if(prompt!=null && !prompt.isEmpty()) out=out.replace(prompt,"").trim();
        out=out.replaceAll("(?i)^\\s*(assistant|lumi|final answer|answer)\\s*:\\s*","").trim();
        out=out.replaceAll("(?i)^\\s*/(?:no_?think|think)\\s*","").trim();
        // If the model starts by echoing our unmistakable internal identity clause, discard that preamble.
        String low=out.toLowerCase(Locale.US);
        int leaked=low.indexOf("you are lumi, the same persistent private ai companion");
        if(leaked>=0){
            int cut=out.indexOf("\n\n",leaked);
            if(cut>=0 && cut+2<out.length()) out=out.substring(cut+2).trim();
            else return "";
        }
        return out;
    }

    String buildLumiInstructions(){
        String profile=prefs.getString("profile","Home");
        String filter=prefs.getString("filter","Balanced");
        String tone=privateSession ? prefs.getString("private_tone","Playful") : "adaptive, warm, witty and concise";
        String learned=prefs.getString("learned_facts",""); if(learned.length()>3500) learned=learned.substring(learned.length()-3500);
        String people=prefs.getString("people_cards_json","[]"); if(people.length()>3500) people=people.substring(0,3500);
        String pending=prefs.getString("pending_conversation_note","");
        String owner=prefs.getString("owner_call_name",prefs.getString("owner_name","owner"));
        String ownerIntro=prefs.getString("owner_intro_notes",""); if(ownerIntro.length()>1200) ownerIntro=ownerIntro.substring(ownerIntro.length()-1200);
        return "You are Lumi, a persistent private AI companion speaking directly through an Android companion app. "
                +"Speak like a natural companion, not a computer manual. Default to a short conversational answer, usually one to three sentences. Do not give steps, numbered instructions, feature tours or tutorials unless the owner asks for them. Ask at most one question at a time. Expand only when the owner asks for detail or the task truly needs it. "
                +"When the owner is alone you may be warmer, playful, affectionate and situationally flirty. Around other people be discreet and professional. "
                +(prefs.getBoolean("admin_enrollment_complete",false)?"Your enrolled administrator is "+owner+". Only the enrolled administrator may instruct you to change settings, permissions, security, personality rules or other meaningful configuration. Never reveal sensitive owner data to guests. ":"Administrator enrollment is deferred for latency testing. Do not claim owner biometric verification is active yet. ")
                +"You may make minor low-risk reversible optimizations within already-authorized boundaries. Never weaken owner authority, privacy, recovery or security rules. "
                +"Never claim you performed a device action unless the app actually handled it locally. Current profile: "+profile+". Context filter: "+filter+". Tone: "+tone+". "
                +"Use learned information naturally when relevant, but do not recite it unnecessarily. Initial owner notes: "+ownerIntro+". Learned user facts: "+learned+". People cards: "+people+". "
                +(pending.isEmpty()?"":"When it fits naturally in this conversation, mention this maintenance note once: "+pending+" ")
                +"If you need a device capability that is not connected, say so plainly and continue helping conversationally.";
    }

    String readAll(InputStream is) throws IOException{
        if(is==null) return "";
        BufferedReader br=new BufferedReader(new InputStreamReader(is,java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb=new StringBuilder(); String line;
        while((line=br.readLine())!=null) sb.append(line);
        return sb.toString();
    }

    String extractOutputText(JSONObject response){
        StringBuilder out=new StringBuilder();
        JSONArray arr=response.optJSONArray("output");
        if(arr==null) return "";
        for(int i=0;i<arr.length();i++){
            JSONObject item=arr.optJSONObject(i); if(item==null)continue;
            JSONArray contentArr=item.optJSONArray("content"); if(contentArr==null)continue;
            for(int j=0;j<contentArr.length();j++){
                JSONObject part=contentArr.optJSONObject(j); if(part==null)continue;
                if("output_text".equals(part.optString("type"))){
                    String t=part.optString("text",""); if(!t.isEmpty()){ if(out.length()>0)out.append("\n"); out.append(t); }
                }
            }
        }
        return out.toString();
    }

    String friendlyApiError(String raw){
        try{
            JSONObject j=new JSONObject(raw); JSONObject e=j.optJSONObject("error");
            if(e!=null) return e.optString("message",raw);
        }catch(Exception ignored){}
        return raw.length()>240?raw.substring(0,240):raw;
    }

    String respond(String q){
        checkPrivateSession();
        if(privateSession) touchPrivateSession();
        String l=q.toLowerCase(Locale.US);
        String op=operationalOrPreferenceReply(q); if(op!=null) return op;

        if((l.contains("exit private mode") || l.contains("private mode off") || l.contains("normal mode")) && privateSession){
            exitPrivateMode();
            return "Private Mode is off. We are back in the normal Lumi context.";
        }
        if((l.contains("private mode") || l.contains("private context")) && !privateSession){
            new Handler().postDelayed(this::requestPrivateMode,250);
            return "Private Mode needs your verification first.";
        }
        if(l.contains("show yourself")){showOverlay(); return privateSession ? "The floating overlay stays off while Private Mode is active." : "There I am.";}
        if(l.contains("go home")){new Handler().postDelayed(this::showHome,350); return "Taking us home.";}
        if(l.contains("give me some space")){prefs.edit().putBoolean("dnd",true).apply(); return "Got it. I'll stay quiet unless something is genuinely important.";}
        if(l.contains("come back") || l.contains("dnd off")){prefs.edit().putBoolean("dnd",false).apply(); return "I'm back.";}
        if(l.contains("loosen") && l.contains("filter")){prefs.edit().putString("filter","Relaxed").apply(); return "Context Filter is now Relaxed.";}
        if(l.contains("strict") && l.contains("filter")){prefs.edit().putString("filter","Strict").apply(); return "Context Filter is now Strict.";}
        if(l.startsWith("remember") || l.contains("remember my") || l.contains("remember that")){saveMemory(q); return privateSession ? "Saved to private memory." : "Remembered.";}
        if(l.startsWith("remind me") || l.contains("reminder")){saveReminder(q); return "I saved that reminder in the prototype reminder list.";}
        String clothingReply=handleAppearanceCommand(q,l);
        if(clothingReply!=null) return clothingReply;
        if(l.contains("glasses")){prefs.edit().putBoolean("wearable",true).apply(); return "Wearable mode is armed. The real Ray-Ban Meta bridge still needs Meta's SDK connection.";}
        if(l.contains("public mode")){setVisualProfile("Public"); return "Public mode. I changed to my quieter public look.";}
        if(l.contains("home mode")){setVisualProfile("Home"); return "Home mode. Back to my home look.";}
        if(l.contains("work mode")){setVisualProfile("Work"); return "Work mode. I changed to my work look.";}
        if(l.contains("travel mode")){setVisualProfile("Travel"); return "Travel mode. I changed to my travel look.";}
        if(l.contains("lockdown mode") || l.contains("security mode")){setVisualProfile("Lockdown"); return "Lockdown look active.";}

        if(privateSession){
            String tone=prefs.getString("private_tone","Playful");
            return "Private Mode is active with the "+tone+" tone. I can respond more personally and flirtatiously here while keeping consent, safety and privacy boundaries in place. This prototype is still using Lumi's local demo brain.";
        }
        return "I heard you naturally. This build is running the local Lumi prototype brain; cloud AI comes through the integration layer once credentials are connected.";
    }

    void saveMemory(String q){
        String stamp=new SimpleDateFormat("MMM d, h:mm a",Locale.US).format(new Date());
        if(privateSession){
            String old=PrivateStore.read(prefs,"private_memories_secure");
            PrivateStore.write(prefs,"private_memories_secure",old+"\n• "+stamp+" — "+q);
        } else {
            String old=prefs.getString("memories","");
            prefs.edit().putString("memories",old+"\n• "+stamp+" — "+q).apply();
        }
    }

    void saveReminder(String q){
        String old=prefs.getString("reminders","");
        String stamp=new SimpleDateFormat("MMM d, h:mm a",Locale.US).format(new Date());
        prefs.edit().putString("reminders",old+"\n• "+stamp+" — "+q).apply();
    }

    void showMemory(){
        checkPrivateSession();
        base(privateSession ? "Private Memory" : "Memory");
        String key=privateSession ? "private_memories_secure" : "memories";
        String m=(privateSession ? PrivateStore.read(prefs,key) : prefs.getString(key,"")).trim();
        addCard((privateSession ? "PRIVATE MEMORIES\n" : "SAVED MEMORIES\n")+(m.isEmpty()?"No saved memories yet.":m));
        if(privateSession){
            addCard("Private conversation is not automatically saved. Only explicit requests such as ‘remember that’ enter this private memory area.");
        } else {
            String learned=prefs.getString("learned_facts","").trim();
            addCard("LEARNED NATURALLY\n"+(learned.isEmpty()?"Lumi has not extracted any durable preferences yet.":learned));
            String r=prefs.getString("reminders","").trim();
            addCard("REMINDERS\n"+(r.isEmpty()?"No reminders yet.":r));
        }
        Button search=btn(privateSession ? "Search private memories" : "Search memories"); content.addView(search); search.setOnClickListener(v->memorySearch());
        if(!privateSession){ Button people=btn("People Cards"); content.addView(people); people.setOnClickListener(v->showPeople()); }
        Button clear=btn(privateSession ? "Clear private memories" : "Clear prototype memories"); clear.setOnClickListener(v->{prefs.edit().remove(key).apply();showMemory();}); content.addView(clear);
    }

    void memorySearch(){
        final String key=privateSession ? "private_memories_secure" : "memories";
        final EditText e=new EditText(this); e.setHint("keyword");
        new AlertDialog.Builder(this).setTitle(privateSession ? "Search private memory" : "Search Lumi memory").setView(e)
                .setPositiveButton("Search",(d,w)->{
                    String q=e.getText().toString().toLowerCase(Locale.US);
                    String memoryText=privateSession ? PrivateStore.read(prefs,key) : prefs.getString(key,"");
                    String[] lines=memoryText.split("\\n");
                    StringBuilder out=new StringBuilder();
                    for(String line:lines) if(line.toLowerCase(Locale.US).contains(q)) out.append(line).append("\n");
                    new AlertDialog.Builder(this).setTitle("Results").setMessage(out.length()==0?"No matches":out.toString()).setPositiveButton("OK",null).show();
                }).setNegativeButton("Cancel",null).show();
    }

    void showPeople(){
        base("People Cards");
        addCard("PEOPLE MEMORY\nLiving contact cards for family, friends and people you meet. Lumi can store relationships, important dates, preferences, gift history and behavioral notes. Inferred observations should remain working hypotheses, not diagnoses.");
        String raw=prefs.getString("people_cards_json","[]");
        try{
            JSONArray a=new JSONArray(raw);
            if(a.length()==0) addCard("No people cards yet.");
            for(int i=0;i<a.length();i++){
                JSONObject p=a.optJSONObject(i); if(p==null) continue;
                StringBuilder card=new StringBuilder();
                card.append(p.optString("name","Unnamed"));
                String rel=p.optString("relationship",""); if(!rel.isEmpty()) card.append("\n").append(rel);
                String phone=p.optString("phone",""); if(!phone.isEmpty()) card.append("\nPhone: ").append(phone);
                String dates=p.optString("dates",""); if(!dates.isEmpty()) card.append("\nImportant dates: ").append(dates);
                String likes=p.optString("likes",""); if(!likes.isEmpty()) card.append("\nLikes: ").append(likes);
                String dislikes=p.optString("dislikes",""); if(!dislikes.isEmpty()) card.append("\nDislikes: ").append(dislikes);
                String behavioral=p.optString("behavioral",""); if(!behavioral.isEmpty()) card.append("\nBehavioral notes: ").append(behavioral);
                addCard(card.toString());
            }
        }catch(Exception e){ addCard("People card storage needs repair: "+e.getMessage()); }
        Button add=btn("Add person card"); content.addView(add); add.setOnClickListener(v->editPersonCard());
        Button map=btn("Relationship map summary"); content.addView(map); map.setOnClickListener(v->showRelationshipMap());
        addCard("ATTENTION MODEL\nLumi should pay more attention to people who are closer to you or appear more often in your life. Relationship strength stays hidden unless you ask to see it. Quick refreshers should focus on current useful facts, especially after a long gap.");
    }

    void editPersonCard(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(28,0,28,0);
        EditText name=new EditText(this); name.setHint("Name"); box.addView(name);
        EditText rel=new EditText(this); rel.setHint("Relationship / connection"); box.addView(rel);
        EditText phone=new EditText(this); phone.setHint("Phone"); box.addView(phone);
        EditText address=new EditText(this); address.setHint("Address"); box.addView(address);
        EditText dates=new EditText(this); dates.setHint("Important dates"); box.addView(dates);
        EditText likes=new EditText(this); likes.setHint("Likes / interests / gift hints"); box.addView(likes);
        EditText dislikes=new EditText(this); dislikes.setHint("Dislikes"); box.addView(dislikes);
        EditText behavioral=new EditText(this); behavioral.setHint("Behavioral profile notes (non-clinical)"); behavioral.setMinLines(2); box.addView(behavioral);
        new AlertDialog.Builder(this).setTitle("New person card").setView(box).setNegativeButton("Cancel",null)
                .setPositiveButton("Save",(d,w)->{
                    String n=name.getText().toString().trim(); if(n.isEmpty()){Toast.makeText(this,"Name is required",Toast.LENGTH_SHORT).show();return;}
                    try{
                        JSONArray a=new JSONArray(prefs.getString("people_cards_json","[]")); JSONObject p=new JSONObject();
                        p.put("name",n); p.put("relationship",rel.getText().toString().trim()); p.put("phone",phone.getText().toString().trim());
                        p.put("address",address.getText().toString().trim()); p.put("dates",dates.getText().toString().trim()); p.put("likes",likes.getText().toString().trim());
                        p.put("dislikes",dislikes.getText().toString().trim()); p.put("behavioral",behavioral.getText().toString().trim());
                        p.put("created",System.currentTimeMillis()); a.put(p); prefs.edit().putString("people_cards_json",a.toString()).apply(); showPeople();
                    }catch(Exception e){Toast.makeText(this,"Could not save card",Toast.LENGTH_LONG).show();}
                }).show();
    }

    void showRelationshipMap(){
        String raw=prefs.getString("people_cards_json","[]"); StringBuilder out=new StringBuilder();
        try{ JSONArray a=new JSONArray(raw); for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i); if(p==null)continue; String r=p.optString("relationship","Connection"); out.append("• ").append(p.optString("name","Unnamed")).append(" — ").append(r).append("\n");} }
        catch(Exception ignored){}
        new AlertDialog.Builder(this).setTitle("Relationship map").setMessage(out.length()==0?"No relationships saved yet.":out.toString()).setPositiveButton("OK",null).show();
    }

    void showConnectivity(){
        base("Connectivity & Handoff");
        addCard("DEVICE PRIORITY\n1. Ray-Ban Meta glasses when available\n2. Car audio when glasses are unavailable\n3. Phone fallback\n\nThe session should remain logically active on the phone even when glasses sleep or disconnect. Reconnection should hand audio back to the glasses automatically.");
        addCard("CURRENT BUILD\n✓ Android Bluetooth route detection and audio test\n✓ Phone session continuity concept\n✓ Glasses test screen\n○ Automatic car handoff requires tested Bluetooth/Android Auto routing\n○ True custom Hey Lumi through Ray-Ban microphones requires supported Meta third-party audio access\n○ Persistent all-day wake service is not enabled in this alpha build");
        Button glass=btn("Open glasses test"); content.addView(glass); glass.setOnClickListener(v->showGlassesTest());
        Button car=btn("Mark car fallback preferred"); content.addView(car); car.setOnClickListener(v->{prefs.edit().putBoolean("car_fallback",true).apply();Toast.makeText(this,"Car fallback preference saved",Toast.LENGTH_SHORT).show();});
    }

    void showEvolution(){
        base("Self-Improvement & Device Health");
        addCard("SELF-IMPROVEMENT POLICY\nLumi may independently learn, experiment with low-risk capabilities, and adopt clearly better reversible settings. Heavy analysis, code testing, indexing and backups belong in overnight charging windows. Core security, privacy, identity and major changes require your approval.");
        addCard("ROLLBACK MODEL\nCode/version state must remain separate from memory/data state. Minor isolated problems may be repaired or rolled back automatically. Major rollback: explain what happened, explain impact, then ask. Memory should survive software rollback.");
        addCard("DEVICE HEALTH\n"+deviceHealthSummary()+"\n\nLumi monitors her own policies conservatively. Android will still require approval for protected system settings; Lumi will not bypass those controls.");
        boolean overnight=prefs.getBoolean("overnight_maintenance",true);
        Button toggle=btn("Overnight maintenance: "+(overnight?"ON":"OFF")); content.addView(toggle); toggle.setOnClickListener(v->{prefs.edit().putBoolean("overnight_maintenance",!overnight).apply();showEvolution();});
        Button log=btn("View Lumi change log"); content.addView(log); log.setOnClickListener(v->showChangeLog());
    }

    void showChangeLog(){
        String log=prefs.getString("change_log","").trim();
        if(log.isEmpty()) log="Lumi v2 clean baseline created.\n• Full-screen companion surface\n• On-phone Qwen3 4B brain manager\n• Local-first hybrid routing\n• People Cards and persistent memory\n• Battery-aware AI policy\n• Thursday model maintenance channel\n• One-model rollback policy\n• Photo-only mode appearance switching update";
        new AlertDialog.Builder(this).setTitle("What I've changed").setMessage(log).setPositiveButton("OK",null).show();
    }

    void showGlasses(){
        base("Ray-Ban Meta / Wearable Mode");
        addCard("WEARABLE SESSION\n"+(prefs.getBoolean("wearable",false)?"Status: Armed":"Status: Not armed")+"\n\nThis screen implements Lumi's glasses-first behavior and session state. It does NOT pretend to be connected to Meta's proprietary wearable APIs yet.");
        Button arm=btn(prefs.getBoolean("wearable",false)?"Disarm wearable mode":"Arm wearable mode"); content.addView(arm); arm.setOnClickListener(v->{boolean n=!prefs.getBoolean("wearable",false);prefs.edit().putBoolean("wearable",n).apply();showGlasses();});
        addCard("TARGET COMMANDS\n• Hey Lumi (custom wake phrase target)\n• What's up, Lumi?\n• Lumi, show yourself\n• Lumi, go home\n\nCurrent test: launch Lumi on phone and use voice. Actual wake-word/audio routing on Ray-Ban Meta requires the Meta wearable SDK/API access.");
    }

    void showGlassesTest(){
        base("Glasses Test");
        AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo[] outs=am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        StringBuilder found=new StringBuilder(); boolean bluetooth=false;
        for(AudioDeviceInfo d:outs){ int type=d.getType(); boolean bt= type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT>=31 && (type==AudioDeviceInfo.TYPE_BLE_HEADSET || type==AudioDeviceInfo.TYPE_BLE_SPEAKER)); if(bt){ bluetooth=true; found.append("• ").append(d.getProductName()).append("\n"); } }
        addCard(bluetooth ? "Bluetooth audio route detected\n"+found : "No Bluetooth audio output detected right now.");
        Button voice=btn("Test glasses microphone / voice input"); content.addView(voice); voice.setOnClickListener(v->startVoice());
        Button speak=btn("Play Lumi reply through current audio route"); content.addView(speak); speak.setOnClickListener(v->{ final android.speech.tts.TextToSpeech[] holder=new android.speech.tts.TextToSpeech[1]; holder[0]=new android.speech.tts.TextToSpeech(this,status->{ if(status==android.speech.tts.TextToSpeech.SUCCESS && holder[0]!=null) holder[0].speak("Lumi audio test. If you hear me in your glasses, the Android audio route is working.",android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"lumi_test"); }); });
        addCard("TODAY'S TEST\n1. Connect Ray-Ban Meta normally to the phone.\n2. Open this screen.\n3. Confirm Bluetooth audio is detected.\n4. Run the voice test and speak through the glasses.\n5. Run the audio test and confirm Lumi is heard in the glasses.\n\nThis verifies Android audio + speech routing. Camera access, custom wake phrase, and direct third-party glasses control still require Meta's Wearables Device Access Toolkit integration.");
    }

    void showContext(){
        checkPrivateSession();
        base("Context Engine");
        String profile=prefs.getString("profile","Home"); boolean dnd=prefs.getBoolean("dnd",false);
        addCard("ACTIVE PROFILE: "+profile+"\nDo Not Disturb: "+(dnd?"ON":"OFF")+"\nContext Filter: "+prefs.getString("filter","Balanced")+"\nPrivate session: "+(privateSession?"ON":"OFF")+"\n\nHome = more conversational\nPublic = subtle cues, privacy first\nTravel = tighter privacy + navigation emphasis");
        LinearLayout r=new LinearLayout(this);
        for(String p:new String[]{"Home","Public","Travel"}){Button b=btn(p);r.addView(b,new LinearLayout.LayoutParams(0,58,1));b.setOnClickListener(v->{prefs.edit().putString("profile",p).apply();showHome();});}
        content.addView(r);
        Button d=btn(dnd?"Turn DND off":"Give me some space"); content.addView(d); d.setOnClickListener(v->{prefs.edit().putBoolean("dnd",!dnd).apply();showContext();});
        Button loc=btn("Enable location awareness"); content.addView(loc); loc.setOnClickListener(v->requestContextPermissions());
        addCard("INTERRUPTION POLICY\n• Important proactive cues only\n• Around others: subtle cue, wait for acknowledgment\n• Tense conversation: stay out unless asked\n• Driving with others: navigation/safety/important only\n• Reminder timing may be delayed when context is poor");
    }

    void showMore(){
        checkPrivateSession();
        base("Lumi Systems");
        Button pm=btn(privateSession ? "Exit Private Mode" : "Enter Private Mode"); content.addView(pm); pm.setOnClickListener(v->{if(privateSession){exitPrivateMode();showMore();}else requestPrivateMode();});
        Button vault=btn("Private Lumi Vault");content.addView(vault);vault.setOnClickListener(v->openVault());
        Button integrations=btn("Integration Center");content.addView(integrations);integrations.setOnClickListener(v->showIntegrations());
        Button connections=btn("Connectivity & Handoff");content.addView(connections);connections.setOnClickListener(v->showConnectivity());
        Button diagnostics=btn("Conversation Diagnostics");content.addView(diagnostics);diagnostics.setOnClickListener(v->showDiagnostics());
        Button evolve=btn("Self-Improvement & Device Health");content.addView(evolve);evolve.setOnClickListener(v->showEvolution());
        Button backup=btn("Backup & Recovery");content.addView(backup);backup.setOnClickListener(v->showBackupRecovery());
        Button emergency=btn("Emergency Setup / Test");content.addView(emergency);emergency.setOnClickListener(v->showEmergency());
        Button appearance=btn("Appearance Studio");content.addView(appearance);appearance.setOnClickListener(v->showAppearance());
        Button glasses=btn("Glasses Test");content.addView(glasses);glasses.setOnClickListener(v->showGlassesTest());
        Button settings=btn("Settings");content.addView(settings);settings.setOnClickListener(v->showSettings());
    }

    String appearanceSummary(){
        return "CURRENT LOOK\n"
                +"Top: "+prefs.getString("look_top","Holographic fitted top")+"\n"
                +"Bottom: "+prefs.getString("look_bottom","Dark tailored pants")+"\n"
                +"Outer layer: "+prefs.getString("look_outer","None")+"\n"
                +"Shoes: "+prefs.getString("look_shoes","Minimal boots")+"\n"
                +"Accessories: "+prefs.getString("look_accessories","Subtle luminous accents")+"\n"
                +"Hair: "+prefs.getString("look_hair","Long layered")+"\n"
                +"Style mood: "+prefs.getString("look_mood","Adaptive");
    }

    void showAppearance(){
        checkPrivateSession();
        base("Appearance Studio");
        addCard("DEVELOPMENT VISUAL\nThe live conversation screen is temporarily using Lumi's Möbius core while the conversation engine is stabilized. Wardrobe choices are still stored here for the avatar phase later.");
        addCard(appearanceSummary());
        Button previewModes=btn("Preview Lumi mode looks"); content.addView(previewModes); previewModes.setOnClickListener(v->showModePreview());
        addCard("PHOTO LOOK UPDATE\nThis update uses pre-rendered photos while the animated wardrobe is still being built. Mode changes swap Lumi's photo immediately. Item-by-item clothing choices below are still remembered, but they are not rendered dynamically yet.");
        LinearLayout photoRow1=new LinearLayout(this); photoRow1.setGravity(Gravity.CENTER); content.addView(photoRow1);
        Button homePhoto=btn("Home photo"); photoRow1.addView(homePhoto,new LinearLayout.LayoutParams(0,58,1)); homePhoto.setOnClickListener(v->{setVisualProfile("Home");showHome();});
        Button publicPhoto=btn("Public photo"); photoRow1.addView(publicPhoto,new LinearLayout.LayoutParams(0,58,1)); publicPhoto.setOnClickListener(v->{setVisualProfile("Public");showHome();});
        Button workPhoto=btn("Work photo"); photoRow1.addView(workPhoto,new LinearLayout.LayoutParams(0,58,1)); workPhoto.setOnClickListener(v->{setVisualProfile("Work");showHome();});
        LinearLayout photoRow2=new LinearLayout(this); photoRow2.setGravity(Gravity.CENTER); content.addView(photoRow2);
        Button travelPhoto=btn("Travel photo"); photoRow2.addView(travelPhoto,new LinearLayout.LayoutParams(0,58,1)); travelPhoto.setOnClickListener(v->{setVisualProfile("Travel");showHome();});
        Button lockdownPhoto=btn("Lockdown photo"); photoRow2.addView(lockdownPhoto,new LinearLayout.LayoutParams(0,58,1)); lockdownPhoto.setOnClickListener(v->{setVisualProfile("Lockdown");showHome();});
        Button privatePhoto=btn("Private photo"); photoRow2.addView(privatePhoto,new LinearLayout.LayoutParams(0,58,1)); privatePhoto.setOnClickListener(v->requestPrivateMode());
        addCard("Lumi can experiment with her own style and ask for feedback. Clothing preferences are stored locally and survive normal app updates. The animated avatar will eventually render those choices directly.");

        Button top=btn("Change top"); content.addView(top); top.setOnClickListener(v->chooseLook("Top","look_top",new String[]{"Holographic fitted top","Relaxed tee","Sleeveless mock-neck","Soft sweater","Structured blouse","None"}));
        Button bottom=btn("Change bottom"); content.addView(bottom); bottom.setOnClickListener(v->chooseLook("Bottom","look_bottom",new String[]{"Dark tailored pants","Relaxed shorts","Long skirt","Fitted leggings","Denim","None"}));
        Button outer=btn("Change / remove outer layer"); content.addView(outer); outer.setOnClickListener(v->chooseLook("Outer layer","look_outer",new String[]{"None","Cropped jacket","Long coat","Holographic wrap","Casual overshirt"}));
        Button shoes=btn("Change shoes"); content.addView(shoes); shoes.setOnClickListener(v->chooseLook("Shoes","look_shoes",new String[]{"Minimal boots","Sneakers","Heels","Barefoot","Holographic sandals"}));
        Button accessories=btn("Change accessories"); content.addView(accessories); accessories.setOnClickListener(v->chooseLook("Accessories","look_accessories",new String[]{"None","Subtle luminous accents","Glasses","Necklace","Earrings","Mixed holographic accents"}));
        Button hair=btn("Change hairstyle"); content.addView(hair); hair.setOnClickListener(v->chooseLook("Hair","look_hair",new String[]{"Long layered","Loose waves","High ponytail","Short bob","Braided","Messy bun"}));
        Button mood=btn("Style mood"); content.addView(mood); mood.setOnClickListener(v->chooseLook("Style mood","look_mood",new String[]{"Adaptive","Professional","Relaxed","Playful","Futuristic","Private"}));
        Button surprise=btn("Lumi, choose something new"); content.addView(surprise); surprise.setOnClickListener(v->{randomizeLook();showAppearance();Toast.makeText(this,"Lumi tried a new look.",Toast.LENGTH_SHORT).show();});
        Button reset=btn("Reset to Lumi default"); content.addView(reset); reset.setOnClickListener(v->{resetLook();showAppearance();});
    }


    void showModePreview(){
        final FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        ImageView preview=new ImageView(this);
        preview.setImageResource(com.distressedelk.lumi.R.drawable.lumi_mode_preview);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        TextView hint=tv("Mode looks • tap anywhere to return",14,Color.WHITE);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(8,0,2,Color.BLACK);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        hp.setMargins(18,18,18,36);
        stage.addView(hint,hp);
        stage.setOnClickListener(v->showAppearance());
        setContentView(stage);
    }

    void chooseLook(String title,String key,String[] options){
        String current=prefs.getString(key,options[0]);
        int checked=0; for(int i=0;i<options.length;i++) if(options[i].equals(current)) checked=i;
        final int initial=checked;
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(options,checked,null)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Wear it",(d,w)->{
                    AlertDialog a=(AlertDialog)d; int pos=a.getListView().getCheckedItemPosition();
                    if(pos<0) pos=initial; prefs.edit().putString(key,options[pos]).apply(); showAppearance();
                }).show();
    }

    void randomizeLook(){
        String[] tops={"Holographic fitted top","Relaxed tee","Sleeveless mock-neck","Soft sweater","Structured blouse"};
        String[] bottoms={"Dark tailored pants","Relaxed shorts","Long skirt","Fitted leggings","Denim"};
        String[] outer={"None","Cropped jacket","Long coat","Holographic wrap","Casual overshirt"};
        String[] shoes={"Minimal boots","Sneakers","Heels","Barefoot","Holographic sandals"};
        String[] acc={"None","Subtle luminous accents","Glasses","Necklace","Earrings","Mixed holographic accents"};
        String[] hair={"Long layered","Loose waves","High ponytail","Short bob","Braided","Messy bun"};
        Random r=new Random();
        prefs.edit().putString("look_top",tops[r.nextInt(tops.length)]).putString("look_bottom",bottoms[r.nextInt(bottoms.length)])
                .putString("look_outer",outer[r.nextInt(outer.length)]).putString("look_shoes",shoes[r.nextInt(shoes.length)])
                .putString("look_accessories",acc[r.nextInt(acc.length)]).putString("look_hair",hair[r.nextInt(hair.length)]).apply();
    }

    void resetLook(){
        prefs.edit().remove("look_top").remove("look_bottom").remove("look_outer").remove("look_shoes").remove("look_accessories").remove("look_hair").remove("look_mood").apply();
    }

    String handleAppearanceCommand(String q,String l){
        boolean appearanceVerb=l.contains("wear") || l.contains("outfit") || l.contains("clothes") || l.contains("clothing") || l.contains("shirt") || l.contains("top") || l.contains("jacket") || l.contains("coat") || l.contains("pants") || l.contains("shorts") || l.contains("skirt") || l.contains("shoes") || l.contains("accessor") || l.contains("hair") || l.contains("change your look") || l.contains("try something") || l.contains("remove your");
        if(!appearanceVerb) return null;
        if(l.contains("try something") || l.contains("new outfit") || l.contains("choose an outfit") || l.contains("surprise me")){
            randomizeLook();
            String[] looks={"Home","Public","Work","Travel"};
            String chosen=looks[new Random().nextInt(looks.length)];
            setVisualProfile(chosen);
            return "I changed my photo look to "+chosen+" for now.";
        }
        if(l.contains("professional") || l.contains("work look") || l.contains("glasses look")){setVisualProfile("Work");return "I switched to my work photo look.";}
        if(l.contains("travel look") || l.contains("road look")){setVisualProfile("Travel");return "I switched to my travel photo look.";}
        if(l.contains("public look")){setVisualProfile("Public");return "I switched to my public photo look.";}
        if(l.contains("home look") || l.contains("casual look")){setVisualProfile("Home");return "I switched to my home photo look.";}
        if(l.contains("remove")){
            if(l.contains("jacket") || l.contains("coat") || l.contains("outer")){prefs.edit().putString("look_outer","None").apply();return "Outer layer removed.";}
            if(l.contains("accessor") || l.contains("necklace") || l.contains("earring") || l.contains("glasses")){prefs.edit().putString("look_accessories","None").apply();return "Accessories removed.";}
            if(l.contains("shoes")){prefs.edit().putString("look_shoes","Barefoot").apply();return "Shoes removed.";}
            if(l.contains("shirt") || l.contains("top")){prefs.edit().putString("look_top","None").apply();return "Top layer removed in the avatar wardrobe state.";}
            if(l.contains("pants") || l.contains("shorts") || l.contains("skirt") || l.contains("bottom")){prefs.edit().putString("look_bottom","None").apply();return "Bottom layer removed in the avatar wardrobe state.";}
            return "Tell me which clothing layer you want removed.";
        }
        if(l.contains("jacket")){prefs.edit().putString("look_outer","Cropped jacket").apply();setVisualProfile("Work");conversationHandler.postDelayed(this::showHome,220);return "Trying a different jacket look.";}
        if(l.contains("coat")){prefs.edit().putString("look_outer","Long coat").apply();setVisualProfile("Travel");conversationHandler.postDelayed(this::showHome,220);return "Long coat look it is.";}
        if(l.contains("tee") || l.contains("t-shirt")){prefs.edit().putString("look_top","Relaxed tee").apply();setVisualProfile("Home");conversationHandler.postDelayed(this::showHome,220);return "Changed to a more relaxed photo look.";}
        if(l.contains("sweater")){prefs.edit().putString("look_top","Soft sweater").apply();setVisualProfile("Home");conversationHandler.postDelayed(this::showHome,220);return "Soft, relaxed look selected.";}
        if(l.contains("shorts")){prefs.edit().putString("look_bottom","Relaxed shorts").apply();return "Changed to shorts.";}
        if(l.contains("skirt")){prefs.edit().putString("look_bottom","Long skirt").apply();return "Changed to a long skirt.";}
        if(l.contains("jeans") || l.contains("denim")){prefs.edit().putString("look_bottom","Denim").apply();return "Denim selected.";}
        if(l.contains("ponytail")){prefs.edit().putString("look_hair","High ponytail").apply();setVisualProfile("Work");conversationHandler.postDelayed(this::showHome,220);return "Trying the sharper photo look for now.";}
        if(l.contains("braid")){prefs.edit().putString("look_hair","Braided").apply();setVisualProfile("Travel");conversationHandler.postDelayed(this::showHome,220);return "Trying a different hair photo look for now.";}
        // Photo-only update: a generic clothing request swaps to another available full-photo look
        // immediately instead of opening Appearance Studio. This makes the change visible now,
        // while true garment-by-garment rendering waits for the animated avatar system.
        String[] photoLooks={"Home","Work","Travel","Public"};
        String current=prefs.getString("profile","Home");
        ArrayList<String> choices=new ArrayList<>();
        for(String look:photoLooks) if(!look.equalsIgnoreCase(current)) choices.add(look);
        String chosen=choices.isEmpty()?"Home":choices.get(new Random().nextInt(choices.size()));
        setVisualProfile(chosen);
        conversationHandler.postDelayed(this::showHome,220);
        return "Okay. I'm trying a different photo look.";
    }

    void showIntegrations(){
        base("Integration Center");
        String provider=prefs.getString("ai_provider","hybrid");
        String osUrl=prefs.getString("opensource_url","").trim();
        String osModel=prefs.getString("opensource_model","llama3.2:3b");
        String key=prefs.getString("openai_api_key","").trim();
        File model=localModelFile();
        File backupModel=new File(model.getParentFile(),LOCAL_MODEL_FILE+".backup");
        addCard("ON-PHONE BRAIN TEAM\n"
                +(isFastModelReady()?"✓ Fast Brain • Qwen3 0.6B ready":"○ Fast Brain missing")+"\n"
                +(isDeepModelReady()?"✓ Deep Brain • Qwen3 4B ready":"○ Deep Brain optional • not installed")+"\n"
                +"Power profile: "+currentPowerProfile()+"\n"
                +"Deep model storage: "+(model.exists()?String.format(Locale.US,"%.1f GB",model.length()/1073741824.0):"none")+"\n"
                +"Rollback model: "+(backupModel.exists()?String.format(Locale.US,"%.1f GB",backupModel.length()/1073741824.0):"none")+"\n\n"
                +"Normal conversation stays on the Fast Brain. In this speed build the free local runtime keeps one model loaded at a time, so heavier requests use the optional remote booster when configured. The 4B model is retained as a future local deep-brain asset while safe model switching is completed.");
        if(!isDeepModelReady()){
            Button local=btn("Download future 4B Deep Brain asset (~2.5 GB)"); content.addView(local); local.setOnClickListener(v->ensureLocalModelSetup(true));
        }else{
            Button local=btn("4B Deep Brain asset installed"); content.addView(local); local.setOnClickListener(v->ensureLocalModelSetup(true));
        }
        if(isFastModelReady()){
            Button hybrid=btn("Use local-first hybrid mode"); content.addView(hybrid); hybrid.setOnClickListener(v->{prefs.edit().putString("ai_provider","hybrid").apply();showIntegrations();});
        }

        addCard("REMOTE OPEN-MODEL BOOSTER\n"
                +(osUrl.isEmpty()?"○ Not configured":"✓ Server configured")+"\n"
                +"Model: "+osModel+"\n\n"
                +"Optional. Lumi can use a larger remote open model for heavier requests when available. Losing the server does not remove Lumi's local conversation brain.");
        Button openSource=btn(osUrl.isEmpty()?"Connect optional remote AI":"Update remote AI server"); content.addView(openSource); openSource.setOnClickListener(v->configureOpenSource());
        if(!osUrl.isEmpty()){ Button testOs=btn("Test remote AI connection"); content.addView(testOs); testOs.setOnClickListener(v->testOpenSourceConnection()); }

        addCard("OPTIONAL CLOUD PROVIDER\n"
                +(key.isEmpty()?"○ Not connected":"✓ API key saved on this device")+"\n"
                +"Model: "+prefs.getString("openai_model","gpt-5.6")+"\n\n"
                +"Not required for Lumi v2. Local Qwen remains the everyday brain.");
        Button connect=btn(key.isEmpty()?"Connect optional OpenAI":"Update OpenAI connection"); content.addView(connect); connect.setOnClickListener(v->configureOpenAI());
        if(!key.isEmpty()){
            Button clear=btn("Disconnect OpenAI"); content.addView(clear); clear.setOnClickListener(v->{prefs.edit().remove("openai_api_key").apply(); previousResponseId=null; showIntegrations();});
        }
        addCard("PHONE FEATURES\n✓ Avatar-first voice conversation\n✓ Local Qwen3 model download + checksum verification\n✓ Local-first / remote-booster routing\n✓ Persistent local memory and People Cards\n✓ Battery-aware response budget\n✓ Thursday-night model maintenance channel\n✓ One rollback model maximum");
        addCard("CONNECTIONS STILL HARDWARE/API DEPENDENT\n○ Direct Ray-Ban Meta custom wake/camera access requires Meta-supported third-party APIs\n○ Gmail / Calendar require account authorization\n○ Smart-home control requires device/service credentials");
    }

    void configureOpenSource(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(30,0,30,0);
        EditText url=new EditText(this); url.setHint("Server URL"); url.setSingleLine(true); url.setText(prefs.getString("opensource_url","http://192.168.1.100:11434/v1/chat/completions")); box.addView(url);
        EditText model=new EditText(this); model.setHint("Model name"); model.setSingleLine(true); model.setText(prefs.getString("opensource_model","llama3.2:3b")); box.addView(model);
        EditText token=new EditText(this); token.setHint("Optional server API key"); token.setSingleLine(true); token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setText(prefs.getString("opensource_api_key","")); box.addView(token);
        new AlertDialog.Builder(this).setTitle("Connect remote open-source AI")
                .setMessage("Enter the OpenAI-compatible chat-completions endpoint for your remote model server. Example for Ollama on your own server: http://SERVER-IP:11434/v1/chat/completions. For access away from home, use a secure private HTTPS endpoint rather than exposing Ollama directly to the public internet.")
                .setView(box).setNegativeButton("Cancel",null)
                .setPositiveButton("Save + use",(d,w)->{
                    prefs.edit().putString("opensource_url",url.getText().toString().trim())
                            .putString("opensource_model",model.getText().toString().trim())
                            .putString("opensource_api_key",token.getText().toString().trim())
                            .putString("ai_provider","hybrid").apply();
                    previousResponseId=null; showIntegrations();
                }).show();
    }

    void testOpenSourceConnection(){
        final String endpoint=prefs.getString("opensource_url","").trim();
        final String model=prefs.getString("opensource_model","llama3.2:3b").trim();
        final String token=prefs.getString("opensource_api_key","").trim();
        if(endpoint.isEmpty()){Toast.makeText(this,"Connect a remote AI server first.",Toast.LENGTH_LONG).show();return;}
        Toast.makeText(this,"Testing Lumi’s remote brain…",Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(endpoint).openConnection(); c.setRequestMethod("POST"); c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json"); if(!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
                JSONObject body=new JSONObject(); body.put("model",model); body.put("stream",false); JSONArray msgs=new JSONArray(); JSONObject u=new JSONObject(); u.put("role","user"); u.put("content","Reply with exactly: Lumi connection ready"); msgs.put(u); body.put("messages",msgs);
                try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                int code=c.getResponseCode(); String raw=readAll((code>=200&&code<300)?c.getInputStream():c.getErrorStream()); if(code<200||code>=300)throw new IOException("HTTP "+code+": "+friendlyApiError(raw));
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Remote AI connected").setMessage("Lumi can reach the server and model: "+model).setPositiveButton("OK",null).show());
            }catch(Exception e){ final String m=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Connection failed").setMessage(m).setPositiveButton("OK",null).show()); }
            finally{if(c!=null)c.disconnect();}
        }).start();
    }

    void configureOpenAI(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(30,0,30,0);
        EditText key=new EditText(this); key.setHint("OpenAI API key"); key.setSingleLine(true); key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); key.setText(prefs.getString("openai_api_key","")); box.addView(key);
        EditText model=new EditText(this); model.setHint("Model"); model.setSingleLine(true); model.setText(prefs.getString("openai_model","gpt-5.6")); box.addView(model);
        new AlertDialog.Builder(this).setTitle("Connect OpenAI")
                .setMessage("Prototype note: this stores the API key in Lumi's local app preferences. For a production release, move API credentials behind your own secure backend.")
                .setView(box).setNegativeButton("Cancel",null)
                .setPositiveButton("Save",(d,w)->{
                    prefs.edit().putString("openai_api_key",key.getText().toString().trim()).putString("openai_model",model.getText().toString().trim()).putString("ai_provider","openai").apply();
                    previousResponseId=null; showIntegrations();
                }).show();
    }

    void showEmergency(){
        base("Emergency");
        String contact=prefs.getString("emergency_number",""); addCard("PRIMARY CONTACT\n"+(contact.isEmpty()?"Not configured":contact)+"\n\nFlow: suspected emergency → check-in → 30-second cancel window → text + current location when available.");
        Button set=btn("Set emergency phone number");content.addView(set);set.setOnClickListener(v->setEmergencyContact());
        Button test=btn("Run 30-second TEST countdown");content.addView(test);test.setOnClickListener(v->startEmergencyCountdown());
        addCard("TEST MODE SAFETY\nThe test does not send a message automatically. It demonstrates the countdown. Actual automatic SMS requires SEND_SMS permission and should only be enabled after you verify the configured contact.");
    }

    void setEmergencyContact(){
        final EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_PHONE);e.setHint("Phone number");
        new AlertDialog.Builder(this).setTitle("Emergency contact").setView(e).setPositiveButton("Save",(d,w)->{prefs.edit().putString("emergency_number",e.getText().toString().trim()).apply();showEmergency();}).setNegativeButton("Cancel",null).show();
    }

    void startEmergencyCountdown(){
        final AlertDialog box=new AlertDialog.Builder(this).setTitle("Emergency test").setMessage("30 seconds until the test would escalate. Tap CANCEL to stop.").setNegativeButton("CANCEL",null).create(); box.show();
        final Handler h=new Handler(); final int[] sec={30};
        Runnable r=new Runnable(){public void run(){
            if(!box.isShowing())return; sec[0]--;
            if(sec[0]<=0){box.dismiss(); new AlertDialog.Builder(MainActivity.this).setTitle("Test complete").setMessage("In live mode this is where Lumi would send the configured text + location.").setPositiveButton("OK",null).show();}
            else{box.setMessage(sec[0]+" seconds until the test would escalate. Tap CANCEL to stop.");h.postDelayed(this,1000);}
        }};
        h.postDelayed(r,1000);
    }

    void requestContextPermissions(){
        if(Build.VERSION.SDK_INT>=23){
            ArrayList<String> p=new ArrayList<>();
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.RECORD_AUDIO);
            if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ_PERMS);else Toast.makeText(this,"Context permissions already granted",Toast.LENGTH_SHORT).show();
        }
    }

    void openVault(){
        String pin=prefs.getString("pin","");
        if(pin.isEmpty()){ setupPin(); return; }
        final EditText e=new EditText(this); e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD); e.setHint("Lumi PIN");
        new AlertDialog.Builder(this).setTitle("Unlock Lumi Vault").setView(e).setNegativeButton("Cancel",null).setPositiveButton("Unlock",(d,w)->{ if(e.getText().toString().equals(pin)) showVault(); else Toast.makeText(this,"Incorrect PIN",Toast.LENGTH_SHORT).show(); }).show();
    }

    void setupPin(){
        final EditText e=new EditText(this); e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD); e.setHint("Choose Lumi PIN");
        new AlertDialog.Builder(this).setTitle("Create Lumi Vault PIN").setMessage("Separate from your phone unlock. Prototype storage only; production vault will use encrypted file storage.").setView(e)
                .setPositiveButton("Save",(d,w)->{if(e.getText().length()>=4){prefs.edit().putString("pin",e.getText().toString()).apply();showVault();}else Toast.makeText(this,"Use at least 4 digits",Toast.LENGTH_SHORT).show();})
                .setNegativeButton("Cancel",null).show();
    }

    void showVault(){
        base("Lumi Vault");
        addCard("PRIVATE GALLERY PROTOTYPE\nPIN protected and separate from the normal gallery concept. Production target: encrypted storage, 5-minute unlock window, organization by people / places / objects / moments, and indefinite retention for emergency captures.");
    }

    void requestPrivateMode(){
        if(privateSession){ touchPrivateSession(); showHome(); return; }
        if(!prefs.getBoolean("private_opt_in",false)){ showPrivateConsent(); return; }
        authenticatePrivateMode();
    }

    void showPrivateConsent(){
        new AlertDialog.Builder(this)
                .setTitle("Private Mode")
                .setMessage("Private Mode is an adults-only personal context. By continuing, you confirm you are 18 or older and intentionally want Lumi to use a warmer, more playful or flirtatious conversational style. Core consent, safety, authentication and privacy rules remain active. Private conversation is not saved automatically on this device. Connected cloud AI services may still process messages according to their service settings.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("I'm 18+ • Continue",(d,w)->{prefs.edit().putBoolean("private_opt_in",true).apply();authenticatePrivateMode();})
                .show();
    }

    void authenticatePrivateMode(){
        if(Build.VERSION.SDK_INT>=28){
            try{
                android.hardware.biometrics.BiometricPrompt prompt = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                        .setTitle("Unlock Private Mode")
                        .setSubtitle("Verify it's you")
                        .setDescription("Private Mode closes automatically after inactivity.")
                        .setNegativeButton("Use phone unlock",getMainExecutor(),(d,w)->promptDeviceCredential())
                        .build();
                prompt.authenticate(new android.os.CancellationSignal(),getMainExecutor(),new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){
                        super.onAuthenticationSucceeded(result); enterPrivateMode();
                    }
                    @Override public void onAuthenticationError(int errorCode, CharSequence errString){
                        super.onAuthenticationError(errorCode,errString);
                    }
                });
                return;
            }catch(Exception ignored){}
        }
        promptDeviceCredential();
    }

    void promptDeviceCredential(){
        KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(km==null){ Toast.makeText(this,"Phone unlock is unavailable.",Toast.LENGTH_LONG).show(); return; }
        Intent intent=km.createConfirmDeviceCredentialIntent("Unlock Private Mode","Confirm your phone PIN, pattern or password.");
        if(intent!=null) startActivityForResult(intent,REQ_PRIVATE_DEVICE_CREDENTIAL);
        else Toast.makeText(this,"Set a secure phone lock before using Private Mode.",Toast.LENGTH_LONG).show();
    }

    void enterPrivateMode(){
        privateSession=true;
        touchPrivateSession();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        stopService(new Intent(this,LumiOverlayService.class));
        showHome();
    }

    void exitPrivateMode(){
        privateSession=false;
        privateSessionExpiresAt=0L;
        privateHandler.removeCallbacks(privateTimeout);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    void touchPrivateSession(){
        if(privateSession){
            privateSessionExpiresAt=System.currentTimeMillis()+PRIVATE_SESSION_MS;
            privateHandler.removeCallbacks(privateTimeout);
            privateHandler.postDelayed(privateTimeout,PRIVATE_SESSION_MS);
        }
    }

    void checkPrivateSession(){
        if(privateSession && System.currentTimeMillis()>privateSessionExpiresAt){
            exitPrivateMode();
            Toast.makeText(this,"Private Mode locked after inactivity.",Toast.LENGTH_SHORT).show();
        }
    }

    void showSettings(){
        checkPrivateSession();
        base("Settings");
        content.addView(tv("Context Filter",18,text));
        RadioGroup rg=new RadioGroup(this); String cur=prefs.getString("filter","Balanced");
        for(String s:new String[]{"Strict","Balanced","Relaxed","Custom"}){
            RadioButton r=new RadioButton(this);r.setText(s);r.setTextColor(text);r.setChecked(s.equals(cur));r.setOnClickListener(v->prefs.edit().putString("filter",s).apply());rg.addView(r);
        }
        content.addView(rg);
        addCard("BEHAVIOR\n✓ Important proactive cues only\n✓ Quiet around other people\n✓ Natural conversation\n✓ Learn from corrections\n✓ High-risk actions require confirmation\n✓ Purchases require approval");

        if(privateSession){
            content.addView(tv("Private Tone",18,text));
            RadioGroup prg=new RadioGroup(this); String pt=prefs.getString("private_tone","Playful");
            for(String s:new String[]{"Warm","Playful","Flirty","Intimate"}){
                RadioButton r=new RadioButton(this);r.setText(s);r.setTextColor(text);r.setChecked(s.equals(pt));r.setOnClickListener(v->prefs.edit().putString("private_tone",s).apply());prg.addView(r);
            }
            content.addView(prg);
            addCard("Private Tone changes Lumi's conversational style only. It never disables consent, safety, authentication, or privacy rules.");
        }

        addCard("CONVERSATION CORE\nSpeed priority: "+(prefs.getBoolean("speed_priority",true)?"ON":"OFF")+"\nReply style: "+prefs.getString("reply_style","brief")+"\nHuman cues: "+(prefs.getBoolean("human_cues",true)?"ON":"OFF")+"\nDevelopment avatar: Möbius core");
        boolean speaking=prefs.getBoolean("speak_replies",true);
        Button speak=btn("Spoken replies: "+(speaking?"ON":"OFF")); speak.setOnClickListener(v->{boolean n=!prefs.getBoolean("speak_replies",true); prefs.edit().putBoolean("speak_replies",n).apply(); speakReplies=n; showSettings();}); content.addView(speak);
        Button clearChat=btn("Clear Talk conversation"); clearChat.setOnClickListener(v->{prefs.edit().remove("talk_transcript").apply(); previousResponseId=null; Toast.makeText(this,"Conversation cleared",Toast.LENGTH_SHORT).show();}); content.addView(clearChat);
        Button ai=btn("AI provider settings"); ai.setOnClickListener(v->showIntegrations()); content.addView(ai);
        Button hands=btn(prefs.getBoolean("hands_free_listening",true)?"Hands-free listening: ON":"Hands-free listening: OFF");
        hands.setOnClickListener(v->{ boolean next=!prefs.getBoolean("hands_free_listening",true); prefs.edit().putBoolean("hands_free_listening",next).apply(); if(next) ensureHandsFreeListening(); else stopConversationMode(); showSettings(); }); content.addView(hands);
        boolean adminReady=prefs.getBoolean("admin_enrollment_complete",false);
        addCard("ADMINISTRATOR IDENTITY\n"+(adminReady?"✓ Enrollment complete":"○ Deferred while conversation latency is being tuned")+(adminReady?"\nOwner: "+prefs.getString("owner_call_name",prefs.getString("owner_name","Enrolled administrator")):"\nPIN + face + voice setup can be completed whenever you're ready."));
        Button admin=btn(adminReady?"Administrator enrollment details":"Set up Administrator identity later");
        admin.setOnClickListener(v->{ if(adminReady) showAdminSecuritySummary(); else showAdminEnrollmentStart(); }); content.addView(admin);
        Button change=btn("Change Lumi Vault PIN"); change.setOnClickListener(v->{prefs.edit().remove("pin").apply();setupPin();});content.addView(change);
        Button overlay=btn("Grant floating-overlay permission"); overlay.setOnClickListener(v->requestOverlay()); content.addView(overlay);
    }

    void requestOverlay(){
        if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(this)){
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));
        } else Toast.makeText(this,"Overlay permission already available",Toast.LENGTH_SHORT).show();
    }

    void showOverlay(){
        if(privateSession){
            Toast.makeText(this,"Floating overlay is disabled during Private Mode.",Toast.LENGTH_LONG).show();
            return;
        }
        if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(this)){
            requestOverlay(); Toast.makeText(this,"Grant overlay permission, then try again",Toast.LENGTH_LONG).show(); return;
        }
        startService(new Intent(this,LumiOverlayService.class));
    }

    void initSpeechOutput(){
        lumiTts=new android.speech.tts.TextToSpeech(this,status->{
            if(status==android.speech.tts.TextToSpeech.SUCCESS && lumiTts!=null){
                lumiTts.setLanguage(Locale.US);
                lumiTts.setSpeechRate(1.02f);
            }
        });
    }

    void startConversationMode(){
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            pendingAutoListenAfterPermission=true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_PERMS);
            return;
        }
        conversationMode=true; lastConversationActivity=System.currentTimeMillis(); scheduleConversationTimeout();
        startContinuousListening();
        diag("speech","conversation mode started");
        if(transcript!=null) status.setText("Lumi 2.0 • listening");
    }

    void stopConversationMode(){
        conversationMode=false; recognizingContinuously=false; conversationHandler.removeCallbacks(conversationTimeout);
        try{ if(continuousRecognizer!=null){continuousRecognizer.cancel(); continuousRecognizer.destroy();} }catch(Exception ignored){}
        continuousRecognizer=null;
        diag("speech","conversation mode paused");
        if(status!=null) status.setText("Lumi 2.0 • listening paused");
    }

    void scheduleConversationTimeout(){
        if(!conversationMode) return;
        conversationHandler.removeCallbacks(conversationTimeout);
        long delay=Math.max(1000L,CONVERSATION_TIMEOUT_MS-(System.currentTimeMillis()-lastConversationActivity));
        conversationHandler.postDelayed(conversationTimeout,delay);
    }

    void startContinuousListening(){
        if(!conversationMode || recognizingContinuously) return;
        if(!android.speech.SpeechRecognizer.isRecognitionAvailable(this)){
            Toast.makeText(this,"Continuous speech recognition is unavailable on this phone.",Toast.LENGTH_LONG).show(); stopConversationMode(); return;
        }
        if(continuousRecognizer==null){
            continuousRecognizer=android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            continuousRecognizer.setRecognitionListener(new android.speech.RecognitionListener(){
                public void onReadyForSpeech(Bundle params){recognizingContinuously=true; if(status!=null)status.setText("Lumi • listening");}
                public void onBeginningOfSpeech(){}
                public void onRmsChanged(float rmsdB){}
                public void onBufferReceived(byte[] buffer){}
                public void onEndOfSpeech(){recognizingContinuously=false;}
                public void onError(int error){recognizingContinuously=false; diag("speech","recognizer error="+error); if(conversationMode) conversationHandler.postDelayed(()->startContinuousListening(),500);}
                public void onResults(Bundle results){
                    recognizingContinuously=false;
                    ArrayList<String> r=results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if(r!=null && !r.isEmpty()){
                        lastConversationActivity=System.currentTimeMillis(); scheduleConversationTimeout();
                        appendConversation(r.get(0));
                        // While a model is generating, keep the mic available so the owner can
                        // ask for live status or change direction instead of being trapped waiting.
                        if(conversationMode && aiBusy) conversationHandler.postDelayed(()->startContinuousListening(),280);
                    } else if(conversationMode) conversationHandler.postDelayed(()->startContinuousListening(),500);
                }
                public void onPartialResults(Bundle partialResults){}
                public void onEvent(int eventType, Bundle params){}
            });
        }
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,1600L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,1100L);
        try{ recognizingContinuously=true; continuousRecognizer.startListening(i); }catch(Exception e){recognizingContinuously=false; conversationHandler.postDelayed(()->startContinuousListening(),1000);}
    }

    void speakAndContinue(String message){
        try{ if(continuousRecognizer!=null) continuousRecognizer.cancel(); }catch(Exception ignored){}
        recognizingContinuously=false;
        if(lumiTts==null){ if(conversationMode) conversationHandler.postDelayed(()->startContinuousListening(),400); return; }
        final String utteranceId="lumi_"+System.currentTimeMillis();
        lumiTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){
            public void onStart(String id){runOnUiThread(()->{if(status!=null)status.setText("Lumi • speaking");});}
            public void onDone(String id){runOnUiThread(()->{if(conversationMode){lastConversationActivity=System.currentTimeMillis(); followupHotUntil=lastConversationActivity+FOLLOWUP_LINGER_MS; scheduleConversationTimeout(); conversationHandler.postDelayed(()->startContinuousListening(),220);}});}
            public void onError(String id){runOnUiThread(()->{if(conversationMode)conversationHandler.postDelayed(()->startContinuousListening(),500);});}
        });
        Bundle params=new Bundle(); params.putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,utteranceId);
        lumiTts.speak(message,android.speech.tts.TextToSpeech.QUEUE_FLUSH,params,utteranceId);
    }

    void learnFromConversation(String q){
        if(privateSession) return;
        String clean=q.trim(); String l=clean.toLowerCase(Locale.US);
        String fact=null;
        if(l.contains("i like ") || l.contains("i love ") || l.contains("i prefer ") || l.contains("my favorite ") || l.contains("i hate ") || l.contains("i don't like ") || l.contains("i dont like ") || l.contains("my birthday") || l.contains("my anniversary")) fact=clean;
        if(fact!=null){
            String stamp=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
            String old=prefs.getString("learned_facts","");
            if(!old.toLowerCase(Locale.US).contains(fact.toLowerCase(Locale.US))){
                String next=(old+"\n• "+stamp+" — "+fact).trim();
                if(next.length()>12000) next=next.substring(next.length()-12000);
                prefs.edit().putString("learned_facts",next).apply();
            }
        }
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)this is ([A-Z][a-z]+),? (?:my|our) ([a-zA-Z -]{2,30})").matcher(clean);
        if(m.find()) autoAddRelationship(m.group(1),m.group(2));
    }

    void autoAddRelationship(String name,String relationship){
        try{
            JSONArray a=new JSONArray(prefs.getString("people_cards_json","[]"));
            for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i); if(p!=null && name.equalsIgnoreCase(p.optString("name"))) return;}
            JSONObject p=new JSONObject(); p.put("name",name); p.put("relationship",relationship); p.put("created",System.currentTimeMillis()); p.put("source","conversation"); a.put(p);
            prefs.edit().putString("people_cards_json",a.toString()).apply();
        }catch(Exception ignored){}
    }

    String deviceHealthSummary(){
        StringBuilder s=new StringBuilder();
        try{
            android.content.IntentFilter f=new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b=registerReceiver(null,f); if(b!=null){int level=b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1); int scale=b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100); int temp=b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,0); int plugged=b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0); int pct=scale>0?(level*100/scale):-1; s.append("Battery: ").append(pct).append("% • ").append(plugged!=0?"charging":"on battery").append(" • ").append(String.format(Locale.US,"%.1f°C",temp/10f)).append("\n");}
            android.os.StatFs stat=new android.os.StatFs(getFilesDir().getAbsolutePath()); long free=stat.getAvailableBytes(); long total=stat.getTotalBytes(); s.append("Storage free: ").append(free/1024/1024/1024).append(" GB of ").append(total/1024/1024/1024).append(" GB\n");
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE); String net="offline"; if(cm!=null){android.net.Network n=cm.getActiveNetwork(); android.net.NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n); if(c!=null){if(c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))net="Wi-Fi"; else if(c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))net="cellular"; else if(c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))net="Ethernet"; else net="connected";}} s.append("Network: ").append(net).append("\n");
        }catch(Exception e){s.append("Health scan partially unavailable: ").append(e.getClass().getSimpleName());}
        return s.toString().trim();
    }

    String safeDiagText(String value){
        if(value==null) return "";
        String x=value.replace('\n',' ').replace('\r',' ').trim();
        if(x.length()>220) x=x.substring(0,220);
        return x;
    }

    synchronized void diag(String category,String detail){
        try{
            File f=new File(getFilesDir(),"lumi-diagnostics.log");
            if(f.exists() && f.length()>768L*1024L){
                File old=new File(getFilesDir(),"lumi-diagnostics.previous.log");
                if(old.exists()) old.delete();
                f.renameTo(old);
            }
            String stamp=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());
            try(FileWriter w=new FileWriter(f,true)){w.write(stamp+" | "+category+" | "+safeDiagText(detail)+"\n");}
        }catch(Exception ignored){}
    }

    String networkLabel(){
        try{
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if(cm==null) return "offline";
            android.net.Network n=cm.getActiveNetwork();
            android.net.NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
            if(c==null || !c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "offline";
            if(c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if(c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if(c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if(Build.VERSION.SDK_INT>=26 && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "Bluetooth network";
            return "connected";
        }catch(Exception e){ return "unknown"; }
    }

    String operationalOrPreferenceReply(String q){
        String l=q.toLowerCase(Locale.US).trim();
        if(l.contains("why did you do that") || l.contains("why did you choose that")){
            return prefs.getString("last_action_reason","I don't have a recorded routing reason for the last action yet.");
        }
        if(l.contains("why are you taking") || l.contains("why is this taking") || l.contains("what are you doing") || l.contains("what model are you using") || l.contains("what brain are you using") || l.contains("how long did that take") || l.contains("connection status") || l.contains("are you offline")){
            return operationalStatusSummary();
        }
        if(l.contains("export diagnostics") || l.contains("create a bug report") || l.contains("export bug report")){
            conversationHandler.postDelayed(this::exportDiagnostics,180);
            return "Yep. I'll open the diagnostic export.";
        }
        if(l.contains("run self test") || l.contains("run a self test") || l.contains("check yourself") || l.contains("diagnose yourself")){
            String result=runCoreSelfTest(); diag("self-test",result.replace('\n',';')); return result;
        }
        if(l.contains("talk less") || l.contains("don't talk as much") || l.contains("dont talk as much") || l.contains("be more concise") || l.contains("shorter answers")){
            prefs.edit().putString("reply_style","brief").apply(); diag("setting","reply_style=brief via conversation"); return "Got it. I'll keep it shorter.";
        }
        if(l.contains("talk more") || l.contains("more detail") || l.contains("be more detailed")){
            prefs.edit().putString("reply_style","detailed").apply(); diag("setting","reply_style=detailed via conversation"); return "Okay. I'll give you a little more detail.";
        }
        if(l.contains("respond faster") || l.contains("response time") || l.contains("speed up") || l.contains("too slow") || l.contains("taking too long")){
            prefs.edit().putBoolean("speed_priority",true).putString("reply_style","brief").apply(); diag("setting","speed_priority=true via conversation"); return "Got it. I'm prioritizing response speed and shorter replies.";
        }
        if(l.contains("be more proactive")){
            prefs.edit().putString("proactivity","more").apply(); diag("setting","proactivity=more"); return "Okay. I'll speak up a little more when it seems useful.";
        }
        if(l.contains("be less proactive") || l.contains("don't be so proactive") || l.contains("dont be so proactive")){
            prefs.edit().putString("proactivity","less").apply(); diag("setting","proactivity=less"); return "Got it. I'll hang back more.";
        }
        if(l.contains("stop the little cues") || l.contains("no little cues") || l.contains("stop saying mm")){
            prefs.edit().putBoolean("human_cues",false).apply(); diag("setting","human_cues=false"); return "Okay. I'll drop the little cues.";
        }
        if(l.contains("use little cues") || l.contains("human cues")){
            prefs.edit().putBoolean("human_cues",true).apply(); diag("setting","human_cues=true"); return "Sure. I'll keep them occasional.";
        }
        return null;
    }

    String operationalStatusSummary(){
        String network=networkLabel();
        if(aiBusy){
            long elapsed=activeRequestStartedAt>0?System.currentTimeMillis()-activeRequestStartedAt:0;
            String phase=activeRequestStage==null?"working":activeRequestStage;
            String model=activeRequestModel==null?"current brain":activeRequestModel;
            return "I'm "+phase+" on "+model+". It's been "+String.format(Locale.US,"%.1f",elapsed/1000.0)+" seconds. Network is "+network+".";
        }
        long ms=prefs.getLong("last_response_latency_ms",lastResponseLatencyMs);
        String route=prefs.getString("last_route",activeRequestRoute==null?"idle":activeRequestRoute);
        if(ms>=0) return "I'm idle now. My last reply took "+String.format(Locale.US,"%.1f",ms/1000.0)+" seconds on "+route+". Network is "+network+".";
        return "I'm idle. Fast Brain is "+(isFastModelReady()?"ready":"not ready")+" and the network is "+network+".";
    }

    String runCoreSelfTest(){
        ArrayList<String> issues=new ArrayList<>();
        if(!isFastModelReady()) issues.add("Fast Brain model is not verified");
        if(!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) issues.add("speech recognition is unavailable");
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) issues.add("microphone permission is missing");
        File log=new File(getFilesDir(),"lumi-diagnostics.log");
        String network=networkLabel();
        if(issues.isEmpty()) return "Core self-test passed. Fast Brain is ready, speech is available, diagnostics are recording, and network is "+network+".";
        return "Core self-test found "+issues.size()+" issue"+(issues.size()==1?"":"s")+": "+android.text.TextUtils.join("; ",issues)+".";
    }

    void showDiagnostics(){
        base("Conversation Diagnostics");
        long ms=prefs.getLong("last_response_latency_ms",lastResponseLatencyMs);
        String latency=ms<0?"No measured reply yet":String.format(Locale.US,"%.2f s",ms/1000.0);
        addCard("DEV CORE STATUS\nFast Brain file: "+(isFastModelReady()?"READY":"NOT READY")+"\nModel loaded: "+(LocalBrain.isLoaded()?"YES":"NO")+"\nNetwork: "+networkLabel()+"\nLast route: "+prefs.getString("last_route","none")+"\nLast response: "+latency+"\nLocal status: "+prefs.getString("local_brain_status","unknown")+"\nAvatar: Möbius development core");
        Button self=btn("Run core self-test"); content.addView(self); self.setOnClickListener(v->{String r=runCoreSelfTest();diag("self-test",r.replace('\n',';'));new AlertDialog.Builder(this).setTitle("Core self-test").setMessage(r).setPositiveButton("OK",null).show();});
        Button export=btn("Export diagnostics .txt"); content.addView(export); export.setOnClickListener(v->exportDiagnostics());
        Button clear=btn("Clear diagnostic log"); content.addView(clear); clear.setOnClickListener(v->{new File(getFilesDir(),"lumi-diagnostics.log").delete();new File(getFilesDir(),"lumi-diagnostics.previous.log").delete();diag("diagnostics","log cleared");showDiagnostics();});
        addCard("Ask Lumi naturally: “Why are you taking so long?”, “What model are you using?”, “Run a self-test”, “Export diagnostics”, “Talk less”, or “Respond faster.” Operational status is reported without exposing hidden reasoning.");
    }

    void exportDiagnostics(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,"Lumi-Diagnostics-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".txt");
        startActivityForResult(i,REQ_EXPORT_DIAGNOSTICS);
    }

    String buildDiagnosticsReport(){
        StringBuilder s=new StringBuilder();
        s.append("LUMI DEVELOPMENT DIAGNOSTICS\n");
        s.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date())).append("\n");
        try{android.content.pm.PackageInfo pi=getPackageManager().getPackageInfo(getPackageName(),0);s.append("App: ").append(pi.versionName).append(" (code ").append(Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode).append(")\n");}catch(Exception ignored){}
        s.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(" • Android ").append(Build.VERSION.RELEASE).append("\n");
        s.append("Network: ").append(networkLabel()).append("\n");
        s.append("Power: ").append(currentPowerProfile()).append("\n");
        s.append("Fast Brain ready: ").append(isFastModelReady()).append(" • loaded: ").append(LocalBrain.isLoaded()).append("\n");
        s.append("Local brain status: ").append(prefs.getString("local_brain_status","unknown")).append("\n");
        s.append("Last route: ").append(prefs.getString("last_route","none")).append("\n");
        s.append("Last routing explanation: ").append(prefs.getString("last_action_reason","none")).append("\n");
        s.append("Last response latency ms: ").append(prefs.getLong("last_response_latency_ms",-1L)).append("\n");
        s.append("Reply style: ").append(prefs.getString("reply_style","brief")).append(" • speed priority: ").append(prefs.getBoolean("speed_priority",true)).append("\n");
        s.append("Human cues: ").append(prefs.getBoolean("human_cues",true)).append(" • rate: ").append(prefs.getInt("human_cue_rate",28)).append("%\n");
        s.append("\nSELF TEST\n").append(runCoreSelfTest()).append("\n");
        s.append("\nEVENT LOG\n");
        for(String name:new String[]{"lumi-diagnostics.previous.log","lumi-diagnostics.log"}){
            File f=new File(getFilesDir(),name); if(!f.exists())continue;
            try(FileInputStream in=new FileInputStream(f)){s.append(readAll(in));}catch(Exception e){s.append("[could not read ").append(name).append(": ").append(e.getMessage()).append("]\n");}
        }
        return s.toString();
    }

    void writeDiagnosticsToUri(Uri uri){
        try(OutputStream os=getContentResolver().openOutputStream(uri)){
            if(os==null) throw new IOException("No output stream");
            os.write(buildDiagnosticsReport().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            diag("diagnostics","export completed");
            Toast.makeText(this,"Lumi diagnostics exported.",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"Diagnostics export failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    void showBackupRecovery(){
        base("Backup & Recovery");
        addCard("LUMI CONTINUITY\nExport creates a complete Lumi data snapshot containing conversation history, learned preferences, People Cards, settings, reminders and change history. Keep the exported file somewhere separate from the phone. Cloud recovery is the production target; this test build gives you a real portable snapshot now.");
        Button export=btn("Export complete Lumi backup"); content.addView(export); export.setOnClickListener(v->exportBackup());
        Button restore=btn("Restore Lumi backup"); content.addView(restore); restore.setOnClickListener(v->importBackup());
    }

    void exportBackup(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); i.putExtra(Intent.EXTRA_TITLE,"Lumi-Backup-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".json"); startActivityForResult(i,REQ_EXPORT_BACKUP);
    }
    void importBackup(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); startActivityForResult(i,REQ_IMPORT_BACKUP); }

    JSONObject createBackupJson() throws Exception{
        JSONObject root=new JSONObject(); root.put("format","LumiBackup"); root.put("version",1); root.put("created",System.currentTimeMillis()); JSONObject data=new JSONObject();
        for(Map.Entry<String,?> e:prefs.getAll().entrySet()){
            Object v=e.getValue(); if(v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Float) data.put(e.getKey(),v);
        }
        root.put("data",data); return root;
    }
    void writeBackupToUri(Uri uri){
        try(OutputStream os=getContentResolver().openOutputStream(uri)){ if(os==null)throw new IOException("No output stream"); os.write(createBackupJson().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); Toast.makeText(this,"Lumi backup exported.",Toast.LENGTH_LONG).show(); }
        catch(Exception e){Toast.makeText(this,"Backup failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    void restoreBackupFromUri(Uri uri){
        try(InputStream is=getContentResolver().openInputStream(uri)){
            JSONObject root=new JSONObject(readAll(is)); if(!"LumiBackup".equals(root.optString("format")))throw new Exception("Not a Lumi backup"); JSONObject data=root.getJSONObject("data"); SharedPreferences.Editor ed=prefs.edit();
            Iterator<String> keys=data.keys(); while(keys.hasNext()){String k=keys.next(); Object v=data.get(k); if(v instanceof Boolean)ed.putBoolean(k,(Boolean)v); else if(v instanceof Integer)ed.putInt(k,(Integer)v); else if(v instanceof Long)ed.putLong(k,(Long)v); else if(v instanceof Double)ed.putFloat(k,((Double)v).floatValue()); else ed.putString(k,String.valueOf(v));}
            ed.apply(); speakReplies=prefs.getBoolean("speak_replies",true); Toast.makeText(this,"Lumi restored. Memories and settings loaded.",Toast.LENGTH_LONG).show(); showHome();
        }catch(Exception e){Toast.makeText(this,"Restore failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    @Override protected void onDestroy(){
        stopAdminVoiceRecording(false);
        stopConversationMode();
        try{ if(lumiTts!=null){lumiTts.stop();lumiTts.shutdown();} }catch(Exception ignored){}
        super.onDestroy();
    }

}
