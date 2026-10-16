package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.link.Hosts
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * The loopback ports a generated config listens on once its box or probe runs, held until [close]. Xray sets
 * SO_REUSEPORT on its listeners, so two live configs of this process dealt the same port would both bind it and
 * cross-wire their traffic: no port is dealt again while a lease holds it. The owner of the box closes the lease with
 * it; a build given none leases for its own duration only.
 */
class PortLease : Closeable {
    internal val ports = ArrayList<Int>()

    internal fun reserve(count: Int, host: String = "127.0.0.1"): List<Int> = LocalPorts.reserve(count, host, this)

    override fun close() {
        LocalPorts.release(this)
    }
}

/** MkManyPorts and GetRandomString (src/global/Utils.cpp:204-219, :77-91). */
internal object LocalPorts {
    private const val AUTH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    /** Binds one port may take while the kernel keeps dealing leased ones; then 0 is recorded. */
    private const val MAX_ATTEMPTS = 32

    /** Every port a live [PortLease] holds; guards the leases' lists too. */
    private val leased = HashSet<Int>()

    /**
     * Reserves [count] distinct loopback ports for [lease]: every listener stays bound until all of them have been
     * recorded, so a batch never repeats a port, and one already leased is bound past; then all are closed. A failed
     * bind records 0, which the callers treat as "reserve again" or as an error (generate.cpp:1451-1457, :1488-1499).
     */
    fun reserve(count: Int, host: String, lease: PortLease): List<Int> {
        val ports = ArrayList<Int>(count)
        val sockets = ArrayList<ServerSocket>(count)
        // listenWithRetry (Utils.cpp:184-194): a host that is not an address literal binds to any interface.
        val bindAddress = if (host.isNotEmpty() && Hosts.isIpAddress(host)) {
            try {
                InetAddress.getByName(host.removePrefix("[").removeSuffix("]"))
            } catch (e: IOException) {
                null
            }
        } else null
        try {
            repeat(count) {
                var port = 0
                var attempts = 0
                while (port == 0 && attempts++ < MAX_ATTEMPTS) {
                    val socket = bind(bindAddress) ?: break
                    sockets.add(socket)
                    // Leased while still bound, so a concurrent batch can never be dealt it unseen.
                    if (claim(socket.localPort, lease)) port = socket.localPort
                }
                ports.add(port)
            }
        } finally {
            for (socket in sockets) {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // nothing to release
                }
            }
        }
        return ports
    }

    private fun bind(address: InetAddress?): ServerSocket? {
        var socket: ServerSocket? = null
        try {
            socket = ServerSocket()
            socket.bind(if (address != null) InetSocketAddress(address, 0) else InetSocketAddress(0))
            return socket
        } catch (e: IOException) {
            // recorded as 0
        } catch (e: SecurityException) {
            // recorded as 0
        }
        try {
            socket?.close()
        } catch (e: IOException) {
            // never bound
        }
        return null
    }

    private fun claim(port: Int, lease: PortLease): Boolean = synchronized(leased) {
        if (!leased.add(port)) return false
        lease.ports.add(port)
        true
    }

    fun release(lease: PortLease) {
        synchronized(leased) {
            leased.removeAll(lease.ports.toSet())
            lease.ports.clear()
        }
    }

    /** GetRandomString: [length] characters of `[A-Za-z0-9]`. */
    @JvmStatic
    fun randomAuth(length: Int = 32): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(AUTH_ALPHABET[random.nextInt(AUTH_ALPHABET.length)]) }
        return sb.toString()
    }
}
