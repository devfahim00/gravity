package com.gravity.vpn.tunnel

import com.gravity.vpn.Profile
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** SSH session + local SOCKS5 server whose CONNECTs become SSH direct-tcpip channels (like `ssh -D`). */
class SshTunnel(
    private val p: Profile,
    private val transport: Transport,
    private val log: (String) -> Unit
) {
    @Volatile private var session: Session? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false
    private val pool = Executors.newCachedThreadPool()

    val isAlive: Boolean get() = !closed && session?.isConnected == true

    fun start(socksPort: Int) {
        val jsch = JSch()
        val useKey = p.privateKey.isNotBlank()
        if (useKey) {
            jsch.addIdentity("gravity", p.privateKey.toByteArray(), null, p.password.takeIf { it.isNotBlank() }?.toByteArray())
        }
        val s = jsch.getSession(p.user, p.host, p.port)
        if (!useKey) s.setPassword(p.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", if (useKey) "publickey" else "password,keyboard-interactive")
        s.setSocketFactory(transport)
        s.serverAliveInterval = 15_000
        s.serverAliveCountMax = 3
        log("SSH: connecting (${p.mode.label})")
        s.connect(30_000)
        session = s
        log("SSH: authenticated as ${p.user}")

        val ss = ServerSocket(socksPort, 128, InetAddress.getByName("127.0.0.1"))
        server = ss
        Thread({
            while (!closed) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }, "socks-accept").start()
        log("SOCKS5: listening on 127.0.0.1:$socksPort")
    }

    private fun handle(c: Socket) {
        try {
            c.tcpNoDelay = true
            val i = DataInputStream(c.getInputStream())
            val o = c.getOutputStream()
            if (i.readUnsignedByte() != 5) return
            i.readFully(ByteArray(i.readUnsignedByte()))
            o.write(byteArrayOf(5, 0)); o.flush()

            i.readUnsignedByte()                       // ver
            val cmd = i.readUnsignedByte()
            i.readUnsignedByte()                       // rsv
            val host = when (i.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { i.readFully(it) }).hostAddress
                3 -> String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                4 -> InetAddress.getByAddress(ByteArray(16).also { i.readFully(it) }).hostAddress
                else -> { reply(o, 8); return }
            }
            val port = i.readUnsignedShort()
            if (cmd != 1) { reply(o, 7); return }      // only CONNECT (no UDP over SSH)

            val s = session ?: run { reply(o, 1); return }
            val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(host); ch.setPort(port)
            val cin = ch.inputStream
            val cout = ch.outputStream
            try { ch.connect(15_000) } catch (e: Exception) { reply(o, 5); return }
            reply(o, 0)

            pool.execute { pipe(c.getInputStream(), cout, c, ch) }
            pipe(cin, o, c, ch)
        } catch (_: Exception) {
            runCatching { c.close() }
        }
    }

    private fun reply(o: OutputStream, code: Int) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
    }

    private fun pipe(src: InputStream, dst: OutputStream, c: Socket, ch: ChannelDirectTCPIP) {
        try {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n); dst.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { c.close() }
            runCatching { ch.disconnect() }
        }
    }

    fun stop() {
        closed = true
        runCatching { server?.close() }
        runCatching { session?.disconnect() }
        pool.shutdownNow()
    }
}
