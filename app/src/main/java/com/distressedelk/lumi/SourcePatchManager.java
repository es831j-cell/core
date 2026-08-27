package com.distressedelk.lumi;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Code371 canonical-source inspector + bounded owner-approved source staging.
 *
 * This class never compiles or installs code. It creates an exact staged source tree for one
 * owner-approved core_update request. The trusted build relay compiles/signs that tree remotely;
 * Lumi verifies the returned APK signature/version, creates a local recovery checkpoint, and then
 * opens Android's standard installer for explicit user approval.
 */
final class SourcePatchManager {
    private static final int MAX_RESULTS=12;
    private static final int MAX_READ_CHARS=24000;
    private static final int MAX_FILE_BYTES=1024*1024;
    private static final int MAX_EDITS=10;
    private static final int MAX_REPLACEMENT_CHARS=48000;
    private static final long MAX_EXPANDED_SOURCE_BYTES=96L*1024L*1024L;

    private SourcePatchManager(){}

    static JSONObject search(Context c, SharedPreferences p, String query, int requestedMax) throws Exception {
        String q=safe(query).toLowerCase(Locale.US);
        if(q.length()<2) throw new SecurityException("Source search query is too short");
        int max=Math.max(1,Math.min(MAX_RESULTS,requestedMax<=0?6:requestedMax));
        JSONArray results=new JSONArray();
        File source=CanonicalSourceManager.canonicalArchive(c,p);
        try(ZipFile zip=new ZipFile(source)){
            Enumeration<? extends ZipEntry> en=zip.entries();
            while(en.hasMoreElements() && results.length()<max){
                ZipEntry e=en.nextElement(); if(e.isDirectory())continue;
                String path=e.getName().replace('\\','/');
                if(!readablePath(path))continue;
                String text=readEntry(zip,e,MAX_FILE_BYTES); String low=text.toLowerCase(Locale.US); int at=low.indexOf(q);
                if(at<0 && !path.toLowerCase(Locale.US).contains(q))continue;
                int start=at<0?0:Math.max(0,at-220); int end=Math.min(text.length(),start+700);
                results.put(new JSONObject().put("path",path).put("snippet",text.substring(start,end)));
            }
        }
        return new JSONObject().put("ok",true).put("query",q).put("results",results)
                .put("mode","OWNER_APPROVED_BRIDGE_BUILD")
                .put("note","Read-only inspection. A core_update request plus explicit owner approval is required before source staging.");
    }

    static JSONObject read(Context c, SharedPreferences p, String rawPath, int requestedMax, String rawAnchor) throws Exception {
        String path=normalizePath(rawPath);
        if(!readablePath(path)) throw new SecurityException("Source path is outside Lumi's readable canonical-source allow-list");
        int max=Math.max(1000,Math.min(MAX_READ_CHARS,requestedMax<=0?12000:requestedMax)); String anchor=safe(rawAnchor);
        if(anchor.length()>1000) throw new SecurityException("Source read anchor is too long");
        File source=CanonicalSourceManager.canonicalArchive(c,p);
        try(ZipFile zip=new ZipFile(source)){
            ZipEntry e=zip.getEntry(path); if(e==null || e.isDirectory()) throw new java.io.FileNotFoundException("Canonical source file not found: "+path);
            String full=readEntry(zip,e,MAX_FILE_BYTES); int start=0; boolean anchorFound=false; int anchorAt=-1;
            if(!anchor.isEmpty()){
                anchorAt=full.indexOf(anchor); if(anchorAt<0)anchorAt=full.toLowerCase(Locale.US).indexOf(anchor.toLowerCase(Locale.US));
                if(anchorAt>=0){anchorFound=true; int before=Math.max(400,max/3); start=Math.max(0,anchorAt-before); if(start+max>full.length())start=Math.max(0,full.length()-max);}
            }
            int end=Math.min(full.length(),start+max); String text=full.substring(start,end);
            return new JSONObject().put("ok",true).put("path",path).put("content",text).put("truncated",start>0||end<full.length())
                    .put("charactersReturned",text.length()).put("startCharacter",start).put("endCharacter",end)
                    .put("anchor",anchor).put("anchorFound",anchorFound).put("anchorCharacter",anchorAt).put("mode","OWNER_APPROVED_BRIDGE_BUILD");
        }
    }

