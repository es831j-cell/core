# Unified Interaction Architecture

**Design principle: One Lumi. Many interfaces.**

Voice, text, touch, gestures, Ray-Ban Meta glasses, phone cameras, device sensors, notifications, widgets, workshop cameras, and future interaction methods are input/output interfaces to the same Lumi Core.

All interfaces must share the same Lumi identity and personality, memory, context engine, rules-first router, local AI team, optional remote booster, tools/actions, permissions, connected services, project knowledge, environmental awareness, user preferences, and security/privacy controls.

Canonical flow:

`Interface -> Lumi Core -> Context/Permission Check -> Router -> Model/Tool/Action -> Best Available Output Interface`

An interface can temporarily lack a capability because of hardware, permission, privacy, safety, connectivity, or security constraints. In that case Lumi should route through another authorized component, summarize/defer/queue appropriately, or explain the limitation rather than becoming a separate weaker assistant.

Conversation, active task state, project context, identity, and memory must survive interface handoffs. New interfaces must attach to Lumi Core rather than creating independent assistant architectures.
