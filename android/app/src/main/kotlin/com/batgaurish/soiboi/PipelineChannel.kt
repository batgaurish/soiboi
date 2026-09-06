package com.batgaurish.soiboi

import android.os.Handler
import android.os.Looper
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Bridges Dart to the bundled `soiboi_pipeline` Python package.
 *
 * Android cannot spawn a Python interpreter as a subprocess the way the desktop
 * build does, so the same module is imported in-process through Chaquopy. Dart
 * sees an identical stream of newline-delimited JSON events either way and
 * never learns which transport it is on.
 *
 * A download takes minutes, so results arrive over an EventChannel rather than
 * a single MethodChannel reply: progress has to reach the UI while the work is
 * still running.
 */
class PipelineChannel(engine: FlutterEngine, private val context: android.content.Context) {

    companion object {
        private const val METHOD = "com.batgaurish.soiboi/pipeline"
        private const val EVENTS = "com.batgaurish.soiboi/pipeline_events"
    }

    // Python work must never touch the main thread: importing the runtime alone
    // takes noticeable time, and a download blocks for minutes.
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var events: EventChannel.EventSink? = null

    init {
        MethodChannel(engine.dartExecutor.binaryMessenger, METHOD)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "run" -> {
                        val command = call.argument<String>("command") ?: ""
                        val payload = call.argument<String>("payload") ?: "{}"
                        run(command, payload, result)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(engine.dartExecutor.binaryMessenger, EVENTS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, sink: EventChannel.EventSink?) {
                    events = sink
                }

                override fun onCancel(args: Any?) {
                    events = null
                }
            })
    }

    private fun ensureStarted() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    private fun run(command: String, payload: String, result: MethodChannel.Result) {
        executor.execute {
            val terminal: String = try {
                ensureStarted()
                val py = Python.getInstance()
                val module = py.getModule("soiboi_pipeline.__main__")

                // Progress events are pushed straight to Dart as they happen.
                // Anything thrown from the callback must not escape into Python,
                // or a UI hiccup would abort a download.
                val emit = object {
                    @Suppress("unused")
                    fun __call__(event: PyObject?) {
                        val json = event?.toString() ?: return
                        main.post {
                            try {
                                events?.success(json)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }

                val response = module.callAttr(
                    "handle_json",
                    command,
                    payload,
                    emit,
                )
                response.toString()
            } catch (e: Throwable) {
                // Surfaced as a normal pipeline error rather than a platform
                // exception, so Dart handles one failure shape.
                """{"event":"error","code":"android_bridge","message":${quote(e.toString())}}"""
            }

            main.post { result.success(terminal) }
        }
    }

    private fun quote(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        return "\"$escaped\""
    }
}
