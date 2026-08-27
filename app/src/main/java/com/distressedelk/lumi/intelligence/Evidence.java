package com.distressedelk.lumi.intelligence;
public record Evidence(String title, String url, String publisher, String text, long observedAtEpochMs, double sourceQuality, boolean fresh) {
  public Evidence { sourceQuality=Math.max(0.0,Math.min(1.0,sourceQuality)); }
}
