package com.ninepointnine.helper.data.device

import java.net.Inet4Address
import java.net.NetworkInterface

/** A deliberately small IPv4 scan unit. Large networks are rejected, not truncated. */
data class Ipv4Subnet(
    private val address: Int,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in MIN_PREFIX_LENGTH..MAX_PREFIX_LENGTH) { "ipv4_prefix_unsupported" }
    }

    fun hostAddresses(maxHosts: Int = MAX_HOSTS_PER_SUBNET): List<String> {
        val hostCount = hostCount()
        val usableCount = usableHostCount()
        if (usableCount <= 0L || usableCount > maxHosts) return emptyList()
        val mask = -1 shl (32 - prefixLength)
        val network = address and mask
        val firstHost = network + 1
        val lastHost = (network.toLong() + hostCount - 2L).toInt()
        return (firstHost..lastHost).map(::toIpv4)
    }

    fun exceedsHostLimit(maxHosts: Int = MAX_HOSTS_PER_SUBNET): Boolean =
        usableHostCount() > maxHosts

    private fun hostCount(): Long = 1L shl (32 - prefixLength)

    private fun usableHostCount(): Long = hostCount() - 2L

    companion object {
        const val MIN_PREFIX_LENGTH = 16
        const val MAX_PREFIX_LENGTH = 30
        const val MAX_HOSTS_PER_SUBNET = 254
        const val MAX_TOTAL_HOSTS = 512

        fun from(address: Inet4Address, prefixLength: Short): Ipv4Subnet? =
            prefixLength.toInt()
                .takeIf { it in MIN_PREFIX_LENGTH..MAX_PREFIX_LENGTH }
                ?.let { Ipv4Subnet(toInt(address.address), it) }

        private fun toInt(bytes: ByteArray): Int = bytes.fold(0) { value, byte ->
            (value shl 8) or (byte.toInt() and 0xff)
        }

        private fun toIpv4(value: Int): String = listOf(
            value ushr 24 and 0xff,
            value ushr 16 and 0xff,
            value ushr 8 and 0xff,
            value and 0xff,
        ).joinToString(".")
    }
}

fun interface LocalIpv4SubnetProvider {
    fun subnets(): List<Ipv4Subnet>
}

/** Reads currently-up interface addresses only when the user starts discovery. */
class JdkLocalIpv4SubnetProvider : LocalIpv4SubnetProvider {
    override fun subnets(): List<Ipv4Subnet> = runCatching {
        buildList {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@buildList
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue
                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val address = interfaceAddress.address as? Inet4Address ?: return@forEach
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach
                    Ipv4Subnet.from(address, interfaceAddress.networkPrefixLength)?.let(::add)
                }
            }
        }.distinct()
    }.getOrDefault(emptyList())
}
