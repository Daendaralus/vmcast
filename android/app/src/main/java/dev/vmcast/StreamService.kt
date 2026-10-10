package dev.vmcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Foreground service that keeps the stream, Wi-Fi radio and CPU awake with the screen off. */
class StreamService : Service() {
    private var wifiLowLatency: WifiManager.WifiLock? = null
    private var wifiHighPerf: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var params: Intent? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                params = Intent(intent)
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(intent.getStringExtra(EXTRA_HOST) ?: ""),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                acquireLocks()
                watchNetwork()
                restartEngine()
            }
            ACTION_STOP -> {
                stopEngine()
                releaseLocks()
                unwatchNetwork()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEngine()
        releaseLocks()
        unwatchNetwork()
        super.onDestroy()
    }

    private fun restartEngine() {
        val p = params ?: return
        stopEngine()
        engine = StreamEngine(
            this,
            p.getStringExtra(EXTRA_HOST) ?: "",
            p.getIntExtra(EXTRA_PORT, 6990),
            p.getBooleanExtra(EXTRA_COMM, false),
            p.getIntExtra(EXTRA_EXTRA_MS, 0),
            codecFor(p),
        ).also { it.start() }
    }

    private fun codecFor(p: Intent): StreamEngine.Codec {
        val opus = when (p.getStringExtra(EXTRA_CODEC)) {
            CODEC_PCM -> false
            CODEC_OPUS -> true
            else -> !onPlainWifi()
        }
        return StreamEngine.Codec(
            opus,
            p.getIntExtra(EXTRA_KBPS, 128),
            p.getDoubleExtra(EXTRA_FRAME_MS, 10.0),
            p.getIntExtra(EXTRA_REDUNDANCY, 1),
        )
    }

    /** Wi-Fi without a VPN on top: the LAN case where raw PCM is fine. */
    private fun onPlainWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * On every switch of the default network (Wi-Fi lost, mobile data taking
     * over, the VPN coming up on top of it, ...) move the stream to a new socket,
     * and in Auto mode switch codec if needed.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private val settle = Runnable {
                val p = params ?: return@Runnable
                val current = engine ?: return@Runnable
                if (p.getStringExtra(EXTRA_CODEC) == CODEC_AUTO && codecFor(p).opus != current.codec.opus) {
                    restartEngine()
                } else {
                    current.reconnect()
                }
            }
            // Called on the binder thread; coalesce bursts (Wi-Fi lost, mobile up, VPN up).
            override fun onAvailable(network: Network) = schedule()
            override fun onLost(network: Network) = schedule()
            private fun schedule() {
                main.removeCallbacks(settle)
                main.postDelayed(settle, 500) // let routes settle
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unwatchNetwork() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock != null) return
        val wm = getSystemService(WifiManager::class.java)
        // Low-latency mode only applies while the app is visible; high-perf keeps
        // Wi-Fi power save off with the screen off (deprecated but still honored on many builds).
        wifiLowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "vmcast:lowlatency").apply {
            setReferenceCounted(false); acquire()
        }
        wifiHighPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vmcast:highperf").apply {
            setReferenceCounted(false); acquire()
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vmcast:stream").apply {
                setReferenceCounted(false); acquire()
            }
    }

    private fun releaseLocks() {
        wifiLowLatency?.release(); wifiLowLatency = null
        wifiHighPerf?.release(); wifiHighPerf = null
        wakeLock?.release(); wakeLock = null
    }

    private fun buildNotification(host: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("vmcast")
            .setContentText("Streaming from $host")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        @Volatile var engine: StreamEngine? = null
            private set

        const val ACTION_START = "dev.vmcast.START"
        const val ACTION_STOP = "dev.vmcast.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_COMM = "comm"
        const val EXTRA_EXTRA_MS = "extraMs"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_KBPS = "kbps"
        const val EXTRA_FRAME_MS = "frameMs"
        const val EXTRA_REDUNDANCY = "redundancy"
        const val CODEC_AUTO = "auto"
        const val CODEC_PCM = "pcm"
        const val CODEC_OPUS = "opus"
        private const val CHANNEL = "stream"
        private const val NOTIFICATION_ID = 1
    }
}
