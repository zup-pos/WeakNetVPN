package com.weaktool.vpn.core.vpn

/**
 * 轻量 IPv4 报文头解析(仅 IPv4；IPv6 直接透传由上层策略决定)。
 *
 * 所有访问都基于读字段一次，避免反复解析。本类不持有整包数据，只描述偏移。
 */
object IpPacket {

    const val IPV4 = 4
    const val IPV6 = 6
    const val TCP = 6
    const val UDP = 17
    const val ICMP = 1

    const val IHL_OFF = 0      // version + IHL
    const val PROTO_OFF = 9
    const val SRC_OFF = 12
    const val DST_OFF = 16
    const val FRAG_OFF = 6     // flags + fragment offset

    /** 从 buffer 解析 IPv4 头信息并填充到 [hdr] 对象；返回 payload 起始偏移。 */
    fun parse(buf: ByteArray, hdr: Header): Int {
        val version = (buf[0].toInt() ushr 4) and 0x0f
        hdr.version = version
        if (version != IPV4) {
            hdr.totalLength = buf.size
            return 0
        }
        val ihl = (buf[0].toInt() and 0x0f) * 4
        hdr.ihl = ihl
        hdr.protocol = buf[PROTO_OFF].toInt() and 0xff
        hdr.src = readInt(buf, SRC_OFF)
        hdr.dst = readInt(buf, DST_OFF)
        hdr.totalLength = buf.size
        // fragment offset (13 bits) 非 0 = 分片报文，TCP 无法直接处理
        val frag = ((buf[FRAG_OFF].toInt() and 0xff) shl 8) or
            (buf[FRAG_OFF + 1].toInt() and 0xff)
        hdr.moreFragments = (frag and 0x2000) != 0
        hdr.fragmentOffset = frag and 0x1fff
        return ihl
    }

    fun readInt(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 24) or
            ((buf[off + 1].toInt() and 0xff) shl 16) or
            ((buf[off + 2].toInt() and 0xff) shl 8) or
            (buf[off + 3].toInt() and 0xff)

    /** 主机序 int -> 点分十进制字符串。 */
    fun ipToStr(ip: Int): String {
        return "${(ip ushr 24) and 0xff}.${(ip ushr 16) and 0xff}." +
            "${(ip ushr 8) and 0xff}.${ip and 0xff}"
    }

    class Header {
        var version = 0
        var ihl = 20
        var protocol = 0
        var src = 0
        var dst = 0
        var totalLength = 0
        var moreFragments = false
        var fragmentOffset = 0
    }
}