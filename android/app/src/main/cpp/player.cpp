// Native playback: position-addressed jitter buffer + AAudio low-latency output.
//
// The AAudio data callback runs on a SCHED_FIFO thread owned by the audio
// framework, so it can meet 1-burst deadlines that a Java thread cannot. The
// network thread (Kotlin) pushes packets in through JNI; a Kotlin supervisor
// thread calls tune() periodically to (re)open the stream, adapt its buffer
// size to observed xruns and measure output latency.

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <opus.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <climits>
#include <cmath>
#include <cstring>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "vmcast", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "vmcast", __VA_ARGS__)

namespace {

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// Same algorithm as the original Kotlin version: write at absolute frame
// positions, read at a fractional position whose speed (+-0.5 %) steers the
// fill level towards a target derived from the arrival jitter of the last 3 s.
class JitterBuffer {
public:
    explicit JitterBuffer(int rate) : sampleRate(rate), data_(kCap * 2, 0.f), tags_(kCap) {
        for (auto& t : tags_) t.store(-1, std::memory_order_relaxed);
    }

    const int sampleRate;

    // --- Network thread ---

    /// Extend a 32-bit wire position to the absolute 64-bit timeline.
    int64_t unwrap(int64_t pos32) {
        int64_t abs = lastAbs_ < 0 ? pos32 : lastAbs_ + (int32_t)(uint32_t)(pos32 - (lastAbs_ & 0xffffffffLL));
        lastAbs_ = abs;
        return abs;
    }

    /// Feed the jitter estimator: a packet completing media up to `endAbs` arrived now.
    void arrival(int64_t endAbs, int64_t arrivalNs) { recordArrival(endAbs, arrivalNs); }

    void putPcm16(int64_t abs, const uint8_t* pcm, int frames, int channels) {
        write(abs, frames, [&](int i, float& l, float& r) {
            const uint8_t* p = pcm + i * channels * 2;
            l = (int16_t)(p[0] | (p[1] << 8)) / 32768.f;
            r = channels >= 2 ? (int16_t)(p[2] | (p[3] << 8)) / 32768.f : l;
        });
    }

    void putFloatStereo(int64_t abs, const float* pcm, int frames) {
        write(abs, frames, [&](int i, float& l, float& r) {
            l = pcm[i * 2];
            r = pcm[i * 2 + 1];
        });
    }

