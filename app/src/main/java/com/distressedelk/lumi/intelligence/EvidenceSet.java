package com.distressedelk.lumi.intelligence;
import java.util.*;
public final class EvidenceSet {
  private final List<Evidence> items = new ArrayList<>();
  public void add(Evidence e){ if(e!=null && e.text()!=null && !e.text().isBlank() && items.stream().noneMatch(x -> canonical(x.url()).equals(canonical(e.url())))) items.add(e); }
  public List<Evidence> ranked(){ return items.stream().sorted(Comparator.comparingDouble(Evidence::sourceQuality).reversed().thenComparing(Comparator.comparing(Evidence::fresh).reversed())).toList(); }
  public int size(){ return items.size(); }
  public double confidence(){ if(items.isEmpty()) return 0; double avg=items.stream().mapToDouble(Evidence::sourceQuality).average().orElse(0); double diversity=Math.min(1.0,items.stream().map(Evidence::publisher).filter(Objects::nonNull).distinct().count()/3.0); return Math.min(1.0,0.70*avg+0.30*diversity); }
  public boolean hasIndependentCorroboration(){ return items.stream().map(Evidence::publisher).filter(Objects::nonNull).distinct().count()>=2; }
  private static String canonical(String u){ if(u==null)return ""; return u.replaceFirst("https?://(www\\.)?","").replaceAll("[/?#]+$","").toLowerCase(Locale.ROOT); }
}