    static JSONObject apply(Context c, SharedPreferences p, String requestId, String requestedChange, JSONArray edits) throws Exception {
        String id=safe(requestId);
        if(id.isEmpty()) throw new SecurityException("Missing Lumi core-update request id");
        Bundle pending=LumiSelfUpdateEngine.call(c,"maintenance_request_status");
        if(!pending.getBoolean("ok",false) || !id.equals(pending.getString("request_id","")))
            throw new SecurityException("Source staging does not match Lumi's queued maintenance request");
        if(!"core_update".equals(pending.getString("change_type","")))
            throw new SecurityException("Queued Lumi request is not a core update");
        if(!CanonicalSourceManager.isHealthy(c,p)) throw new SecurityException("Canonical source is not healthy; build staging blocked");
        if(edits==null || edits.length()<1 || edits.length()>MAX_EDITS) throw new SecurityException("A core patch requires 1-"+MAX_EDITS+" bounded edits");

        long baseVersion=currentVersionCode(c); long targetVersion=baseVersion+1L;
        String baseSha=p.getString("canonical_source_sha256","").toLowerCase(Locale.US);
        if(!baseSha.matches("[0-9a-f]{64}")) throw new SecurityException("Canonical source hash is unavailable");

        File requestRoot=requestRoot(c,id); deleteRecursive(requestRoot);
        File sourceRoot=new File(requestRoot,"source"); if(!sourceRoot.mkdirs()) throw new java.io.IOException("Could not create trusted build staging folder");
        expandCanonical(CanonicalSourceManager.canonicalArchive(c,p),sourceRoot);

        JSONArray changed=new JSONArray();
        for(int i=0;i<edits.length();i++){
            JSONObject edit=edits.getJSONObject(i); String op=safe(edit.optString("operation","replace")).toLowerCase(Locale.US);
            String path=normalizePath(edit.optString("path",""));
            if(!writablePath(path)) throw new SecurityException("Core patch path is outside the bounded app-source allow-list: "+path);
            File target=safeChild(sourceRoot,path);
            if("replace".equals(op)){
                if(!target.isFile()) throw new java.io.FileNotFoundException("Patch target not found: "+path);
                String find=edit.optString("find",""); String replacement=edit.optString("replace","");
                if(find.isEmpty()) throw new SecurityException("Replace edit requires exact find text");
                if(find.length()>MAX_REPLACEMENT_CHARS || replacement.length()>MAX_REPLACEMENT_CHARS) throw new SecurityException("Patch edit is too large");
                String original=readUtf8(target,MAX_FILE_BYTES); int first=original.indexOf(find); int second=first<0?-1:original.indexOf(find,first+find.length());
                if(first<0) throw new SecurityException("Exact patch anchor was not found in "+path);
                if(second>=0) throw new SecurityException("Patch anchor is ambiguous in "+path+"; provide a more specific exact match");
                writeUtf8(target,original.substring(0,first)+replacement+original.substring(first+find.length()));
            }else if("create".equals(op)){
                if(target.exists()) throw new SecurityException("Create edit refuses to overwrite existing file: "+path);
                String content=edit.optString("content",""); if(content.length()>MAX_REPLACEMENT_CHARS) throw new SecurityException("Created source file is too large");
                File parent=target.getParentFile(); if(parent!=null && !parent.exists() && !parent.mkdirs()) throw new java.io.IOException("Could not create source directory");
                writeUtf8(target,content);
            }else throw new SecurityException("Unsupported source edit operation: "+op);
            changed.put(path);
        }

        String buildPath="app/build.gradle"; File buildFile=safeChild(sourceRoot,buildPath);
        String build=readUtf8(buildFile,MAX_FILE_BYTES);
        String oldCode="versionCode "+baseVersion; String newCode="versionCode "+targetVersion;
        if(!build.contains(oldCode)) throw new SecurityException("Could not locate current versionCode in app/build.gradle");
        build=replaceExactlyOnce(build,oldCode,newCode,"app versionCode");
        String oldName=extractGradleQuoted(build,"versionName");
        if(oldName.isEmpty()) throw new SecurityException("Could not locate current versionName in app/build.gradle");
        String newName=nextBridgeVersionName(targetVersion);
        build=replaceExactlyOnce(build,"versionName '"+oldName+"'","versionName '"+newName+"'","app versionName");
        writeUtf8(buildFile,build); changed.put(buildPath);

        JSONObject meta=new JSONObject().put("format","LumiTrustedBuildStage").put("formatVersion",1)
                .put("requestId",id).put("requestedChange",safe(requestedChange)).put("baseVersionCode",baseVersion)
                .put("targetVersionCode",targetVersion).put("targetVersionName",newName).put("baseSourceSha256",baseSha)
                .put("filesChanged",changed).put("createdAt",System.currentTimeMillis());
        writeUtf8(new File(requestRoot,"stage.json"),meta.toString(2)+"\n");
        String stagedSha=sha256Tree(sourceRoot);
        p.edit().putBoolean("trusted_core_build_active",true).putString("trusted_core_build_request_id",id)
                .putString("trusted_core_build_stage","SOURCE_STAGED").putString("trusted_core_build_requested_change",safe(requestedChange))
                .putLong("trusted_core_build_target_version",targetVersion).putString("trusted_core_build_target_name",newName)
                .putString("trusted_core_build_base_sha256",baseSha).putString("trusted_core_build_overlay_sha256",stagedSha)
                .putString("trusted_core_build_overlay_paths",changed.toString()).remove("trusted_core_build_error").apply();
        UpdateTransactionManager.markStage(p,id,"SOURCE_STAGED",targetVersion);
        return new JSONObject().put("ok",true).put("state","SOURCE_STAGED").put("request_id",id)
                .put("base_version",baseVersion).put("target_version",targetVersion).put("target_name",newName)
                .put("files_changed",changed).put("staged_tree_sha256",stagedSha);
    }

