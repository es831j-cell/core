package com.distressedelk.lumi.visual;
public final class TransitionPlanner {
  public record Plan(long durationMs, int frames, float startAlpha, float endAlpha){}
  public Plan smoothCrossFade(long requestedMs, int displayHz){ long d=Math.max(180,Math.min(1200,requestedMs)); int hz=Math.max(30,Math.min(240,displayHz)); int frames=Math.max(2,(int)Math.round(d/1000.0*hz)); return new Plan(d,frames,0f,1f); }
}
