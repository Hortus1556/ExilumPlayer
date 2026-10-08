#include <cmath>
#include <algorithm>
#include "audio.h"
#include "effects.h"
#include "soundcheck.h"
#include "spectrum.h"
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>
#include <sys/resource.h>
#include <thread>
#include <unistd.h>

#define TAG "Exilum Oboe"
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define lock() stream_mutex.lock()
#define unlock() stream_mutex.unlock()

// true = callback renders audio; false = output silence
static std::atomic<bool> is_playing(false);
// true while inside onAudioReady, used to drain in-flight callback safely
static std::atomic<bool> in_callback(false);
// set by callback when the render callback returns < 0 (module ended)
static std::atomic<bool> module_ended(false);

// Master volume, as a percentage. Applied to the finished buffer below rather
// than by whoever rendered it, so it reaches every decoder equally and none of
// them has to know it exists.
// Linear, worked out once when the boost is set. See set_output_boost_db.
static std::atomic<float> output_boost{1.0f};
static std::atomic<int> output_gain_percent(100);

// Frames left in the fade that follows a seek, and how long it was to start
// with. Written by whoever seeks, read and counted down by the callback.
static std::atomic<int> ramp_frames_left(0);
static std::atomic<int> ramp_frames_total(0);
// set by onErrorAfterClose when Oboe reports ErrorDisconnected
static std::atomic<bool> audio_disconnected(false);
// passed to the render callback: 0 = loop forever, 1 = play once
static std::atomic<int> loop_flag(1);
// swappable render function; set by the active backend via set_render_callback()
static std::atomic<AudioRenderCallback> render_callback(nullptr);
// Total frames handed to the device since the stream started - see
// get_frames_written(). Advanced only after the render callback returns, so a
// read from inside it yields the stream position of the buffer being filled.
static std::atomic<int64_t> frames_written(0);

// Play-head interpolation state - see get_frames_played(). The device's own
// frames-read counter only steps once per callback, so these hold the last
// step it reported and when, to project a continuous position between steps.
static std::atomic<int64_t> play_anchor_frames(0);
static std::atomic<int64_t> play_anchor_nanos(0);
static std::atomic<int64_t> play_last_reported(0);

/**
 * The stream, or none. Opened, closed and started under [stream_mutex]; read
 * by threads that take no lock - the views asking how far playback has got,
 * every frame - which is why it is handed over and taken with
 * std::atomic_store and std::atomic_load, and why a reader keeps the copy it
 * loaded for the whole of its call: the stream it is asking stays alive
 * under it while another thread closes and lets go of it. Read plainly, a
 * route change that closed the stream as a view asked it something was a
 * call through a pointer just freed.
 */
static std::shared_ptr<oboe::AudioStream> audio_stream;
class PlayerAudioCallback;
static PlayerAudioCallback* audio_callback = nullptr;
static int actual_channels = 2;
static int effective_format_flags = 0;
/** Never made or destroyed: it was, with each open and close, and a thread could be at it either side of that. */
static std::mutex stream_mutex;

// Spin until the current callback invocation finishes (normally at most one burst
// period, ~5ms). Bounded by a timeout: if the audio route dies mid-callback (e.g.
// a Samsung screen recorder starting internal-audio capture forces a routing
// change), the audio HAL can abandon that callback invocation without ever
// returning, which would otherwise leave in_callback stuck at true forever and
// hang whichever thread calls this — set_playing(0)/pause() is invoked
// synchronously from the main thread, so an unbounded spin here means a frozen UI.
static void wait_for_callback() {
  constexpr auto kMaxWait = std::chrono::milliseconds(200);
  const auto deadline = std::chrono::steady_clock::now() + kMaxWait;
  while (in_callback.load(std::memory_order_acquire)) {
    if (std::chrono::steady_clock::now() >= deadline) {
      LOG_WARN("wait_for_callback: timed out after 200ms — stream likely disconnected, giving up");
      break;
    }
    std::this_thread::sleep_for(std::chrono::microseconds(200));
  }
}

