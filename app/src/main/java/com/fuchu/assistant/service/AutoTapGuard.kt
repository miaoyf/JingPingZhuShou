package com.fuchu.assistant.service

import android.os.SystemClock

/**
 * 自点免疫：服务自己发的手势/ACTION_CLICK 会触发目标应用的 TYPE_VIEW_CLICKED，
 * 若不标记会被"手动跳过学习"当成用户亲点又学一遍（10-25 腾讯视频实测
 * learned manual ×3 全是自点）。每次自动点击后记录落点，手动学习时比对忽略。
 */
object AutoTapGuard {
    @Volatile private var time = 0L
    @Volatile private var x = -1
    @Volatile private var y = -1

    fun mark(px: Int, py: Int) {
        x = px
        y = py
        time = SystemClock.elapsedRealtime()
    }

    fun isOurs(px: Int, py: Int, windowMs: Long = 800L): Boolean {
        if (time == 0L) return false
        val dt = SystemClock.elapsedRealtime() - time
        return dt in 0..windowMs &&
            kotlin.math.abs(px - x) <= 120 && kotlin.math.abs(py - y) <= 120
    }
}