    template <class Sample>
    void write(int64_t abs, int frames, Sample sample) {
        int64_t head = readHead_.load(std::memory_order_relaxed);
        if (head != INT64_MIN && abs + frames <= head) {
            lateDrops.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        for (int i = 0; i < frames; i++) {
            int64_t a = abs + i;
            size_t slot = (size_t)(a & kMask);
            sample(i, data_[slot * 2], data_[slot * 2 + 1]);
            tags_[slot].store(a, std::memory_order_release);
        }
        int64_t we = writeEnd_.load(std::memory_order_relaxed);
        if (we == INT64_MIN || abs + frames > we) writeEnd_.store(abs + frames, std::memory_order_release);
    }

    // Audio callback thread. No locks, no allocation.
    void read(float* out, int frames, int burst, int extraFrames) {
        int64_t we = writeEnd_.load(std::memory_order_acquire);
        double target = std::max(jitterFrames.load(std::memory_order_relaxed) + burst + sampleRate * kSafetyS + extraFrames,
                                 sampleRate * kMinTargetS);
        targetFrames.store(target, std::memory_order_relaxed);
        if (we == INT64_MIN) {
            std::memset(out, 0, sizeof(float) * frames * 2);
            return;
        }
        if (std::isnan(readPos_)) {
            readPos_ = we - target;
            fillEma_ = target;
        }
        double fill = we - readPos_;
        fillFrames.store(fill, std::memory_order_relaxed);
        if (rebuffering_) {
            if (fill < target) {
                std::memset(out, 0, sizeof(float) * frames * 2);
                return;
            }
            rebuffering_ = false;
            fillEma_ = fill;
        }
        if (fill < frames) {
            underruns.fetch_add(1, std::memory_order_relaxed);
            rebuffering_ = true;
            std::memset(out, 0, sizeof(float) * frames * 2);
            return;
        }
        if (fill > target + std::max(sampleRate * 0.03, target)) {
            readPos_ = we - target;
            fillEma_ = target;
        }
        fillEma_ += 0.02 * ((we - readPos_) - fillEma_);
        double ratio = 1.0 + std::clamp((fillEma_ - target) / (2.0 * sampleRate), -0.005, 0.005);
        speed.store(ratio, std::memory_order_relaxed);

        double p = readPos_;
        for (int i = 0; i < frames; i++) {
            int64_t i0 = (int64_t)std::floor(p);
            float f = (float)(p - i0);
            size_t s0 = (size_t)(i0 & kMask), s1 = (size_t)((i0 + 1) & kMask);
            bool ok0 = tags_[s0].load(std::memory_order_acquire) == i0;
            bool ok1 = tags_[s1].load(std::memory_order_acquire) == i0 + 1;
            float l0 = ok0 ? data_[s0 * 2] : 0.f, r0 = ok0 ? data_[s0 * 2 + 1] : 0.f;
            float l1 = ok1 ? data_[s1 * 2] : 0.f, r1 = ok1 ? data_[s1 * 2 + 1] : 0.f;
            out[i * 2] = l0 + (l1 - l0) * f;
            out[i * 2 + 1] = r0 + (r1 - r0) * f;
            p += ratio;
        }
        readPos_ = p;
        readHead_.store((int64_t)std::floor(p), std::memory_order_relaxed);
    }

    std::atomic<double> jitterFrames{0}, fillFrames{0}, targetFrames{0}, speed{1.0};
    std::atomic<int64_t> underruns{0}, lateDrops{0};

private:
    static constexpr int64_t kCap = 1 << 17;
    static constexpr int64_t kMask = kCap - 1;
    static constexpr int kWin = 4096;
    static constexpr int64_t kWindowNs = 3000000000LL;
    static constexpr double kSafetyS = 0.002;
    static constexpr double kMinTargetS = 0.004;

    void recordArrival(int64_t endAbs, int64_t arrivalNs) {
        winTime_[winIdx_] = arrivalNs;
        winOffset_[winIdx_] = arrivalNs * (sampleRate / 1e9) - endAbs;
        winIdx_ = (winIdx_ + 1) % kWin;
        if (winCount_ < kWin) winCount_++;
        if (winIdx_ % 32 != 0) return;
        int64_t horizon = arrivalNs - kWindowNs;
        double lo = INFINITY, hi = -INFINITY;
        for (int k = 0; k < winCount_; k++) {
            if (winTime_[k] < horizon) continue;
            lo = std::min(lo, winOffset_[k]);
            hi = std::max(hi, winOffset_[k]);
        }
        if (hi >= lo) jitterFrames.store(hi - lo, std::memory_order_relaxed);
    }

    std::vector<float> data_;
    std::vector<std::atomic<int64_t>> tags_;
    std::atomic<int64_t> writeEnd_{INT64_MIN}, readHead_{INT64_MIN};
    int64_t lastAbs_ = -1;
    int64_t winTime_[kWin]{};
    double winOffset_[kWin]{};
    int winCount_ = 0, winIdx_ = 0;
    double readPos_ = NAN, fillEma_ = 0;
    bool rebuffering_ = true;
};

struct Player {
    explicit Player(bool comm) : comm(comm) {}

    const bool comm;
    std::atomic<JitterBuffer*> jb{nullptr};
    std::vector<JitterBuffer*> retired; // freed in the destructor, after the stream is closed
    std::atomic<int> extraMs{0};
    std::atomic<bool> disconnected{false};
    AAudioStream* stream = nullptr;
    int streamRate = 0;
    std::atomic<int> burst{0};
    int32_t lastXruns = 0;
    double outputLatencyMs = NAN;

    // Opus decoding state (network thread only).
    OpusDecoder* dec = nullptr;
    JitterBuffer* decJb = nullptr;
    int decFrame = 0;
    int64_t nextPos = INT64_MIN; // next absolute frame the decoder expects
    std::vector<float> decPcm = std::vector<float>(960 * 2);
    std::atomic<int64_t> recovered{0}, concealed{0};

    ~Player() {
        close();
        if (dec) opus_decoder_destroy(dec);
        delete jb.load();
        for (auto* j : retired) delete j;
    }

    void putPcm16(int64_t pos32, const uint8_t* pcm, int frames, int channels, int64_t arrivalNs) {
        JitterBuffer* j = jb.load(std::memory_order_acquire);
        if (!j) return;
        int64_t abs = j->unwrap(pos32);
        j->arrival(abs + frames, arrivalNs);
        j->putPcm16(abs, pcm, frames, channels);
    }

