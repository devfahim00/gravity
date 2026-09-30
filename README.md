# Gravity

Kotlin Android VPN app. Every mode carries an **SSH** session; the mode only changes the transport underneath it.

| Mode | Transport |
|---|---|
| Direct | TCP -> SSH |
| Direct + SNI | TCP -> TLS (custom SNI) -> SSH |
| Proxy | TCP -> HTTP proxy (CONNECT or custom payload) -> SSH |
| Proxy + SNI | TCP -> HTTP proxy -> TLS (custom SNI) -> SSH |
| DNSTT | dnstt-client (local port) -> DNS tunnel -> SSH |

## Architecture
`VpnService` (TUN) -> **hev-socks5-tunnel** (native tun2socks, submodule) -> local SOCKS5 (`SshTunnel`)
-> SSH `direct-tcpip` channels (JSch) -> `Transport` (mode above). DNS is resolved remotely (hev mapdns + SOCKS domain requests).
The app excludes itself from the VPN so its own SSH/dnstt sockets never loop.

## Build
CI (`.github/workflows/build.yml`) builds `libdnstt.so` (dnstt-client) for each ABI, then the debug APK.
Locally: `git clone --recursive`, put dnstt-client binaries in `app/src/main/jniLibs/<abi>/libdnstt.so`, run `gradle :app:assembleDebug` (needs Android SDK + NDK).

## Server notes
- SNI modes need a TLS terminator in front of sshd (stunnel / nginx `stream` with `ssl_preread`).
- DNSTT needs a dnstt-server on your NS domain forwarding to `127.0.0.1:22`. Use IP-literal resolvers (Go binary has no system resolver on Android).
- TLS certificates are not validated in SNI modes (fronting); SSH host key checking is currently off too — pin host keys before production use.
