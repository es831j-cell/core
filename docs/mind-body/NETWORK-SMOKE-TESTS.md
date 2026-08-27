# Network smoke tests for the merged Android build

1. "What's 2 + 2?" -> DIRECT, no HTTP request.
2. "What's the weather in Greenville, Texas right now?" -> WEB_LOOKUP, fresh timestamp present.
3. "What is Reddit's current stock price?" -> WEB_LOOKUP. If no reliable quote provider is available, say so instead of inventing a number.
4. "What happened in AI news today? Compare sources." -> WEB_RESEARCH, at least 3 retrieved sources when available.
5. Disable network and ask a current question -> local fallback explicitly marks fresh data unavailable.
6. Inject a dead URL -> continue to remaining providers and record failure in Black Box.
7. Ambiguous research request -> planner generates multiple targeted retrievals rather than one giant query.
