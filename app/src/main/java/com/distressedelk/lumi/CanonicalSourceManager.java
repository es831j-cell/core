package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Code325 canonical source-of-truth manager.
 *
 * Every installed Lumi core carries a deterministic, non-secret source snapshot inside the APK.
 * Core update packages must also carry that exact snapshot plus a Source Sync change record before
 * the APK can be staged. This keeps runtime/core evolution and the maintainable source tree coupled.
 *
 * Signing keys, credentials, build outputs and other secrets are intentionally excluded from the
 * canonical source archive. The archive is source-of-truth for code, not a signing-key backup.
 */
final class CanonicalSourceManager {
    static final int FORMAT_VERSION = 1;
    static final String ASSET_META = "lumi-source/canonical-source-metadata.json";
    static final String ASSET_ZIP = "lumi-source/canonical-source-baseline.zip";
    static final String APK_META_ENTRY = "assets/" + ASSET_META;
    static final String APK_ZIP_ENTRY = "assets/" + ASSET_ZIP;
    static final long MAX_SOURCE_BYTES = 80L * 1024L * 1024L;

    private CanonicalSourceManager() {}

    static void initialize(Context c, SharedPreferences p) {
        try {
            long installed = currentVersionCode(c);
            File root = root(c);
            if (!root.exists() && !root.mkdirs()) throw new java.io.IOException("Could not create canonical source folder");

            // A staged record is promoted only after Android proves the matching target core is now installed.
            promoteStagedIfInstalled(c, p, installed);

            JSONObject embedded = readAssetJson(c, ASSET_META);
            long embeddedVersion = embedded.optLong("versionCode", -1L);
            String embeddedHash = embedded.optString("sourceArchiveSha256", "").toLowerCase(Locale.US);
            if (embeddedVersion != installed || !isSha(embeddedHash)) {
                markHealth(p, "ERROR", "Installed APK source metadata does not match installed version " + installed);
                return;
            }

            File canonical = canonicalZip(c);
            long recordedVersion = p.getLong("canonical_source_version_code", -1L);
            String recordedHash = p.getString("canonical_source_sha256", "").toLowerCase(Locale.US);
            boolean healthy = canonical.isFile() && canonical.length() > 0L && recordedVersion == installed
                    && embeddedHash.equals(recordedHash) && embeddedHash.equals(sha256(canonical));
            if (!healthy) {
                // Manual/APK-Factory installs can legitimately bypass Lumi's in-app source staging path.
                // Seed/repair the canonical source from the source snapshot embedded in the installed APK.
                File tmp = new File(root, "canonical-source.zip.new");
                copyAsset(c, ASSET_ZIP, tmp, MAX_SOURCE_BYTES);
                String actual = sha256(tmp);
                if (!embeddedHash.equals(actual)) throw new SecurityException("Embedded canonical source checksum mismatch");
                atomicReplace(tmp, canonical);
                writeJsonAtomic(canonicalMeta(c), embedded);
                p.edit().putLong("canonical_source_version_code", installed)
                        .putString("canonical_source_sha256", actual)
                        .putString("canonical_source_origin", "embedded-apk-baseline")
                        .putLong("canonical_source_promoted_at", System.currentTimeMillis())
                        .apply();
                ledger(c, "SEED", -1L, installed, "", actual, "Canonical source recovered from installed APK baseline");
            }
            markHealth(p, "HEALTHY", "Canonical source matches installed Lumi code " + installed);
        } catch (Throwable t) {
            markHealth(p, "ERROR", t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    /**
     * Mandatory precondition for every in-app core staging transaction on Code325+.
     * The source snapshot is preserved before the pending APK is published.
     */
    static JSONObject verifyAndStageCoreSource(Context c, SharedPreferences p, JSONObject manifest,
                                               File staging, File stagedApk, long incomingVersionCode,
                                               String updateId) throws Exception {
        JSONObject record = manifest.optJSONObject("sourceRecord");
        if (record == null) throw new SecurityException("Core update is missing required sourceRecord");
        if (record.optInt("formatVersion", -1) != FORMAT_VERSION) throw new SecurityException("Unsupported sourceRecord format");

        long current = currentVersionCode(c);
        long baseVersion = record.optLong("baseVersionCode", -1L);
        long targetVersion = record.optLong("targetVersionCode", -1L);
        if (baseVersion != current) throw new SecurityException("Source record base version does not match installed Lumi");
        if (targetVersion != incomingVersionCode) throw new SecurityException("Source record target version does not match core APK");

        initialize(c, p);
        String baseHash = record.optString("baseSourceSha256", "").toLowerCase(Locale.US);
        String expectedCurrent = p.getString("canonical_source_sha256", "").toLowerCase(Locale.US);
        long expectedCurrentVersion = p.getLong("canonical_source_version_code", -1L);
        if (expectedCurrentVersion != current || !isSha(expectedCurrent)) throw new SecurityException("Current canonical source is not healthy; core update blocked");
        if (!expectedCurrent.equals(baseHash)) throw new SecurityException("Source record is based on different Lumi source; merge/rebase required");

        String sourcePath = safePayloadPath(record.getString("sourceArchivePath"));
        String changePath = safePayloadPath(record.getString("changeRecordPath"));
        String patchPath = safePayloadPath(record.getString("patchPath"));
        String targetHash = record.optString("targetSourceSha256", "").toLowerCase(Locale.US);
        if (!isSha(targetHash)) throw new SecurityException("Target source checksum is malformed");

        File sourcePayload = stagedPayloadForPath(manifest, staging, sourcePath);
        File changePayload = stagedPayloadForPath(manifest, staging, changePath);
        File patchPayload = stagedPayloadForPath(manifest, staging, patchPath);
        if (!sourcePayload.isFile() || sourcePayload.length() <= 0L || sourcePayload.length() > MAX_SOURCE_BYTES)
            throw new SecurityException("Canonical source payload is missing or too large");
        String sourceActual = sha256(sourcePayload);
        if (!targetHash.equals(sourceActual)) throw new SecurityException("Canonical source payload checksum mismatch");

        JSONObject change = new JSONObject(readUtf8(changePayload, 2 * 1024 * 1024));
        validateChangeRecord(change, baseVersion, targetVersion, baseHash, targetHash, updateId);
        if (!patchPayload.isFile() || patchPayload.length() <= 0L || patchPayload.length() > 8L * 1024L * 1024L)
            throw new SecurityException("Source patch is missing or too large");

        // Prove the source payload is the exact deterministic baseline embedded in the incoming APK.
        JSONObject apkMeta;
        String apkSourceHash;
        try (ZipFile apkZip = new ZipFile(stagedApk)) {
            apkMeta = readZipJson(apkZip, APK_META_ENTRY, 256 * 1024);
            ZipEntry embeddedSource = apkZip.getEntry(APK_ZIP_ENTRY);
            if (embeddedSource == null) throw new SecurityException("Incoming core APK does not embed canonical source");
            apkSourceHash = sha256(apkZip.getInputStream(embeddedSource), MAX_SOURCE_BYTES);
        }
        if (apkMeta.optLong("versionCode", -1L) != incomingVersionCode)
            throw new SecurityException("Incoming APK canonical metadata has wrong version");
        if (!targetHash.equals(apkMeta.optString("sourceArchiveSha256", "").toLowerCase(Locale.US)) || !targetHash.equals(apkSourceHash))
            throw new SecurityException("Incoming APK was not built from the supplied canonical source snapshot");

        File stagedDir = stagedDir(c);
        deleteRecursive(stagedDir);
        if (!stagedDir.mkdirs()) throw new java.io.IOException("Could not create staged canonical source folder");
        File stagedSource = new File(stagedDir, "canonical-source.zip");
        File stagedChange = new File(stagedDir, "source-change-record.json");
        File stagedPatch = new File(stagedDir, "source.patch");
        atomicCopy(sourcePayload, stagedSource);
        atomicCopy(changePayload, stagedChange);
        atomicCopy(patchPayload, stagedPatch);
        JSONObject stagedMeta = new JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("updateId", updateId)
                .put("baseVersionCode", baseVersion)
                .put("targetVersionCode", targetVersion)
                .put("baseSourceSha256", baseHash)
                .put("targetSourceSha256", targetHash)
                .put("stagedAt", System.currentTimeMillis());
        writeJsonAtomic(new File(stagedDir, "staged-source.json"), stagedMeta);
        p.edit().putLong("canonical_source_staged_target_version", targetVersion)
                .putString("canonical_source_staged_target_sha256", targetHash)
                .putString("canonical_source_staged_update_id", updateId)
                .putLong("canonical_source_staged_at", System.currentTimeMillis())
                .apply();
        ledger(c, "STAGE", baseVersion, targetVersion, baseHash, targetHash, "Source preserved before core APK staging; update=" + updateId);
        return stagedMeta;
    }

    private static void promoteStagedIfInstalled(Context c, SharedPreferences p, long installed) throws Exception {
        File staged = stagedDir(c);
        File metaFile = new File(staged, "staged-source.json");
        File source = new File(staged, "canonical-source.zip");
        long prefTarget = p.getLong("canonical_source_staged_target_version", -1L);

        // R58 recovery: a manual or newer trusted install can jump past an older staged
        // source transaction. The staged source is no longer promotable in that case and
        // must not remain visible forever as "waiting for matching installed core".
        // Clear both a complete stale staging directory and orphaned staging preferences
        // once the installed signed core is newer than the staged target.
        if (!metaFile.isFile() || !source.isFile()) {
            if (prefTarget > 0L && prefTarget <= installed) {
                String stagedUpdate = p.getString("canonical_source_staged_update_id", "");
                String stagedHash = p.getString("canonical_source_staged_target_sha256", "");
                deleteRecursive(staged);
                clearStagedPrefs(p);
                ledger(c, "DISCARD_SUPERSEDED_STAGED", -1L, prefTarget, "", stagedHash,
                        "Installed Lumi code " + installed + " superseded orphaned staged source transaction " + stagedUpdate);
            }
            return;
        }
        JSONObject m = new JSONObject(readUtf8(metaFile, 256 * 1024));
        long target = m.optLong("targetVersionCode", -1L);
        if (target > 0L && target < installed) {
            String stagedUpdate = m.optString("updateId", "");
            String stagedHash = m.optString("targetSourceSha256", "");
            long base = m.optLong("baseVersionCode", -1L);
            String baseHash = m.optString("baseSourceSha256", "");
            deleteRecursive(staged);
            clearStagedPrefs(p);
            ledger(c, "DISCARD_SUPERSEDED_STAGED", base, target, baseHash, stagedHash,
                    "Installed Lumi code " + installed + " superseded staged source transaction " + stagedUpdate);
            return;
        }
        if (target != installed) return;
        String targetHash = m.optString("targetSourceSha256", "").toLowerCase(Locale.US);
        if (!isSha(targetHash) || !targetHash.equals(sha256(source))) throw new SecurityException("Staged source failed promotion checksum");

        JSONObject embedded = readAssetJson(c, ASSET_META);
        if (embedded.optLong("versionCode", -1L) != installed || !targetHash.equals(embedded.optString("sourceArchiveSha256", "").toLowerCase(Locale.US))) {
            // R55 manual-repair recovery: a trusted externally built/manual install can legitimately
            // supersede a failed in-app build that targeted the same version code. Do not leave
            // canonical source poisoned by that abandoned staged hash. Discard only the stale
            // staged transaction; initialize() will then seed from the source embedded in the
            // actually installed, correctly signed APK.
            String stagedUpdate=m.optString("updateId","");
            deleteRecursive(staged);
            clearStagedPrefs(p);
            ledger(c, "DISCARD_STALE_STAGED", m.optLong("baseVersionCode",-1L), installed,
                    m.optString("baseSourceSha256",""), targetHash,
                    "Installed signed APK superseded staged source transaction "+stagedUpdate+"; reseeding from embedded APK baseline");
            return;
        }

        atomicCopy(source, canonicalZip(c));
        writeJsonAtomic(canonicalMeta(c), embedded);
        File change = new File(staged, "source-change-record.json");
        File patch = new File(staged, "source.patch");
        if (change.isFile() || patch.isFile()) {
            File history = new File(root(c), "history");
            if (!history.exists()) history.mkdirs();
            long stamp=System.currentTimeMillis();
            if(change.isFile()) atomicCopy(change, new File(history, "change-" + installed + "-" + stamp + ".json"));
            if(patch.isFile()) atomicCopy(patch, new File(history, "patch-" + installed + "-" + stamp + ".patch"));
        }
        String baseHash = m.optString("baseSourceSha256", "");
        long base = m.optLong("baseVersionCode", -1L);
        p.edit().putLong("canonical_source_version_code", installed)
                .putString("canonical_source_sha256", targetHash)
                .putString("canonical_source_origin", "verified-core-transaction")
                .putLong("canonical_source_promoted_at", System.currentTimeMillis())
                .apply();
        clearStagedPrefs(p);
        ledger(c, "PROMOTE", base, installed, baseHash, targetHash, "Installed core matched staged source; canonical source advanced");
        deleteRecursive(staged);
    }

    static JSONObject stageExternallyBuiltCore(Context c, SharedPreferences p, File apk, long incomingVersionCode,
                                                  String updateId, String requestedChange, String provenance) throws Exception {
        long baseVersion=currentVersionCode(c);
        String source=(provenance==null||provenance.trim().isEmpty())?"TRUSTED_EXTERNAL":provenance.trim().toUpperCase(Locale.US);
        boolean relay="TRUSTED_RELAY".equals(source);
        String label=relay?"Trusted relay":"Factory recovery";
        if(incomingVersionCode<=baseVersion) throw new SecurityException(label+" core is not a forward version");
        initialize(c,p);
        String baseHash=p.getString("canonical_source_sha256","").toLowerCase(Locale.US);
        if(p.getLong("canonical_source_version_code",-1L)!=baseVersion || !isSha(baseHash) || !isHealthyNoInit(c,p))
            throw new SecurityException("Canonical source is not healthy; "+label+" core staging blocked");

        JSONObject apkMeta;
        String targetHash;
        File staged=stagedDir(c); deleteRecursive(staged);
        if(!staged.mkdirs()) throw new java.io.IOException("Could not create "+label+" core source staging folder");
        File stagedSource=new File(staged,"canonical-source.zip");
        try(ZipFile zip=new ZipFile(apk)){
            apkMeta=readZipJson(zip,APK_META_ENTRY,256*1024);
            if(apkMeta.optLong("versionCode",-1L)!=incomingVersionCode) throw new SecurityException(label+" core canonical metadata version mismatch");
            targetHash=apkMeta.optString("sourceArchiveSha256","").toLowerCase(Locale.US);
            if(!isSha(targetHash)) throw new SecurityException(label+" core canonical source hash is malformed");
            ZipEntry sourceEntry=zip.getEntry(APK_ZIP_ENTRY);
            if(sourceEntry==null) throw new SecurityException(label+" core does not embed canonical source");
            try(InputStream in=zip.getInputStream(sourceEntry); BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(stagedSource))){ copyLimited(in,out,MAX_SOURCE_BYTES); }
        }
        if(!targetHash.equals(sha256(stagedSource))) throw new SecurityException(label+" core embedded source checksum mismatch");
        if(relay){
            String expectedTarget=p.getString("trusted_core_build_target_source_sha256","").toLowerCase(Locale.US);
            if(isSha(expectedTarget) && !expectedTarget.equals(targetHash)) throw new SecurityException("Trusted relay APK was not built from the exact verified bridge-core source snapshot");
        }

        JSONArray changed=new JSONArray().put("app/build.gradle");
        String overlayPaths=p.getString("trusted_core_build_overlay_paths","");
        if(!overlayPaths.trim().isEmpty()){
            try{ JSONArray extra=new JSONArray(overlayPaths); for(int i=0;i<extra.length();i++){ String v=extra.optString(i,""); if(!v.isEmpty()) changed.put(v); } }catch(Exception ignored){}
        }
        String lower=requestedChange==null?"":requestedChange.toLowerCase(Locale.US);
        JSONObject change=new JSONObject().put("format","LumiSourceChangeRecord").put("formatVersion",FORMAT_VERSION)
                .put("updateId",safe(updateId)).put("baseVersionCode",baseVersion).put("targetVersionCode",incomingVersionCode)
                .put("baseSourceSha256",baseHash).put("targetSourceSha256",targetHash)
                .put("reason",safe(requestedChange)).put("provenance",source).put("filesChanged",changed)
                .put("tests",new JSONArray().put(label+" signed Lumi core").put("Lumi Update Center package validation").put("Lumi signing/version verification").put("Lumi post-install validation"));
        writeJsonAtomic(new File(staged,"source-change-record.json"),change);
        String patch="Externally built trusted core transaction\nprovenance="+source+"\nbaseVersionCode="+baseVersion+"\ntargetVersionCode="+incomingVersionCode+"\nrequestedChange="+safe(requestedChange)+"\noverlaySha256="+p.getString("trusted_core_build_overlay_sha256","")+"\noverlayFiles="+p.getString("trusted_core_build_overlay_paths","[]")+"\n";
        try(FileOutputStream out=new FileOutputStream(new File(staged,"source.patch"))){ out.write(patch.getBytes(StandardCharsets.UTF_8)); }
        JSONObject stagedMeta=new JSONObject().put("formatVersion",FORMAT_VERSION).put("updateId",safe(updateId))
                .put("baseVersionCode",baseVersion).put("targetVersionCode",incomingVersionCode)
                .put("baseSourceSha256",baseHash).put("targetSourceSha256",targetHash).put("provenance",source).put("stagedAt",System.currentTimeMillis());
        writeJsonAtomic(new File(staged,"staged-source.json"),stagedMeta);
        p.edit().putLong("canonical_source_staged_target_version",incomingVersionCode)
                .putString("canonical_source_staged_target_sha256",targetHash)
                .putString("canonical_source_staged_update_id",safe(updateId))
                .putString("canonical_source_staged_provenance",source)
                .putLong("canonical_source_staged_at",System.currentTimeMillis()).apply();
        ledger(c,relay?"STAGE_TRUSTED_RELAY_BUILD":"STAGE_FACTORY_RECOVERY_BUILD",baseVersion,incomingVersionCode,baseHash,targetHash,label+" core source preserved before Android install approval; update="+safe(updateId));
        return stagedMeta;
    }

    static JSONObject stageFactoryBuiltCore(Context c, SharedPreferences p, File apk, long incomingVersionCode,
                                                String updateId, String requestedChange) throws Exception {
        return stageExternallyBuiltCore(c,p,apk,incomingVersionCode,updateId,requestedChange,"FACTORY_RECOVERY");
    }

    static JSONObject stageTrustedRelayBuiltCore(Context c, SharedPreferences p, File apk, long incomingVersionCode,
                                                   String updateId, String requestedChange) throws Exception {
        return stageExternallyBuiltCore(c,p,apk,incomingVersionCode,updateId,requestedChange,"TRUSTED_RELAY");
    }

    static String statusSummary(Context c, SharedPreferences p) {
        initialize(c, p);
        long v = p.getLong("canonical_source_version_code", -1L);
        String h = p.getString("canonical_source_sha256", "");
        String health = p.getString("canonical_source_health", "UNKNOWN");
        String detail = p.getString("canonical_source_health_detail", "No source status yet");
        long staged = p.getLong("canonical_source_staged_target_version", -1L);
        StringBuilder s = new StringBuilder();
        s.append("Status: ").append(health).append("\n");
        s.append("Canonical version: ").append(v < 0 ? "none" : v).append("\n");
        s.append("Source SHA-256: ").append(shortHash(h)).append("\n");
        s.append("Origin: ").append(p.getString("canonical_source_origin", "none")).append("\n");
        if (staged > 0) s.append("Staged target: ").append(staged).append(" • waiting for matching installed core\n");
        s.append(detail);
        return s.toString();
    }

    static boolean isHealthy(Context c, SharedPreferences p) {
        initialize(c, p);
        try {
            long installed = currentVersionCode(c);
            return "HEALTHY".equals(p.getString("canonical_source_health", ""))
                    && p.getLong("canonical_source_version_code", -1L) == installed
                    && canonicalZip(c).isFile();
        } catch (Exception e) { return false; }
    }

    static File canonicalArchive(Context c, SharedPreferences p) throws Exception {
        initialize(c, p);
        if (!isHealthyNoInit(c, p)) throw new java.io.FileNotFoundException("Canonical source is not healthy");
        return canonicalZip(c);
    }

    static String exportFileName(Context c, SharedPreferences p) {
        long v = p.getLong("canonical_source_version_code", -1L);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        return "Lumi-Code" + (v < 0 ? "Unknown" : v) + "-CANONICAL-SOURCE-" + stamp + ".zip";
    }

    static String ledgerTail(Context c, int maxChars) {
        File f = new File(root(c), "canonical-source-ledger.jsonl");
        if (!f.isFile()) return "No canonical-source transactions recorded.";
        try {
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(all, StandardCharsets.UTF_8);
            if (s.length() > maxChars) s = s.substring(s.length() - maxChars);
            return s;
        } catch (Exception e) { return "Could not read canonical-source ledger: " + safe(e.getMessage()); }
    }

    private static void clearStagedPrefs(SharedPreferences p) {
        p.edit().remove("canonical_source_staged_target_version")
                .remove("canonical_source_staged_target_sha256")
                .remove("canonical_source_staged_update_id")
                .remove("canonical_source_staged_at")
                .apply();
    }

    private static void validateChangeRecord(JSONObject c, long baseVersion, long targetVersion, String baseHash, String targetHash, String updateId) throws Exception {
        if (!"LumiSourceChangeRecord".equals(c.optString("format"))) throw new SecurityException("Invalid source change record format");
        if (c.optInt("formatVersion", -1) != FORMAT_VERSION) throw new SecurityException("Unsupported source change record version");
        if (c.optLong("baseVersionCode", -1L) != baseVersion || c.optLong("targetVersionCode", -1L) != targetVersion)
            throw new SecurityException("Source change record version mismatch");
        if (!baseHash.equals(c.optString("baseSourceSha256", "").toLowerCase(Locale.US)) || !targetHash.equals(c.optString("targetSourceSha256", "").toLowerCase(Locale.US)))
            throw new SecurityException("Source change record checksum mismatch");
        String recUpdate = c.optString("updateId", "");
        if (!recUpdate.isEmpty() && !updateId.equals(recUpdate)) throw new SecurityException("Source change record update id mismatch");
        JSONArray changed = c.optJSONArray("filesChanged");
        if (changed == null) throw new SecurityException("Source change record must list filesChanged");
        String reason = c.optString("reason", "").trim();
        if (reason.isEmpty()) throw new SecurityException("Source change record must explain the reason for the core change");
    }

    private static File stagedPayloadForPath(JSONObject manifest, File staging, String path) throws Exception {
        JSONArray files = manifest.optJSONArray("files");
        if (files == null) throw new SecurityException("Core update has no declared payload list");
        for (int i = 0; i < files.length(); i++) {
            if (path.equals(files.getJSONObject(i).optString("path"))) return new File(staging, "payload/" + i + ".bin");
        }
        throw new SecurityException("Source record payload is not declared: " + path);
    }

    private static String safePayloadPath(String path) {
        String v = path == null ? "" : path.replace('\\', '/').trim();
        if (!v.startsWith("payload/") || v.contains("../") || v.contains(":")) throw new SecurityException("Unsafe source record payload path");
        return v;
    }

    private static JSONObject readAssetJson(Context c, String asset) throws Exception {
        try (InputStream in = c.getAssets().open(asset)) { return new JSONObject(readUtf8(in, 256 * 1024)); }
    }

    private static JSONObject readZipJson(ZipFile zip, String path, int max) throws Exception {
        ZipEntry e = zip.getEntry(path);
        if (e == null) throw new SecurityException("Incoming APK is missing " + path);
        try (InputStream in = zip.getInputStream(e)) { return new JSONObject(readUtf8(in, max)); }
    }

    private static void copyAsset(Context c, String asset, File out, long max) throws Exception {
        try (InputStream in = new BufferedInputStream(c.getAssets().open(asset));
             OutputStreamCompat os = new OutputStreamCompat(out)) { copyLimited(in, os.out, max); }
    }

    private static String readUtf8(File f, int max) throws Exception { try (InputStream in = new FileInputStream(f)) { return readUtf8(in, max); } }
    private static String readUtf8(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n, total = 0;
        while ((n = in.read(b)) > 0) { total += n; if (total > max) throw new SecurityException("Metadata is too large"); out.write(b, 0, n); }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static long currentVersionCode(Context c) throws Exception {
        PackageInfo i = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? i.getLongVersionCode() : i.versionCode;
    }

    private static File root(Context c) { return new File(c.getFilesDir(), "canonical_source"); }
    private static File canonicalZip(Context c) { return new File(root(c), "canonical-source.zip"); }
    private static File canonicalMeta(Context c) { return new File(root(c), "canonical-source-metadata.json"); }
    private static File stagedDir(Context c) { return new File(root(c), "staged"); }

    private static void markHealth(SharedPreferences p, String state, String detail) {
        p.edit().putString("canonical_source_health", state).putString("canonical_source_health_detail", safe(detail))
                .putLong("canonical_source_health_checked_at", System.currentTimeMillis()).apply();
    }

    private static boolean isHealthyNoInit(Context c, SharedPreferences p) {
        try { return "HEALTHY".equals(p.getString("canonical_source_health", ""))
                && p.getLong("canonical_source_version_code", -1L) == currentVersionCode(c)
                && canonicalZip(c).isFile(); } catch (Exception e) { return false; }
    }

    private static void ledger(Context c, String event, long baseVersion, long targetVersion, String baseHash, String targetHash, String detail) {
        try {
            File f = new File(root(c), "canonical-source-ledger.jsonl");
            if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
            JSONObject o = new JSONObject().put("timestamp", System.currentTimeMillis()).put("event", event)
                    .put("baseVersionCode", baseVersion).put("targetVersionCode", targetVersion)
                    .put("baseSourceSha256", baseHash == null ? "" : baseHash).put("targetSourceSha256", targetHash == null ? "" : targetHash)
                    .put("detail", safe(detail));
            try (FileOutputStream out = new FileOutputStream(f, true)) { out.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) {}
    }

    private static String sha256(File f) throws Exception { try (InputStream in = new FileInputStream(f)) { return sha256(in, MAX_SOURCE_BYTES); } }
    private static String sha256(InputStream in, long max) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[1024 * 1024]; int n; long total = 0;
        while ((n = in.read(b)) > 0) { total += n; if (total > max) throw new SecurityException("Source archive exceeds allowed size"); md.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte x : md.digest()) s.append(String.format(Locale.US, "%02x", x & 0xff)); return s.toString();
    }

    private static boolean isSha(String s) { return s != null && s.matches("[0-9a-f]{64}"); }
    private static String shortHash(String h) { return isSha(h) ? h.substring(0, 12) + "…" : "none"; }
    private static String safe(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim(); }

    private static void writeJsonAtomic(File out, JSONObject json) throws Exception {
        File tmp = new File(out.getAbsolutePath() + ".new"); if (tmp.getParentFile() != null && !tmp.getParentFile().exists()) tmp.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(tmp)) { fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8)); }
        atomicReplace(tmp, out);
    }

