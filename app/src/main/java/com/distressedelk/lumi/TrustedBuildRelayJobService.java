package com.distressedelk.lumi;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/** Durable background driver for the owner-approved build relay. R105 runs through artifact verification and staging; Android user approval and Lumi post-install validation complete the flow without ChatGPT or a companion app. */
public final class TrustedBuildRelayJobService extends JobService {
    private static final int JOB_ID=0x4C554D49;

    static void schedule(Context c,long delayMs){
        try{
            JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if(js==null)return;
            JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(c,TrustedBuildRelayJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(Math.max(1000L,delayMs)).setOverrideDeadline(Math.max(30000L,delayMs+60000L)).build();
            js.schedule(job);
        }catch(Throwable ignored){}
    }

    @Override public boolean onStartJob(JobParameters params){
        new Thread(() -> {
            boolean again=false; SharedPreferences p=getSharedPreferences("lumi",MODE_PRIVATE);
            try{again=TrustedBuildRelayClient.runOneStep(this,p); p.edit().putInt("trusted_core_build_poll_failures",0).apply();}
            catch(Throwable t){
                String m=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
                String stage=p.getString("trusted_core_build_stage","");
                int failures=p.getInt("trusted_core_build_poll_failures",0);
                boolean passive="RELAY_BUILD_RUNNING".equals(stage)||"WAITING_FOR_RELAY_BUILD".equals(stage)||"POST_INSTALL_VALIDATION_PENDING".equals(stage);
                boolean transport=TrustedBuildRelayClient.isRetryable(t) && ("RELAY_UPLOAD_QUEUED".equals(stage)||"RELAY_UPLOADING_SOURCE".equals(stage)||"RELAY_COMMIT_CREATED".equals(stage)||"RELAY_DISPATCH_PENDING".equals(stage));
                if((passive||transport) && failures<8){
                    failures++;
                    p.edit().putInt("trusted_core_build_poll_failures",failures).putString("trusted_core_build_error",SecretStore.redact(m))
                            .putString("trusted_core_build_stage",transport&& !"RELAY_DISPATCH_PENDING".equals(stage)?"RELAY_UPLOAD_FAILED_RETRYABLE":stage).apply();
                    again=true;
                }else TrustedBuildRelayClient.markFailure(p,m);
            }
            if(again){int n=p.getInt("trusted_core_build_poll_failures",0);long delay=Math.min(300000L,10000L*(1L<<Math.min(5,n)));schedule(this,delay);} jobFinished(params,false);
        },"LumiTrustedBuildRelay").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return getSharedPreferences("lumi",MODE_PRIVATE).getBoolean("trusted_core_build_active",false);}
}
