package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

/** Captures the last uncaught Java crash before handing control back to Android. */
final class BlackBoxCrashHandler implements Thread.UncaughtExceptionHandler {
    private static volatile boolean installed=false;
    private final Context app;
    private final SharedPreferences prefs;
    private final Thread.UncaughtExceptionHandler prior;

    private BlackBoxCrashHandler(Context context,SharedPreferences prefs,Thread.UncaughtExceptionHandler prior){
        this.app=context.getApplicationContext(); this.prefs=prefs; this.prior=prior;
    }

    static synchronized void install(Context context,SharedPreferences prefs){
        if(installed || context==null) return;
        Thread.UncaughtExceptionHandler prior=Thread.getDefaultUncaughtExceptionHandler();
        if(prior instanceof BlackBoxCrashHandler){ installed=true; return; }
        Thread.setDefaultUncaughtExceptionHandler(new BlackBoxCrashHandler(context,prefs,prior));
        installed=true;
    }

    @Override public void uncaughtException(Thread thread,Throwable error){
        try{
            long now=System.currentTimeMillis();
            String detail=describe(error);
            if(prefs!=null) prefs.edit()
                    .putLong("blackbox_last_uncaught_at",now)
                    .putString("blackbox_last_uncaught_thread",thread==null?"unknown":thread.getName())
                    .putString("blackbox_last_uncaught",SecretStore.redact(detail))
                    .apply();
            DeveloperFlightRecorder.record(app,prefs,-1L,"CRASH","UNCAUGHT_EXCEPTION",detail,"","","process-fatal",false,false,false);
        }catch(Throwable ignored){}
        if(prior!=null) prior.uncaughtException(thread,error);
    }

    private static String describe(Throwable t){
        if(t==null) return "unknown throwable";
        StringBuilder s=new StringBuilder();
        s.append(t.getClass().getName()).append(": ").append(String.valueOf(t.getMessage()));
        StackTraceElement[] stack=t.getStackTrace();
        int n=Math.min(stack==null?0:stack.length,16);
        for(int i=0;i<n;i++) s.append("\n at ").append(stack[i]);
        Throwable cause=t.getCause();
        if(cause!=null && cause!=t) s.append("\nCaused by ").append(cause.getClass().getName()).append(": ").append(String.valueOf(cause.getMessage()));
        String v=s.toString();
        return v.length()>12000?v.substring(0,12000):v;
    }
}
