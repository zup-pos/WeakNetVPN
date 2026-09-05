package com.weaktool.vpn.core.vpn

/** 下行(远端->应用)封包工具：把 TCP/UDP 负载还原成 IP 报文，即时计算校验和。 */
object IpBuilder {

    fun checksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0; var i = 0
        while (i + 1 < len) {
            sum += ((buf[off + i].toInt() and 0xff) shl 8) or (buf[off + i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < len) sum += (buf[off + i].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    /** srcIp/dstIp 为 int（主机序）。inner=TCP/UDP/ICMP 头+负载。 */
    fun ip(proto: Int, srcIp: Int, dstIp: Int, inner: ByteArray): ByteArray {
        val total = 20 + inner.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p[1] = 0
        p[2] = ((total ushr 8) and 0xff).toByte(); p[3] = (total and 0xff).toByte()
        p[6] = 0x40.toByte(); p[7] = 0          // DF
        p[8] = 64.toByte(); p[9] = proto.toByte()
        writeInt(p, 12, srcIp); writeInt(p, 16, dstIp)
        val c = checksum(p, 0, 20)
        p[10] = ((c ushr 8) and 0xff).toByte(); p[11] = (c and 0xff).toByte()
        System.arraycopy(inner, 0, p, 20, inner.size)
        return p
    }

    fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xff).toByte(); b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte(); b[off + 3] = (v and 0xff).toByte()
    }
}
