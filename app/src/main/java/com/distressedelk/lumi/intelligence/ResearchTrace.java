package com.distressedelk.lumi.intelligence;
public record ResearchTrace(String route, int sourceCount, double confidence, boolean conflictDetected, long retrievalMs, long reasoningMs, String publicReason) {}
