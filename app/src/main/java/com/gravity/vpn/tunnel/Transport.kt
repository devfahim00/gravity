package com.gravity.vpn.tunnel

import com.gravity.vpn.Mode
import com.gravity.vpn.Profile
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Builds the raw byte pipe that SSH runs over, according to the selected mode:
 *  DIRECT      : TCP -> ssh server
 *  DIRECT_SNI  : TCP -> TLS(SNI) -> ssh server      (server side needs a TLS terminator, e.g. stunnel/nginx stream)
 *  PROXY       : TCP -> HTTP proxy (CONNECT / custom payload) -> ssh server
 *  PROXY_SNI   : TCP -> HTTP proxy -> TLS(SNI) -> ssh server
 *  DNSTT       : TCP -> local dnstt-client (127.0.0.1:dnsttPort) -> DNS tunnel -> ssh server
 */
class Transport(private val p: Profile, private val dnsttPort: Int) : SocketFactory {

    override fun createSocket(host: String, port: Int): Socket = when (p.mode) {
        Mode.DIRECT -> plain(p.host, p.port)
        Mode.DIRECT_SNI -> tls(plain(p.host, p.port), p.port)
        Mode.PROXY -> viaProxy()
        Mode.PROXY_SNI -> tls(viaProxy(), p.port)
        Mode.DNSTT -> plain("127.0.0.1", dnsttPort)
    }

    override fun getInputStream(s: Socket): InputStream = s.getInputStream()
    override fun getOutputStream(s: Socket): OutputStream = s.getOutputStream()

    private fun plain(host: String, port: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), 15_000)
        return s
    }

    // SNI fronting hosts rarely present a matching cert, so validation is intentionally disabled.
    // SSH host identity is what protects the session; TLS here is only a camouflage layer.
    private fun tls(base: Socket, port: Int): Socket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        val sniHost = p.sni.ifBlank { p.host }
        val ssl = ctx.socketFactory.createSocket(base, sniHost, port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sniHost)) }
        base.soTimeout = 15_000
        ssl.startHandshake()
        base.soTimeout = 0
        return ssl
    }

    private fun viaProxy(): Socket {
        val s = plain(p.proxyHost, p.proxyPort)
        val target = "${p.host}:${p.port}"
        val req = if (p.payload.isBlank()) {
            "CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n\r\n"
        } else {
            p.payload
                .replace("[host_port]", target).replace("[host]", p.host).replace("[port]", p.port.toString())
                .replace("[crlf]", "\r\n").replace("\\r\\n", "\r\n").replace("\\n", "\n")
        }
        s.soTimeout = 15_000
        s.getOutputStream().apply { write(req.toByteArray()); flush() }

        // Read the response head byte-by-byte so no tunnel bytes are swallowed.
        val inp = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw IOException("Proxy closed connection")
            head.append(b.toChar())
            if (head.length > 16_384) throw IOException("Proxy response too large")
        }
        val code = head.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad proxy response")
        if (code !in 200..299 && code != 101) throw IOException("Proxy refused: ${head.lineSequence().first()}")
        s.soTimeout = 0
        return s
    }
}
