package com.weaktool.vpn.qos

/**
 * QoS 网络画像模型 —— 描述当前弱网模拟的参数集合。
 *
 * @param bandwidthKbps 带宽上限(kbps)。0 表示不限速。
 * @param delayMs       单向额外延迟(ms)。
 * @param jitterMs      抖动(ms)，与 delayMs 叠加成随机延迟区间。
 * @param lossPercent   随机丢包率(%)，0..100。
 */
data class NetProfile(
    val bandwidthKbps: Int = 0,
    val delayMs: Int = 0,
    val jitterMs: Int = 0,
    val lossPercent: Int = 0
) {
    init {
        require(bandwidthKbps >= 0) { "bandwidth must be >= 0" }
        require(delayMs >= 0) { "delay must be >= 0" }
        require(jitterMs in 0..delayMs * 4) { "jitter out of plausible range" }
        require(lossPercent in 0..100) { "lossPercent must be 0..100" }
    }

    fun isActive(): Boolean =
        bandwidthKbps > 0 || delayMs > 0 || jitterMs > 0 || lossPercent > 0

    companion object {
        val DISABLED = NetProfile(0, 0, 0, 0)
    }
}