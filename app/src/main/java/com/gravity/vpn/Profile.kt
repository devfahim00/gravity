package com.gravity.vpn

import android.content.Context
import org.json.JSONObject

/** All connection modes tunnel SSH; they only differ in how the SSH transport socket is created. */
enum class Mode(val label: String) {
    DIRECT("Direct"),
    DIRECT_SNI("Direct + SNI"),
    PROXY("Proxy"),
    PROXY_SNI("Proxy + SNI"),
    DNSTT("DNSTT")
}

data class Profile(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val password: String = "",      // SSH password, or key passphrase when a private key is set
    val privateKey: String = "",    // PEM text (optional)
    val mode: Mode = Mode.DIRECT,
    val sni: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val payload: String = "",       // custom HTTP payload; placeholders: [host_port] [host] [port] [crlf]
    val dnsType: String = "udp",    // udp | doh | dot
    val dnsResolver: String = "8.8.8.8:53",
    val dnsDomain: String = "",
    val dnsPubkey: String = ""
) {
    fun save(ctx: Context) {
        val o = JSONObject()
            .put("host", host).put("port", port).put("user", user).put("password", password)
            .put("privateKey", privateKey).put("mode", mode.name).put("sni", sni)
            .put("proxyHost", proxyHost).put("proxyPort", proxyPort).put("payload", payload)
            .put("dnsType", dnsType).put("dnsResolver", dnsResolver)
            .put("dnsDomain", dnsDomain).put("dnsPubkey", dnsPubkey)
        ctx.getSharedPreferences("gravity", Context.MODE_PRIVATE).edit().putString("profile", o.toString()).apply()
    }

    companion object {
        fun load(ctx: Context): Profile {
            val s = ctx.getSharedPreferences("gravity", Context.MODE_PRIVATE).getString("profile", null)
                ?: return Profile()
            val o = JSONObject(s)
            return Profile(
                host = o.optString("host"), port = o.optInt("port", 22), user = o.optString("user"),
                password = o.optString("password"), privateKey = o.optString("privateKey"),
                mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.DIRECT),
                sni = o.optString("sni"), proxyHost = o.optString("proxyHost"),
                proxyPort = o.optInt("proxyPort", 8080), payload = o.optString("payload"),
                dnsType = o.optString("dnsType", "udp"), dnsResolver = o.optString("dnsResolver", "8.8.8.8:53"),
                dnsDomain = o.optString("dnsDomain"), dnsPubkey = o.optString("dnsPubkey")
            )
        }
    }
}

enum class VpnState { IDLE, CONNECTING, CONNECTED }
