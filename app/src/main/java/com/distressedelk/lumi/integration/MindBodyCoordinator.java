package com.distressedelk.lumi.integration;
import com.distressedelk.lumi.intelligence.*;
import com.distressedelk.lumi.visual.*;
/** Keeps intelligence and visual subsystems independent. No reasoning route mutates visual state. */
public final class MindBodyCoordinator {
  private final ReasoningRouter reasoningRouter;
  private final TransitionPlanner transitionPlanner;
  public MindBodyCoordinator(ReasoningRouter r, TransitionPlanner t){ reasoningRouter=r; transitionPlanner=t; }
  public RouteDecision route(String q, boolean online, boolean externalAiHealthy){ return reasoningRouter.decide(q,online,externalAiHealthy); }
  public TransitionPlanner.Plan transition(long ms,int hz){ return transitionPlanner.smoothCrossFade(ms,hz); }
}
