package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;

/** R102 permanent removal of Private Mode state/assets generated at runtime. */
final class PrivateModePurge {
    private PrivateModePurge(){}
    static void apply(Context c, SharedPreferences p){
        if(c==null || p==null) return;
        SharedPreferences.Editor e=p.edit();
        for(Map.Entry<String,?> x:new ArrayList<>(p.getAll().entrySet())){
            String k=x.getKey(); if(k==null) continue;
            if(k.startsWith("private_") || k.startsWith("render_bridge_") ||
                    "private_opt_in".equals(k) || "visual_avatar_asset".equals(k) || "visual_avatar_fallback_used".equals(k)){ e.remove(k); }
        }
        e.putString("profile","Home")
                .putBoolean("developer_visual_pyramid",true)
                .putBoolean("pyramid_wireframe_mode",false)
                .putString("visual_avatar_asset","lumi_pyramid_approved_reference")
                .putBoolean("private_mode_removed",true)
                .putLong("private_mode_removed_at",System.currentTimeMillis())
                .apply();
        try{ LumiMemoryVault.get(c).purgePrivateModeData(); }catch(Throwable ignored){}
        try{ SecretStore.clear(p,"render_bridge_api_key"); }catch(Throwable ignored){}
        deleteRecursive(new File(c.getFilesDir(),"render-bridge"));
        deleteRecursive(new File(c.getFilesDir(),"lumi_updates/avatar/private.img"));
    }
    private static void deleteRecursive(File f){
        if(f==null || !f.exists()) return;
        if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)deleteRecursive(k);}
        try{f.delete();}catch(Throwable ignored){}
    }
}
