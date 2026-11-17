package com.sidesense.enhance

import android.util.Log

/**
 * 日志接口: 模块里所有日志都通过它输出, 方便统一开关。
 * 消息用 lambda 传入, 日志关闭时不会执行字符串拼接和反射等开销。
 *
 * 用法:
 *   SsLog.i { "message" }
 *   SsLog.w(t) { "message" }
 *   SsLog.e(t) { "message" }
 *
 * 查看: adb logcat -s SideSenseSort
 */
interface SsLogger {
    fun i(msg: () -> String)
    fun w(t: Throwable? = null, msg: () -> String)
    fun e(t: Throwable? = null, msg: () -> String)
}

object SsLog : SsLogger {

    private const val TAG = "SideSenseSort"

    /** 总开关: false 时关闭 info / warn 日志 */
    @Volatile
    var enabled: Boolean = false

    /** 错误日志开关: 即使 enabled=false 也保留 error 日志, 方便出问题时排查; 想完全静默改为 false */
    @Volatile
    var errorEnabled: Boolean = true

    override fun i(msg: () -> String) {
        if (enabled) Log.i(TAG, msg())
    }

    override fun w(t: Throwable?, msg: () -> String) {
        if (enabled) Log.w(TAG, msg(), t)
    }

    override fun e(t: Throwable?, msg: () -> String) {
        if (enabled || errorEnabled) Log.e(TAG, msg(), t)
    }
}