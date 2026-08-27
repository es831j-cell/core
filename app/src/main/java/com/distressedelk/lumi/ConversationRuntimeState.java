package com.distressedelk.lumi;

/**
 * Code381 single authoritative conversation lifecycle.
 *
 * The state machine does not own Android objects. It owns the truth about which phase the
 * conversation is in and a monotonically increasing generation token. Asynchronous STT/TTS
 * callbacks from an older generation are stale by definition and must not re-arm speech,
 * mutate visuals, or promote a turn.
 */
final class ConversationRuntimeState {
    enum State { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED, STOPPED, RECOVERING }

    private int generation = 1;
    private State state = State.IDLE;
    private long changedAt = System.currentTimeMillis();
    private String reason = "init";

    synchronized int newGeneration(State next, String why) {
        generation++;
        state = next == null ? State.IDLE : next;
        changedAt = System.currentTimeMillis();
        reason = safe(why);
        return generation;
    }

    synchronized int transition(State next, String why) {
        state = next == null ? State.IDLE : next;
        changedAt = System.currentTimeMillis();
        reason = safe(why);
        return generation;
    }

    synchronized int generation() { return generation; }
    synchronized boolean current(int token) { return token == generation; }
    synchronized State state() { return state; }
    synchronized long changedAt() { return changedAt; }
    synchronized String reason() { return reason; }

    synchronized String snapshot() {
        return "state=" + state + " • generation=" + generation + " • ageMs="
                + Math.max(0L, System.currentTimeMillis() - changedAt) + " • reason=" + reason;
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\n',' ').replace('\r',' ').trim();
    }
}
