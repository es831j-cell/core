package com.distressedelk.lumi.intelligence;
public interface AiProvider {
  String id();
  boolean isConfigured();
  boolean isHealthy();
  String capabilitySummary();
  ProviderAnswer ask(String prompt) throws Exception;
  record ProviderAnswer(String providerId, String answer, double confidence, long latencyMs) {}
}
