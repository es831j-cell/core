package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owner-approved GitHub Actions trusted build relay.
 *
 * Lumi pushes the exact staged source tree to an owner-configured private repository, dispatches
 * the fixed signing workflow, polls by immutable head commit SHA, downloads the resulting APK,
 * verifies package/signing/version locally, then stages it for Android's normal installer.
 * Lumi owns checkpointing and post-install validation. The GitHub token is stored only in
 * Android-Keystore-backed SecretStore and is never exposed to the model or diagnostics.
 */
final class TrustedBuildRelayClient {
    static final String DEFAULT_BRANCH="lumi-release";
    static final String DEFAULT_WORKFLOW="lumi-bridge-build.yml";
    static final String PREFLIGHT_WORKFLOW="lumi-bridge-preflight.yml";
    private static final long PREFLIGHT_FRESH_MS=24L*60L*60L*1000L;
    private static final String API="https://api.github.com";
    private static final int CONNECT_MS=12000, READ_MS=45000;
    private static final long MAX_ARTIFACT_BYTES=850L*1024L*1024L;

    private static final String BRIDGE_BUILD_YAML_B64="bmFtZTogTHVtaSBUcnVzdGVkIFJlbGF5IEJ1aWxkCnJ1bi1uYW1lOiBMdW1pIHRydXN0ZWQgcmVsYXkgJHt7IGlucHV0cy5yZXF1ZXN0X2lkIH19CgpvbjoKICB3b3JrZmxvd19kaXNwYXRjaDoKICAgIGlucHV0czoKICAgICAgcmVxdWVzdF9pZDoKICAgICAgICBkZXNjcmlwdGlvbjogT3duZXItYXBwcm92ZWQgR3VhcmRpYW4gcmVxdWVzdCBpZAogICAgICAgIHJlcXVpcmVkOiB0cnVlCiAgICAgICAgdHlwZTogc3RyaW5nCiAgICAgIHRhcmdldF92ZXJzaW9uOgogICAgICAgIGRlc2NyaXB0aW9uOiBFeHBlY3RlZCBmb3J3YXJkIHZlcnNpb24gY29kZQogICAgICAgIHJlcXVpcmVkOiB0cnVlCiAgICAgICAgdHlwZTogc3RyaW5nCiAgICAgIHRhcmdldF9uYW1lOgogICAgICAgIGRlc2NyaXB0aW9uOiBFeHBlY3RlZCB2ZXJzaW9uIG5hbWUKICAgICAgICByZXF1aXJlZDogdHJ1ZQogICAgICAgIHR5cGU6IHN0cmluZwogICAgICB0YXJnZXRfc291cmNlX3NoYTI1NjoKICAgICAgICBkZXNjcmlwdGlvbjogRXhhY3QgdmVyaWZpZWQgY2Fub25pY2FsLXNvdXJjZSBhcmNoaXZlIFNIQS0yNTYKICAgICAgICByZXF1aXJlZDogdHJ1ZQogICAgICAgIHR5cGU6IHN0cmluZwoKcGVybWlzc2lvbnM6CiAgY29udGVudHM6IHJlYWQKCmpvYnM6CiAgc2lnbmVkLWJ1aWxkOgogICAgcnVucy1vbjogdWJ1bnR1LWxhdGVzdAogICAgdGltZW91dC1taW51dGVzOiAzNQogICAgc3RlcHM6CiAgICAgIC0gdXNlczogYWN0aW9ucy9jaGVja291dEB2NAogICAgICAgIHdpdGg6CiAgICAgICAgICBmZXRjaC1kZXB0aDogMAogICAgICAtIG5hbWU6IFZlcmlmeSByZWxheSB0cmFuc2FjdGlvbiBtYXJrZXIKICAgICAgICBzaGVsbDogYmFzaAogICAgICAgIHJ1bjogfAogICAgICAgICAgc2V0IC1ldW8gcGlwZWZhaWwKICAgICAgICAgIHRlc3QgLXMgLmx1bWkvcmVsYXkuanNvbgogICAgICAgICAgcHl0aG9uIC0gPDwnUFknCiAgICAgICAgICBpbXBvcnQganNvbiwgcmUKICAgICAgICAgIGQ9anNvbi5sb2FkKG9wZW4oJy5sdW1pL3JlbGF5Lmpzb24nLCdyJyxlbmNvZGluZz0ndXRmLTgnKSkKICAgICAgICAgIHJlcT0nJHt7IGlucHV0cy5yZXF1ZXN0X2lkIH19JwogICAgICAgICAgdGFyZ2V0PScke3sgaW5wdXRzLnRhcmdldF92ZXJzaW9uIH19JwogICAgICAgICAgYXNzZXJ0IGQuZ2V0KCdyZXF1ZXN0SWQnKSA9PSByZXEsICdyZWxheSByZXF1ZXN0IG1pc21hdGNoJwogICAgICAgICAgYXNzZXJ0IHN0cihkLmdldCgndGFyZ2V0VmVyc2lvbicpKSA9PSB0YXJnZXQsICdyZWxheSB0YXJnZXQgbWlzbWF0Y2gnCiAgICAgICAgICBhc3NlcnQgZC5nZXQoJ3RhcmdldFNvdXJjZVNoYTI1NicpID09ICcke3sgaW5wdXRzLnRhcmdldF9zb3VyY2Vfc2hhMjU2IH19JywgJ3JlbGF5IHRhcmdldCBzb3VyY2UgbWlzbWF0Y2gnCiAgICAgICAgICBhc3NlcnQgcmUuZnVsbG1hdGNoKHInWzAtOWEtZl17NjR9JywgZC5nZXQoJ3RhcmdldFNvdXJjZVNoYTI1NicsJycpKSwgJ21hbGZvcm1lZCB0YXJnZXQgc291cmNlIGhhc2gnCiAgICAgICAgICBhc3NlcnQgcmUuZnVsbG1hdGNoKHIndHgtWzAtOV0rLVtBLVphLXowLTktXSsnLCByZXEpLCAnbWFsZm9ybWVkIHJlcXVlc3QgaWQnCiAgICAgICAgICBwcmludCgncmVsYXkgbWFya2VyIHZlcmlmaWVkJykKICAgICAgICAgIFBZCiAgICAgIC0gdXNlczogYWN0aW9ucy9zZXR1cC1qYXZhQHY0CiAgICAgICAgd2l0aDoKICAgICAgICAgIGRpc3RyaWJ1dGlvbjogdGVtdXJpbgogICAgICAgICAgamF2YS12ZXJzaW9uOiAnMTcnCiAgICAgIC0gdXNlczogZ3JhZGxlL2FjdGlvbnMvc2V0dXAtZ3JhZGxlQHY0CiAgICAgICAgd2l0aDoKICAgICAgICAgIGdyYWRsZS12ZXJzaW9uOiAnOC45JwogICAgICAtIHVzZXM6IGFuZHJvaWQtYWN0aW9ucy9zZXR1cC1hbmRyb2lkQHYzCiAgICAgIC0gbmFtZTogUmVzdG9yZSBwcml2YXRlIEx1bWkgc2lnbmluZyBpZGVudGl0eSBmcm9tIEZhY3RvcnkgYmFzZWxpbmUKICAgICAgICBzaGVsbDogYmFzaAogICAgICAgIHJ1bjogfAogICAgICAgICAgc2V0IC1ldW8gcGlwZWZhaWwKICAgICAgICAgIGdpdCBmZXRjaCBvcmlnaW4gbWFpbjpyZWZzL3JlbW90ZXMvb3JpZ2luL21haW4gLS1kZXB0aD0xCiAgICAgICAgICBnaXQgc2hvdyBvcmlnaW4vbWFpbjphcGtmYWN0b3J5LXByb2plY3QuemlwID4gL3RtcC9hcGtmYWN0b3J5LXByb2plY3QuemlwCiAgICAgICAgICB1bnppcCAtcCAvdG1wL2Fwa2ZhY3RvcnktcHJvamVjdC56aXAgYXBwL2tleXN0b3JlLnByb3BlcnRpZXMgPiBhcHAva2V5c3RvcmUucHJvcGVydGllcwogICAgICAgICAgdW56aXAgLXAgL3RtcC9hcGtmYWN0b3J5LXByb2plY3QuemlwIGFwcC9sdW1pLXByb3RvdHlwZS1kZWJ1Zy5qa3MgPiBhcHAvbHVtaS1wcm90b3R5cGUtZGVidWcuamtzCiAgICAgICAgICB0ZXN0IC1zIGFwcC9rZXlzdG9yZS5wcm9wZXJ0aWVzCiAgICAgICAgICB0ZXN0IC1zIGFwcC9sdW1pLXByb3RvdHlwZS1kZWJ1Zy5qa3MKICAgICAgICAgIGNobW9kIDYwMCBhcHAva2V5c3RvcmUucHJvcGVydGllcyBhcHAvbHVtaS1wcm90b3R5cGUtZGVidWcuamtzCiAgICAgIC0gbmFtZTogVmVyaWZ5IHJlcXVlc3RlZCB2ZXJzaW9uIG1hdGNoZXMgc291cmNlCiAgICAgICAgc2hlbGw6IGJhc2gKICAgICAgICBydW46IHwKICAgICAgICAgIHNldCAtZXVvIHBpcGVmYWlsCiAgICAgICAgICBncmVwIC1FcSAidmVyc2lvbkNvZGVbWzpzcGFjZTpdXSske3sgaW5wdXRzLnRhcmdldF92ZXJzaW9uIH19KFtbOnNwYWNlOl1dfCQpIiBhcHAvYnVpbGQuZ3JhZGxlCiAgICAgICAgICBncmVwIC1GcSAidmVyc2lvbk5hbWUgJyR7eyBpbnB1dHMudGFyZ2V0X25hbWUgfX0nIiBhcHAvYnVpbGQuZ3JhZGxlCiAgICAgIC0gbmFtZTogQnVpbGQgc2lnbmVkIHJlbGVhc2UKICAgICAgICBydW46IGdyYWRsZSA6YXBwOmFzc2VtYmxlUmVsZWFzZSAtLW5vLWRhZW1vbiAtLXN0YWNrdHJhY2UKICAgICAgLSBuYW1lOiBTZWxlY3QgZXhhY3RseSBvbmUgQVBLIGFuZCBwcm92ZSBjYW5vbmljYWwgc291cmNlIGlkZW50aXR5CiAgICAgICAgc2hlbGw6IGJhc2gKICAgICAgICBydW46IHwKICAgICAgICAgIHNldCAtZXVvIHBpcGVmYWlsCiAgICAgICAgICBtYXBmaWxlIC10IGFwa3MgPCA8KGZpbmQgYXBwL2J1aWxkL291dHB1dHMvYXBrL3JlbGVhc2UgLW1heGRlcHRoIDEgLXR5cGUgZiAtbmFtZSAnKi5hcGsnIC1wcmludCkKICAgICAgICAgIHRlc3QgIiR7I2Fwa3NbQF19IiAtZXEgMQogICAgICAgICAgY3AgIiR7YXBrc1swXX0iIEx1bWktUmVsYXktU2lnbmVkLmFwawogICAgICAgICAgdGVzdCAtcyBMdW1pLVJlbGF5LVNpZ25lZC5hcGsKICAgICAgICAgIHVuemlwIC1wIEx1bWktUmVsYXktU2lnbmVkLmFwayBhc3NldHMvbHVtaS1zb3VyY2UvY2Fub25pY2FsLXNvdXJjZS1tZXRhZGF0YS5qc29uID4gL3RtcC9sdW1pLXNvdXJjZS1tZXRhLmpzb24KICAgICAgICAgIHB5dGhvbiAtIDw8J1BZJwogICAgICAgICAgaW1wb3J0IGpzb24KICAgICAgICAgIGQ9anNvbi5sb2FkKG9wZW4oJy90bXAvbHVtaS1zb3VyY2UtbWV0YS5qc29uJywncicsZW5jb2Rpbmc9J3V0Zi04JykpCiAgICAgICAgICBhc3NlcnQgc3RyKGQuZ2V0KCd2ZXJzaW9uQ29kZScpKSA9PSAnJHt7IGlucHV0cy50YXJnZXRfdmVyc2lvbiB9fScsICdBUEsgY2Fub25pY2FsIHZlcnNpb24gbWlzbWF0Y2gnCiAgICAgICAgICBhc3NlcnQgZC5nZXQoJ3NvdXJjZUFyY2hpdmVTaGEyNTYnKSA9PSAnJHt7IGlucHV0cy50YXJnZXRfc291cmNlX3NoYTI1NiB9fScsICdBUEsgY2Fub25pY2FsIHNvdXJjZSBoYXNoIG1pc21hdGNoJwogICAgICAgICAgcHJpbnQoJ3JlbGF5IEFQSyBjYW5vbmljYWwgc291cmNlIGlkZW50aXR5IHZlcmlmaWVkJykKICAgICAgICAgIFBZCiAgICAgIC0gdXNlczogYWN0aW9ucy91cGxvYWQtYXJ0aWZhY3RAdjQKICAgICAgICB3aXRoOgogICAgICAgICAgbmFtZTogbHVtaS1yZWxheS0ke3sgaW5wdXRzLnJlcXVlc3RfaWQgfX0KICAgICAgICAgIHBhdGg6IEx1bWktUmVsYXktU2lnbmVkLmFwawogICAgICAgICAgaWYtbm8tZmlsZXMtZm91bmQ6IGVycm9yCiAgICAgICAgICByZXRlbnRpb24tZGF5czogNwo=";
    private static final String BRIDGE_PREFLIGHT_YAML_B64="bmFtZTogTHVtaSBCcmlkZ2UgUHJlZmxpZ2h0CnJ1bi1uYW1lOiBMdW1pIGJyaWRnZSBwcmVmbGlnaHQgJHt7IGlucHV0cy5yZXF1ZXN0X2lkIH19CgpvbjoKICB3b3JrZmxvd19kaXNwYXRjaDoKICAgIGlucHV0czoKICAgICAgcmVxdWVzdF9pZDoKICAgICAgICBkZXNjcmlwdGlvbjogQnJpZGdlIHByZWZsaWdodCByZXF1ZXN0IGlkCiAgICAgICAgcmVxdWlyZWQ6IHRydWUKICAgICAgICB0eXBlOiBzdHJpbmcKCnBlcm1pc3Npb25zOgogIGNvbnRlbnRzOiByZWFkCgpqb2JzOgogIHByZWZsaWdodDoKICAgIHJ1bnMtb246IHVidW50dS1sYXRlc3QKICAgIHRpbWVvdXQtbWludXRlczogNQogICAgc3RlcHM6CiAgICAgIC0gdXNlczogYWN0aW9ucy9jaGVja291dEB2NAogICAgICAtIHVzZXM6IGFjdGlvbnMvc2V0dXAtamF2YUB2NAogICAgICAgIHdpdGg6CiAgICAgICAgICBkaXN0cmlidXRpb246IHRlbXVyaW4KICAgICAgICAgIGphdmEtdmVyc2lvbjogJzE3JwogICAgICAtIG5hbWU6IExvYWQtdGVzdCByZXRhaW5lZCBwcml2YXRlIEx1bWkgc2lnbmluZyBiYXNlbGluZQogICAgICAgIHNoZWxsOiBiYXNoCiAgICAgICAgcnVuOiB8CiAgICAgICAgICBzZXQgLWV1byBwaXBlZmFpbAogICAgICAgICAgUkVRPScke3sgaW5wdXRzLnJlcXVlc3RfaWQgfX0nCiAgICAgICAgICBbWyAiJFJFUSIgPX4gXnBmLVswLTldKy1bQS1aYS16MC05XSskIF1dCiAgICAgICAgICB0ZXN0IC1zIGFwa2ZhY3RvcnktcHJvamVjdC56aXAKICAgICAgICAgIHVuemlwIC1wIGFwa2ZhY3RvcnktcHJvamVjdC56aXAgYXBwL2tleXN0b3JlLnByb3BlcnRpZXMgPiAvdG1wL2tleXN0b3JlLnByb3BlcnRpZXMKICAgICAgICAgIHVuemlwIC1wIGFwa2ZhY3RvcnktcHJvamVjdC56aXAgYXBwL2x1bWktcHJvdG90eXBlLWRlYnVnLmprcyA+IC90bXAvbHVtaS1wcmVmbGlnaHQuamtzCiAgICAgICAgICB0ZXN0IC1zIC90bXAva2V5c3RvcmUucHJvcGVydGllcwogICAgICAgICAgdGVzdCAtcyAvdG1wL2x1bWktcHJlZmxpZ2h0LmprcwogICAgICAgICAgU1RPUkVfUEFTU1dPUkQ9IiQoc2VkIC1uICdzL15zdG9yZVBhc3N3b3JkPS8vcCcgL3RtcC9rZXlzdG9yZS5wcm9wZXJ0aWVzIHwgaGVhZCAtMSkiCiAgICAgICAgICBLRVlfQUxJQVM9IiQoc2VkIC1uICdzL15rZXlBbGlhcz0vL3AnIC90bXAva2V5c3RvcmUucHJvcGVydGllcyB8IGhlYWQgLTEpIgogICAgICAgICAgdGVzdCAtbiAiJFNUT1JFX1BBU1NXT1JEIgogICAgICAgICAgdGVzdCAtbiAiJEtFWV9BTElBUyIKICAgICAgICAgIGtleXRvb2wgLWxpc3QgLWtleXN0b3JlIC90bXAvbHVtaS1wcmVmbGlnaHQuamtzIC1zdG9yZXBhc3MgIiRTVE9SRV9QQVNTV09SRCIgLWFsaWFzICIkS0VZX0FMSUFTIiA+L2Rldi9udWxsCiAgICAgICAgICBybSAtZiAvdG1wL2x1bWktcHJlZmxpZ2h0LmprcyAvdG1wL2tleXN0b3JlLnByb3BlcnRpZXMKICAgICAgICAgIGVjaG8gIkx1bWkgYnJpZGdlIHJldGFpbmVkIHNpZ25pbmcgYmFzZWxpbmUgUEFTUyBmb3IgJFJFUSIK";

