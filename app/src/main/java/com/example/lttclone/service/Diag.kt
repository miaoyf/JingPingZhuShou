package com.example.lttclone.service

import android.content.Context
import android.util.Log
import java.io.File

object Diag {

    private const val TAG = "LttService"
    private const val FILE_NAME = "ltt_diag.log"
    private const val MAX_BYTES = 512 * 1024
    private const val TAIL_LINES = 200

    @Volatile
    private var target: File? = null

    fun init(context: Context) {
        if (target == null) {
            synchronized(this) {
                if (target == null) {
                    target = File(context.applicationContext.filesDir, FILE_NAME)
                }
            }
        }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val file = target ?: return
        try {
            if (!file.exists()) file.createNewFile()
            if (file.length() > MAX_BYTES) {
                val bytes = file.readBytes()
                val keep = bytes.size - MAX_BYTES / 2
                file.writeBytes(bytes.copyOfRange(keep, bytes.size))
            }
            file.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Throwable) {
        }
    }

    fun tail(context: Context, lines: Int = TAIL_LINES): String {
        init(context)
        val file = target ?: return ""
        return try {
            if (!file.exists()) return ""
            val all = file.readLines()
            if (all.size <= lines) all.joinToString("\n") else all.takeLast(lines).joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }
}