class PlayerAudioCallback : public oboe::AudioStreamDataCallback,
                            public oboe::AudioStreamErrorCallback {
public:
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
    in_callback.store(true, std::memory_order_release);

    // PerformanceMode::None (required so screen recording can capture this
    // stream — see oboe_open) doesn't get AAudio's elevated MMAP-path thread
    // scheduling the way LowLatency does, making this callback more prone to
    // being preempted mid-critical-section while it holds player-libopenmpt's
    // native mutex — the same mutex every pattern/channel/sample JNI call
    // from the UI needs, which is what actually shows up as view lag.
    // Manually lowering this thread's niceness (once, per stream instance —
    // onAudioReady always runs on the one thread Oboe created for it) recovers
    // most of that scheduling priority without touching PerformanceMode.
    if (!priority_boosted_) {
      priority_boosted_ = true;
      // setpriority legitimately returns -1 on success (it can return the new
      // nice value), so errno, cleared first, is the only reliable indicator.
      errno = 0;
      if (setpriority(PRIO_PROCESS, gettid(), -19) != 0 && errno != 0) {
        LOG_WARN("Failed to raise audio callback thread priority: errno=%d", errno);
      }
    }

    static int callback_count = 0;
    static int32_t last_xrun_count = 0;
    if (++callback_count >= 1000) {
      callback_count = 0;
      if (audioStream->isXRunCountSupported()) {
        auto xRunResult = audioStream->getXRunCount();
        if (xRunResult) {
          int32_t current = xRunResult.value();
          if (current != last_xrun_count) {
            LOG_WARN("XRuns detected: %d (new: %d)", current, current - last_xrun_count);
            last_xrun_count = current;
          }
        }
      }
    }

    const int numChannels = audioStream->getChannelCount();
    const int bytesPerSample = (audioStream->getFormat() == oboe::AudioFormat::I32) ? 4 : 2;
    const int numBytes = numFrames * numChannels * bytesPerSample;

    AudioRenderCallback callback = render_callback.load(std::memory_order_acquire);
    if (is_playing.load(std::memory_order_acquire) && callback != nullptr) {
      int ret = callback(audioData, numFrames, numChannels, bytesPerSample);
      if (ret < 0) {
        bool was_ended = module_ended.exchange(true, std::memory_order_release);
        if (!was_ended) LOG_INFO("onAudioReady: module_ended set by render callback");
        // What it rendered before the end is in the buffer, and the rest of
        // it is silence already: whoever says the tune has ended has filled
        // what it did not play (renderOpenMpt's silenceTail, loopingRender).
        // Zeroed here as well, the last notes of every module - up to a
        // buffer of them - were never heard.
      }
    } else {
      memset(audioData, 0, numBytes);
    }

    // The fade that follows a seek. Before the gain, so that turning the
    // volume down does not also shorten the fade into inaudibility.
    int rampLeft = ramp_frames_left.load(std::memory_order_acquire);
    if (rampLeft > 0) {
      const int rampTotal = std::max(1, ramp_frames_total.load(std::memory_order_relaxed));
      for (int frame = 0; frame < numFrames && rampLeft > 0; frame++, rampLeft--) {
        // Rising from nothing to whole across the fade.
        const int done = rampTotal - rampLeft;
        for (int channel = 0; channel < numChannels; channel++) {
          const int at = frame * numChannels + channel;
          if (bytesPerSample == 4) {
            static_cast<float*>(audioData)[at] *= static_cast<float>(done) / rampTotal;
          } else {
            int16_t* out = static_cast<int16_t*>(audioData);
            out[at] = static_cast<int16_t>(static_cast<int32_t>(out[at]) * done / rampTotal);
          }
        }
      }
      ramp_frames_left.store(rampLeft, std::memory_order_release);
    }

    // Sound Check, when it is on - every track brought to the same loudness -
    // and the effects, when any is (see effects.h). Before the master volume,
    // which with Sound Check on is only ever the ducking the audio focus asks
    // for. After the seek's fade, so the step at a seek point never rings on
    // in a reverb.
    effects_process_output(audioData, numFrames, numChannels, bytesPerSample, audioStream->getSampleRate());

    // What the spectrum view shows: the sound as it is here, the equalizer in
    // it and the volume not yet - so its bars follow the music and not the
    // slider. Every callback, the silent ones too, each frame under its own
    // number: see spectrum.h.
    if (bytesPerSample == 2) {
      spectrum_write(static_cast<const int16_t*>(audioData), numFrames, numChannels,
                     frames_written.load(std::memory_order_relaxed), audioStream->getSampleRate());
    }

    // Applied here rather than by whoever rendered, so it applies to all of
    // them equally and none of them has to know about it.
    const int gain = output_gain_percent.load(std::memory_order_relaxed);
    if (gain != 100) {
      const int count = numFrames * numChannels;
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData);
        const float scale = gain / 100.0f;
        for (int i = 0; i < count; i++) out[i] *= scale;
      } else {
        int16_t* out = static_cast<int16_t*>(audioData);
        for (int i = 0; i < count; i++) {
          const int32_t scaled = (static_cast<int32_t>(out[i]) * gain) / 100;
          out[i] = static_cast<int16_t>(scaled < -32768 ? -32768 : (scaled > 32767 ? 32767 : scaled));
        }
      }
    }

    // The per-file boost, after the master volume and clipped at full scale:
    // at +99 dB there is nothing left of a waveform but its sign, which is
    // what was asked for.
    // Not with Sound Check on: the point of it is that no track is louder
    // than another, and a file's own boost would make it so.
    const float boost = sound_check_enabled() ? 1.0f : output_boost.load(std::memory_order_relaxed);
    if (boost != 1.0f) {
      const int count = numFrames * numChannels;
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData);
        for (int i = 0; i < count; i++) out[i] = std::clamp(out[i] * boost, -1.0f, 1.0f);
      } else {
        int16_t* out = static_cast<int16_t*>(audioData);
        for (int i = 0; i < count; i++) {
          const float scaled = static_cast<float>(out[i]) * boost;
          out[i] = static_cast<int16_t>(std::clamp(scaled, -32768.0f, 32767.0f));
        }
      }
    }

    // After the render callback, never before: the renderer reads this counter
    // to learn the stream frame position of the buffer it is filling, which is
    // what it stamps its state snapshots with. Counted for silence too, so it
    // stays on the same timeline as getFramesRead() below.
    frames_written.fetch_add(numFrames, std::memory_order_release);

    in_callback.store(false, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
  }

  void onErrorAfterClose(oboe::AudioStream* oboeStream, oboe::Result error) override {
    LOG_ERROR("Stream closed due to error: %s", oboe::convertToText(error));
    // By the time this fires, Oboe has already torn the stream down — regardless
    // of which Result code is reported. Some OEM audio HALs (observed on Samsung
    // when a screen recorder starts capturing internal audio, which forces an
    // audio routing/session reconfiguration) report something other than
    // ErrorDisconnected here, and previously only ErrorDisconnected triggered
    // recovery, leaving playback silently and permanently stuck. Always flag it.
    audio_disconnected.store(true, std::memory_order_release);
  }

