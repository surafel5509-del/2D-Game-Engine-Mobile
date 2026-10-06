package com.sengine

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import com.sengine.ui.CrashActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SEngineApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("SEngine", "Uncaught exception on thread ${thread.name}", throwable)
                val stackTrace = Log.getStackTraceString(throwable)
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val message = "Time: $time\nThread: ${thread.name}\n\n$stackTrace"

                try {
                    val crashFile = File(filesDir, "crash.txt")
                    crashFile.writeText(message)
                } catch (_: Throwable) {}

                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra("error", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (t: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
