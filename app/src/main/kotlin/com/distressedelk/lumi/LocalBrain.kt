package com.distressedelk.lumi

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Owns a single on-device llama.cpp model for the lifetime of the process.
 * The model object deliberately stays local to the worker coroutine so the
 * app does not depend on the library's internal handle type.
 */
object LocalBrain {
    interface Callback {
        fun onReply(text: String, tokensPerSecond: Double)
        fun onError(message: String)
    }

    private data class Request(
        val prompt: String,
        val systemPrompt: String,
        val maxTokens: Int,
        val callback: Callback
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)

    @Volatile private var workerStarted = false
    @Volatile private var loaded = false
    @Volatile private var activeModelPath: String? = null

    @JvmStatic fun isLoaded(): Boolean = loaded

    /** Start loading the fast model before the first user turn so model-load time is
     * paid during app startup instead of after the user speaks. */
    @JvmStatic fun warm(modelPath: String, contextSize: Int, threads: Int) {
        ensureWorker(modelPath, contextSize, threads)
    }

    @Synchronized
    private fun ensureWorker(modelPath: String, contextSize: Int, threads: Int) {
        if (workerStarted && activeModelPath == modelPath) return
        if (workerStarted && activeModelPath != modelPath) {
            // Process restart is the clean model-switch boundary in this v2 baseline.
            return
        }
        workerStarted = true
        activeModelPath = modelPath
        scope.launch {
            try {
                val model = Llama.loadModel(
                    modelPath = modelPath,
                    config = LlamaConfig(contextSize = contextSize, threads = threads)
                )
                loaded = true
                for (request in requests) {
                    try {
                        val result = Llama.complete(
                            model,
                            prompt = request.prompt,
                            systemPrompt = request.systemPrompt,
                            maxTokens = request.maxTokens
                        )
                        val cleaned = sanitizeVisibleReply(result.text)
                        request.callback.onReply(cleaned, result.tokensPerSecond.toDouble())
                    } catch (t: Throwable) {
                        request.callback.onError(t.message ?: t.javaClass.simpleName)
                    }
                }
                Llama.releaseModel(model)
            } catch (t: Throwable) {
                loaded = false
                workerStarted = false
                activeModelPath = null
                // Drain anything already queued so callers do not hang forever.
                while (true) {
                    val pending = requests.tryReceive().getOrNull() ?: break
                    pending.callback.onError(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun sanitizeVisibleReply(raw: String?): String {
        if (raw == null) return ""
        var out = raw.replace("\u0000", "").trim()
        out = out.replace(Regex("<think\\b[^>]*>.*?</think\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
        val close = out.lowercase().lastIndexOf("</think>")
        if (close >= 0) out = out.substring(close + 8).trim()
        out = out.replace(Regex("<think\\b[^>]*>.*$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
        return out
    }

    @JvmStatic fun probe(
        modelPath: String,
        contextSize: Int,
        threads: Int,
        callback: Callback
    ) {
        scope.launch {
            try {
                val candidate = Llama.loadModel(
                    modelPath = modelPath,
                    config = LlamaConfig(contextSize = contextSize, threads = threads)
                )
                val result = Llama.complete(
                    candidate,
                    prompt = "Reply with exactly: ready /no_think",
                    systemPrompt = "You are a local model health check. Output only the requested word. /no_think",
                    maxTokens = 8
                )
                Llama.releaseModel(candidate)
                callback.onReply(result.text.trim(), result.tokensPerSecond.toDouble())
            } catch (t: Throwable) {
                callback.onError(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    @JvmStatic fun ask(
        modelPath: String,
        contextSize: Int,
        threads: Int,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        callback: Callback
    ) {
        ensureWorker(modelPath, contextSize, threads)
        scope.launch {
            requests.send(Request(prompt, systemPrompt, maxTokens, callback))
        }
    }
}
