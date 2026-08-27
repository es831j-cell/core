package com.distressedelk.lumi;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Code382 bridge-core update intake.
 *
 * A bridge-core ZIP carries the next canonical Lumi source snapshot rather than a prebuilt APK.
 * The owner selects the ZIP, Lumi verifies the complete source envelope against the currently
 * installed canonical source, then a freshly authenticated administrator may hand that exact
 * source snapshot to the trusted build relay. Lumi owns the protected checkpoint and APK verification;
 * package/signature/version verifier, installer and post-install certifier.
 */
final class BridgeUpdatePackage {
    static final String TYPE="bridge-core";
    private static final long MAX_SOURCE_BYTES=80L*1024L*1024L;
    private static final long MAX_PATCH_BYTES=12L*1024L*1024L;
    private static final long MAX_CHANGE_BYTES=2L*1024L*1024L;
    private static final long MAX_EXPANDED_BYTES=180L*1024L*1024L;
    private static final int MAX_ZIP_ENTRIES=5000;

    private BridgeUpdatePackage(){}

    static void stageVerified(Activity a, SharedPreferences p, JSONObject manifest, File staging,
                              String updateId, String name, String version, String notes) throws Exception {
        JSONObject sr=manifest.optJSONObject("sourceRecord");
        if(sr==null) throw new SecurityException("Bridge-core update is missing sourceRecord");
        if(sr.optInt("formatVersion",-1)!=CanonicalSourceManager.FORMAT_VERSION)
            throw new SecurityException("Unsupported bridge-core sourceRecord format");

        long installed=currentVersionCode(a);
        long base=sr.optLong("baseVersionCode",-1L);
        long target=sr.optLong("targetVersionCode",-1L);
        if(base!=installed) throw new SecurityException("Bridge-core package is based on a different installed Lumi version");
        if(target<=installed || target-installed>50L) throw new SecurityException("Bridge-core target version is outside the allowed forward range");

        CanonicalSourceManager.initialize(a,p);
        if(!CanonicalSourceManager.isHealthy(a,p)) throw new SecurityException("Canonical source is not healthy; bridge-core update blocked");
        String baseSha=lowerSha(sr.optString("baseSourceSha256",""));
        String currentSha=lowerSha(p.getString("canonical_source_sha256",""));
        if(!isSha(baseSha)||!baseSha.equals(currentSha)) throw new SecurityException("Bridge-core package is not based on Lumi's current canonical source");
        String targetSha=lowerSha(sr.optString("targetSourceSha256",""));
        if(!isSha(targetSha)) throw new SecurityException("Bridge-core target source checksum is malformed");

        String sourcePath=safePayloadPath(sr.optString("sourceArchivePath",""));
        String changePath=safePayloadPath(sr.optString("changeRecordPath",""));
        String patchPath=safePayloadPath(sr.optString("patchPath",""));
        File source=stagedPayloadForPath(manifest,staging,sourcePath);
        File change=stagedPayloadForPath(manifest,staging,changePath);
        File patch=stagedPayloadForPath(manifest,staging,patchPath);
        if(!source.isFile() || source.length()<=0L || source.length()>MAX_SOURCE_BYTES) throw new SecurityException("Bridge-core canonical source archive is missing or too large");
        if(!targetSha.equals(sha256(source))) throw new SecurityException("Bridge-core canonical source archive checksum mismatch");
        if(!change.isFile() || change.length()<=0L || change.length()>MAX_CHANGE_BYTES) throw new SecurityException("Bridge-core change record is missing or too large");
        if(!patch.isFile() || patch.length()<=0L || patch.length()>MAX_PATCH_BYTES) throw new SecurityException("Bridge-core source patch is missing or too large");

        JSONObject cr=new JSONObject(readUtf8(change,(int)MAX_CHANGE_BYTES));
        validateChangeRecord(cr,updateId,base,target,baseSha,targetSha);
        String targetName=sr.optString("targetVersionName",cr.optString("targetVersionName",version)).trim();
        if(targetName.isEmpty()) targetName=version;
        validateSourceArchive(source,target,targetName);

        File root=pendingRoot(a,updateId); deleteRecursive(root);
        if(!root.mkdirs()) throw new java.io.IOException("Could not create pending bridge update folder");
        atomicCopy(source,new File(root,"canonical-source.zip"));
        atomicCopy(change,new File(root,"source-change-record.json"));
        atomicCopy(patch,new File(root,"source.patch"));
        JSONObject meta=new JSONObject().put("format","LumiBridgeCorePending").put("formatVersion",1)
                .put("updateId",updateId).put("name",name).put("version",version).put("releaseNotes",notes)
                .put("baseVersionCode",base).put("targetVersionCode",target).put("targetVersionName",targetName)
                .put("baseSourceSha256",baseSha).put("targetSourceSha256",targetSha)
                .put("requestedChange",cr.optString("reason",notes).trim()).put("filesChanged",cr.optJSONArray("filesChanged"))
                .put("stagedAt",System.currentTimeMillis());
        writeUtf8(new File(root,"pending.json"),meta.toString(2)+"\n");
        p.edit().putString("pending_bridge_update_id",updateId)
                .putString("pending_bridge_update_name",name)
                .putString("pending_bridge_update_version",version)
                .putString("pending_bridge_update_notes",notes)
                .putString("pending_bridge_update_root",root.getAbsolutePath())
                .putLong("pending_bridge_target_version",target)
                .putString("pending_bridge_target_name",targetName)
                .putString("pending_bridge_target_sha256",targetSha)
                .putString("pending_bridge_state","VERIFIED_WAITING_OWNER")
                .putLong("pending_bridge_staged_at",System.currentTimeMillis()).apply();
    }