    /** Code382: stage a fully verified canonical source snapshot imported from a bridge-core ZIP. */
    static JSONObject stageArchive(Context c, SharedPreferences p, String requestId, String requestedChange, File sourceArchive,
                                   long baseVersion, long targetVersion, String targetName, String baseSha, String targetSha,
                                   JSONArray filesChanged) throws Exception {
        String id=safe(requestId);
        if(id.isEmpty()) throw new SecurityException("Missing Lumi core-update request id");
        Bundle pending=LumiSelfUpdateEngine.call(c,"maintenance_request_status");
        if(!pending.getBoolean("ok",false) || !id.equals(pending.getString("request_id","")))
            throw new SecurityException("Bridge source staging does not match Lumi's queued maintenance request");
        if(!"core_update".equals(pending.getString("change_type","")))
            throw new SecurityException("Queued Lumi request is not a core update");
        if(!CanonicalSourceManager.isHealthy(c,p)) throw new SecurityException("Canonical source is not healthy; bridge source staging blocked");
        long installed=currentVersionCode(c);
        String currentSha=p.getString("canonical_source_sha256","").toLowerCase(Locale.US);
        if(baseVersion!=installed || !currentSha.equals(baseSha)) throw new SecurityException("Bridge source package no longer matches the installed canonical source");
        if(targetVersion<=baseVersion) throw new SecurityException("Bridge source target must be a forward version");
        if(sourceArchive==null || !sourceArchive.isFile()) throw new java.io.FileNotFoundException("Verified bridge source archive is missing");
        if(!targetSha.matches("[0-9a-f]{64}") || !targetSha.equals(sha256(sourceArchive))) throw new SecurityException("Verified bridge source archive checksum changed after import");

        File requestRoot=requestRoot(c,id); deleteRecursive(requestRoot);
        File sourceRoot=new File(requestRoot,"source"); if(!sourceRoot.mkdirs()) throw new java.io.IOException("Could not create trusted bridge staging folder");
        expandCanonical(sourceArchive,sourceRoot);
        File buildFile=safeChild(sourceRoot,"app/build.gradle");
        String build=readUtf8(buildFile,MAX_FILE_BYTES);
        if(!build.matches("(?s).*versionCode\\s+"+targetVersion+"(?:\\s|$).*$")) throw new SecurityException("Staged bridge source versionCode does not match target");
        String stagedName=extractGradleQuoted(build,"versionName");
        if(targetName==null || targetName.trim().isEmpty()) targetName=stagedName;
        if(stagedName.isEmpty() || !stagedName.equals(targetName)) throw new SecurityException("Staged bridge source versionName does not match target");
        File workflow=safeChild(sourceRoot,".github/workflows/lumi-bridge-build.yml");
        if(!workflow.isFile()) throw new SecurityException("Staged bridge source is missing the trusted build workflow");

        JSONArray changed=filesChanged==null?new JSONArray():filesChanged;
        JSONObject meta=new JSONObject().put("format","LumiTrustedBuildStage").put("formatVersion",2)
                .put("sourceMode","VERIFIED_BRIDGE_CORE_ZIP").put("requestId",id).put("requestedChange",safe(requestedChange))
                .put("baseVersionCode",baseVersion).put("targetVersionCode",targetVersion).put("targetVersionName",targetName)
                .put("baseSourceSha256",baseSha).put("targetSourceArchiveSha256",targetSha)
                .put("filesChanged",changed).put("createdAt",System.currentTimeMillis());
        writeUtf8(new File(requestRoot,"stage.json"),meta.toString(2)+"\n");
        String stagedTreeSha=sha256Tree(sourceRoot);
        p.edit().putBoolean("trusted_core_build_active",true).putString("trusted_core_build_request_id",id)
                .putString("trusted_core_build_stage","SOURCE_STAGED").putString("trusted_core_build_requested_change",safe(requestedChange))
                .putLong("trusted_core_build_target_version",targetVersion).putString("trusted_core_build_target_name",targetName)
                .putString("trusted_core_build_base_sha256",baseSha).putString("trusted_core_build_target_source_sha256",targetSha)
                .putString("trusted_core_build_overlay_sha256",stagedTreeSha).putString("trusted_core_build_overlay_paths",changed.toString())
                .putString("trusted_core_build_source_mode","VERIFIED_BRIDGE_CORE_ZIP").remove("trusted_core_build_error").apply();
        UpdateTransactionManager.markStage(p,id,"SOURCE_STAGED",targetVersion);
        return new JSONObject().put("ok",true).put("state","SOURCE_STAGED").put("request_id",id)
                .put("base_version",baseVersion).put("target_version",targetVersion).put("target_name",targetName)
                .put("files_changed",changed).put("staged_tree_sha256",stagedTreeSha).put("target_source_sha256",targetSha);
    }

