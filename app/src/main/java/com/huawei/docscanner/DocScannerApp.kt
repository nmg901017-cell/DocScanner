package com.huawei.docscanner

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocScannerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashLog = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) +
                        "\n" + throwable.toString() + "\n" + sw.toString()
                File(filesDir, "crash_log.txt").writeText(crashLog)
                Log.e("DocScanner", "Crashed: $crashLog")
            } catch (e: Exception) {
                Log.e("DocScanner", "Error writing crash log", e)
            }
            handler?.uncaughtException(thread, throwable)
        }
    }
}
