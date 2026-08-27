package com.distressedelk.lumi.visual;
public interface ExternalRenderProvider {
  String id();
  boolean configured();
  RenderResult render(RenderRequest request) throws Exception;
  record RenderRequest(String sourceAssetId, String instruction, String mode, long requestId){}
  record RenderResult(String providerId, String stagedAssetPath, String sha256, boolean requiresApproval){}
}
