package com.gravity.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gravity.vpn.Bus
import com.gravity.vpn.Mode
import com.gravity.vpn.Profile
import com.gravity.vpn.VpnState
import com.gravity.vpn.tunnel.DnsttRunner
import com.gravity.vpn.tunnel.SshTunnel
import com.gravity.vpn.tunnel.Transport
import java.io.File

class GravityVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.gravity.vpn.START"
        const val ACTION_STOP = "com.gravity.vpn.STOP"
        const val SOCKS_PORT = 10808
        const val DNSTT_PORT = 7000
        private const val CHANNEL = "gravity"
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunnel: SshTunnel? = null
    private var dnstt: DnsttRunner? = null
    private var tproxyStarted = false
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        startForegroundCompat()
        Bus.state.value = VpnState.CONNECTING
        Thread({ connect() }, "gravity-connect").start()
        return START_NOT_STICKY
    }

    private fun connect() {
        val p = Profile.load(this)
        try {
            if (p.host.isBlank() || p.user.isBlank()) error("Server host and username are required")
            if (p.mode == Mode.PROXY || p.mode == Mode.PROXY_SNI) {
                if (p.proxyHost.isBlank()) error("Proxy host is required for proxy modes")
            }

            if (p.mode == Mode.DNSTT) {
                dnstt = DnsttRunner(this, p, DNSTT_PORT, Bus::log).also { it.start() }
            }
            tunnel = SshTunnel(p, Transport(p, DNSTT_PORT), Bus::log).also { it.start(SOCKS_PORT) }

            val pfd = Builder()
                .setSession("Gravity")
                .setMtu(1500)
                .addAddress("198.18.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:198:18::1", 126)   // claim IPv6 too, hev drops it => no v6 leak
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")          // answered by hev "mapdns", resolved remotely via SOCKS domain
                .addDisallowedApplication(packageName) // our own SSH/dnstt sockets bypass the VPN (no loop)
                .establish() ?: error("VPN permission not granted")
            tun = pfd

            val cfg = File(filesDir, "hev.yml")
            cfg.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 198.18.0.1
                socks5:
                  port: $SOCKS_PORT
                  address: 127.0.0.1
                  udp: 'udp'
                mapdns:
                  address: 198.18.0.2
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  log-level: warn
                """.trimIndent()
            )
            if (!TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)) error("tun2socks failed to start")
            tproxyStarted = true

            Bus.state.value = VpnState.CONNECTED
            Bus.log("VPN: connected")

            while (running && tunnel?.isAlive == true) Thread.sleep(2000)
            if (running) Bus.log("SSH session dropped")
        } catch (e: Throwable) {
            Bus.log("Error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            stopAll()
        }
    }

    @Synchronized
    private fun stopAll() {
        running = false
        if (tproxyStarted) { runCatching { TProxyService.TProxyStopService() }; tproxyStarted = false }
        runCatching { tun?.close() }; tun = null
        runCatching { tunnel?.stop() }; tunnel = null
        runCatching { dnstt?.stop() }; dnstt = null
        Bus.state.value = VpnState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopAll() }
    override fun onDestroy() { stopAll(); super.onDestroy() }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Gravity")
            .setContentText("SSH tunnel active")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }
}
