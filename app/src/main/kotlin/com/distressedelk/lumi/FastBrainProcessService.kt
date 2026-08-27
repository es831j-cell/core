package com.distressedelk.lumi

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Native llama inference boundary in android:process=":fastbrain".
 *
 * Code350 makes final-result delivery authoritative. Terminal RESULT_OK is sent before any
 * terminal telemetry can race a client timeout, and every message carries the stable request id.
 * Qwen3 ordinary completions are explicitly forced into no-think mode to avoid spending the
 * tiny conversational token budget on hidden reasoning.
 */
class FastBrainProcessService : Service() {
    private val resetAction = "com.distressedelk.lumi.fastbrain.RESET"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(capacity = 4)
    @Volatile private var workerStarted = false
    @Volatile private var activePath: String? = null

    private data class Command(
        val requestId: Long,
        val action: String,
        val path: String,
        val contextSize: Int,
        val threads: Int,
        val prompt: String,
        val system: String,
        val maxTokens: Int,
        val sanitize: Boolean,
        val receiver: ResultReceiver
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val rr = receiver(intent) ?: return START_NOT_STICKY
        val requestId = intent.getLongExtra("requestId", -1L)
        if (intent.action == resetAction) {
            stage(rr, requestId, "reset-command-received")
            try { rr.send(4, Bundle().apply { putLong("requestId", requestId); putString("status", "reset-ack") }) } catch (_: Throwable) {}
            stopSelf(startId)
            Handler(Looper.getMainLooper()).postDelayed({
                try { Process.killProcess(Process.myPid()) } catch (_: Throwable) {}
            }, 60L)
            return START_NOT_STICKY
        }

        val cmd = Command(
            requestId,
            intent.action.orEmpty(),
            intent.getStringExtra("modelPath").orEmpty(),
            intent.getIntExtra("contextSize", 512),
            intent.getIntExtra("threads", 4),
            intent.getStringExtra("prompt").orEmpty(),
            intent.getStringExtra("systemPrompt").orEmpty(),
            intent.getIntExtra("maxTokens", 32),
            intent.getBooleanExtra("sanitize", true),
            rr
        )
        stage(rr, requestId, "command-accepted")
        ensureWorker(cmd.path, cmd.contextSize, cmd.threads, rr, requestId)
        if (commands.trySend(cmd).isFailure) sendError(rr, requestId, "Fast Brain worker queue is full")
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun receiver(intent: Intent): ResultReceiver? = intent.getParcelableExtra("receiver")

    @Synchronized
    private fun ensureWorker(path: String, contextSize: Int, threads: Int, statusReceiver: ResultReceiver, requestId: Long) {
        if (workerStarted && activePath == path) {
            stage(statusReceiver, requestId, "model-already-active")
            return
        }
        if (workerStarted) {
            stage(statusReceiver, requestId, "worker-already-started")
            return
        }
        workerStarted = true
        activePath = path
        scope.launch {
            try {
                stage(statusReceiver, requestId, "model-load-start")
                val model = Llama.loadModel(path, LlamaConfig(contextSize = contextSize, threads = threads.coerceIn(1, 3)))
                stage(statusReceiver, requestId, "model-load-complete")
                for (cmd in commands) {
                    try {
                        when (cmd.action) {
                            "com.distressedelk.lumi.fastbrain.WARM" -> {
                                cmd.receiver.send(3, Bundle().apply {
                                    putLong("requestId", cmd.requestId)
                                    putString("status", "loaded")
                                    putString("workerStage", "warm-complete")
                                })
                            }
                            "com.distressedelk.lumi.fastbrain.ASK" -> {
                                stage(cmd.receiver, cmd.requestId, "generation-start")
                                val noThinkPrompt = if (cmd.prompt.contains("/no_think", ignoreCase = true)) cmd.prompt else cmd.prompt + "\n/no_think"
                                val noThinkSystem = cmd.system + " Reply directly. Do not emit hidden reasoning or <think> tags."
                                val result = Llama.complete(model, noThinkPrompt, noThinkSystem, cmd.maxTokens.coerceIn(4, 128))
                                val text = if (cmd.sanitize) LocalBrain.sanitizeVisibleReply(result.text)
                                else result.text?.replace("\u0000", "")?.trim().orEmpty()
                                // Final result first. Do not publish a separate terminal stage before this.
                                cmd.receiver.send(1, Bundle().apply {
                                    putLong("requestId", cmd.requestId)
                                    putString("workerStage", "generation-complete")
                                    putString("text", text)
                                    putDouble("tps", result.tokensPerSecond.toDouble())
                                })
                            }
                            "com.distressedelk.lumi.fastbrain.PROBE" -> {
                                stage(cmd.receiver, cmd.requestId, "probe-generation-start")
                                val system = "Reply with exactly READY. No reasoning. No explanation."
                                val result = Llama.complete(model, "READY? /no_think", system, 8)
                                val visible = LocalBrain.sanitizeVisibleReply(result.text)
                                cmd.receiver.send(1, Bundle().apply {
                                    putLong("requestId", cmd.requestId)
                                    putString("workerStage", "probe-generation-complete")
                                    putString("text", visible)
                                    putDouble("tps", result.tokensPerSecond.toDouble())
                                })
                            }
                            else -> sendError(cmd.receiver, cmd.requestId, "Unknown Fast Brain action")
                        }
                    } catch (t: Throwable) {
                        sendError(cmd.receiver, cmd.requestId, t.message ?: t.javaClass.simpleName)
                    }
                }
                Llama.releaseModel(model)
            } catch (t: Throwable) {
                stage(statusReceiver, requestId, "model-load-error")
                while (true) {
                    val pending = commands.tryReceive().getOrNull() ?: break
                    sendError(pending.receiver, pending.requestId, t.message ?: t.javaClass.simpleName)
                }
            } finally {
                workerStarted = false
                activePath = null
            }
        }
    }

    private fun stage(rr: ResultReceiver, requestId: Long, status: String) {
        try { rr.send(5, Bundle().apply { putLong("requestId", requestId); putString("status", status) }) } catch (_: Throwable) {}
    }

    private fun sendError(rr: ResultReceiver, requestId: Long, message: String) {
        try { rr.send(2, Bundle().apply { putLong("requestId", requestId); putString("error", message) }) } catch (_: Throwable) {}
    }
}
