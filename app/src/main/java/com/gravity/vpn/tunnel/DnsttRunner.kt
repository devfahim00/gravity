package com.gravity.vpn.tunnel

import android.content.Context
import com.gravity.vpn.Profile
import java.io.File

/**
 * Runs the dnstt-client binary (packaged as libdnstt.so per ABI) which exposes a local TCP port
 * tunnelled through DNS to the dnstt server; SSH then connects to that local port.
 */
class DnsttRunner(
    private val ctx: Context,
    private val p: Profile,
    private val localPort: Int,
    private val log: (String) -> Unit
) {
    private var proc: Process? = null

    fun start() {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libdnstt.so")
        require(bin.exists()) { "libdnstt.so missing for this ABI (built by the CI workflow)" }
        require(p.dnsDomain.isNotBlank() && p.dnsPubkey.isNotBlank()) { "DNSTT needs domain + public key" }

        val resolverFlag = when (p.dnsType) { "doh" -> "-doh"; "dot" -> "-dot"; else -> "-udp" }
        val cmd = listOf(bin.path, resolverFlag, p.dnsResolver, "-pubkey", p.dnsPubkey, p.dnsDomain, "127.0.0.1:$localPort")
        log("DNSTT: starting client via ${p.dnsType} ${p.dnsResolver}")
        val pr = ProcessBuilder(cmd).redirectErrorStream(true).start()
        proc = pr
        Thread({
            runCatching { pr.inputStream.bufferedReader().forEachLine { log("dnstt: $it") } }
        }, "dnstt-log").start()
        Thread.sleep(1500)
        check(pr.isAlive) { "dnstt-client exited early" }
    }

    fun stop() {
        runCatching { proc?.destroy() }
    }
}
