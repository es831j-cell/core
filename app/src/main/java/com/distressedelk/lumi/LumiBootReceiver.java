package com.distressedelk.lumi;

import android.content.*;
import android.os.Build;

/** Starts Lumi continuity after boot and finalizes native self-updates after package replacement. */
public class LumiBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent){
        String action=intent==null?"":intent.getAction();
        boolean boot=Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean replaced=Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if(!boot && !replaced) return;
        android.content.SharedPreferences p=context.getSharedPreferences("lumi",Context.MODE_PRIVATE);
        RuntimePolicy.applyStartupPolicy(context,p);
        MaintenanceFoundation.initialize(context,p);
        LumiSelfUpdateEngine.initialize(context,p);
        if(replaced) LumiSelfUpdateEngine.onPackageReplaced(context,p);
        if(p.getBoolean("trusted_core_build_active",false)) TrustedBuildRelayJobService.schedule(context,3000L);
        if(boot){
            Intent core=new Intent(context,LumiCoreService.class);
            try{ if(Build.VERSION.SDK_INT>=26)context.startForegroundService(core); else context.startService(core); }catch(Exception ignored){}
        }
    }
}
