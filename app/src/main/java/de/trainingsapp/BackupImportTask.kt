package de.trainingsapp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Retained only across configuration changes; never retains an Activity. */
internal class BackupImportTask(context: Context, private val uri: Uri) {
    data class Result(val error: String?)
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val cancelled = AtomicBoolean(false)
    private val cancellationSignal = CancellationSignal()
    @Volatile private var worker: Thread? = null
    private var activeInput: InputStream? = null
    private var result: Result? = null
    private var listener: ((Result) -> Unit)? = null
    private var delivered = false

    fun start() {
        executor.execute {
            worker = Thread.currentThread()
            var restored = false
            val outcome = try {
                checkCancelled()
                val text = app.contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)?.use { descriptor ->
                    val input = descriptor.createInputStream()
                    synchronized(lock) { activeInput = input }
                    try {
                        checkCancelled()
                        input.use { BackupIo.readUtf8(it) }
                    } finally { synchronized(lock) { activeInput = null } }
                }
                    ?: throw IOException("Datei nicht lesbar.")
                checkCancelled()
                Store(app).use { it.restoreJson(text); restored = true }
                // A committed restore invalidates the previous workout draft, even after rotation.
                app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE)
                    .edit().remove("active").commit()
                Result(null)
            } catch (error: Exception) {
                if (restored) Result(null) else Result(if (cancelled.get() || Thread.currentThread().isInterrupted)
                    "Import abgebrochen. Vorhandene Daten wurden nicht ersetzt."
                    else error.message ?: "Die Sicherung konnte nicht wiederhergestellt werden.")
            } finally {
                worker = null
                executor.shutdown()
            }
            synchronized(lock) { result = outcome }
            main.post { deliver() }
        }
    }

    fun attach(callback: (Result) -> Unit) {
        synchronized(lock) { listener = callback; delivered = false }
        main.post { deliver() }
    }

    fun detach() { synchronized(lock) { listener = null } }

    fun cancel() {
        cancelled.set(true)
        cancellationSignal.cancel()
        worker?.interrupt()
        val input = synchronized(lock) { activeInput }
        try { input?.close() } catch (_: IOException) { /* Worker reports cancellation. */ }
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Import abgebrochen.")
    }

    private fun deliver() {
        val delivery = synchronized(lock) {
            val value = result
            val callback = listener
            if (value == null || callback == null || delivered) null
            else { delivered = true; Pair(callback, value) }
        }
        delivery?.let { (callback, value) -> callback(value) }
    }
}