private:
  bool priority_boosted_ = false;
};

// See set_render_frame_offset. Thread-local, so the UI thread's reads of
// get_frames_written() are never shifted by a render in progress.
static thread_local int64_t tl_render_frame_offset = 0;

void set_render_frame_offset(int64_t frames) { tl_render_frame_offset = frames; }

int64_t get_frames_written() {
  return frames_written.load(std::memory_order_acquire) + tl_render_frame_offset;
}


void set_output_boost_db(int db) {
  const int clamped = std::clamp(db, -99, 99);
  output_boost.store(static_cast<float>(std::pow(10.0, clamped / 20.0)), std::memory_order_relaxed);
}

static int64_t monotonic_nanos() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
    std::chrono::steady_clock::now().time_since_epoch()).count();
}

int64_t get_frames_played() {
  const std::shared_ptr<oboe::AudioStream> stream = std::atomic_load(&audio_stream);
  if (!stream) return 0;

  // Oboe maintains this for both AAudio and OpenSL ES; for an output stream
  // "frames read" means frames consumed by the device, i.e. actually heard.
  // It only advances once per audio callback, though, in steps of a whole
  // burst - it is a counter, not a clock.
  int64_t read = stream->getFramesRead();
  const int64_t written = frames_written.load(std::memory_order_acquire);
  // Guard against a device/HAL that reports nothing useful (some report a flat
  // 0, some briefly report ahead of what we wrote across a restart). Falling
  // back to "no latency" shows the most recently rendered state - wrong by the
  // buffer depth, but never stuck.
  if (read < 0 || read > written) read = written;

  const int64_t now = monotonic_nanos();
  int64_t anchorFrames = play_anchor_frames.load(std::memory_order_relaxed);
  int64_t anchorNanos = play_anchor_nanos.load(std::memory_order_relaxed);
  int64_t last = play_last_reported.load(std::memory_order_relaxed);

  // Counters went backwards - the stream restarted underneath us, so every
  // previous estimate describes a timeline that no longer exists.
  if (read < anchorFrames || written < last) {
    play_anchor_frames.store(read, std::memory_order_relaxed);
    play_anchor_nanos.store(now, std::memory_order_relaxed);
    play_last_reported.store(read, std::memory_order_relaxed);
    return read;
  }

  // Re-anchor whenever the device actually reports progress, then project
  // forward from there with the monotonic clock.
  //
  // Interpolating rather than returning the raw counter is the whole point.
  // The counter steps once per callback (~23ms at a 1024-frame burst) while
  // the UI asks once per display frame (16.7ms at 60Hz), and the two rates
  // are unrelated - so consecutive frames keep landing on the same counter
  // value and then jumping two steps at once. Everything derived from this
  // inherits that: a pattern row transition gets deferred to the next counter
  // step, which is up to a full burst late and never the same amount late
  // twice (visible as rows advancing unevenly), and a scope's read position
  // stalls and lurches instead of flowing. The judder is worst when the
  // display period and the burst period are closest, which is why 60Hz looks
  // worse than 120Hz here.
  //
  // Playback consumes frames at exactly the sample rate, so between two
  // counter steps the true play head is simply "last known position plus
  // elapsed time", and that is an exact model of the hardware rather than a
  // smoothing filter over it. Same reasoning as ft2-clone advancing its sync
  // timestamps arithmetically instead of re-reading a clock per tick.
  if (read != anchorFrames) {
    anchorFrames = read;
    anchorNanos = now;
    play_anchor_frames.store(anchorFrames, std::memory_order_relaxed);
    play_anchor_nanos.store(anchorNanos, std::memory_order_relaxed);
  }

  const int64_t rate = stream->getSampleRate();
  int64_t projected = anchorFrames + ((now - anchorNanos) * rate) / 1000000000LL;
  // Can't have played what was never written, and a play head that went
  // backwards would show as the pattern jumping to a previous row.
  if (projected > written) projected = written;
  if (projected < last) projected = last;
  play_last_reported.store(projected, std::memory_order_relaxed);
  return projected;
}