    private static void atomicCopy(File source, File dest) throws Exception {
        File tmp = new File(dest.getAbsolutePath() + ".new"); if (tmp.getParentFile() != null && !tmp.getParentFile().exists()) tmp.getParentFile().mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(source)); BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            copyLimited(in, out, MAX_SOURCE_BYTES);
        }
        atomicReplace(tmp, dest);
    }

    private static void atomicReplace(File tmp, File dest) throws Exception {
        if (dest.getParentFile() != null && !dest.getParentFile().exists()) dest.getParentFile().mkdirs();
        if (dest.exists() && !dest.delete()) throw new java.io.IOException("Could not replace canonical source");
        if (!tmp.renameTo(dest)) {
            try (InputStream in = new FileInputStream(tmp); FileOutputStream out = new FileOutputStream(dest)) { copyLimited(in, out, MAX_SOURCE_BYTES); }
            tmp.delete();
        }
    }

    private static void copyLimited(InputStream in, java.io.OutputStream out, long max) throws Exception {
        byte[] b = new byte[64 * 1024]; int n; long total = 0;
        while ((n = in.read(b)) > 0) { total += n; if (total > max) throw new SecurityException("Source archive exceeds allowed size"); out.write(b, 0, n); }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return; if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) deleteRecursive(k); } f.delete();
    }

    /** Tiny closeable wrapper so copyAsset can construct/close its buffered file stream cleanly. */
    private static final class OutputStreamCompat implements AutoCloseable {
        final BufferedOutputStream out;
        OutputStreamCompat(File f) throws Exception { if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs(); out = new BufferedOutputStream(new FileOutputStream(f)); }
        @Override public void close() throws Exception { out.close(); }
    }
}
