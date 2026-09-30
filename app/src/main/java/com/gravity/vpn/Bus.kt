package com.gravity.vpn

import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny in-process bus between the VPN service and the UI. */
object Bus {
    val state = MutableStateFlow(VpnState.IDLE)
    val logs = MutableStateFlow<List<String>>(emptyList())

    fun log(msg: String) {
        logs.value = (logs.value + msg).takeLast(200)
    }
}