    static boolean hasPending(Context c,SharedPreferences p){
        String root=p.getString("pending_bridge_update_root","");
        if(root.isEmpty()) return false;
        File f=new File(root,"pending.json");
        if(!f.isFile()){clear(p);return false;}
        try{
            JSONObject m=new JSONObject(readUtf8(f,256*1024));
            if(m.optLong("baseVersionCode",-1L)!=currentVersionCode(c)){clear(p);return false;}
            return true;
        }catch(Exception e){clear(p);return false;}
    }

    static String label(SharedPreferences p){
        String n=p.getString("pending_bridge_update_name","Lumi bridge update");
        String v=p.getString("pending_bridge_update_version","");
        return v.isEmpty()?n:n+" • "+v;
    }

    static JSONObject status(Context c,SharedPreferences p)throws Exception{
        boolean pending=hasPending(c,p);
        JSONObject o=new JSONObject().put("ok",true).put("pending",pending);
        if(!pending) return o.put("state","NONE");
        return o.put("state",p.getString("pending_bridge_state","VERIFIED_WAITING_OWNER"))
                .put("label",label(p)).put("update_id",p.getString("pending_bridge_update_id",""))
                .put("target_version",p.getLong("pending_bridge_target_version",-1L))
                .put("target_version_name",p.getString("pending_bridge_target_name",""))
                .put("target_source_sha256",p.getString("pending_bridge_target_sha256",""))
                .put("request_id",p.getString("pending_bridge_request_id",""))
                .put("staged_at",p.getLong("pending_bridge_staged_at",0L))
                .put("started_at",p.getLong("pending_bridge_started_at",0L));
    }

