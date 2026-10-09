package dev.vmcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("vmcast", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var modeComm: RadioButton
    private lateinit var extraLabel: TextView
    private lateinit var extra: SeekBar
    private lateinit var codecGroup: RadioGroup
    private lateinit var frameGroup: RadioGroup
    private lateinit var kbps: SeekBar
    private lateinit var redundancy: SeekBar
    private lateinit var toggle: Button
    private lateinit var stats: TextView

    private val tick = object : Runnable {
        override fun run() {
            val e = StreamService.engine
            stats.text = e?.statsText() ?: "not running"
            toggle.text = if (e != null) "Stop" else "Start"
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }

        host = EditText(this).apply {
            hint = "PC address (LAN or WireGuard IP)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("host", ""))
        }
        port = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("port", 6990).toString())
        }
        val modeMedia = RadioButton(this).apply { text = "Playback (media / A2DP, stereo)"; id = 1 }
        modeComm = RadioButton(this).apply { text = "Communication (call / SCO or LE, lower BT latency, mono)"; id = 2 }
        val modes = RadioGroup(this).apply {
            addView(modeMedia); addView(modeComm)
            check(if (prefs.getBoolean("comm", false)) 2 else 1)
            setOnCheckedChangeListener { _, _ -> if (StreamService.engine != null) start() }
        }
        extraLabel = label("")
        extra = SeekBar(this).apply {
            max = 200
            progress = prefs.getInt("extraMs", 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    extraLabel.text = "Extra buffer: $v ms"
                    StreamService.engine?.extraMs = v
                    prefs.edit().putInt("extraMs", v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        extraLabel.text = "Extra buffer: ${extra.progress} ms"

        val restartIfRunning = { if (StreamService.engine != null) start() }
        fun radios(options: List<Pair<String, Int>>, checked: Int) = RadioGroup(this).apply {
            options.forEach { (t, i) -> addView(RadioButton(this@MainActivity).apply { text = t; id = i }) }
            check(checked)
            setOnCheckedChangeListener { _, _ -> restartIfRunning() }
        }
        codecGroup = radios(
            listOf("Auto (PCM on home Wi-Fi, Opus on mobile / VPN)" to 10, "PCM (uncompressed, LAN)" to 11, "Opus" to 12),
            when (prefs.getString("codec", StreamService.CODEC_AUTO)) {
                StreamService.CODEC_PCM -> 11
                StreamService.CODEC_OPUS -> 12
                else -> 10
            },
        )
        frameGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("5 ms" to 20, "10 ms" to 21, "20 ms" to 22).forEach { (t, i) ->
                addView(RadioButton(this@MainActivity).apply { text = t; id = i })
            }
            check(when (prefs.getFloat("frameMs", 10f)) { 5f -> 20; 20f -> 22; else -> 21 })
            setOnCheckedChangeListener { _, _ -> restartIfRunning() }
        }
        fun stepper(label: TextView, max: Int, value: Int, describe: (Int) -> String) = SeekBar(this).apply {
            this.max = max
            progress = value
            label.text = describe(value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { label.text = describe(v) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = restartIfRunning()
            })
        }
        val kbpsLabel = label("")
        kbps = stepper(kbpsLabel, 14, (prefs.getInt("kbps", 128) - 32) / 16) { "Opus bitrate: ${32 + it * 16} kbps" }
        val redundancyLabel = label("")
        redundancy = stepper(redundancyLabel, 3, prefs.getInt("redundancy", 1)) {
            "Opus redundancy: $it previous frame(s) per packet (survives $it lost in a row, ${it + 1}x bitrate)"
        }
        toggle = Button(this).apply {
            text = "Start"
            setOnClickListener { if (StreamService.engine != null) stop() else start() }
        }
        stats = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }

        col.addView(TextView(this).apply { text = "vmcast"; textSize = 24f })
        col.addView(label("PC address"))
        col.addView(host, MATCH_PARENT, WRAP_CONTENT)
        col.addView(label("Port"))
        col.addView(port, MATCH_PARENT, WRAP_CONTENT)
        col.addView(label("Codec"))
        col.addView(codecGroup)
        col.addView(kbpsLabel)
        col.addView(kbps, MATCH_PARENT, WRAP_CONTENT)
        col.addView(label("Opus frame size (smaller = lower latency, more packets)"))
        col.addView(frameGroup)
        col.addView(redundancyLabel)
        col.addView(redundancy, MATCH_PARENT, WRAP_CONTENT)
        col.addView(label("Bluetooth mode"))
        col.addView(modes)
        col.addView(extraLabel)
        col.addView(extra, MATCH_PARENT, WRAP_CONTENT)
        col.addView(toggle, MATCH_PARENT, WRAP_CONTENT)
        col.addView(stats, MATCH_PARENT, WRAP_CONTENT)
        // targetSdk 35 is edge-to-edge: keep content clear of the status/nav bars and keyboard.
        setContentView(ScrollView(this).apply {
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun start() {
        val h = host.text.toString().trim()
        val p = port.text.toString().toIntOrNull() ?: 6990
        if (h.isEmpty()) {
            host.error = "required"
            return
        }
        val codec = when (codecGroup.checkedRadioButtonId) {
            11 -> StreamService.CODEC_PCM
            12 -> StreamService.CODEC_OPUS
            else -> StreamService.CODEC_AUTO
        }
        val frameMs = when (frameGroup.checkedRadioButtonId) { 20 -> 5f; 22 -> 20f; else -> 10f }
        val kbpsValue = 32 + kbps.progress * 16
        prefs.edit()
            .putString("host", h).putInt("port", p).putBoolean("comm", modeComm.isChecked)
            .putString("codec", codec).putFloat("frameMs", frameMs).putInt("kbps", kbpsValue)
            .putInt("redundancy", redundancy.progress)
            .apply()
        startForegroundService(
            Intent(this, StreamService::class.java)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_HOST, h)
                .putExtra(StreamService.EXTRA_PORT, p)
                .putExtra(StreamService.EXTRA_COMM, modeComm.isChecked)
                .putExtra(StreamService.EXTRA_EXTRA_MS, extra.progress)
                .putExtra(StreamService.EXTRA_CODEC, codec)
                .putExtra(StreamService.EXTRA_KBPS, kbpsValue)
                .putExtra(StreamService.EXTRA_FRAME_MS, frameMs.toDouble())
                .putExtra(StreamService.EXTRA_REDUNDANCY, redundancy.progress)
        )
    }

    private fun stop() {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
    }
}
