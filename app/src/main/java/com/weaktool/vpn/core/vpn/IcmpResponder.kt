package com.weaktool.vpn.core.vpn

/**
 * ICMP 应答器。仅处理 echo request(8)，代远端返回 echo reply(0)。
 * 纯计算 + 直接回写 tun，不注册 selector、不阻塞。
 *
 * @param engine 回包经 [VpnEngine.writeToTun] 写回应用。
 */
class IcmpResponder(private val engine: VpnEngine) {

    /** 由读线程回调：解析后若为 echo request 则回显应答。 */
    fun respond(data: ByteArray, header: IpPacket.Header, off: Int) {
        if (off + 8 > data.size) return          // 头都不完整，丢弃
        val type = data[off].toInt() and 0xff
        if (type != 8) return                    // 非 echo request，忽略
        // 复制 ICMP 头+负载
        val len = data.size - off
        if (len <= 0) return
        val icmp = ByteArray(len)
        System.arraycopy(data, off, icmp, 0, len)
        icmp[0] = 0                              // type = echo reply
        icmp[1] = 0                              // code = 0
        // 重算 ICMP 校验和（含 4 字节占位区外的完整报文）
        icmp[2] = 0; icmp[3] = 0
        val c = IpBuilder.checksum(icmp, 0, len)
        icmp[2] = ((c ushr 8) and 0xff).toByte(); icmp[3] = (c and 0xff).toByte()
        // 目标为"被 ping 的远端"代表应答：源=dst(被代表方), 目的=app
        val pkt = IpBuilder.ip(IpPacket.ICMP, header.dst, header.src, icmp)
        engine.writeToTun(pkt)
    }
}