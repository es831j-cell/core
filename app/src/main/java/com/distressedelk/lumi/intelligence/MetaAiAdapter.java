package com.distressedelk.lumi.intelligence;
/** Deliberately disabled until Lumi has a supported Meta programmatic bridge. The glasses integration can still use its own transport later. */
public final class MetaAiAdapter implements AiProvider {
  @Override public String id(){ return "meta-ai"; }
  @Override public boolean isConfigured(){ return false; }
  @Override public boolean isHealthy(){ return false; }
  @Override public String capabilitySummary(){ return "Meta AI / glasses bridge placeholder; unavailable until a supported connector is configured"; }
  @Override public ProviderAnswer ask(String prompt){ throw new IllegalStateException("Meta AI connector is not configured"); }
}
