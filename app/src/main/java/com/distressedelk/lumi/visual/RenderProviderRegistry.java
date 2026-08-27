package com.distressedelk.lumi.visual;
import java.util.*;
public final class RenderProviderRegistry {
  private final Map<String,ExternalRenderProvider> providers=new LinkedHashMap<>();
  public void register(ExternalRenderProvider p){ if(p!=null)providers.put(p.id(),p); }
  public Optional<ExternalRenderProvider> configured(String id){ ExternalRenderProvider p=providers.get(id); return p!=null&&p.configured()?Optional.of(p):Optional.empty(); }
  public int size(){ return providers.size(); }
}