void reset_render_callback() {
  render_callback.store(nullptr, std::memory_order_release);
}

void set_output_gain(int percent) {
  output_gain_percent.store(percent < 0 ? 0 : (percent > 400 ? 400 : percent),
                            std::memory_order_relaxed);
}

int get_output_gain(void) { return output_gain_percent.load(std::memory_order_relaxed); }

void ramp_output_in(int ms) {
  const int rate = get_stream_rate();
  if (rate <= 0 || ms <= 0) return;
  const int frames = ms * rate / 1000;
  ramp_frames_total.store(frames, std::memory_order_relaxed);
  ramp_frames_left.store(frames, std::memory_order_release);
}

int get_stream_rate(void) {
  const std::shared_ptr<oboe::AudioStream> stream = std::atomic_load(&audio_stream);
  return stream ? stream->getSampleRate() : 0;
}

void set_render_callback(AudioRenderCallback callback) {
  render_callback.store(callback, std::memory_order_release);
}

int get_playing(void) { return is_playing.load(std::memory_order_acquire) ? 1 : 0; }

void set_playing(int val) {
  if (!val) {
    is_playing.store(false, std::memory_order_release);
    wait_for_callback();
  } else {
    module_ended.store(false, std::memory_order_release);
    is_playing.store(true, std::memory_order_release);
  }
}

