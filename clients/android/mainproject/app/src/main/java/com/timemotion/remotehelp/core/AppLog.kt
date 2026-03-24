package com.timemotion.remotehelp.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.timemotion.remotehelp.ui.UiFeedbackBus
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

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
                previousHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    logThrowable("UncaughtException", throwable, "thread=${thread.name}")
                    previousHandler?.uncaughtException(thread, throwable)
                }
                crashHandlerInstalled = true
            }
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message, null)
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

    fun openLogsDirectory(context: Context) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                buildInitialTreeUri(context)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            UiFeedbackBus.emitTopToast("未找到可打开日志目录的文件管理器")
            logThrowable("AppLog", e, "无法打开日志目录")
        } catch (t: Throwable) {
            UiFeedbackBus.emitTopToast("打开日志目录失败")
            logThrowable("AppLog", t, "打开日志目录失败")
        }
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
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

    private fun buildInitialTreeUri(context: Context) = runCatching {
        val docId = "primary:Android/data/${context.packageName}/files/Documents/$LOG_SUBDIR"
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", docId)
    }.getOrNull()
}
