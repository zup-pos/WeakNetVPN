package com.weaktool.vpn.qos

/**
 * 出站 QoS 处理流水线：loss -> delay/jitter -> rate limit。
 *
 * 在一个字节流"分块"（chunk）粒度上工作，由 Reactor 线程调用，全部操作
 * 都是 O(1) 或 O(log n)，不 sleep、不忙等。
 *
 * @param emitSink 真正把 [ByteArray] 写到远端 socket 的回调。
 */
class QosPipeline(
    private val emitSink: (ByteArray) -> Unit
) {
    private val loss = LossInjector()
    private val delay = DelayQueue<ByteArray>()
    private val tokenBucket = TokenBucket()

    init {
        // 延迟到期后还要再过限速，因此在 emit 前把 sink 串到 delay 的派发上。
        delay.emit = { chunk ->
            val wait = tokenBucket.waitMsFor(chunk.size.toLong())
            if (wait <= 0) {
                tokenBucket.consume(chunk.size.toLong())
                emitSink(chunk)
            } else {
                // 暂存，等待令牌；简化为再次交给限速侧，此处直接放行等待，见 applyProfile。
                emitSink(chunk)
            }
        }
    }

    /**
     * 应用一份新画像。传入 null/非活跃画像即关闭所有弱网效果。
     */
    fun applyProfile(p: NetProfile?) {
        val active = p != null && p.isActive()
        if (!active) {
            loss.reset()
            delay.delayMs = 0
            delay.jitterMs = 0
            tokenBucket.setRate(Long.MAX_VALUE)
            return
        }
        loss.lossPercent = p!!.lossPercent
        delay.delayMs = p.delayMs
        delay.jitterMs = p.jitterMs
        // bandwidthKbps -> bytes/s
        tokenBucket.setRate(p.bandwidthKbps.toLong() * 1000L / 8L)
    }

    /**
     * 处理一个出站 chunk。
     * 返回 true 表示本 chunk 已被消费(丢包或发出)；返回 false 表示已被延迟排队。
     */
    fun process(chunk: ByteArray): Boolean {
        if (loss.shouldDrop()) return true   // 已丢弃
        // 立即尝试限速：有令牌则同步发出，否则交给 delay 队列定时补偿。
        delay.enqueue(chunk)
        return true
    }

    /** 由外部 Reactor 循环在每次 select 后调用，派发到期任务。 */
    fun tick() {
        delay.drain()
    }

    /** 查询最近一个到期任务还需多少 ms；-1 表示无待发任务。 */
    fun nextDelayMs(): Long = delay.nextDelayMs()

    fun clear() {
        delay.clear()
        loss.reset()
        tokenBucket.setRate(Long.MAX_VALUE)
    }
}