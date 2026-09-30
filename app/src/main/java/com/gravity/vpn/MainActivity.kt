package com.gravity.vpn

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gravity.vpn.vpn.GravityVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        var p by remember { mutableStateOf(Profile.load(this)) }
        val state by Bus.state.collectAsState()
        val logs by Bus.logs.collectAsState()

        val startVpn = {
            p.save(this)
            startService(Intent(this, GravityVpnService::class.java).setAction(GravityVpnService.ACTION_START))
        }
        val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) startVpn() else Bus.log("VPN permission denied")
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Gravity", style = MaterialTheme.typography.headlineMedium)
            Text("Status: ${state.name}")

            Text("SSH server", style = MaterialTheme.typography.titleMedium)
            F("Host", p.host) { p = p.copy(host = it.trim()) }
            F("Port", p.port.toString()) { p = p.copy(port = it.toIntOrNull() ?: 22) }
            F("Username", p.user) { p = p.copy(user = it.trim()) }
            F("Password / key passphrase", p.password, secret = true) { p = p.copy(password = it) }
            F("Private key (PEM, optional)", p.privateKey, lines = 3) { p = p.copy(privateKey = it) }

            Text("Mode", style = MaterialTheme.typography.titleMedium)
            Mode.values().forEach { m ->
                Row { RadioButton(selected = p.mode == m, onClick = { p = p.copy(mode = m) }); Text(m.label, Modifier.padding(top = 12.dp)) }
            }

            if (p.mode == Mode.DIRECT_SNI || p.mode == Mode.PROXY_SNI) {
                F("SNI host", p.sni) { p = p.copy(sni = it.trim()) }
            }
            if (p.mode == Mode.PROXY || p.mode == Mode.PROXY_SNI) {
                F("Proxy host", p.proxyHost) { p = p.copy(proxyHost = it.trim()) }
                F("Proxy port", p.proxyPort.toString()) { p = p.copy(proxyPort = it.toIntOrNull() ?: 8080) }
                F("Payload (blank = CONNECT). [host_port] [host] [port] [crlf]", p.payload, lines = 3) { p = p.copy(payload = it) }
            }
            if (p.mode == Mode.DNSTT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("udp", "doh", "dot").forEach { t ->
                        FilterChip(selected = p.dnsType == t, onClick = { p = p.copy(dnsType = t) }, label = { Text(t.uppercase()) })
                    }
                }
                F("Resolver (udp/dot: ip:port, doh: https URL)", p.dnsResolver) { p = p.copy(dnsResolver = it.trim()) }
                F("Tunnel domain (NS)", p.dnsDomain) { p = p.copy(dnsDomain = it.trim()) }
                F("Server public key (hex)", p.dnsPubkey) { p = p.copy(dnsPubkey = it.trim()) }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (state == VpnState.IDLE) {
                        val i = VpnService.prepare(this@MainActivity)
                        if (i != null) vpnPermission.launch(i) else startVpn()
                    } else {
                        startService(Intent(this@MainActivity, GravityVpnService::class.java).setAction(GravityVpnService.ACTION_STOP))
                    }
                }
            ) { Text(if (state == VpnState.IDLE) "Connect" else "Disconnect") }

            Text("Log", style = MaterialTheme.typography.titleMedium)
            Text(logs.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun F(label: String, value: String, secret: Boolean = false, lines: Int = 1, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) },
            modifier = Modifier.fillMaxWidth(), minLines = lines, maxLines = if (lines == 1) 1 else 6,
            singleLine = lines == 1,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
        )
    }
}
