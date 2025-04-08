package com.yourusername.arcoredetection

import android.app.Application
import android.os.Environment
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ARCoreDetectionApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Setup Timber for logging
        if (BuildConfig.DEBUG) {
            // Plant debug tree for console logs
            Timber.plant(Timber.DebugTree())

            // Plant custom file logging tree
            Timber.plant(FileLoggingTree(this))
        }

        Timber.i("Application started")
    }

    /**
     * Custom Timber Tree that logs to both console and a file
     */
    class FileLoggingTree(private val application: Application) : Timber.Tree() {
        private val LOG_FILE_NAME = "arcore_detection_log"

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            try {
                // Create log directory if it doesn't exist
                val logDir = File(
                    application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "logs"
                )
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }

                // Create log file with date
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val today = dateFormat.format(Date())
                val logFile = File(logDir, "${LOG_FILE_NAME}_$today.txt")

                // Create file if it doesn't exist
                if (!logFile.exists()) {
                    logFile.createNewFile()
                }

                // Format log entry
                val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                val time = timeFormat.format(Date())
                val priorityChar = when (priority) {
                    Log.VERBOSE -> 'V'
                    Log.DEBUG -> 'D'
                    Log.INFO -> 'I'
                    Log.WARN -> 'W'
                    Log.ERROR -> 'E'
                    Log.ASSERT -> 'A'
                    else -> '?'
                }

                val logEntry = "$time $priorityChar/$tag: $message${t?.let { "\n${it.stackTraceToString()}" } ?: ""}\n"

                // Append to file
                FileOutputStream(logFile, true).use { stream ->
                    stream.write(logEntry.toByteArray())
                }
            } catch (e: Exception) {
                // If file logging fails, don't crash the app
                Log.e("FileLoggingTree", "Error writing to log file", e)
            }
        }
    }
}