package com.timemotion.remotehelp.core

import android.content.Context
import android.os.Environment
import android.util.Log
import com.timemotion.remotehelp.ui.shared.UiFeedbackBus
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class LogFileInfo(
    val fileName: String,
    val lastModified: Long,
    val sizeBytes: Long
)

object AppLog {
    private const val TAG = "RemoteHelp"
    private const val LOG_SUBDIR = "RemoteHelp/logs"
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var logDirectory: File? = null
    @Volatile
    private var crashHandlerInstalled = false

    fun install(context: Context) {
        if (appContext != null) {
            return
        }
        synchronized(this) {
            if (appContext != null) {
                return
            }
            appContext = context.applicationContext
            logDirectory = resolveLogDirectory(context.applicationContext).apply { mkdirs() }
            if (!crashHandlerInstalled) {
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    logThrowable("UncaughtException", throwable, "thread=${thread.name}")
                    UiFeedbackBus.emitTopToast("应用发生异常，已记录到本地日志")
                }
                crashHandlerInstalled = true
            }
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, message, throwable)
    }

    fun logThrowable(tag: String, throwable: Throwable, message: String? = null) {
        val composedMessage = buildString {
            if (!message.isNullOrBlank()) {
                append(message)
                append(" | ")
            }
            append(throwable.javaClass.simpleName)
            append(": ")
            append(throwable.message ?: "unknown")
        }
        e(tag, composedMessage, throwable)
    }

    fun logDirectoryPath(context: Context? = null): String {
        val resolvedContext = context ?: appContext ?: return ""
        return resolveLogDirectory(resolvedContext).absolutePath
    }

    fun listLogFiles(context: Context? = null): List<LogFileInfo> {
        val resolvedContext = context ?: appContext ?: return emptyList()
        val dir = resolveLogDirectory(resolvedContext)
        return runCatching {
            dir.mkdirs()
            dir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.map {
                    LogFileInfo(
                        fileName = it.name,
                        lastModified = it.lastModified(),
                        sizeBytes = it.length()
                    )
                }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun readLogFile(context: Context? = null, fileName: String): String {
        val resolvedContext = context ?: appContext ?: return ""
        val dir = resolveLogDirectory(resolvedContext)
        return runCatching {
            val target = File(dir, fileName)
            if (!target.exists() || !target.isFile) {
                return@runCatching ""
            }
            target.readText(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        if (level != "W" && level != "E") {
            return
        }
        val currentContext = appContext ?: return
        val currentDir = logDirectory ?: resolveLogDirectory(currentContext).also {
            it.mkdirs()
            logDirectory = it
        }
        val now = System.currentTimeMillis()
        val line = buildString {
            append(dateFormat.format(Date(now)))
            append(" [")
            append(level)
            append("] ")
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
                appendLine()
                append(stackTraceToString(throwable))
            }
            appendLine()
        }
        executor.execute {
            runCatching {
                val file = File(currentDir, "${fileDateFormat.format(Date(now))}.log")
                FileWriter(file, true).use { writer ->
                    writer.append(line)
                }
            }.onFailure {
                Log.e(TAG, "写入日志失败", it)
            }
        }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun resolveLogDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        return File(baseDir, LOG_SUBDIR)
    }

}