    private TrustedBuildRelayClient(){}

    static JSONObject status(SharedPreferences p)throws Exception{
        String owner=p.getString("build_relay_github_owner","").trim(); String repo=p.getString("build_relay_github_repo","").trim();
        String branch=p.getString("build_relay_github_branch",DEFAULT_BRANCH).trim(); String workflow=p.getString("build_relay_github_workflow",DEFAULT_WORKFLOW).trim();
        boolean token=!SecretStore.get(p,"github_build_token").trim().isEmpty();
        return new JSONObject().put("provider","github-actions").put("configured",!owner.isEmpty()&&!repo.isEmpty()&&token)
                .put("owner",owner).put("repo",repo).put("branch",branch).put("workflow",workflow)
                .put("active",p.getBoolean("trusted_core_build_active",false)).put("request_id",p.getString("trusted_core_build_request_id",""))
                .put("stage",p.getString("trusted_core_build_stage","IDLE")).put("target_version",p.getLong("trusted_core_build_target_version",-1L))
                .put("error",p.getString("trusted_core_build_error","")).put("run_id",p.getLong("trusted_core_build_run_id",-1L))
                .put("commit_sha",p.getString("trusted_core_build_commit_sha",""))
                .put("preflight_ok",p.getBoolean("build_relay_last_preflight_ok",false))
                .put("preflight_at",p.getLong("build_relay_last_preflight_at",0L))
                .put("preflight_detail",p.getString("build_relay_last_preflight_detail",""))
                .put("workflow_id",p.getLong("build_relay_workflow_id",-1L))
                .put("preflight_workflow_id",p.getLong("build_relay_preflight_workflow_id",-1L))
                 .put("retry_count",p.getInt("trusted_core_build_poll_failures",0))
                .put("ref_rebase_count",p.getInt("trusted_core_build_ref_rebase_count",0))
                .put("ref_sync_head",p.getString("trusted_core_build_ref_sync_head",""));
    }

