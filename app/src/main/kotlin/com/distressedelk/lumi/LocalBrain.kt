package com.distressedelk.lumi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Main-process proxy for Lumi's isolated Fast Brain worker.
 *
 * Code350 makes completion delivery request-scoped and self-reconciling:
 * - every request has a stable request id carried into the worker and back;
 * - lifecycle stage is tracked per request, so another request cannot corrupt timeout decisions;
 * - the final result is authoritative and is delivered before terminal telemetry;
 * - native generation gets a soft deadline plus a bounded hard deadline;
 * - late/stale conversation turns can still update global Fast Brain health in MainActivity.
 */
object LocalBrain {
    interface Callback {
        fun onReply(text: String, tokensPerSecond: Double)
        fun onError(message: String)
    }

    private const val ACTION_WARM = "com.distressedelk.lumi.fastbrain.WARM"
    private const val ACTION_ASK = "com.distressedelk.lumi.fastbrain.ASK"
    private const val ACTION_PROBE = "com.distressedelk.lumi.fastbrain.PROBE"
    private const val ACTION_RESET = "com.distressedelk.lumi.fastbrain.RESET"
    private const val EXTRA_RECEIVER = "receiver"
    private const val EXTRA_REQUEST_ID = "requestId"

    private const val RESULT_OK = 1
    private const val RESULT_ERROR = 2
    private const val RESULT_WARM = 3
    private const val RESULT_RESET = 4
    private const val RESULT_STAGE = 5

    private const val REQUEST_TIMEOUT_MS = 9000L
    private const val PROBE_TIMEOUT_MS = 10000L
    private const val REQUEST_HARD_TIMEOUT_MS = 30000L
    private const val PROBE_HARD_TIMEOUT_MS = 24000L
    private const val COLD_START_TIMEOUT_MS = 45000L
    private const val WARM_TIMEOUT_MS = 45000L
    private const val RESET_TIMEOUT_MS = 3000L
    private const val RESET_SETTLE_MS = 1500L

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var inFlight = false
    @Volatile private var lastRequestStartedAt = 0L
    @Volatile private var lastWorkerStage = "idle"
    @Volatile private var lastWorkerStageAt = 0L
    @Volatile private var lastCompletedRequestId = -1L
    @Volatile private var lastCompletedAction = ""
    @Volatile private var lastCompletedAt = 0L
    @Volatile private var lastCompletedText = ""
    @Volatile private var lastCompletedTps = 0.0

    private val rejectedRequests = AtomicLong(0L)
    private val main = Handler(Looper.getMainLooper())
    private val active = ConcurrentHashMap<Long, Boolean>()
    private val requestStages = ConcurrentHashMap<Long, String>()
    private val requestActions = ConcurrentHashMap<Long, String>()
    private val serial = AtomicLong(0L)

    @JvmStatic fun initialize(context: Context) { appContext = context.applicationContext }
    @JvmStatic fun isLoaded(): Boolean = loaded
    @JvmStatic fun isBusy(): Boolean = inFlight
    @JvmStatic fun lastRequestAgeMs(): Long = if (!inFlight || lastRequestStartedAt <= 0L) 0L else System.currentTimeMillis() - lastRequestStartedAt
    @JvmStatic fun rejectedRequestCount(): Long = rejectedRequests.get()
    @JvmStatic fun workerStage(): String = lastWorkerStage
    @JvmStatic fun workerStageAgeMs(): Long = if (lastWorkerStageAt <= 0L) 0L else System.currentTimeMillis() - lastWorkerStageAt
    @JvmStatic fun lastCompletedRequestId(): Long = lastCompletedRequestId
    @JvmStatic fun lastCompletedAction(): String = lastCompletedAction
    @JvmStatic fun lastCompletedAt(): Long = lastCompletedAt
    @JvmStatic fun lastCompletedText(): String = lastCompletedText
    @JvmStatic fun lastCompletedTps(): Double = lastCompletedTps
    @JvmStatic fun isResponsive(): Boolean {
        if (!loaded || inFlight) return false
        val s = lastWorkerStage
        return s == "model-ready" || s == "warm-complete" || s == "probe-complete" || s == "generation-complete"
                || s == "late-probe-complete" || s == "late-generation-complete" || s == "model-already-active"
    }

    @JvmStatic fun warm(modelPath: String, contextSize: Int, threads: Int) {
        warmInternal(modelPath, contextSize, threads, null)
    }

    @JvmStatic fun warmForRecovery(modelPath: String, contextSize: Int, threads: Int, callback: Callback) {
        warmInternal(modelPath, contextSize, threads, callback)
    }