    static JSONObject start(Activity a,SharedPreferences p)throws Exception{
        if(!IdentityHierarchy.strongAdminSessionActive(p)) throw new SecurityException("Fresh administrator verification is required before a bridge-core build can start");
        if(!hasPending(a,p)) throw new java.io.FileNotFoundException("No verified bridge-core update is waiting");
        File root=new File(p.getString("pending_bridge_update_root",""));
        JSONObject meta=new JSONObject(readUtf8(new File(root,"pending.json"),256*1024));
        long installed=currentVersionCode(a);
        long base=meta.optLong("baseVersionCode",-1L),target=meta.optLong("targetVersionCode",-1L);
        String baseSha=meta.optString("baseSourceSha256","").toLowerCase(Locale.US);
        String targetSha=meta.optString("targetSourceSha256","").toLowerCase(Locale.US);
        CanonicalSourceManager.initialize(a,p);
        if(base!=installed || p.getLong("canonical_source_version_code",-1L)!=installed || !baseSha.equals(p.getString("canonical_source_sha256","")))
            throw new SecurityException("Lumi changed after this bridge-core ZIP was verified; import a package based on the current canonical source");

        String requested=meta.optString("requestedChange",meta.optString("releaseNotes","Bridge-core update")).trim();
        if(requested.length()<3) requested="Bridge-core update to Code "+target;
        if(requested.length()>500) requested=requested.substring(0,500);

        // R105: Lumi owns the durable transaction. A previous active build/install is resumed,
        // never overwritten, and there is no companion-app authority to reconcile.
        UpdateTransactionManager.ReconcileResult reconcile=UpdateTransactionManager.reconcileBeforeNewBridge(a,p);
        if(reconcile.resumeExisting){
            p.edit().putBoolean("zero_chat_newer_bridge_waiting",true)
                    .putString("zero_chat_newer_bridge_update_id",meta.optString("updateId",p.getString("pending_bridge_update_id","")))
                    .putString("zero_chat_newer_bridge_reason",reconcile.message).apply();
            TrustedBuildRelayJobService.schedule(a,1000L);
            ZeroChatUpdateCoordinator.resume(a,p,"newer-relay-package-waiting");
            return new JSONObject().put("ok",true).put("state","EXISTING_TRANSACTION_RESUMED")
                    .put("request_id",reconcile.requestId).put("stage",reconcile.stage)
                    .put("message",reconcile.message).put("newer_update_waiting",true);
        }
        if(!reconcile.readyForNew) throw new SecurityException("Update transaction reconciliation did not reach a safe state: "+reconcile.message);

        JSONObject preflight=TrustedBuildRelayClient.preflight(a,p,true);
        if(!preflight.optBoolean("ok",false)) throw new SecurityException("Trusted build relay preflight did not pass");

        String requestId="tx-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        if(!UpdateTransactionManager.beginBound(p,requestId,"core_update",requested))
            throw new SecurityException("Lumi could not bind the durable update transaction after reconciliation");
        MaintenanceSession.bindWriteApprovalToRequest(p,requestId,"core_update",requested);

        JSONObject checkpoint=RecoverySnapshotManager.create(a,p,"lumi-native-self-update-before-relay-code-"+target);
        UpdateTransactionManager.markStage(p,requestId,"CHECKPOINTED",target);

        JSONArray filesChanged=meta.optJSONArray("filesChanged");
        if(filesChanged==null) filesChanged=new JSONArray();
        SourcePatchManager.stageArchive(a,p,requestId,requested,new File(root,"canonical-source.zip"),base,target,
                meta.optString("targetVersionName",""),baseSha,targetSha,filesChanged);
        JSONObject started=TrustedBuildRelayClient.start(a,p,requestId,requested);
        p.edit().putString("pending_bridge_state","RELAY_STARTED").putString("pending_bridge_request_id",requestId)
                .putLong("pending_bridge_started_at",System.currentTimeMillis()).apply();
        return new JSONObject().put("ok",true).put("state","RELAY_STARTED").put("request_id",requestId)
                .put("target_version",target).put("relay",started).put("preflight",preflight)
                .put("checkpoint",RecoverySnapshotManager.latestPath(p));
    }

    static void clear(SharedPreferences p){
        String root=p.getString("pending_bridge_update_root","");
        if(!root.isEmpty()) deleteRecursive(new File(root));
        p.edit().remove("pending_bridge_update_id").remove("pending_bridge_update_name").remove("pending_bridge_update_version")
                .remove("pending_bridge_update_notes").remove("pending_bridge_update_root").remove("pending_bridge_target_version")
                .remove("pending_bridge_target_name").remove("pending_bridge_target_sha256").remove("pending_bridge_state")
                .remove("pending_bridge_request_id").remove("pending_bridge_staged_at").remove("pending_bridge_started_at").apply();
    }

    private static void validateChangeRecord(JSONObject c,String updateId,long base,long target,String baseSha,String targetSha)throws Exception{
        if(!"LumiSourceChangeRecord".equals(c.optString("format"))) throw new SecurityException("Invalid bridge-core change record format");
        if(c.optInt("formatVersion",-1)!=1) throw new SecurityException("Unsupported bridge-core change record version");
        if(c.optLong("baseVersionCode",-1L)!=base || c.optLong("targetVersionCode",-1L)!=target) throw new SecurityException("Bridge-core change record version mismatch");
        if(!baseSha.equals(c.optString("baseSourceSha256","").toLowerCase(Locale.US)) || !targetSha.equals(c.optString("targetSourceSha256","").toLowerCase(Locale.US)))
            throw new SecurityException("Bridge-core change record checksum mismatch");
        String id=c.optString("updateId",""); if(!id.isEmpty()&&!updateId.equals(id)) throw new SecurityException("Bridge-core change record update id mismatch");
        if(c.optJSONArray("filesChanged")==null) throw new SecurityException("Bridge-core change record must list filesChanged");
        if(c.optString("reason","").trim().isEmpty()) throw new SecurityException("Bridge-core change record must explain the update");
    }

