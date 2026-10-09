package dev.vmcast

/** JNI bridge to player.cpp (jitter buffer + AAudio output). */
object NativePlayer {
    init {
        System.loadLibrary("vmcast")
    }

    @JvmStatic external fun create(communication: Boolean): Long
    @JvmStatic external fun destroy(handle: Long)
    /** Network thread: start a fresh jitter buffer (new stream or sample rate). */
    @JvmStatic external fun reset(handle: Long, sampleRate: Int)
    @JvmStatic external fun put(handle: Long, pos: Long, buf: ByteArray, off: Int, frames: Int, channels: Int, arrivalNs: Long)
    /** Network thread: one Opus packet payload (count + frames, newest first). */
    @JvmStatic external fun putOpus(handle: Long, pos: Long, buf: ByteArray, off: Int, len: Int, frameSize: Int, arrivalNs: Long)
    @JvmStatic external fun setExtraMs(handle: Long, ms: Int)
    /** Supervisor thread: (re)open the stream, adapt buffer size, measure latency. */
    @JvmStatic external fun tune(handle: Long)
    @JvmStatic external fun stats(handle: Long, out: DoubleArray)

    // Indices into the stats array.
    const val S_RATE = 0
    const val S_JITTER = 1
    const val S_FILL = 2
    const val S_TARGET = 3
    const val S_SPEED = 4
    const val S_UNDERRUNS = 5
    const val S_LATE = 6
    const val S_XRUNS = 7
    const val S_BUFFER = 8
    const val S_BURST = 9
    const val S_OUT_LATENCY = 10
    const val S_DEVICE = 11
    const val S_RECOVERED = 12
    const val S_CONCEALED = 13
    const val S_COUNT = 14
}
