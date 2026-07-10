package io.github.xyzboom.aiFuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC

/**
 * 日志工具类，提供统一的追踪功能。
 */
object LogUtils {
    
    /** 追踪 ID 键名 */
    const val TRACE_ID_KEY = "traceId"
    
    /** 生成唯一追踪 ID */
    fun generateTraceId(): String = "${System.currentTimeMillis()}-${Thread.currentThread().id}"
    
    /** 设置追踪 ID */
    fun setTraceId(traceId: String) {
        MDC.put(TRACE_ID_KEY, traceId)
    }
    
    /** 清除追踪 ID */
    fun clearTraceId() {
        MDC.remove(TRACE_ID_KEY)
    }
    
    /** 在追踪上下文中执行代码块 */
    inline fun <T> withTrace(traceId: String = generateTraceId(), block: () -> T): T {
        setTraceId(traceId)
        try {
            return block()
        } finally {
            clearTraceId()
        }
    }
    
    /** 获取当前追踪 ID */
    fun currentTraceId(): String? = MDC.get(TRACE_ID_KEY)
}

/**
 * 获取 Logger 的快捷函数。
 */
inline fun <reified T> logger() = KotlinLogging.logger(T::class.java.name)