    static File stagedSourceRoot(Context c, SharedPreferences p, String requestId) throws Exception {
        String id=safe(requestId); if(id.isEmpty() || !id.equals(p.getString("trusted_core_build_request_id",""))) throw new SecurityException("No staged source matches this request");
        File root=new File(requestRoot(c,id),"source"); if(!root.isDirectory()) throw new java.io.FileNotFoundException("Trusted build staged source is missing"); return root;
    }

    static JSONObject stagedMeta(Context c,String requestId) throws Exception { return new JSONObject(readUtf8(new File(requestRoot(c,safe(requestId)),"stage.json"),256*1024)); }

    private static boolean readablePath(String path){
        if(path==null || path.isEmpty() || path.startsWith("/") || path.contains("../") || path.indexOf('\0')>=0)return false;
        String low=path.toLowerCase(Locale.US);
        if(low.contains("keystore") || low.endsWith(".jks") || low.endsWith(".p12") || low.endsWith(".apk"))return false;
        return textPath(path) && path.startsWith("app/src/main/");
    }

    private static boolean writablePath(String path){
        if(path==null || path.isEmpty() || path.contains("../") || path.startsWith("/") || path.indexOf('\0')>=0)return false;
        String low=path.toLowerCase(Locale.US);
        if(low.contains("keystore")||low.contains("signing")||low.contains("secret")||low.endsWith("androidmanifest.xml")||low.contains("lumi-source/"))return false;
        if(!(path.startsWith("app/src/main/java/")||path.startsWith("app/src/main/res/")||path.startsWith("app/src/main/assets/")))return false;
        return textPath(path);
    }

