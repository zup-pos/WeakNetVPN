package com.weaktool.vpn.qos

import java.util.PriorityQueue

/**
 * 延迟/抖动调度器。
 *
 * 把一个带时间戳的"待发送任务"按到期时间排序。Reactor 线程通过 [nextDelayMs]
 * 查询最近任务的剩余等待时间，从而决定 `Selector.select(timeout)` 该睡多久——
 * 有包等发就睡到最近到期点，没有就无限阻塞等新事件。绝不做忙轮询。
 *
 * 线程模型：全部方法仅在单线程 Reactor 内被调用，故不加锁。
 */
class DelayQueue<T> {

    private data class Entry<T>(val dueNs: Long, val payload: T)

    private val heap = PriorityQueue<Entry<T>>(64) { a, b ->
        when {
            a.dueNs < b.dueNs -> -1
            a.dueNs > b.dueNs -> 1
            else -> 0
        }
    }

    @Volatile
    var delayMs: Int = 0
    @Volatile
    var jitterMs: Int = 0

    /** 入队：在 [baseDelayMs]+随机抖动后的时刻派发 [payload]。 */
    fun enqueue(payload: T) {
        val delay = delayMs + jitter()
        if (delay <= 0) {
            // 无延迟，直接"立即到期"——由调度方决定是否同步发。
            emit?.invoke(payload)
            return
        }
        heap.offer(Entry(System.nanoTime() + delay * 1_000_000L, payload))
    }

    /** 距最近到期任务还有多少 ms；无任务返回 -1（表示可无限阻塞）。 */
    fun nextDelayMs(): Long {
        val head = heap.peek() ?: return -1L
        val remainNs = head.dueNs - System.nanoTime()
        if (remainNs <= 0) return 0L
        return (remainNs + 999_999L) / 1_000_000L
    }

    /** 派发所有已到期任务。@return 本次派发个数。 */
    fun drain(): Int {
        var count = 0
        val now = System.nanoTime()
        while (true) {
            val head = heap.peek() ?: break
            if (head.dueNs > now) break
            heap.poll()
            emit?.invoke(head.payload)
            count++
        }
        return count
    }

    fun isEmpty(): Boolean = heap.isEmpty()

    fun size(): Int = heap.size

    fun clear() { heap.clear() }

    /** 延迟到期回调。由外部注入（通常是向 socket 真正写出的回调）。 */
    var emit: ((T) -> Unit)? = null

    private fun jitter(): Int {
        val j = jitterMs
        if (j <= 0) return 0
        return (Math.random() * (2.0 * j + 1.0)).toInt() - j
    }
}