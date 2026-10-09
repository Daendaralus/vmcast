# vmcast

Low-latency desktop audio from Voicemeeter to an Android phone.

- `sender/`: Rust. Taps a Voicemeeter bus through the Remote API bus-insert
  callback (no extra virtual cable, no extra buffering), sends raw PCM over UDP,
  or Opus with frame redundancy for lossy links (mobile data / WireGuard).
- `android/`: Kotlin + a small C++ core. Adaptive jitter buffer and AAudio
  low-latency output on a real-time callback thread, never takes audio focus
  (other apps keep playing), media or communication Bluetooth mode, Auto codec
  (PCM on plain Wi-Fi, Opus on mobile/VPN). libopus 1.5.2 is vendored in
  `android/app/src/main/cpp/third_party/opus`.

Measured on a Pixel 7a over 5 GHz Wi-Fi: about 27-30 ms from Voicemeeter to the
phone's audio stack (network, jitter buffer, output), Bluetooth not included.
- `PROTOCOL.md`: the wire format.

## Sender

```
cd sender
cargo build --release
target\release\vmcast.exe --bus A1
```

Options: `--bus A1..A5|B1..B3` (default A1), `--port 6990`, `--max-frames 240`,
`--no-qos`. The phone connects to it; nothing to configure per client.

Windows Firewall must allow inbound UDP 6990 (admin PowerShell):

```
New-NetFirewallRule -DisplayName vmcast -Direction Inbound -Protocol UDP -LocalPort 6990 -Action Allow -Profile Any -RemoteAddress LocalSubnet
```

Add your WireGuard client range to `-RemoteAddress` for use over the tunnel.

Latency is lowest with Voicemeeter at 48 kHz (Android's native rate) and a small
engine buffer (Menu → System Settings). The sender prints the block size.

## Android app

Needs JDK 17 and an Android SDK with platform 35, NDK 27.2.12479018 and
CMake 3.22.1.

```
cd android
$env:JAVA_HOME="$env:LOCALAPPDATA\devtools\jdk17"
.\gradlew.bat installDebug
```

The stats panel shows each latency stage: network RTT, measured jitter, buffer fill
vs target, output latency (includes Bluetooth on recent Android), and underruns.
"Extra buffer" adds a fixed margin on top of the adaptive target if you hear dropouts.
