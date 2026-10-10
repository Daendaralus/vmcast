package dev.vmcast

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * One streaming session: a network thread that talks the vmcast protocol and
 * feeds the native jitter buffer, plus a supervisor thread that keeps the
 * native AAudio stream tuned. Playback itself happens in the AAudio callback
 * (see player.cpp).
 *
 * Audio focus is deliberately never requested, so other apps keep playing and
 * get mixed with this stream.
 */
class StreamEngine(
    context: Context,
    private val host: String,
    private val port: Int,
    private val communicationMode: Boolean,
    extraMs: Int,
    val codec: Codec,
) {
    /** What the phone asks the sender for. Opus settings are ignored for PCM. */
    data class Codec(val opus: Boolean, val kbps: Int = 128, val frameMs: Double = 10.0, val redundancy: Int = 1) {
        override fun toString() =
            if (opus) "Opus $kbps kbps, %.1f ms frames, redundancy $redundancy".format(frameMs) else "PCM 16-bit"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val clientId = Random.nextInt()
    private val player = NativePlayer.create(communicationMode)
    @Volatile private var running = false
    private var server: InetSocketAddress? = null
    private var netThread: Thread? = null
    private var tuneThread: Thread? = null

    var extraMs: Int = extraMs
        set(v) {
            field = v
            NativePlayer.setExtraMs(player, v)
        }

    // Stats (written by worker threads, read by the UI).
    @Volatile var state = "idle"; private set
    @Volatile private var rttMs = Double.NaN
    @Volatile private var lostPackets = 0L
    @Volatile private var packetsPerSec = 0.0
    @Volatile private var kbpsIn = 0.0
    @Volatile private var framesPerPacket = 0
    @Volatile private var route = "-"
    private val stats = DoubleArray(NativePlayer.S_COUNT)

    fun start() {
        running = true
        NativePlayer.setExtraMs(player, extraMs)
        if (communicationMode) enterCommunicationMode()
        netThread = Thread(::netLoop, "vmcast-net").apply { start() }
        tuneThread = Thread(::tuneLoop, "vmcast-tune").apply { start() }
    }

    fun stop() {
        running = false
        netThread?.join(1000) // it sends BYE on its way out (no network on the caller's thread)
        tuneThread?.join(1000)
        NativePlayer.destroy(player)
        if (communicationMode) {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        state = "stopped"
    }

    private fun enterCommunicationMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val wanted = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val dev = audioManager.availableCommunicationDevices
            .filter { it.type in wanted }
            .minByOrNull { wanted.indexOf(it.type) }
        if (dev == null || !audioManager.setCommunicationDevice(dev)) {
            route = "no Bluetooth headset for communication mode"
        }
    }

    private fun hello(): DatagramPacket {
        if (!codec.opus) return control(T_HELLO, 16, CODEC_PCM16)
        val p = control(T_HELLO, 20, CODEC_OPUS)
        ByteBuffer.wrap(p.data).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(16, codec.kbps.toShort())
            .put(18, (codec.frameMs * 2).toInt().toByte())
            .put(19, codec.redundancy.toByte())
        return p
    }

    private fun control(type: Int, len: Int, codecByte: Int = 0): DatagramPacket {
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        b.put('V'.code.toByte()).put('C'.code.toByte()).put(type.toByte()).put(codecByte.toByte()).putInt(clientId)
        if (len >= 16) b.putLong(System.nanoTime())
        // Android's DatagramSocket wants an explicit address even on a connected socket.
        return DatagramPacket(b.array(), len, server)
    }

    /**
     * Ask for a fresh socket. Android pins a socket to the network that was
     * default when it was created, so after a network change (e.g. Wi-Fi ->
     * mobile data, then the VPN coming up) the old one may lead nowhere.
     */
    fun reconnect() {
        reconnectRequested = true
    }

    @Volatile private var reconnectRequested = false

    private fun openSocket(): DatagramSocket? = try {
        val addr = InetSocketAddress(host, port) // re-resolve: the name may map elsewhere now
        server = addr
        DatagramSocket().apply {
            connect(addr)
            soTimeout = 50
            receiveBufferSize = 256 * 1024
        }
    } catch (e: Exception) {
        Log.w(TAG, "socket setup", e)
        state = "no route to $host:$port (${e.message}), retrying"
        null
    }

    private fun netLoop() {
        var s: DatagramSocket? = openSocket()
        var socketOpenedNs = System.nanoTime()
        reconnectRequested = false
        state = "connecting to $host:$port"
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var nextHello = 0L
        var lastSeq = -1L
        var streamId = 0
        var streamRate = 0
        var lastPacketNs = 0L
        var countStart = System.nanoTime()
        var count = 0
        var bytes = 0L

        while (running) {
            val now = System.nanoTime()
            // Self-heal: no audio for 2 s on this socket (or a network change was
            // signalled) -> new socket on whatever network is default now.
            val quietSince = maxOf(lastPacketNs, socketOpenedNs)
            if (reconnectRequested || s == null || now - quietSince > RECONNECT_AFTER_NS) {
                if (s != null) Log.i(TAG, if (reconnectRequested) "network changed, reconnecting" else "no audio, reconnecting")
                reconnectRequested = false
                s?.close()
                s = openSocket()
                socketOpenedNs = now
                nextHello = 0L
                if (s == null) {
                    Thread.sleep(250)
                    continue
                }
            }
            val sock = s!!
            if (now >= nextHello) {
                runCatching { sock.send(hello()) }
                    .onFailure { Log.w(TAG, "hello", it); state = "send failed: ${it.message}" }
                nextHello = now + 250_000_000L
            }
            if (lastPacketNs != 0L && now - lastPacketNs > 1_000_000_000L) state = "no audio from $host:$port"
            if (now - countStart >= 1_000_000_000L) {
                packetsPerSec = count * 1e9 / (now - countStart)
                // UDP payload only; IP/UDP (+ WireGuard) headers add ~28 (+32) bytes per packet.
                kbpsIn = bytes * 8e6 / (now - countStart)
                count = 0
                bytes = 0
                countStart = now
            }

            try {
                pkt.setLength(buf.size)
                sock.receive(pkt)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                // e.g. ENETUNREACH while the old network goes away: start over on a new socket.
                if (running) state = "socket error: ${e.message}, reconnecting"
                sock.close()
                s = null
                Thread.sleep(250)
                continue
            }
            val arrival = System.nanoTime()
            val len = pkt.length
            if (len < 8 || buf[0] != 'V'.code.toByte() || buf[1] != 'C'.code.toByte()) continue
            val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            when (buf[2].toInt()) {
                T_PONG -> if (len >= 16 && bb.getInt(4) == clientId) {
                    val sample = (arrival - bb.getLong(8)) / 1e6
                    rttMs = if (rttMs.isNaN()) sample else rttMs * 0.8 + sample * 0.2
                }
                T_AUDIO -> {
                    val packetCodec = buf[3].toInt()
                    if (len < HEADER || (packetCodec != CODEC_PCM16 && packetCodec != CODEC_OPUS)) continue
                    val channels = buf[4].toInt() and 0xff
                    val frames = bb.getShort(6).toInt() and 0xffff
                    val seq = bb.getInt(8).toLong() and 0xffffffffL
                    val pos = bb.getInt(12).toLong() and 0xffffffffL
                    val rate = bb.getInt(16)
                    val sid = bb.getInt(20)
                    if (channels !in 1..2 || rate <= 0) continue
                    if (packetCodec == CODEC_PCM16 && HEADER + frames * channels * 2 > len) continue

                    if (rate != streamRate || sid != streamId) {
                        NativePlayer.reset(player, rate)
                        streamRate = rate
                        streamId = sid
                        lastSeq = -1
                    }
                    if (lastSeq >= 0) {
                        val gap = (seq - lastSeq - 1) and 0xffffffffL
                        if (gap in 1..10_000) lostPackets += gap
                    }
                    lastSeq = seq
                    framesPerPacket = frames
                    if (packetCodec == CODEC_OPUS) {
                        NativePlayer.putOpus(player, pos, buf, HEADER, len - HEADER, frames, arrival)
                    } else {
                        NativePlayer.put(player, pos, buf, HEADER, frames, channels, arrival)
                    }
                    bytes += len
                    lastPacketNs = arrival
                    count++
                    state = "streaming"
                }
            }
        }
        s?.let { runCatching { it.send(control(T_BYE, 8)) }; it.close() }
    }

    private fun tuneLoop() {
        var lastDevice = -1
        while (running) {
            NativePlayer.tune(player)
            synchronized(stats) { NativePlayer.stats(player, stats) }
            val dev = stats[NativePlayer.S_DEVICE].takeIf { !it.isNaN() }?.toInt() ?: -1
            if (dev != lastDevice) {
                lastDevice = dev
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == dev }?.let {
                    route = "${typeName(it.type)} (${it.productName})"
                }
            }
            Thread.sleep(100)
        }
    }

    fun statsText(): String {
        val v = synchronized(stats) { stats.copyOf() }
        val rate = v[NativePlayer.S_RATE]
        fun ms(frames: Double) = frames * 1000.0 / rate
        val fill = ms(v[NativePlayer.S_FILL])
        val out = v[NativePlayer.S_OUT_LATENCY]
        val total = rttMs / 2 + fill + out
        return buildString {
            appendLine("state      $state")
            appendLine("route      $route")
            appendLine("codec      $codec")
            appendLine("rate       %.0f Hz, $framesPerPacket frames/pkt, %.0f pkt/s, %.0f kbit/s".format(rate, packetsPerSec, kbpsIn))
            appendLine("rtt        %.1f ms".format(rttMs))
            appendLine("jitter     %.1f ms (3 s window)".format(ms(v[NativePlayer.S_JITTER])))
            appendLine("buffer     %.1f ms (target %.1f)".format(fill, ms(v[NativePlayer.S_TARGET])))
            appendLine("speed      %+.2f %%".format((v[NativePlayer.S_SPEED] - 1.0) * 100))
            appendLine(
                "output     %.1f ms (AAudio, %.0f frames buf, burst %.0f)".format(
                    out, v[NativePlayer.S_BUFFER], v[NativePlayer.S_BURST]
                )
            )
            appendLine("lost/late  $lostPackets / %.0f".format(v[NativePlayer.S_LATE]))
            if (codec.opus) {
                appendLine(
                    "opus       %.0f recovered, %.0f concealed".format(v[NativePlayer.S_RECOVERED], v[NativePlayer.S_CONCEALED])
                )
            }
            appendLine("underruns  %.0f buffer, %.0f output".format(v[NativePlayer.S_UNDERRUNS], v[NativePlayer.S_XRUNS]))
            appendLine()
            append("≈ total    %.0f ms + sender block (~3 ms)".format(total))
            // AAudio timestamps stop at the Bluetooth stack; the radio link and the
            // earbuds' own buffer (A2DP: often 150-300 ms) come on top.
            if (route.startsWith("BT") || route.startsWith("BLE")) append("\n           + Bluetooth link (not measurable here)")
        }
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        else -> "type $t"
    }

    companion object {
        private const val T_HELLO = 1
        private const val T_AUDIO = 2
        private const val T_PONG = 3
        private const val T_BYE = 4
        private const val CODEC_PCM16 = 0
        private const val CODEC_OPUS = 1
        private const val HEADER = 24
        private const val TAG = "vmcast"
        private const val RECONNECT_AFTER_NS = 2_000_000_000L
    }
}
