package com.fuchu.assistant.service

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

object Diag {

    private const val TAG = "SkipService"
    private const val FILE_NAME = "fu_diag.log"
    private const val MAX_BYTES = 512 * 1024
    private const val TAIL_LINES = 500
    private const val SIZE_CHECK_EVERY = 64

    @Volatile
    private var target: File? = null

    private var writer: BufferedWriter? = null
    private var sinceCheck = 0

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
            synchronized(this) {
                var w = writer
                if (w == null) {
                    if (!file.exists()) file.createNewFile()
                    w = BufferedWriter(FileWriter(file, true), 4096)
                    writer = w
                }
                sinceCheck++
                if (sinceCheck >= SIZE_CHECK_EVERY) {
                    sinceCheck = 0
                    if (file.length() > MAX_BYTES) {
                        w.close()
                        writer = null
                        val bytes = file.readBytes()
                        val keep = bytes.size - MAX_BYTES / 2
                        file.writeBytes(bytes.copyOfRange(keep, bytes.size))
                        w = BufferedWriter(FileWriter(file, true), 4096)
                        writer = w
                    }
                }
                w.write("${System.currentTimeMillis()} $message\n")
                w.flush()
            }
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