    static String statusSummary(SharedPreferences p){
        try{
            JSONObject o=status(p);
            String repo=o.optString("owner","");
            if(!repo.isEmpty()) repo+="/"+o.optString("repo","");
            else repo="not configured";
            String commit=o.optString("commit_sha","");
            if(commit.length()>12) commit=commit.substring(0,12)+"…";
            String error=SecretStore.redact(o.optString("error",""));
            StringBuilder s=new StringBuilder();
            s.append("Provider: GitHub Actions • configured=").append(o.optBoolean("configured",false)).append("\n");
            s.append("Repository: ").append(repo).append(" • branch=").append(o.optString("branch",DEFAULT_BRANCH)).append("\n");
            s.append("Stage: ").append(o.optString("stage","IDLE")).append(" • active=").append(o.optBoolean("active",false));
            long target=o.optLong("target_version",-1L); if(target>0) s.append(" • target=").append(target); s.append("\n");
            String id=o.optString("request_id",""); if(!id.isEmpty()) s.append("Request: ").append(id).append("\n");
            if(!commit.isEmpty()) s.append("Commit: ").append(commit).append("\n");
            int rebases=o.optInt("ref_rebase_count",0);
            if(rebases>0) s.append("Branch sync: recovered ").append(rebases).append(" non-fast-forward race").append(rebases==1?"":"s").append("\n");
            s.append("Preflight: ").append(o.optBoolean("preflight_ok",false)?"PASS":"NOT VERIFIED");
            long pf=o.optLong("preflight_at",0L); if(pf>0L) s.append(" • ageSec=").append(Math.max(0L,(System.currentTimeMillis()-pf)/1000L)); s.append("\n");
            if(!error.isEmpty()) s.append("Error: ").append(error).append("\n");
            s.append("Credentials: encrypted locally • never exported");
            return s.toString();
        }catch(Exception e){ return "Provider: GitHub Actions • status unavailable: "+SecretStore.redact(e.getMessage()); }
    }