    // Packet payload: u8 count, then count x (u16 len, bytes), newest frame first.
    // Frame k (0 = newest) starts at pos - k * frameSize.
    void putOpus(int64_t pos32, const uint8_t* p, int len, int frameSize, int64_t arrivalNs) {
        JitterBuffer* j = jb.load(std::memory_order_acquire);
        if (!j || j->sampleRate != 48000 || frameSize <= 0 || frameSize > 960 || len < 1) return;
        if (!dec || decFrame != frameSize || decJb != j) {
            int err = 0;
            if (!dec) dec = opus_decoder_create(48000, 2, &err);
            else opus_decoder_ctl(dec, OPUS_RESET_STATE);
            if (!dec) {
                LOGW("opus_decoder_create: %d", err);
                return;
            }
            decFrame = frameSize;
            decJb = j;
            nextPos = INT64_MIN;
        }
        int64_t abs = j->unwrap(pos32);
        j->arrival(abs + frameSize, arrivalNs);

        const int count = p[0];
        const uint8_t* frames[4];
        int lens[4];
        int off = 1, n = 0;
        for (int k = 0; k < count && k < 4; k++) {
            if (off + 2 > len) break;
            int l = p[off] | (p[off + 1] << 8);
            off += 2;
            if (off + l > len) break;
            frames[n] = p + off;
            lens[n] = l;
            n++;
            off += l;
        }
        if (n == 0) return;
        if (nextPos == INT64_MIN) nextPos = abs; // start clean at the newest frame
        if (abs < nextPos) return; // duplicate or reordered-old packet

        int64_t oldest = abs - (int64_t)(n - 1) * frameSize;
        if (oldest - nextPos > 50LL * frameSize) {
            // Long outage: don't synthesize seconds of concealment, just resync.
            opus_decoder_ctl(dec, OPUS_RESET_STATE);
            nextPos = oldest;
        }
        // Frames older than everything in this packet are gone for good: conceal.
        while (nextPos < oldest) {
            int got = opus_decode_float(dec, nullptr, 0, decPcm.data(), frameSize, 0);
            if (got > 0) j->putFloatStereo(nextPos, decPcm.data(), got);
            concealed.fetch_add(1, std::memory_order_relaxed);
            nextPos += frameSize;
        }
        // Decode in order, oldest first; redundant copies fill frames we missed.
        for (int k = n - 1; k >= 0; k--) {
            int64_t at = abs - (int64_t)k * frameSize;
            if (at < nextPos) continue;
            int got = opus_decode_float(dec, frames[k], lens[k], decPcm.data(), frameSize, 0);
            if (got > 0) j->putFloatStereo(at, decPcm.data(), got);
            if (k > 0) recovered.fetch_add(1, std::memory_order_relaxed);
            nextPos = at + frameSize;
        }
    }

    void close() {
        if (stream) {
            AAudioStream_requestStop(stream);
            AAudioStream_close(stream); // waits for an in-flight callback
            stream = nullptr;
        }
    }

