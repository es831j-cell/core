package com.distressedelk.lumi.intelligence;
public record RouteDecision(Route route, boolean needsFreshData, int desiredSources, String reason) {
  public enum Route { DIRECT, LOCAL_MODEL, WEB_LOOKUP, WEB_RESEARCH, EXTERNAL_AI }
}