void wait_for_render(void) { wait_for_callback(); }

int has_module_ended() {
  return module_ended.load(std::memory_order_relaxed) ? 1 : 0;
}

int has_audio_disconnected() {
  return audio_disconnected.load(std::memory_order_acquire) ? 1 : 0;
}

void clear_audio_disconnected() {
  audio_disconnected.store(false, std::memory_order_release);
}

// 0 = loop (repeat-one), 1 = play once
void set_loop_mode(int flag) {
  loop_flag.store(flag, std::memory_order_relaxed);
}

int get_loop_mode() {
  return loop_flag.load(std::memory_order_relaxed);
}

static int oboe_open(int sample_rate, int performance_mode, int channel_count, int audio_api, int format_flags) {
  oboe::AudioStreamBuilder builder;
  (void)performance_mode; // no longer configurable — see pMode below

  // Guard against a stale callback from a prior disconnect
  if (audio_callback) {
    delete audio_callback;
    audio_callback = nullptr;
  }
  audio_callback = new PlayerAudioCallback();

  // This is a background music player, not a game: it has no need for
  // PerformanceMode::LowLatency's reduced input-to-output delay, and on AAudio,
  // LowLatency tries to use the MMAP hardware-direct path, which is generally
  // incompatible with anything capturing this app's audio at the same time
  // (e.g. Android's screen recorder tapping "internal audio" via
  // MediaProjection/AudioPlaybackCapture). When a capture client attaches, the
  // audio service is forced to tear down the MMAP stream and reopen a regular
  // mixer-routed one out from under us — that's the ErrorDisconnected storm
  // screen recording triggers. PerformanceMode::None uses the normal mixer path
  // from the start, so no such forced teardown is ever needed. Hardcoded; the
  // performance-mode setting has been removed.
  //
  // Confirmed by direct A/B test: forcing LowLatency here removes a real view
  // lag regression that showed up after switching to None. The exact
  // mechanism is still under investigation — see the timing instrumentation
  // in player-libopenmpt.cpp's getPatternData/getSampleData/getChannelData
  // and renderOpenMpt.
  const auto pMode = oboe::PerformanceMode::None;

  const auto cCount = (channel_count == 1) ? oboe::ChannelCount::Mono : oboe::ChannelCount::Stereo;

  const auto aApi = [&]() -> oboe::AudioApi {
    switch (audio_api) {
      case 1:
        return oboe::AudioApi::AAudio;
      case 2:
        return oboe::AudioApi::OpenSLES;
      default:
        return oboe::AudioApi::Unspecified;
    }
  }();

  const auto oboeFormat = (format_flags & (1 << 3)) ? oboe::AudioFormat::I32 : oboe::AudioFormat::I16;

  // NOTE: a forced small setFramesPerDataCallback() was tried here and made
  // the lag worse, not better — reverted. Smaller chunks mean more frequent
  // callbacks, and each callback acquisition of player-libopenmpt's native
  // mutex has its own fixed overhead; more frequent short critical sections
  // apparently cost more in aggregate than fewer longer ones. Left at
  // whatever PerformanceMode::None's mixer path negotiates by default.

  builder.setDirection(oboe::Direction::Output)
    ->setAudioApi(aApi)
    ->setChannelCount(cCount)
    ->setDataCallback(audio_callback)
    ->setErrorCallback(audio_callback)
    ->setFormat(oboeFormat)
    ->setPerformanceMode(pMode)
    ->setSampleRate(sample_rate)
    // Oboe converts when the device runs at another rate - see kRenderRate.
    // Medium is its own default, stated so that it stays the choice: sixteen
    // taps, cheap enough for old hardware, and inaudible at these ratios.
    ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
    ->setSharingMode(oboe::SharingMode::Shared);

  std::shared_ptr<oboe::AudioStream> opened;
  oboe::Result result = builder.openStream(opened);
  if (result == oboe::Result::OK) std::atomic_store(&audio_stream, opened);
  if (result != oboe::Result::OK) {
    LOG_ERROR("Failed to open stream: %s", oboe::convertToText(result));
    delete audio_callback;
    audio_callback = nullptr;
    return -1;
  }

  audio_disconnected.store(false, std::memory_order_release);

  actual_channels = audio_stream->getChannelCount();

  const char* sharingMode = (audio_stream->getSharingMode() == oboe::SharingMode::Exclusive) ? "Exclusive" : "Shared";

  LOG_INFO("Stream opened: rate=%d, burst=%d, capacity=%d, sharingMode=%s, channels=%d",
    audio_stream->getSampleRate(),
    audio_stream->getFramesPerBurst(),
    audio_stream->getBufferCapacityInFrames(),
    sharingMode,
    actual_channels);

  const char* apiName;
  switch (audio_stream->getAudioApi()) {
    case oboe::AudioApi::AAudio:
      apiName = "AAudio";
      break;
    case oboe::AudioApi::OpenSLES:
      apiName = "OpenSL ES";
      break;
    default:
      apiName = "Unknown";
      break;
  }
  LOG_INFO("Audio API: %s", apiName);

  return audio_stream->getSampleRate();
}