    private static boolean textPath(String path){String l=path.toLowerCase(Locale.US);return l.endsWith(".java")||l.endsWith(".kt")||l.endsWith(".xml")||l.endsWith(".json")||l.endsWith(".txt")||l.endsWith(".md")||l.endsWith(".properties")||l.endsWith(".csv");}
    private static String normalizePath(String raw){String p=safe(raw).replace('\\','/');while(p.startsWith("./"))p=p.substring(2);return p;}
    private static String readEntry(ZipFile zip, ZipEntry e, int maxBytes) throws Exception {try(InputStream in=zip.getInputStream(e)){ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[16384]; int n,total=0; while((n=in.read(b))>0){total+=n;if(total>maxBytes)throw new SecurityException("Canonical source file exceeds readable size limit");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}

    private static File requestRoot(Context c,String id){ return new File(c.getFilesDir(),"trusted_core_build/"+id.replaceAll("[^A-Za-z0-9._-]","_")); }
    private static File safeChild(File root,String path)throws Exception{File f=new File(root,path);String rp=root.getCanonicalPath()+File.separator;String fp=f.getCanonicalPath();if(!fp.startsWith(rp))throw new SecurityException("Unsafe staged source path");return f;}
    private static void expandCanonical(File zipFile,File dest)throws Exception{
        long total=0; try(ZipInputStream zin=new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))){
            ZipEntry e; byte[] b=new byte[65536]; while((e=zin.getNextEntry())!=null){String name=e.getName().replace('\\','/');if(name.startsWith("/")||name.contains("../"))throw new SecurityException("Unsafe path in canonical source");File out=safeChild(dest,name);if(e.isDirectory()){if(!out.exists()&&!out.mkdirs())throw new java.io.IOException("Could not expand canonical source directory");continue;}File par=out.getParentFile();if(par!=null&&!par.exists()&&!par.mkdirs())throw new java.io.IOException("Could not expand canonical source directory");try(BufferedOutputStream bos=new BufferedOutputStream(new FileOutputStream(out))){int n;while((n=zin.read(b))>0){total+=n;if(total>MAX_EXPANDED_SOURCE_BYTES)throw new SecurityException("Canonical source exceeds trusted build staging limit");bos.write(b,0,n);}}}}
    }
    private static String readUtf8(File f,int max)throws Exception{if(!f.isFile())throw new java.io.FileNotFoundException(f.getPath());if(f.length()>max)throw new SecurityException("Source file exceeds patch limit: "+f.getName());return new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
    private static void writeUtf8(File f,String s)throws Exception{File par=f.getParentFile();if(par!=null&&!par.exists()&&!par.mkdirs())throw new java.io.IOException("Could not create source directory");try(FileOutputStream out=new FileOutputStream(f)){out.write(s.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    private static String replaceExactlyOnce(String src,String find,String repl,String label){int a=src.indexOf(find);if(a<0)throw new SecurityException("Missing "+label);if(src.indexOf(find,a+find.length())>=0)throw new SecurityException("Ambiguous "+label);return src.substring(0,a)+repl+src.substring(a+find.length());}
    private static String extractGradleQuoted(String src,String key){String marker=key+" '";int a=src.indexOf(marker);if(a<0)return "";int s=a+marker.length(),e=src.indexOf('\'',s);return e>s?src.substring(s,e):"";}
    private static String nextBridgeVersionName(long v){return "4.3."+v+"-bridge-build";}
    private static long currentVersionCode(Context c)throws Exception{PackageInfo pi=c.getPackageManager().getPackageInfo(c.getPackageName(),0);return Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode;}
    private static void deleteRecursive(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)deleteRecursive(k);}f.delete();}
    private static String sha256Tree(File root)throws Exception{List<File> files=new ArrayList<>();collect(root,files);Collections.sort(files,(a,b)->relative(root,a).compareTo(relative(root,b)));MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[65536];for(File f:files){String rel=relative(root,f);md.update(rel.getBytes(StandardCharsets.UTF_8));md.update((byte)0);try(InputStream in=new BufferedInputStream(new FileInputStream(f))){int n;while((n=in.read(buf))>0)md.update(buf,0,n);}md.update((byte)0);}return hex(md.digest());}
    private static void collect(File f,List<File> out){if(f.isFile()){out.add(f);return;}File[] kids=f.listFiles();if(kids!=null)for(File k:kids)collect(k,out);}
    static String relative(File root,File f){return root.toPath().relativize(f.toPath()).toString().replace('\\','/');}
    static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)md.update(b,0,n);}return hex(md.digest());}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b));return s.toString();}
}
