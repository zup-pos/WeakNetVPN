package com.weaktool.vpn.qos

import java.util.Random
import java.util.concurrent.ThreadLocalRandom

/**
 * 随机丢包注入器。
 *
 * 只做纯计算，不碰 IO。被 QosPipeline 在 Reactor 线程内调用，
 * 避免任何自旋或忙等。
 */
class LossInjector {

    @Volatile
    var lossPercent: Int = 0

    /** 独立 RNG，避免在共享锁上争用。 */
    private val rng: Random = ThreadLocalRandom.current()

    /**
     * 判定本包是否应被丢弃。
     * @return true = 丢，false = 放行。
     */
    fun shouldDrop(): Boolean {
        val p = lossPercent
        if (p <= 0) return false
        if (p >= 100) return true
        return rng.nextInt(100) < p
    }

    fun reset() { lossPercent = 0 }
}