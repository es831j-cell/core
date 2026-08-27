package com.distressedelk.lumi.visual;
public record VisualMorphProfile(int bodyType, int bust, int waist, int hipsGlutes, int musculature, int clothingOpacity) {
  public VisualMorphProfile { bodyType=clamp(bodyType); bust=clamp(bust); waist=clamp(waist); hipsGlutes=clamp(hipsGlutes); musculature=clamp(musculature); clothingOpacity=clamp(clothingOpacity); }
  private static int clamp(int v){ return Math.max(0,Math.min(100,v)); }
  public static VisualMorphProfile maxUserControls(){ return new VisualMorphProfile(100,100,100,100,100,100); }
}