static void oboe_close() {
  lock();
  // Taken away first: whoever asks from here on finds none, and whoever is
  // asking now has a copy of its own.
  const std::shared_ptr<oboe::AudioStream> closing =
      std::atomic_exchange(&audio_stream, std::shared_ptr<oboe::AudioStream>());
  if (closing) {
    closing->stop();
    closing->close();
    spectrum_reset();
  }
  if (audio_callback) {
    delete audio_callback;
    audio_callback = nullptr;
  }
  unlock();
}

void close_audio() { oboe_close(); }

int get_effective_format_flags() {
  return effective_format_flags;
}

/**
 * The rate every decoder renders at, whatever the device runs at.
 *
 * The stream used to be opened unspecified, at the device's own rate, and each
 * decoder was then told that rate and rebuilt itself to match. That tied how
 * fast a tune played to the hardware in more ways than one. Each decoder had to
 * get the switch exactly right, and they did not all: libvgm's PlayerA quietly
 * keeps a playing player at its old rate, so a VGM rendered 48 kHz audio into a
 * 44.1 kHz stream and ran 9 % slow and flat; the Hively replayer lost its place;
 * a route change mid-song - to Bluetooth at 44.1 kHz, say - rebuilt whatever
 * was playing. And some tempo arithmetic is only exact at some rates: at 48 kHz
 * a Hively interrupt is a whole number of frames at every speed multiplier, at
 * 44.1 kHz it is not.
 *
 * So the decoders render at this, always - it is what they are built at - and
 * Oboe negotiates the device's rate by itself and converts between the two when
 * they differ, which on most devices is never. Nothing downstream of this ever
 * sees the device rate change.
 */
constexpr int kRenderRate = 48000;

int open_audio(int rate, int latency, int performance_mode, int channel_count, int audio_api, int format_flags) {
  (void)latency;
  // Anything but a deliberate rate means the render rate. See kRenderRate.
  if (rate <= 0) rate = kRenderRate;

  // OpenSL ES only supports I16 and Float
  if (audio_api == 2 /* OpenSLES */ && (format_flags & (1 << 3))) {
    LOG_WARN("I32 format not supported by OpenSL ES; falling back to I16");
    format_flags &= ~(1 << 3);
  }

  effective_format_flags = format_flags;
  reset_render_callback();

  int assumed_channels = (channel_count == 1) ? 1 : 2;

  int actual_rate = oboe_open(rate, performance_mode, channel_count, audio_api, format_flags);
  if (actual_rate < 0) return -1;

  if (actual_rate != rate || actual_channels != assumed_channels) {
    LOG_INFO("Buffer mismatch: rate %d->%d, channels %d->%d - reopening", rate, actual_rate, assumed_channels, actual_channels);
    oboe_close();
    int reopen_rate = oboe_open(actual_rate, performance_mode, channel_count, audio_api, format_flags);
    if (reopen_rate < 0) return -1;
    // The reopened stream is the one that will actually play, so its rate is
    // the one libopenmpt must render at. Returning the pre-reopen value here
    // meant a stream that negotiated a different rate the second time played
    // back detuned and at the wrong tempo.
    actual_rate = reopen_rate;
  }

  LOG_INFO("Audio opened: requested_rate=%d, actual_rate=%d, channels=%d", rate, actual_rate, actual_channels);

  return actual_rate;
}