    static aaudio_data_callback_result_t onData(AAudioStream*, void* user, void* audio, int32_t frames) {
        auto* self = static_cast<Player*>(user);
        auto* out = static_cast<float*>(audio);
        JitterBuffer* j = self->jb.load(std::memory_order_acquire);
        if (!j || j->sampleRate != self->streamRate) {
            std::memset(out, 0, sizeof(float) * frames * 2);
        } else {
            j->read(out, frames, self->burst.load(std::memory_order_relaxed),
                    self->extraMs.load(std::memory_order_relaxed) * j->sampleRate / 1000);
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void onError(AAudioStream*, void* user, aaudio_result_t err) {
        LOGW("stream error %s", AAudio_convertResultToText(err));
        static_cast<Player*>(user)->disconnected.store(true);
    }

    bool open(int rate) {
        close();
        AAudioStreamBuilder* b = nullptr;
        if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSampleRate(b, rate);
        AAudioStreamBuilder_setChannelCount(b, 2);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        // Shared, never exclusive: other apps must keep playing alongside us.
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setUsage(b, comm ? AAUDIO_USAGE_VOICE_COMMUNICATION : AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(b, comm ? AAUDIO_CONTENT_TYPE_SPEECH : AAUDIO_CONTENT_TYPE_MUSIC);
        AAudioStreamBuilder_setDataCallback(b, onData, this);
        AAudioStreamBuilder_setErrorCallback(b, onError, this);
        streamRate = rate;
        aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
        AAudioStreamBuilder_delete(b);
        if (r != AAUDIO_OK) {
            LOGW("openStream: %s", AAudio_convertResultToText(r));
            stream = nullptr;
            return false;
        }
        int32_t fpb = AAudioStream_getFramesPerBurst(stream);
        burst.store(fpb);
        AAudioStream_setBufferSizeInFrames(stream, fpb * 2);
        lastXruns = 0;
        disconnected.store(false);
        r = AAudioStream_requestStart(stream);
        LOGI("stream open: %d Hz, burst %d, perf %d, device %d, start %s", AAudioStream_getSampleRate(stream), fpb,
             AAudioStream_getPerformanceMode(stream), AAudioStream_getDeviceId(stream), AAudio_convertResultToText(r));
        return r == AAUDIO_OK;
    }

    void tune() {
        JitterBuffer* j = jb.load(std::memory_order_acquire);
        if (j && (!stream || streamRate != j->sampleRate || disconnected.load())) open(j->sampleRate);
        if (!stream) return;
        int32_t xruns = AAudioStream_getXRunCount(stream);
        if (xruns > lastXruns) {
            // The device glitched: give it one more burst of headroom.
            lastXruns = xruns;
            int32_t size = AAudioStream_getBufferSizeInFrames(stream);
            AAudioStream_setBufferSizeInFrames(stream, std::min(size + burst.load(), AAudioStream_getBufferCapacityInFrames(stream)));
        }
        int64_t pos = 0, ns = 0;
        if (AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &pos, &ns) == AAUDIO_OK) {
            int rate = AAudioStream_getSampleRate(stream);
            double presented = pos + (nowNs() - ns) * (rate / 1e9);
            outputLatencyMs = (AAudioStream_getFramesWritten(stream) - presented) * 1000.0 / rate;
        }
    }
};

Player* P(jlong h) { return reinterpret_cast<Player*>(h); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_vmcast_NativePlayer_create(JNIEnv*, jclass, jboolean comm) {
    return reinterpret_cast<jlong>(new Player(comm));
}

JNIEXPORT void JNICALL Java_dev_vmcast_NativePlayer_destroy(JNIEnv*, jclass, jlong h) { delete P(h); }

JNIEXPORT void JNICALL Java_dev_vmcast_NativePlayer_reset(JNIEnv*, jclass, jlong h, jint rate) {
    Player* p = P(h);
    JitterBuffer* old = p->jb.exchange(new JitterBuffer(rate), std::memory_order_acq_rel);
    if (old) p->retired.push_back(old); // the callback may still be reading it
}

JNIEXPORT void JNICALL Java_dev_vmcast_NativePlayer_put(JNIEnv* env, jclass, jlong h, jlong pos, jbyteArray buf, jint off,
                                                        jint frames, jint channels, jlong arrivalNs) {
    auto* bytes = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(buf, nullptr));
    if (!bytes) return;
    P(h)->putPcm16(pos, bytes + off, frames, channels, arrivalNs);
    env->ReleasePrimitiveArrayCritical(buf, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_dev_vmcast_NativePlayer_putOpus(JNIEnv* env, jclass, jlong h, jlong pos, jbyteArray buf, jint off,
                                                            jint len, jint frameSize, jlong arrivalNs) {
    // Not a critical section: Opus decoding takes a while and must not block the GC.
    std::vector<uint8_t> tmp(len);
    env->GetByteArrayRegion(buf, off, len, reinterpret_cast<jbyte*>(tmp.data()));
    P(h)->putOpus(pos, tmp.data(), len, frameSize, arrivalNs);
}

JNIEXPORT void JNICALL Java_dev_vmcast_NativePlayer_setExtraMs(JNIEnv*, jclass, jlong h, jint ms) { P(h)->extraMs.store(ms); }

JNIEXPORT void JNICALL Java_dev_vmcast_NativePlayer_tune(JNIEnv*, jclass, jlong h) { P(h)->tune(); }

// Layout must match NativePlayer.kt.
JNIEXPORT void JNICALL Java_dev_vmcast_NativePlayer_stats(JNIEnv* env, jclass, jlong h, jdoubleArray out) {
    Player* p = P(h);
    JitterBuffer* j = p->jb.load(std::memory_order_acquire);
    double v[14];
    std::fill(v, v + 14, NAN);
    v[12] = (double)p->recovered.load();
    v[13] = (double)p->concealed.load();
    if (j) {
        v[0] = j->sampleRate;
        v[1] = j->jitterFrames.load();
        v[2] = j->fillFrames.load();
        v[3] = j->targetFrames.load();
        v[4] = j->speed.load();
        v[5] = (double)j->underruns.load();
        v[6] = (double)j->lateDrops.load();
    }
    if (p->stream) {
        v[7] = AAudioStream_getXRunCount(p->stream);
        v[8] = AAudioStream_getBufferSizeInFrames(p->stream);
        v[9] = p->burst.load();
        v[10] = p->outputLatencyMs;
        v[11] = AAudioStream_getDeviceId(p->stream);
    }
    env->SetDoubleArrayRegion(out, 0, 14, v);
}

} // extern "C"