    private static void validateSourceArchive(File source,long target,String targetName)throws Exception{
        long total=0L; int entries=0; boolean build=false,workflow=false;
        try(ZipFile z=new ZipFile(source)){
            java.util.Enumeration<? extends ZipEntry> en=z.entries();
            while(en.hasMoreElements()){
                ZipEntry e=en.nextElement(); entries++; if(entries>MAX_ZIP_ENTRIES) throw new SecurityException("Bridge-core source archive has too many entries");
                String n=e.getName().replace('\\','/');
                if(n.startsWith("/")||n.contains("../")||n.contains(":")||n.indexOf('\0')>=0) throw new SecurityException("Unsafe source archive path: "+n);
                String l=n.toLowerCase(Locale.US);
                if(l.contains("keystore")||l.endsWith(".jks")||l.endsWith(".p12")||l.endsWith(".keystore")||l.equals("local.properties")||l.contains("/.git/")||l.startsWith(".git/")||l.contains("/.gradle/")||l.contains("/build/"))
                    throw new SecurityException("Bridge-core source archive contains forbidden build/signing material: "+n);
                long s=e.getSize(); if(s>0){total+=s;if(total>MAX_EXPANDED_BYTES)throw new SecurityException("Bridge-core source archive expands beyond safety limit");}
                if("app/build.gradle".equals(n)){
                    String text=readEntry(z,e,2*1024*1024);
                    if(!text.matches("(?s).*versionCode\\s+"+target+"(?:\\s|$).*$")) throw new SecurityException("Bridge-core source versionCode does not match sourceRecord target");
                    if(targetName!=null&&!targetName.trim().isEmpty()&&!text.contains("versionName '"+targetName.trim()+"'")) throw new SecurityException("Bridge-core source versionName does not match sourceRecord target");
                    build=true;
                }
                if(".github/workflows/lumi-bridge-build.yml".equals(n)) workflow=true;
            }
        }
        if(!build) throw new SecurityException("Bridge-core source archive is missing app/build.gradle");
        if(!workflow) throw new SecurityException("Bridge-core source archive is missing the trusted bridge workflow");
    }

    private static File stagedPayloadForPath(JSONObject manifest,File staging,String path)throws Exception{
        JSONArray files=manifest.optJSONArray("files"); if(files==null) throw new SecurityException("Bridge-core update has no declared payload list");
        for(int i=0;i<files.length();i++) if(path.equals(files.getJSONObject(i).optString("path"))) return new File(staging,"payload/"+i+".bin");
        throw new SecurityException("Bridge-core payload is not declared: "+path);
    }
    private static String safePayloadPath(String path){String v=path==null?"":path.replace('\\','/').trim();if(!v.startsWith("payload/")||v.contains("../")||v.contains(":"))throw new SecurityException("Unsafe bridge-core payload path");return v;}
    private static String lowerSha(String s){return s==null?"":s.trim().toLowerCase(Locale.US);}
    private static boolean isSha(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static File pendingRoot(Context c,String id){return new File(c.getFilesDir(),"lumi_updates/pending_bridge/"+id.replaceAll("[^A-Za-z0-9._-]","_"));}
    private static long currentVersionCode(Context c)throws Exception{PackageInfo pi=c.getPackageManager().getPackageInfo(c.getPackageName(),0);return Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode;}
    private static String readUtf8(File f,int max)throws Exception{try(InputStream in=new BufferedInputStream(new FileInputStream(f))){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n,total=0;while((n=in.read(b))>0){total+=n;if(total>max)throw new SecurityException("Bridge-core metadata exceeds limit");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static String readEntry(ZipFile z,ZipEntry e,int max)throws Exception{try(InputStream in=z.getInputStream(e)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n,total=0;while((n=in.read(b))>0){total+=n;if(total>max)throw new SecurityException("Bridge-core source metadata exceeds limit");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.US,"%02x",b));return s.toString();}
    private static void atomicCopy(File src,File dst)throws Exception{File parent=dst.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new java.io.IOException("Could not create bridge staging directory");File tmp=new File(dst.getPath()+".new");try(InputStream in=new BufferedInputStream(new FileInputStream(src));OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))){byte[] b=new byte[262144];int n;while((n=in.read(b))>0)out.write(b,0,n);}if(dst.exists()&&!dst.delete())throw new java.io.IOException("Could not replace bridge staged file");if(!tmp.renameTo(dst))throw new java.io.IOException("Could not publish bridge staged file");}
    private static void writeUtf8(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null&&!p.exists()&&!p.mkdirs())throw new java.io.IOException("Could not create bridge staging directory");try(FileOutputStream out=new FileOutputStream(f)){out.write(s.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    private static void deleteRecursive(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)deleteRecursive(k);}f.delete();}
}