    static void configure(SharedPreferences p,String owner,String repo,String branch,String workflow,String token){
        String o=slug(owner),r=slug(repo); if(o.isEmpty()||r.isEmpty()) throw new IllegalArgumentException("GitHub owner and repository are required");
        String requestedBranch=branch==null?"":branch.trim();
        String b=safeBranch(requestedBranch.isEmpty()?DEFAULT_BRANCH:requestedBranch);
        String w=workflow==null||workflow.trim().isEmpty()?DEFAULT_WORKFLOW:workflow.trim();
        if(w.startsWith(".github/workflows/")) w=w.substring(".github/workflows/".length());
        // Migration from the APK Factory repository values used during R98 troubleshooting.
        // main is the repository base branch; Lumi always isolates staged source on lumi-release.
        if("main".equalsIgnoreCase(b) && "apk-factory-build.yml".equalsIgnoreCase(w)){ b=DEFAULT_BRANCH; w=DEFAULT_WORKFLOW; }
        if(!w.matches("[A-Za-z0-9._-]{3,120}\\.ya?ml")) throw new IllegalArgumentException("Invalid workflow filename");
        if(token!=null && !token.trim().isEmpty()) SecretStore.put(p,"github_build_token",token.trim());
        if(SecretStore.get(p,"github_build_token").trim().isEmpty()) throw new IllegalArgumentException("A GitHub token is required");
        p.edit().putString("build_relay_provider","github-actions").putString("build_relay_github_owner",o).putString("build_relay_github_repo",r)
                .putString("build_relay_github_branch",b).putString("build_relay_github_workflow",w).putLong("build_relay_configured_at",System.currentTimeMillis()).apply();
    }

    static JSONObject configureAndPreflight(Context c,SharedPreferences p,String owner,String repo,String branch,String workflow,String token)throws Exception{
        String supplied=token==null?"":token.trim();
        if(!supplied.isEmpty()) SecretStore.put(p,"github_build_token",supplied);
        String saved=SecretStore.get(p,"github_build_token").trim();
        if(saved.isEmpty()) throw new IllegalArgumentException("A GitHub token is required");
        String o=slug(owner);
        if(o.isEmpty()){
            Config temp=new Config("","",DEFAULT_BRANCH,DEFAULT_WORKFLOW,saved);
            JSONObject user=requestJsonRetry(temp,"GET",API+"/user",null,200);
            o=slug(user.optString("login",""));
            if(o.isEmpty()) throw new SecurityException("GitHub token was accepted but the account owner could not be determined");
        }
        configure(p,o,repo,branch,workflow,null);
        return preflight(c,p,true);
    }

    static JSONObject test(Context c,SharedPreferences p)throws Exception{
        return preflight(c,p,true);
    }