    private fun warmInternal(modelPath: String, contextSize: Int, threads: Int, callback: Callback?) {
        val ctx = appContext
        if (ctx == null) {
            callback?.onError("Fast Brain worker is not initialized")
            return
        }
        val id = serial.incrementAndGet()
        activate(id, ACTION_WARM, "warm-dispatched")
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == RESULT_STAGE) {
                    updateRequestStage(id, resultData?.getString("status") ?: "worker-stage")
                    return
                }
                if (active.remove(id) == null) return
                cleanupRequest(id)
                when (resultCode) {
                    RESULT_WARM, RESULT_OK -> {
                        loaded = true
                        recordCompletion(id, ACTION_WARM, "LOADED", 0.0, "model-ready")
                        callback?.onReply("LOADED", 0.0)
                    }
                    else -> {
                        loaded = false
                        val error = resultData?.getString("error") ?: "Fast Brain warm failed"
                        recordStage("warm-error")
                        callback?.onError(error)
                    }
                }
            }
        }
        try {
            start(ctx, ACTION_WARM, id, modelPath, contextSize, threads, null, null, 0, true, receiver)
        } catch (t: Throwable) {
            active.remove(id); cleanupRequest(id); loaded = false
            recordStage("warm-start-error")
            callback?.onError(t.message ?: t.javaClass.simpleName)
            return
        }
        main.postDelayed({
            if (active.remove(id) != null) {
                cleanupRequest(id); loaded = false
                recordStage("warm-timeout")
                callback?.onError("Fast Brain model did not finish loading within ${WARM_TIMEOUT_MS}ms")
            }
        }, WARM_TIMEOUT_MS)
    }

    @JvmStatic fun probe(modelPath: String, contextSize: Int, threads: Int, callback: Callback) {
        dispatch(ACTION_PROBE, modelPath, contextSize, threads, "", "", 8, true, callback)
    }

    @JvmStatic fun askRaw(modelPath: String, contextSize: Int, threads: Int, prompt: String, systemPrompt: String, maxTokens: Int, callback: Callback) {
        dispatch(ACTION_ASK, modelPath, contextSize, threads, prompt, systemPrompt, maxTokens, false, callback)
    }

    @JvmStatic fun ask(modelPath: String, contextSize: Int, threads: Int, prompt: String, systemPrompt: String, maxTokens: Int, callback: Callback) {
        dispatch(ACTION_ASK, modelPath, contextSize, threads, prompt, systemPrompt, maxTokens, true, callback)
    }

    @JvmStatic fun restartWorker(callback: Callback) {
        val ctx = appContext
        if (ctx == null) {
            callback.onError("Fast Brain worker is not initialized")
            return
        }
        loaded = false
        inFlight = false
        active.clear(); requestStages.clear(); requestActions.clear()
        lastRequestStartedAt = 0L
        recordStage("reset-requested")

        val id = serial.incrementAndGet()
        val completed = AtomicBoolean(false)
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == RESULT_STAGE) {
                    recordStage(resultData?.getString("status") ?: "reset-stage")
                    return
                }
                if (resultCode != RESULT_RESET && resultCode != RESULT_OK) {
                    if (completed.compareAndSet(false, true)) {
                        recordStage("reset-error")
                        callback.onError(resultData?.getString("error") ?: "Fast Brain worker reset failed")
                    }
                    return
                }
                if (!completed.compareAndSet(false, true)) return
                loaded = false; inFlight = false
                recordStage("reset-acknowledged-waiting-for-process-death")
                main.postDelayed({
                    loaded = false; inFlight = false
                    recordStage("reset-settled")
                    callback.onReply("RESET_SETTLED", 0.0)
                }, RESET_SETTLE_MS)
            }
        }
        try {
            start(ctx, ACTION_RESET, id, "", 0, 0, "", "", 0, true, receiver)
        } catch (t: Throwable) {
            if (completed.compareAndSet(false, true)) {
                recordStage("reset-start-error")
                callback.onError(t.message ?: t.javaClass.simpleName)
            }
            return
        }
        main.postDelayed({
            if (completed.compareAndSet(false, true)) {
                loaded = false; inFlight = false
                recordStage("reset-ack-timeout")
                callback.onError("Fast Brain worker reset did not acknowledge within ${RESET_TIMEOUT_MS}ms")
            }
        }, RESET_TIMEOUT_MS)
    }

    private fun dispatch(action: String, modelPath: String, contextSize: Int, threads: Int, prompt: String, systemPrompt: String, maxTokens: Int, sanitize: Boolean, callback: Callback) {
        val ctx = appContext
        if (ctx == null) {
            rejectedRequests.incrementAndGet()
            callback.onError("Fast Brain worker is not initialized")
            return
        }
        val id = serial.incrementAndGet()
        activate(id, action, if (action == ACTION_PROBE) "probe-dispatched" else "request-dispatched")
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == RESULT_STAGE) {
                    updateRequestStage(id, resultData?.getString("status") ?: "worker-stage")
                    return
                }
                if (active.remove(id) == null) {
                    // Do not deliver a second callback after a hard timeout, but retain terminal
                    // telemetry so diagnostics can prove that native work actually completed late.
                    if (resultCode == RESULT_OK) {
                        val text = resultData?.getString("text").orEmpty()
                        val tps = resultData?.getDouble("tps", 0.0) ?: 0.0
                        recordCompletion(id, action, text, tps, if (action == ACTION_PROBE) "late-probe-complete" else "late-generation-complete")
                    }
                    return
                }
                cleanupRequest(id)
                when (resultCode) {
                    RESULT_OK -> {
                        loaded = true
                        val text = resultData?.getString("text").orEmpty()
                        val tps = resultData?.getDouble("tps", 0.0) ?: 0.0
                        val terminal = if (action == ACTION_PROBE) "probe-complete" else "generation-complete"
                        recordCompletion(id, action, text, tps, terminal)
                        callback.onReply(text, tps)
                    }
                    else -> {
                        loaded = false
                        recordStage("worker-error")
                        callback.onError(resultData?.getString("error") ?: "Fast Brain worker failed")
                    }
                }
            }
        }
        try {
            start(ctx, action, id, modelPath, contextSize, threads, prompt, systemPrompt, maxTokens, sanitize, receiver)
        } catch (t: Throwable) {
            active.remove(id); cleanupRequest(id); loaded = false
            recordStage("worker-start-error")
            callback.onError(t.message ?: t.javaClass.simpleName)
            return
        }

        val timeoutMs = when {
            action == ACTION_PROBE -> PROBE_TIMEOUT_MS
            !loaded -> COLD_START_TIMEOUT_MS
            else -> REQUEST_TIMEOUT_MS
        }
        main.postDelayed({
            if (!active.containsKey(id)) return@postDelayed
            val stage = requestStages[id] ?: "unknown"
            val generationInProgress = stage.contains("generation-start") || stage.contains("generation-slow-wait")
            if (generationInProgress && (action == ACTION_ASK || action == ACTION_PROBE)) {
                val hard = if (action == ACTION_PROBE) PROBE_HARD_TIMEOUT_MS else REQUEST_HARD_TIMEOUT_MS
                updateRequestStage(id, if (action == ACTION_PROBE) "probe-generation-slow-wait" else "generation-slow-wait")
                val remaining = (hard - timeoutMs).coerceAtLeast(1L)
                main.postDelayed({
                    if (active.remove(id) != null) {
                        val hardStage = requestStages[id] ?: lastWorkerStage
                        cleanupRequest(id); loaded = false
                        recordStage("request-hard-timeout")
                        callback.onError("Fast Brain generation exceeded ${hard}ms at stage ${hardStage}; Lumi stayed open and routed around it")
                    }
                }, remaining)
                return@postDelayed
            }
            if (active.remove(id) != null) {
                cleanupRequest(id); loaded = false
                recordStage("request-timeout")
                callback.onError("Fast Brain worker did not answer within ${timeoutMs}ms at stage ${stage}; Lumi stayed open and routed around it")
            }
        }, timeoutMs)
    }

    private fun activate(id: Long, action: String, stage: String) {
        active[id] = true
        requestActions[id] = action
        requestStages[id] = stage
        inFlight = true
        lastRequestStartedAt = System.currentTimeMillis()
        recordStage(stage)
    }

    private fun updateRequestStage(id: Long, stage: String) {
        if (active.containsKey(id)) requestStages[id] = stage
        recordStage(stage)
    }

    private fun cleanupRequest(id: Long) {
        requestStages.remove(id); requestActions.remove(id)
        inFlight = active.isNotEmpty()
        if (!inFlight) lastRequestStartedAt = 0L
    }

    private fun recordCompletion(id: Long, action: String, text: String, tps: Double, stage: String) {
        lastCompletedRequestId = id
        lastCompletedAction = action
        lastCompletedAt = System.currentTimeMillis()
        lastCompletedText = text
        lastCompletedTps = tps
        recordStage(stage)
    }

    private fun recordStage(stage: String) {
        lastWorkerStage = stage
        lastWorkerStageAt = System.currentTimeMillis()
    }

    private fun start(ctx: Context, action: String, requestId: Long, modelPath: String, contextSize: Int, threads: Int, prompt: String?, systemPrompt: String?, maxTokens: Int, sanitize: Boolean, receiver: ResultReceiver) {
        val i = Intent(ctx, FastBrainProcessService::class.java).setAction(action)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra("modelPath", modelPath)
            .putExtra("contextSize", contextSize)
            .putExtra("threads", threads)
            .putExtra("prompt", prompt)
            .putExtra("systemPrompt", systemPrompt)
            .putExtra("maxTokens", maxTokens)
            .putExtra("sanitize", sanitize)
            .putExtra(EXTRA_RECEIVER, receiver)
        ctx.startService(i)
    }

    @JvmStatic fun sanitizeVisibleReply(raw: String?): String {
        if (raw == null) return ""
        var out = raw.replace("\u0000", "").trim()
        out = out.replace(Regex("<think\\b[^>]*>.*?</think\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
        val close = out.lowercase().lastIndexOf("</think>")
        if (close >= 0) out = out.substring(close + 8).trim()
        out = out.replace(Regex("<think\\b[^>]*>.*$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
        out = out.replace(Regex("\\s*/(?:no_?think|no_?talent|think)\\b", RegexOption.IGNORE_CASE), "").trim()
        return out
    }
}
