package com.distressedelk.lumi.intelligence;
import java.util.*;
public final class ProviderRegistry {
  private final Map<String,AiProvider> providers=new LinkedHashMap<>();
  public void register(AiProvider p){ if(p!=null) providers.put(p.id(),p); }
  public Optional<AiProvider> firstHealthy(){ return providers.values().stream().filter(AiProvider::isConfigured).filter(AiProvider::isHealthy).findFirst(); }
  public List<AiProvider> healthy(){ return providers.values().stream().filter(AiProvider::isConfigured).filter(AiProvider::isHealthy).toList(); }
  public int size(){ return providers.size(); }
}
