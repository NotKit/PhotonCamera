/* java.net: content-type guessing by extension, and a blocking TCP socket over
 * POSIX for the debug client.  URL/HttpURLConnection are ABSENT -- nothing in
 * this tree opens one that is not the debug socket. */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.net

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.gethostbyname
import platform.posix.htons
import platform.posix.in_addr
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket

object URLConnection {
    fun guessContentTypeFromName(fname: String?): String? = when (
        fname?.substringAfterLast('.')?.lowercase()
    ) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "dng" -> "image/x-adobe-dng"
        "gif" -> "image/gif"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "txt" -> "text/plain"
        else -> null
    }
}

class UnknownHostException(message: String? = null) : java.io.IOException(message)

class InetAddress private constructor(private val host: String, private val addr: UInt) {
    fun getHostAddress(): String {
        val n = addr
        return "${n and 0xFFu}.${(n shr 8) and 0xFFu}.${(n shr 16) and 0xFFu}.${(n shr 24) and 0xFFu}"
    }

    fun getHostName(): String = host
    internal fun raw(): UInt = addr
    override fun toString(): String = "$host/${getHostAddress()}"

    companion object {
        fun getByName(host: String): InetAddress = memScoped {
            val he = gethostbyname(host) ?: throw UnknownHostException(host)
            val list = he.pointed.h_addr_list ?: throw UnknownHostException(host)
            val first = list[0] ?: throw UnknownHostException(host)
            // h_addr_list[0] is four network-order bytes for an AF_INET host.
            val b = first.reinterpret<kotlinx.cinterop.ByteVar>()
            var packed = 0u
            for (i in 0..3) packed = packed or ((b[i].toUInt() and 0xFFu) shl (i * 8))
            InetAddress(host, packed)
        }

        fun getLocalHost(): InetAddress = getByName("127.0.0.1")
    }
}

/** A blocking TCP client socket.  Server sockets are absent. */
class Socket(host: String, port: Int) {
    private var fd: Int = -1
    private val streamIn: SocketInputStream
    private val streamOut: SocketOutputStream

    constructor(address: InetAddress, port: Int) : this(address.getHostAddress(), port)

    init {
        val addr = InetAddress.getByName(host)
        fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) throw java.io.IOException("socket() failed")
        memScoped {
            val sa = alloc<sockaddr_in>()
            sa.sin_family = AF_INET.toUShort()
            sa.sin_port = htons(port.toUShort())
            sa.sin_addr.s_addr = addr.raw()
            if (connect(fd, sa.ptr.reinterpret<sockaddr>(), sockaddr_in.size.toUInt()) != 0) {
                close(fd)
                fd = -1
                throw java.io.IOException("connect() to $host:$port failed")
            }
        }
        streamIn = SocketInputStream(this)
        streamOut = SocketOutputStream(this)
    }

    internal fun descriptor(): Int = fd
    fun getInputStream(): java.io.InputStream = streamIn
    fun getOutputStream(): java.io.OutputStream = streamOut
    fun isConnected(): Boolean = fd >= 0
    fun isClosed(): Boolean = fd < 0
    fun setSoTimeout(ms: Int) {}
    fun close() { if (fd >= 0) close(fd); fd = -1 }
}

private class SocketInputStream(private val socket: Socket) : java.io.InputStream() {
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (socket.isClosed()) return -1
        val n = b.usePinned { recv(socket.descriptor(), it.addressOf(off), len.toULong(), 0).toInt() }
        return if (n <= 0) -1 else n
    }

    override fun close() = socket.close()
}

private class SocketOutputStream(private val socket: Socket) : java.io.OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (socket.isClosed()) throw java.io.IOException("socket closed")
        b.usePinned { send(socket.descriptor(), it.addressOf(off), len.toULong(), 0) }
    }

    override fun close() = socket.close()
}

class MalformedURLException(message: String? = null) : java.io.IOException(message)

class URL(private val spec: String) {
    private val uri = android.net.Uri.parse(spec)

    init { if (uri.getScheme() == null) throw MalformedURLException(spec) }

    fun getProtocol(): String = uri.getScheme() ?: ""
    fun getHost(): String = uri.getHost() ?: ""
    fun getPath(): String = uri.getPath() ?: ""
    fun getPort(): Int = -1
    fun openConnection(): HttpURLConnection = HttpURLConnection(this)
    fun openStream(): java.io.InputStream = openConnection().getInputStream()
    override fun toString(): String = spec
}

open class HttpURLConnection internal constructor(private val url: URL) {
    private var connectTimeout = 0
    private var readTimeout = 0

    fun setConnectTimeout(ms: Int) { connectTimeout = ms }
    fun setReadTimeout(ms: Int) { readTimeout = ms }
    fun setRequestMethod(method: String) {}
    fun setRequestProperty(key: String, value: String) {}
    fun getURL(): URL = url
    fun getResponseCode(): Int = -1
    fun disconnect() {}

    /** See the file header: there is no TLS here, so this always fails. */
    fun getInputStream(): java.io.InputStream =
        throw java.io.IOException("no network client on this port: " + url)
}