int play_audio() {
  return restart_audio();
}

int restart_audio() {
  lock();

  if (!audio_stream) {
    LOG_ERROR("restart_audio: audio_stream is null");
    unlock();
    return -1;
  }

  const oboe::StreamState now = audio_stream->getState();
  if (now == oboe::StreamState::Started || now == oboe::StreamState::Starting) {
    LOG_INFO("restart_audio: stream already started");
    unlock();
    return 0;
  }

  LOG_INFO("restart_audio: starting stream");
  // Reset here, not on open: the device's own frames-read counter restarts
  // with the stream, so ours has to as well or the two stop describing the
  // same timeline and every latency figure derived from their difference is
  // garbage. Safe to write unsynchronised - the stream is not running yet, so
  // no callback can be advancing it.
  frames_written.store(0, std::memory_order_release);
  // And what the spectrum kept, which is filed under the frames just reset.
  spectrum_reset();
  play_anchor_frames.store(0, std::memory_order_relaxed);
  play_anchor_nanos.store(0, std::memory_order_relaxed);
  play_last_reported.store(0, std::memory_order_relaxed);
  // A stream started anew starts in silence: nothing rings on from before.
  effects_request_reset();
  oboe::Result result = audio_stream->requestStart();
  unlock();

  if (result != oboe::Result::OK) {
    LOG_ERROR("Failed to start stream: %s", oboe::convertToText(result));
    return -1;
  }

  LOG_INFO("Stream started successfully");
  return 0;
}

int stop_audio() {
  lock();

  if (!audio_stream) {
    unlock();
    return -1;
  }

  oboe::Result result = audio_stream->requestStop();
  unlock();

  return result == oboe::Result::OK ? 0 : -1;
}

int get_audio_stats(struct AudioStats* stats) {
  const std::shared_ptr<oboe::AudioStream> stream = std::atomic_load(&audio_stream);
  if (!stream || !stats) return -1;

  stats->sample_rate = stream->getSampleRate();
  stats->frames_per_burst = stream->getFramesPerBurst();
  stats->buffer_capacity = stream->getBufferCapacityInFrames();
  stats->buffer_size = stream->getBufferSizeInFrames();

  // On an output stream an XRun *is* an underrun; Oboe exposes no separate
  // counter. This used to report a static that nothing ever incremented, so
  // the debug view always showed 0 underruns no matter how badly it glitched.
  auto xRunResult = stream->getXRunCount();
  stats->xrun_count = (stream->isXRunCountSupported() && xRunResult) ? xRunResult.value() : 0;
  stats->underrun_count = stats->xrun_count;

  switch (stream->getAudioApi()) {
    case oboe::AudioApi::AAudio:
      stats->audio_api = "AAudio";
      break;
    case oboe::AudioApi::OpenSLES:
      stats->audio_api = "OpenSL ES";
      break;
    default:
      stats->audio_api = "Unknown";
      break;
  }

  switch (stream->getSharingMode()) {
    case oboe::SharingMode::Exclusive:
      stats->sharing_mode = "Exclusive";
      break;
    default:
      stats->sharing_mode = "Shared";
      break;
  }

  switch (stream->getPerformanceMode()) {
    case oboe::PerformanceMode::None:
      stats->perf_mode = "None";
      break;
    case oboe::PerformanceMode::PowerSaving:
      stats->perf_mode = "PowerSaving";
      break;
    default:
      stats->perf_mode = "LowLatency";
      break;
  }

  switch (stream->getFormat()) {
    case oboe::AudioFormat::I16:
      stats->audio_format = "I16";
      break;
    case oboe::AudioFormat::I32:
      stats->audio_format = "I32";
      break;
    default:
      stats->audio_format = "Unknown";
      break;
  }

  return 0;
}
