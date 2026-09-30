package com.gravity.vpn.vpn

/** JNI bridge to hev-socks5-tunnel (class name fixed via -DPKGNAME/-DCLSNAME in Application.mk). */
object TProxyService {
    init { System.loadLibrary("hev-socks5-tunnel") }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray
}
