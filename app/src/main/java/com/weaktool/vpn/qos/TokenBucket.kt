package com.weaktool.vpn.qos

import kotlin.math.max
import kotlin.math.min

/**
 * 令牌桶限速器。
 *
 * 基于单调时钟累计令牌，而不是用 sleep 忙等节流。核心诉求：调用方是事件驱动的
 * Reactor 线程，它只想知道"当前这包数据能否立即发、不能的话还需等多少 ms"，
 * 得到延迟后交给定时任务调度，绝不在 IO 线程上自旋等待。
 *
 * @param initialRateBps 初始速率(byte/s)。
 * @param burstBytes     桶容量(突发上限)，默认取 1 秒的配额。
 */
class TokenBucket(
    private var initialRateBps: Long = Long.MAX_VALUE,
    burstBytes: Long = Long.MAX_VALUE
) {
    // 用 double 存，避免 long 溢出导致除法错乱；最大约 8 EB/桶。
    private var tokens: Double = burstBytes.toDouble()
    private var capacity: Double = burstBytes.toDouble()
    private var lastRefillNs: Long = System.nanoTime()
    private var paused = false

    @Volatile
    private var rateBps: Double =
        if (initialRateBps <= 0) Double.MAX_VALUE else initialRateBps.toDouble()

    fun setRate(rateBps: Long) {
        this.rateBps = if (rateBps <= 0) Double.MAX_VALUE else rateBps.toDouble()
    }

    /** 恢复初始速率的便捷方法。 */
    fun reset() = setRate(initialRateBps)

    /** 若突发容量变化需要更新容量，供后续扩展。 */
    fun setCapacity(burstBytes: Long) {
        capacity = max(1.0, burstBytes.toDouble())
        tokens = min(tokens, capacity)
    }

    fun pause() { paused = true }

    fun resume() {
        paused = false
        lastRefillNs = System.nanoTime()
    }

    /**
     * 判断当前 [bytes] 是否可立即放行。
     * @return <=0 表示可立即放行(返回的绝对等待毫秒为 0)；
     *         >0  表示还需等待的毫秒数。
     */
    fun waitMsFor(bytes: Long): Long {
        if (paused) return Long.MAX_VALUE
        if (rateBps >= Double.MAX_VALUE) return 0L
        refill()
        if (bytes <= 0) return 0L
        if (tokens >= bytes) {
            tokens -= bytes
            return 0L
        }
        // 剩余令牌不足：返回需要等待的毫秒数，由调度器定时再触发，
        // 不阻塞当前线程。注意这里先不扣减，等真正的发送回调里再 consume。
        val shortage = bytes - tokens
        val waitMs = (shortage / rateBps * 1000.0).toLong().coerceAtLeast(1)
        return waitMs
    }

    /** 真正的扣减入口：延迟到期、可以发送时调用。 */
    fun consume(bytes: Long) {
        refill()
        if (rateBps < Double.MAX_VALUE) {
            tokens -= bytes.toDouble()
            if (tokens < 0) tokens = 0.0
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - lastRefillNs
        if (elapsed > 0) {
            val add = elapsed / 1e9 * rateBps
            tokens = min(capacity, tokens + add)
            lastRefillNs = now
        }
    }
}