    /**
     * Commissioning/pre-build load test. This proves the saved credential can read the private
     * repository, push source, read the fixed workflows, dispatch Actions and prove Lumi's native
     * self-update engine before an update transaction is allowed onto the relay.
     */
    static JSONObject preflight(Context c,SharedPreferences p,boolean forceActionsProbe)throws Exception{
        Config cfg=config(p); long started=System.currentTimeMillis();
        try{
            JSONObject repo=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo);
            if(!repo.optBoolean("private",false)) throw new SecurityException("Trusted build relay repository must be private");
            JSONObject permissions=repo.optJSONObject("permissions");
            if(permissions!=null && !permissions.optBoolean("push",false)) throw new SecurityException("GitHub token can read the relay repository but cannot push source");
            String defaultBranch=repo.optString("default_branch","main");
            if(cfg.branch.equals(defaultBranch)) throw new SecurityException("Relay branch must be separate from the repository default branch; use "+DEFAULT_BRANCH);

            ensureRelayWorkflows(cfg,defaultBranch);
            WorkflowRef buildWorkflow=resolveWorkflow(cfg,DEFAULT_WORKFLOW);
            WorkflowRef preflightWorkflow=resolveWorkflow(cfg,PREFLIGHT_WORKFLOW);
            if(!"active".equalsIgnoreCase(buildWorkflow.state)) throw new SecurityException("Trusted build workflow is not active");
            if(!"active".equalsIgnoreCase(preflightWorkflow.state)) throw new SecurityException("Bridge preflight workflow is not active");
            p.edit().putLong("build_relay_workflow_id",buildWorkflow.id)
                    .putLong("build_relay_preflight_workflow_id",preflightWorkflow.id)
                    .putString("build_relay_github_workflow",DEFAULT_WORKFLOW).apply();

            String defaultHead=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/ref/heads/"+url(defaultBranch)).getJSONObject("object").getString("sha");
            String relayHead=ensureBranch(cfg,defaultBranch);
            patchJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/refs/heads/"+url(cfg.branch),new JSONObject().put("sha",relayHead).put("force",false),200);

            Bundle bridgeArgs=new Bundle(); bridgeArgs.putString("transaction_id","relay-preflight-"+System.currentTimeMillis());
            Bundle probe=LumiSelfUpdateEngine.call(c,"bridge_probe",bridgeArgs);
            if(!probe.getBoolean("ok",false) || !probe.getBoolean("lumiRoundTrip",false) || !probe.getBoolean("transactionEchoOk",false)
                    || !probe.getBoolean("lumiIdentityOk",false) || !probe.getBoolean("maintenanceHostReady",false))
                throw new SecurityException("Lumi native self-update preflight failed: "+probe.getString("failedStage","unknown"));
            if(c.getFilesDir().getUsableSpace()<250L*1024L*1024L) throw new java.io.IOException("Less than 250 MB free storage; relay build staging is blocked");

            long last=p.getLong("build_relay_last_actions_probe_at",0L);
            boolean actionsProbe=forceActionsProbe || !p.getBoolean("build_relay_last_actions_probe_ok",false) || System.currentTimeMillis()-last>PREFLIGHT_FRESH_MS;
            String probeId="pf-"+System.currentTimeMillis()+"-"+Integer.toHexString((int)(Math.random()*0xFFFFFF));
            if(actionsProbe){
                JSONObject inputs=new JSONObject().put("request_id",probeId);
                postNoContent(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/actions/workflows/"+preflightWorkflow.id+"/dispatches",
                        new JSONObject().put("ref",defaultBranch).put("inputs",inputs),204);
                waitForPreflightRun(cfg,preflightWorkflow.id,defaultBranch,defaultHead,probeId);
                p.edit().putBoolean("build_relay_last_actions_probe_ok",true).putLong("build_relay_last_actions_probe_at",System.currentTimeMillis()).apply();
            }
            String detail="private repo + fixed workflows + numeric workflow IDs + isolated relay branch + protected-secret signing probe + Lumi native self-update + storage passed";
            p.edit().putBoolean("build_relay_last_preflight_ok",true).putLong("build_relay_last_preflight_at",System.currentTimeMillis())
                    .putString("build_relay_last_preflight_detail",detail).remove("build_relay_last_preflight_error").apply();
            return new JSONObject().put("ok",true).put("state","BRIDGE_PREFLIGHT_PASS").put("private",true)
                    .put("repo",repo.optString("full_name",cfg.owner+"/"+cfg.repo)).put("default_branch",defaultBranch)
                    .put("branch",cfg.branch).put("workflow",DEFAULT_WORKFLOW).put("workflow_id",buildWorkflow.id)
                    .put("preflight_workflow",PREFLIGHT_WORKFLOW).put("preflight_workflow_id",preflightWorkflow.id)
                    .put("push_permission",permissions==null?"proven-by-ref-write":permissions.optBoolean("push",false)).put("relay_branch_write",true)
                    .put("actions_probe",actionsProbe?"passed":"fresh-pass-reused").put("native_self_update",true)
                    .put("duration_ms",System.currentTimeMillis()-started);
        }catch(Exception e){
            String detail=SecretStore.redact(e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));
            if(e instanceof HttpStatusException){
                int code=((HttpStatusException)e).code;
                if(code==403||code==404)
                    detail="GitHub blocked the relay repository, workflow, or Actions access. Confirm both Lumi workflows exist on the default branch and grant the token repository Contents and Actions read/write, then run Save & load-test again.";
            }
            p.edit().putBoolean("build_relay_last_preflight_ok",false).putLong("build_relay_last_preflight_at",System.currentTimeMillis())
                    .putString("build_relay_last_preflight_detail",detail).putString("build_relay_last_preflight_error",detail).apply();
            if(detail.startsWith("GitHub blocked the relay")) throw new SecurityException(detail);
            throw e;
        }
    }

    private static void waitForPreflightRun(Config cfg,long workflowId,String branch,String headSha,String probeId)throws Exception{
        long deadline=System.currentTimeMillis()+90000L; boolean seen=false;
        while(System.currentTimeMillis()<deadline){
            JSONObject runs=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/actions/workflows/"+workflowId+"/runs?branch="+url(branch)+"&head_sha="+url(headSha)+"&event=workflow_dispatch&per_page=10");
            JSONArray a=runs.optJSONArray("workflow_runs");
            if(a!=null) for(int i=0;i<a.length();i++){
                JSONObject r=a.getJSONObject(i); String title=r.optString("display_title","");
                if(!title.contains(probeId)) continue; seen=true;
                String status=r.optString("status",""); if(!"completed".equals(status)){Thread.sleep(1500L);break;}
                if(!"success".equals(r.optString("conclusion",""))) throw new SecurityException("GitHub Actions bridge preflight failed: "+r.optString("conclusion","unknown"));
                return;
            }
            Thread.sleep(seen?1500L:1200L);
        }
        throw new java.io.IOException("Timed out waiting for the GitHub Actions bridge preflight");
    }

    static JSONObject start(Context c,SharedPreferences p,String requestId,String requestedChange)throws Exception{
        Config cfg=config(p); String id=safe(requestId);
        if(id.isEmpty()||!id.equals(p.getString("trusted_core_build_request_id",""))) throw new SecurityException("No staged source matches this build request");
        preflight(c,p,false);
        SourcePatchManager.stagedSourceRoot(c,p,id);
        if(!UpdateTransactionManager.matches(p,id)) throw new SecurityException("Lumi core-update transaction is no longer active");
        p.edit().putBoolean("trusted_core_build_active",true).putString("trusted_core_build_stage","RELAY_UPLOAD_QUEUED")
                .putString("trusted_core_build_requested_change",safe(requestedChange)).putInt("trusted_core_build_poll_failures",0)
                .putInt("trusted_core_build_ref_rebase_count",0).remove("trusted_core_build_ref_sync_head")
                .remove("trusted_core_build_run_id").remove("trusted_core_build_commit_sha").remove("trusted_core_build_artifact_sha256")
                .remove("trusted_core_build_guardian_session").remove("trusted_core_build_error").apply();
        UpdateTransactionManager.markStage(p,id,"RELAY_UPLOAD_QUEUED",p.getLong("trusted_core_build_target_version",-1L));
        TrustedBuildRelayJobService.schedule(c,1000L);
        return new JSONObject().put("ok",true).put("state","RELAY_UPLOAD_QUEUED").put("provider","github-actions")
                .put("request_id",id).put("repo",cfg.owner+"/"+cfg.repo).put("branch",cfg.branch).put("target_version",p.getLong("trusted_core_build_target_version",-1L));
    }

    static boolean runOneStep(Context c,SharedPreferences p)throws Exception{
        if(!p.getBoolean("trusted_core_build_active",false)) return false;
        String stage=p.getString("trusted_core_build_stage","RELAY_UPLOAD_QUEUED");
        if("SOURCE_STAGED".equals(stage)||"RELAY_UPLOAD_QUEUED".equals(stage)||"RELAY_UPLOAD_FAILED_RETRYABLE".equals(stage)||"RELAY_UPLOADING_SOURCE".equals(stage)){
            pushAndDispatch(c,p); return true;
        }
        if("RELAY_DISPATCH_PENDING".equals(stage)) return reconcileDispatch(c,p);
        if("WAITING_FOR_RELAY_BUILD".equals(stage)||"RELAY_BUILD_RUNNING".equals(stage)) return pollAndMaybeInstall(c,p);
        if("ANDROID_INSTALL_APPROVAL_REQUIRED".equals(stage)||"ANDROID_INSTALL_APPROVAL_PRESENTED".equals(stage)||"POST_INSTALL_VALIDATION_PENDING".equals(stage)) return pollSelfUpdateCompletion(c,p);
        return false;
    }

    private static void pushAndDispatch(Context c,SharedPreferences p)throws Exception{
        Config cfg=config(p); String id=p.getString("trusted_core_build_request_id",""); File root=SourcePatchManager.stagedSourceRoot(c,p,id);
        JSONObject repo=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo); if(!repo.optBoolean("private",false))throw new SecurityException("Build relay repository must be private");
        String baseBranch=repo.optString("default_branch","main"); String baseHead=ensureBranch(cfg,baseBranch);
        p.edit().putString("trusted_core_build_stage","RELAY_UPLOADING_SOURCE").putString("trusted_core_build_base_commit_sha",baseHead).apply();

        JSONArray tree=new JSONArray(); List<File> files=new ArrayList<>(); collect(root,files); Collections.sort(files,(a,b)->SourcePatchManager.relative(root,a).compareTo(SourcePatchManager.relative(root,b)));
        for(File f:files){String rel=SourcePatchManager.relative(root,f); JSONObject entry=new JSONObject().put("path",rel).put("mode","100644").put("type","blob");
            if(isText(rel) && f.length()<=700_000L) entry.put("content",new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            else entry.put("sha",createBlob(cfg,f)); tree.put(entry);}
        JSONObject marker=new JSONObject().put("format","LumiBridgeRelayCommit").put("requestId",id)
                .put("baseSourceSha256",p.getString("trusted_core_build_base_sha256",""))
                .put("targetSourceSha256",p.getString("trusted_core_build_target_source_sha256",""))
                .put("targetVersion",p.getLong("trusted_core_build_target_version",-1L)).put("createdAt",System.currentTimeMillis());
        tree.put(new JSONObject().put("path",".lumi/relay.json").put("mode","100644").put("type","blob").put("content",marker.toString(2)+"\n"));
        JSONObject tr=postJsonRetry(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/trees",new JSONObject().put("tree",tree),201);
        String treeSha=tr.getString("sha");
        String commitSha=commitAndAdvanceRelayRef(cfg,p,id,treeSha,baseHead);
        p.edit().putString("trusted_core_build_stage","RELAY_DISPATCH_PENDING").putLong("trusted_core_build_dispatch_attempt_at",0L).apply();
        dispatchWorkflow(cfg,p,id);
    }


    /**
     * Advance the isolated relay branch without force-pushing. A concurrent relay/preflight may
     * move the branch after we read its head; GitHub then correctly returns HTTP 422
     * "Update is not a fast forward". Re-read the authoritative remote head, recreate the exact
     * same source-tree commit on top of it, and retry a bounded number of times. Other 422s fail
     * closed so policy/authentication problems are never hidden as harmless races.
     */
    private static String commitAndAdvanceRelayRef(Config cfg,SharedPreferences p,String id,String treeSha,String initialParent)throws Exception{
        String parent=initialParent;
        for(int attempt=0;attempt<4;attempt++){
            JSONObject commit=postJsonRetry(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/commits",
                    new JSONObject().put("message","Lumi bridge build "+id+(attempt==0?"":" ref-sync-"+attempt))
                            .put("tree",treeSha).put("parents",new JSONArray().put(parent)),201);
            String commitSha=commit.getString("sha");
            p.edit().putString("trusted_core_build_commit_sha",commitSha).putString("trusted_core_build_stage","RELAY_COMMIT_CREATED").apply();
            try{
                patchJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/refs/heads/"+url(cfg.branch),
                        new JSONObject().put("sha",commitSha).put("force",false),200);
                return commitSha;
            }catch(HttpStatusException e){
                if(!isNonFastForward(e) || attempt>=3) throw e;
                String remoteHead=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/ref/heads/"+url(cfg.branch))
                        .getJSONObject("object").getString("sha");
                if(remoteHead.equals(parent)) throw e;
                int rebases=p.getInt("trusted_core_build_ref_rebase_count",0)+1;
                p.edit().putInt("trusted_core_build_ref_rebase_count",rebases)
                        .putString("trusted_core_build_ref_sync_head",remoteHead)
                        .putString("trusted_core_build_stage","RELAY_REF_RESYNC")
                        .putString("trusted_core_build_error","Recovered GitHub branch race; retrying safely without force-push")
                        .apply();
                parent=remoteHead;
            }
        }
        throw new java.io.IOException("Could not synchronize the trusted relay branch after bounded retries");
    }

    private static boolean isNonFastForward(HttpStatusException e){
        if(e==null || e.code!=422) return false;
        String m=String.valueOf(e.getMessage()).toLowerCase(Locale.US);
        return m.contains("not a fast forward") || m.contains("not fast forward");
    }

    private static void dispatchWorkflow(Config cfg,SharedPreferences p,String id)throws Exception{
        JSONObject inputs=new JSONObject().put("request_id",id).put("target_version",String.valueOf(p.getLong("trusted_core_build_target_version",-1L)))
                .put("target_name",p.getString("trusted_core_build_target_name","Lumi bridge build"))
                .put("target_source_sha256",p.getString("trusted_core_build_target_source_sha256",""));
        p.edit().putLong("trusted_core_build_dispatch_attempt_at",System.currentTimeMillis()).putString("trusted_core_build_stage","RELAY_DISPATCH_PENDING").apply();
        long workflowId=workflowId(p,"build_relay_workflow_id",cfg,DEFAULT_WORKFLOW);
        postNoContent(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/actions/workflows/"+workflowId+"/dispatches",
                new JSONObject().put("ref",cfg.branch).put("inputs",inputs),204);
        long now=System.currentTimeMillis(); p.edit().putString("trusted_core_build_stage","WAITING_FOR_RELAY_BUILD")
                .putLong("trusted_core_build_dispatched_at",now).remove("trusted_core_build_error").apply();
        UpdateTransactionManager.markStage(p,id,"WAITING_FOR_RELAY_BUILD",p.getLong("trusted_core_build_target_version",-1L));
    }

    /** Reconcile a dispatch whose HTTP response may have been lost before attempting a duplicate. */
    private static boolean reconcileDispatch(Context c,SharedPreferences p)throws Exception{
        Config cfg=config(p); String id=p.getString("trusted_core_build_request_id",""); String commit=p.getString("trusted_core_build_commit_sha","");
        if(id.isEmpty()||commit.isEmpty()) throw new SecurityException("Relay dispatch state is incomplete");
        long workflowId=workflowId(p,"build_relay_workflow_id",cfg,DEFAULT_WORKFLOW);
        String path="/repos/"+cfg.owner+"/"+cfg.repo+"/actions/workflows/"+workflowId+"/runs?branch="+url(cfg.branch)+"&head_sha="+url(commit)+"&event=workflow_dispatch&per_page=5";
        JSONObject runs=getJson(cfg,path); JSONArray arr=runs.optJSONArray("workflow_runs");
        if(arr!=null && arr.length()>0){
            p.edit().putString("trusted_core_build_stage","WAITING_FOR_RELAY_BUILD").putLong("trusted_core_build_dispatched_at",System.currentTimeMillis()).remove("trusted_core_build_error").apply();
            UpdateTransactionManager.markStage(p,id,"WAITING_FOR_RELAY_BUILD",p.getLong("trusted_core_build_target_version",-1L));
            return true;
        }
        long attempted=p.getLong("trusted_core_build_dispatch_attempt_at",0L);
        if(attempted>0L && System.currentTimeMillis()-attempted<15000L) return true;
        dispatchWorkflow(cfg,p,id); return true;
    }

    private static boolean pollAndMaybeInstall(Context c,SharedPreferences p)throws Exception{
        Config cfg=config(p); String id=p.getString("trusted_core_build_request_id",""); String commit=p.getString("trusted_core_build_commit_sha",""); if(id.isEmpty()||commit.isEmpty())throw new SecurityException("Trusted build state is incomplete");
        long workflowId=workflowId(p,"build_relay_workflow_id",cfg,DEFAULT_WORKFLOW);
        String path="/repos/"+cfg.owner+"/"+cfg.repo+"/actions/workflows/"+workflowId+"/runs?branch="+url(cfg.branch)+"&head_sha="+url(commit)+"&event=workflow_dispatch&per_page=5";
        JSONObject runs=getJson(cfg,path); JSONArray arr=runs.optJSONArray("workflow_runs"); if(arr==null||arr.length()==0){p.edit().putString("trusted_core_build_stage","WAITING_FOR_RELAY_BUILD").apply();return true;}
        JSONObject run=arr.getJSONObject(0); long runId=run.getLong("id"); String status=run.optString("status",""); String conclusion=run.optString("conclusion","");
        p.edit().putLong("trusted_core_build_run_id",runId).putString("trusted_core_build_stage","completed".equals(status)?"RELAY_BUILD_COMPLETE":"RELAY_BUILD_RUNNING").apply();
        if(!"completed".equals(status)) return true;
        if(!"success".equals(conclusion)){fail(p,id,"GitHub Actions build failed: "+conclusion);return false;}
        JSONObject arts=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/actions/runs/"+runId+"/artifacts"); JSONArray aa=arts.optJSONArray("artifacts"); JSONObject chosen=null;
        if(aa!=null)for(int i=0;i<aa.length();i++){JSONObject x=aa.getJSONObject(i);if(!x.optBoolean("expired",false)&&x.optString("name","").equals("lumi-relay-"+id)){chosen=x;break;}}
        if(chosen==null)throw new java.io.FileNotFoundException("Signed Lumi relay artifact was not published by the build workflow");
        File dir=new File(c.getFilesDir(),"trusted_core_build/"+id.replaceAll("[^A-Za-z0-9._-]","_")+"/artifact");if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException("Could not create relay artifact folder");
        File zip=new File(dir,"github-artifact.zip"); download(cfg,chosen.getString("archive_download_url"),zip);
        File apk=extractSingleApk(zip,dir);
        long target=LumiUpdateManager.stageTrustedRelayBuiltCore(c,p,apk,"relay-"+id,"Lumi Trusted Relay Build",p.getString("trusted_core_build_target_name",""),"Built by owner-approved GitHub Actions relay",p.getString("trusted_core_build_requested_change","core update"));
        UpdateTransactionManager.markStage(p,id,"RELAY_APK_VERIFIED",target);
        Bundle prepared=LumiUpdateManager.preparePendingCoreInstall(c,p);
        if(!prepared.getBoolean("ok",false)) throw new SecurityException("Lumi could not prepare the verified relay build for Android installation");
        p.edit().putString("trusted_core_build_stage","ANDROID_INSTALL_APPROVAL_REQUIRED")
                .putString("trusted_core_build_artifact_sha256",SourcePatchManager.sha256(apk))
                .putBoolean("zero_chat_android_approval_pending",true)
                .remove("trusted_core_build_error").apply();
        UpdateTransactionManager.markStage(p,id,"ANDROID_INSTALL_APPROVAL_REQUIRED",target);
        // The background relay never bypasses Android. The next foreground Lumi session opens
        // the verified APK in Android's installer and the user approves the replacement.
        return true;
    }

    private static boolean pollSelfUpdateCompletion(Context c,SharedPreferences p)throws Exception{
        String id=p.getString("trusted_core_build_request_id",""); long target=p.getLong("trusted_core_build_target_version",-1L);
        if(id.isEmpty()||target<=0L)throw new SecurityException("Trusted build self-update completion state is incomplete");
        long installed=currentVersionCode(c);
        if(installed>=target){
            p.edit().putString("trusted_core_build_stage","POST_INSTALL_VALIDATION_PENDING")
                    .putBoolean("zero_chat_android_approval_pending",false).apply();
            Bundle validation=LumiSelfUpdateEngine.postInstallValidation(c,p);
            if(validation.getBoolean("certified",false)){
                p.edit().putString("trusted_core_build_stage","POST_INSTALL_VALIDATION_COMPLETE")
                        .putBoolean("trusted_core_build_active",false)
                        .putLong("trusted_core_build_completed_at",System.currentTimeMillis())
                        .putBoolean("zero_chat_android_approval_pending",false).remove("trusted_core_build_error").apply();
                UpdateTransactionManager.finish(p,id,"INSTALLED_VALIDATED","Trusted relay core installed and Lumi post-install validation passed");
                return false;
            }
            fail(p,id,"Post-install validation failed: "+validation.getString("error",validation.getString("summary","unknown")));
            return false;
        }
        p.edit().putString("trusted_core_build_stage",p.getString("pending_core_install_state","ANDROID_INSTALL_APPROVAL_REQUIRED"))
                .putBoolean("zero_chat_android_approval_pending",true).apply();
        // Waiting for the user is not a polling condition. Foreground Lumi will open Android's
        // installer; MY_PACKAGE_REPLACED finalizes the transaction after a successful install.
        return false;
    }

    private static long currentVersionCode(Context c)throws Exception{
        android.content.pm.PackageInfo pi=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        return android.os.Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode;
    }

    static void markFailure(SharedPreferences p,String message){String id=p.getString("trusted_core_build_request_id","");fail(p,id,message);}
    private static void fail(SharedPreferences p,String id,String message){p.edit().putString("trusted_core_build_stage","FAILED").putString("trusted_core_build_error",safe(message)).putBoolean("trusted_core_build_active",false).apply();UpdateTransactionManager.finish(p,id,"FAILED",safe(message));}

    private static long workflowId(SharedPreferences p,String key,Config cfg,String filename)throws Exception{
        long id=p.getLong(key,-1L);
        if(id>0L) return id;
        WorkflowRef w=resolveWorkflow(cfg,filename);
        p.edit().putLong(key,w.id).apply();
        return w.id;
    }

    private static WorkflowRef resolveWorkflow(Config cfg,String filename)throws Exception{
        for(int attempt=0;attempt<8;attempt++){
            JSONObject all=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/actions/workflows?per_page=100");
            JSONArray a=all.optJSONArray("workflows");
            if(a!=null) for(int i=0;i<a.length();i++){
                JSONObject w=a.getJSONObject(i);
                String path=w.optString("path","");
                String name=path.substring(path.lastIndexOf('/')+1);
                if(filename.equalsIgnoreCase(name) || (".github/workflows/"+filename).equalsIgnoreCase(path)){
                    long id=w.optLong("id",-1L);
                    if(id<=0L) throw new SecurityException("GitHub returned an invalid workflow id for "+filename);
                    return new WorkflowRef(id,path,w.optString("state","active"));
                }
            }
            if(attempt<7) Thread.sleep(1000L+attempt*350L);
        }
        throw new java.io.FileNotFoundException("GitHub workflow not found on the default branch: "+filename);
    }

    private static void ensureRelayWorkflows(Config cfg,String defaultBranch)throws Exception{
        requireWorkflowFile(cfg,defaultBranch,DEFAULT_WORKFLOW);
        requireWorkflowFile(cfg,defaultBranch,PREFLIGHT_WORKFLOW);
    }

    private static void requireWorkflowFile(Config cfg,String branch,String filename)throws Exception{
        String path=".github/workflows/"+filename;
        String endpoint="/repos/"+cfg.owner+"/"+cfg.repo+"/contents/"+url(path);
        JSONObject current=getJson(cfg,endpoint+"?ref="+url(branch));
        if(!"file".equalsIgnoreCase(current.optString("type","")) || current.optString("sha","").isEmpty())
            throw new java.io.FileNotFoundException("Required Lumi workflow is missing: "+path);
    }

    private static JSONObject putJson(Config cfg,String path,JSONObject body,int expected)throws Exception{
        return requestJsonOnce(cfg,"PUT",API+path,body,expected);
    }

    private static final class WorkflowRef{
        final long id; final String path,state;
        WorkflowRef(long i,String p,String s){id=i;path=p;state=s;}
    }

    private static String ensureBranch(Config cfg,String defaultBranch)throws Exception{
        try{return getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/ref/heads/"+url(cfg.branch)).getJSONObject("object").getString("sha");}
        catch(HttpStatusException e){if(e.code!=404)throw e;}
        JSONObject base=getJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/ref/heads/"+url(defaultBranch)); String sha=base.getJSONObject("object").getString("sha");
        postJson(cfg,"/repos/"+cfg.owner+"/"+cfg.repo+"/git/refs",new JSONObject().put("ref","refs/heads/"+cfg.branch).put("sha",sha),201); return sha;
    }

    private static String createBlob(Config cfg,File f)throws Exception{
        String b64=Base64.encodeToString(java.nio.file.Files.readAllBytes(f.toPath()),Base64.NO_WRAP);
        return requestJsonRetry(cfg,"POST",API+"/repos/"+cfg.owner+"/"+cfg.repo+"/git/blobs",new JSONObject().put("content",b64).put("encoding","base64"),201).getString("sha");
    }
    private static JSONObject getJson(Config cfg,String path)throws Exception{return requestJsonRetry(cfg,"GET",API+path,null,200);}
    private static JSONObject postJson(Config cfg,String path,JSONObject body,int expected)throws Exception{return requestJsonOnce(cfg,"POST",API+path,body,expected);}
    private static JSONObject postJsonRetry(Config cfg,String path,JSONObject body,int expected)throws Exception{return requestJsonRetry(cfg,"POST",API+path,body,expected);}
    private static JSONObject patchJson(Config cfg,String path,JSONObject body,int expected)throws Exception{return requestJsonOnce(cfg,"PATCH",API+path,body,expected);}
    private static void postNoContent(Config cfg,String path,JSONObject body,int expected)throws Exception{requestJsonOnce(cfg,"POST",API+path,body,expected);}

    private static JSONObject requestJsonRetry(Config cfg,String method,String url,JSONObject body,int expected)throws Exception{
        Exception last=null;
        for(int attempt=0;attempt<3;attempt++){
            try{return requestJsonOnce(cfg,method,url,body,expected);}
            catch(Exception e){last=e;if(!isRetryable(e) || attempt==2)throw e;Thread.sleep(700L*(1L<<attempt));}
        }
        throw last==null?new java.io.IOException("GitHub request failed"):last;
    }

    private static JSONObject requestJsonOnce(Config cfg,String method,String url,JSONObject body,int expected)throws Exception{
        HttpURLConnection h=null;
        try{
            h=(HttpURLConnection)new URL(url).openConnection();h.setRequestMethod(method);h.setConnectTimeout(CONNECT_MS);h.setReadTimeout(READ_MS);
            h.setRequestProperty("Accept","application/vnd.github+json");h.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
            h.setRequestProperty("User-Agent","Lumi-Trusted-Build-Relay/2");h.setRequestProperty("Authorization","Bearer "+cfg.token);
            if(body!=null){h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);h.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=h.getOutputStream()){out.write(bytes);out.flush();}}
            int code=h.getResponseCode();InputStream in=(code>=200&&code<300)?h.getInputStream():h.getErrorStream();String raw=readAll(in,2_000_000);
            if(code!=expected)throw new HttpStatusException(code,"GitHub HTTP "+code+": "+bounded(raw,1200));
            if(raw.trim().isEmpty())return new JSONObject().put("ok",true);return new JSONObject(raw);
        }finally{if(h!=null)h.disconnect();}
    }

    static boolean isRetryable(Throwable t){
        Throwable x=t; while(x!=null){
            if(x instanceof HttpStatusException){int c=((HttpStatusException)x).code;return c==408||c==409||c==425||c==429||c==500||c==502||c==503||c==504;}
            if(x instanceof java.net.SocketTimeoutException || x instanceof java.net.ConnectException || x instanceof java.net.SocketException || x instanceof java.io.InterruptedIOException)return true;
            x=x.getCause();
        }
        return false;
    }

    private static void download(Config cfg,String urlText,File dest)throws Exception{String next=urlText;for(int hop=0;hop<5;hop++){HttpURLConnection h=(HttpURLConnection)new URL(next).openConnection();h.setInstanceFollowRedirects(false);h.setConnectTimeout(CONNECT_MS);h.setReadTimeout(READ_MS);h.setRequestProperty("Accept","application/vnd.github+json");h.setRequestProperty("User-Agent","Lumi-Trusted-Build-Relay/1");if(new URL(next).getHost().equalsIgnoreCase("api.github.com"))h.setRequestProperty("Authorization","Bearer "+cfg.token);int code=h.getResponseCode();if(code>=300&&code<400){String loc=h.getHeaderField("Location");h.disconnect();if(loc==null||loc.isEmpty())throw new java.io.IOException("Artifact redirect had no destination");next=loc;continue;}if(code<200||code>=300){String raw=readAll(h.getErrorStream(),100000);h.disconnect();throw new java.io.IOException("Artifact download HTTP "+code+": "+bounded(raw,800));}long total=0;try(InputStream in=new BufferedInputStream(h.getInputStream());BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){byte[] b=new byte[262144];int n;while((n=in.read(b))>0){total+=n;if(total>MAX_ARTIFACT_BYTES)throw new SecurityException("Build artifact exceeds size limit");out.write(b,0,n);}}finally{h.disconnect();}return;}throw new java.io.IOException("Too many artifact redirects");}
    private static File extractSingleApk(File zip,File dir)throws Exception{List<File> apks=new ArrayList<>();try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))){ZipEntry e;byte[] b=new byte[262144];while((e=in.getNextEntry())!=null){if(e.isDirectory()||!e.getName().toLowerCase(Locale.US).endsWith(".apk"))continue;File out=new File(dir,"relay-built-"+apks.size()+".apk");long total=0;try(BufferedOutputStream os=new BufferedOutputStream(new FileOutputStream(out))){int n;while((n=in.read(b))>0){total+=n;if(total>MAX_ARTIFACT_BYTES)throw new SecurityException("Relay APK exceeds size limit");os.write(b,0,n);}}apks.add(out);}}if(apks.size()!=1)throw new SecurityException("Expected exactly one signed Lumi APK in relay artifact, found "+apks.size());return apks.get(0);}

    private static Config config(SharedPreferences p){String token=SecretStore.get(p,"github_build_token").trim();String owner=slug(p.getString("build_relay_github_owner",""));String repo=slug(p.getString("build_relay_github_repo",""));String branch=safeBranch(p.getString("build_relay_github_branch",DEFAULT_BRANCH));String workflow=p.getString("build_relay_github_workflow",DEFAULT_WORKFLOW).trim();if(workflow.startsWith(".github/workflows/"))workflow=workflow.substring(".github/workflows/".length());if("main".equalsIgnoreCase(branch)&&"apk-factory-build.yml".equalsIgnoreCase(workflow)){branch=DEFAULT_BRANCH;workflow=DEFAULT_WORKFLOW;p.edit().putString("build_relay_github_branch",branch).putString("build_relay_github_workflow",workflow).apply();}if(token.isEmpty()||owner.isEmpty()||repo.isEmpty())throw new IllegalStateException("Trusted Build Relay is not configured. Open AI Interface → Trusted Build Relay.");return new Config(owner,repo,branch,workflow,token);}
    private static final class Config{final String owner,repo,branch,workflow,token;Config(String o,String r,String b,String w,String t){owner=o;repo=r;branch=b;workflow=w;token=t;}}
    private static final class HttpStatusException extends java.io.IOException{final int code;HttpStatusException(int c,String m){super(m);code=c;}}
    private static void collect(File f,List<File> out){if(f.isFile()){out.add(f);return;}File[] kids=f.listFiles();if(kids!=null)for(File k:kids)collect(k,out);}
    private static boolean isText(String path){String l=path.toLowerCase(Locale.US);return l.endsWith(".java")||l.endsWith(".kt")||l.endsWith(".xml")||l.endsWith(".json")||l.endsWith(".txt")||l.endsWith(".md")||l.endsWith(".properties")||l.endsWith(".gradle")||l.endsWith(".yml")||l.endsWith(".yaml")||l.endsWith(".csv")||l.endsWith(".pro");}
    private static String readAll(InputStream in,int max)throws Exception{if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n,total=0;while((n=x.read(b))>0){total+=n;if(total>max)break;out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static String slug(String s){String x=s==null?"":s.trim();return x.matches("[A-Za-z0-9_.-]{1,100}")?x:"";}
    private static String safeBranch(String s){String x=s==null?DEFAULT_BRANCH:s.trim();if(!x.matches("[A-Za-z0-9._/-]{1,120}")||x.contains("..")||x.startsWith("/")||x.endsWith("/"))throw new IllegalArgumentException("Invalid relay branch");return x;}
    private static String url(String s)throws Exception{return URLEncoder.encode(s,StandardCharsets.UTF_8.name()).replace("%2F","/");}
    private static String safe(String s){return s==null?"":SecretStore.redact(s.replace('\n',' ').replace('\r',' ').trim());}
    private static String bounded(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,n);}
}
