//
// Routing between decoders. See decoder.h for why this exists.
//
#include "decoder.h"
#include "decoder-gme.h"
#include "decoder-vgm.h"
#include "decoder-hvl.h"
#include "decoder-adplug.h"
#include "decoder-furnace.h"
#include "decoder-sid.h"
#include "decoder-cm.h"
#include "decoder-2sf.h"
#include "decoder-gsf.h"
#include "decoder-usf.h"
#include "decoder-psf.h"
#include "decoder-dsp.h"
#include "decoder-ffmpeg.h"
#include "psf.h"
#include "seek-ticket.h"
#include "loudness.h"
#include "audio.h"
#include "player.h"
#include "player-common.h"
#include "gme/gme.h"
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <new>
#include <cmath>
#include <cstring>
#include <cctype>
#include <string>
#include <vector>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <system_error>
#include <thread>
#include <semaphore.h>

constexpr const char* TAG = "Exilum Decoder";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

/**
 * Written only by loading and releasing, both of which happen on the loader
 * thread while nothing is being rendered - the engine is stopped across a
 * track change. Read from the UI thread, so it is atomic to keep that read
 * well-defined rather than because the two ever race for it.
 */
std::atomic<EngineKind> g_active{EngineKind::None};

/**
 * The custom loop: while both are set, playback that reaches the end point
 * goes back to the start point, in the same buffer - see loopingRender. -1
 * when there is none.
 */
std::atomic<int> g_loopStartMs{-1};
std::atomic<int> g_loopEndMs{-1};

/** The global tempo, which the loop needs to turn song time into frames. */
/**
 * The global pitch, in semitones, and tempo, in percent: where they have
 * been asked to be, and where the engine playing is on its way there. A
 * change reaches the engine playing a buffer at a time rather than at once -
 * see glideStep - and every other engine at once, for whatever loads next.
 */
std::atomic<double> g_pitchTarget{0.0};
std::atomic<double> g_pitchNow{0.0};
std::atomic<double> g_tempoTarget{100.0};
std::atomic<double> g_tempoNow{100.0};

/** The loaded decoder's own render callback, which loopingRender calls. */
std::atomic<AudioRenderCallback> g_engineRender{nullptr};

/** GME spells its extensions in capitals; the formats screen does not. */
std::string lowercased(const char* text) {
  std::string out(text);
  for (char& c : out) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return out;
}

}  // namespace

EngineKind engine_kind() { return g_active.load(std::memory_order_relaxed); }

namespace {

/** Song time at the end of what the loaded decoder has rendered. */
double renderPositionMs() {
  switch (engine_kind()) {
    case EngineKind::Gme: return gme_decoder_render_position_ms();
    case EngineKind::Vgm: return vgm_decoder_render_position_ms();
    case EngineKind::Hvl: return hvl_decoder_render_position_ms();
    case EngineKind::AdPlug: return adplug_decoder_render_position_ms();
    case EngineKind::Furnace: return furnace_decoder_render_position_ms();
    case EngineKind::Sid: return sid_decoder_render_position_ms();
    case EngineKind::CustomMade: return cm_decoder_render_position_ms();
    case EngineKind::Gsf: return gsf_decoder_render_position_ms();
    case EngineKind::TwoSf: return twosf_decoder_render_position_ms();
    case EngineKind::Usf: return usf_decoder_render_position_ms();
    case EngineKind::Psf1: return psf1_decoder_render_position_ms();
    case EngineKind::Dsp: return dsp_decoder_render_position_ms();
    case EngineKind::Ffmpeg: return ffmpeg_decoder_render_position_ms();
    default: return openmpt_render_position_ms();
  }
}

/** Where the loop goes back to, -1 when there is none. */
int loopStartForDecoders() {
  const int start = g_loopStartMs.load(std::memory_order_relaxed);
  const int end = g_loopEndMs.load(std::memory_order_relaxed);
  return start >= 0 && end > start ? start : -1;
}

/** Takes the loaded decoder to [ms] for the loop: no fade, views kept. */
void loopSeek(int ms) {
  switch (engine_kind()) {
    case EngineKind::Gme: gme_decoder_loop_seek_ms(ms); break;
    case EngineKind::Vgm: vgm_decoder_loop_seek_ms(ms); break;
    case EngineKind::Hvl: hvl_decoder_loop_seek_ms(ms); break;
    case EngineKind::AdPlug: adplug_decoder_loop_seek_ms(ms); break;
    case EngineKind::Furnace: furnace_decoder_loop_seek_ms(ms); break;
    case EngineKind::Sid: sid_decoder_loop_seek_ms(ms); break;
    case EngineKind::CustomMade: cm_decoder_loop_seek_ms(ms); break;
    case EngineKind::Gsf: gsf_decoder_loop_seek_ms(ms); break;
    case EngineKind::TwoSf: twosf_decoder_loop_seek_ms(ms); break;
    case EngineKind::Usf: usf_decoder_loop_seek_ms(ms); break;
    case EngineKind::Psf1: psf1_decoder_loop_seek_ms(ms); break;
    case EngineKind::Dsp: dsp_decoder_loop_seek_ms(ms); break;
    case EngineKind::Ffmpeg: ffmpeg_decoder_loop_seek_ms(ms); break;
    default: openmpt_seek(nullptr, ms); break;
  }
}

/** Hands [kind] a pitch of [semitones]; engines that cannot shift pitch are left alone. */
void pitchTo(EngineKind kind, double semitones) {
  switch (kind) {
    case EngineKind::OpenMpt: openmpt_setGlobalPitch(nullptr, semitones); break;
    case EngineKind::Hvl: hvl_decoder_set_pitch(semitones); break;
    case EngineKind::AdPlug: adplug_decoder_set_pitch(semitones); break;
    case EngineKind::Gme: gme_decoder_set_pitch(semitones); break;
    case EngineKind::Vgm: vgm_decoder_set_pitch(semitones); break;
    case EngineKind::Furnace: furnace_decoder_set_pitch(semitones); break;
    case EngineKind::CustomMade: cm_decoder_set_pitch(semitones); break;
    case EngineKind::Gsf: gsf_decoder_set_pitch(semitones); break;
    case EngineKind::TwoSf: twosf_decoder_set_pitch(semitones); break;
    case EngineKind::Usf: usf_decoder_set_pitch(semitones); break;
    case EngineKind::Psf1: psf1_decoder_set_pitch(semitones); break;
    case EngineKind::Dsp: dsp_decoder_set_pitch(semitones); break;
    case EngineKind::Ffmpeg: ffmpeg_decoder_set_pitch(semitones); break;
    case EngineKind::Sid:
    case EngineKind::None: break;
  }
}

/** Hands [kind] a tempo of [percent]; engines that cannot change tempo are left alone. */
void tempoTo(EngineKind kind, double percent) {
  switch (kind) {
    case EngineKind::OpenMpt: openmpt_setGlobalTempo(nullptr, percent); break;
    case EngineKind::Hvl: hvl_decoder_set_tempo(percent); break;
    case EngineKind::AdPlug: adplug_decoder_set_tempo(percent); break;
    case EngineKind::Gme: gme_decoder_set_tempo(percent); break;
    case EngineKind::Vgm: vgm_decoder_set_tempo(percent); break;
    case EngineKind::Furnace: furnace_decoder_set_tempo(percent); break;
    case EngineKind::CustomMade: cm_decoder_set_tempo(percent); break;
    case EngineKind::Gsf: gsf_decoder_set_tempo(percent); break;
    case EngineKind::TwoSf: twosf_decoder_set_tempo(percent); break;
    case EngineKind::Usf: usf_decoder_set_tempo(percent); break;
    case EngineKind::Psf1: psf1_decoder_set_tempo(percent); break;
    case EngineKind::Dsp: dsp_decoder_set_tempo(percent); break;
    case EngineKind::Ffmpeg: ffmpeg_decoder_set_tempo(percent); break;
    case EngineKind::Sid:
    case EngineKind::None: break;
  }
}

constexpr EngineKind kShiftable[] = {
    EngineKind::OpenMpt, EngineKind::Hvl, EngineKind::AdPlug, EngineKind::Gme,
    EngineKind::Vgm, EngineKind::Furnace, EngineKind::CustomMade, EngineKind::Gsf,
    EngineKind::TwoSf,
    EngineKind::Usf,
    EngineKind::Psf1,
    EngineKind::Dsp,
    EngineKind::Ffmpeg,
};

/**
 * How long a glide takes to get most of the way: the time constant of the
 * approach, which covers 95% of the distance in three of them. A fifth of a
 * second from one step to the next reads as the tune being bent there rather
 * than cut over, without lagging behind the button.
 */
constexpr double kGlideSeconds = 0.065;

/** Close enough to stop: a third of a cent, and a fiftieth of a percent. */
constexpr double kPitchSnap = 0.003;
constexpr double kTempoSnap = 0.02;

/**
 * Moves the engine playing a buffer's worth of the way from the pitch and
 * tempo it has to the ones asked for, on the audio thread, just before the
 * buffer is rendered - so each buffer plays at one value and the next goes
 * on from there, and a change is a glide rather than a jump. The approach is
 * exponential: quick to answer, and easing in to where it is going, however
 * many steps were asked for on the way.
 */
void glideStep(int32_t frames) {
  const double rate = std::max(1, get_stream_rate());
  const double share = 1.0 - std::exp(-static_cast<double>(frames) / rate / kGlideSeconds);
  const EngineKind kind = engine_kind();

  const double pitchTarget = g_pitchTarget.load(std::memory_order_relaxed);
  double pitch = g_pitchNow.load(std::memory_order_relaxed);
  if (pitch != pitchTarget) {
    pitch += (pitchTarget - pitch) * share;
    if (std::abs(pitchTarget - pitch) < kPitchSnap) pitch = pitchTarget;
    g_pitchNow.store(pitch, std::memory_order_relaxed);
    pitchTo(kind, pitch);
  }

  const double tempoTarget = g_tempoTarget.load(std::memory_order_relaxed);
  double tempo = g_tempoNow.load(std::memory_order_relaxed);
  if (tempo != tempoTarget) {
    tempo += (tempoTarget - tempo) * share;
    if (std::abs(tempoTarget - tempo) < kTempoSnap) tempo = tempoTarget;
    g_tempoNow.store(tempo, std::memory_order_relaxed);
    tempoTo(kind, tempo);
  }
}

/** Whether the pitch or the tempo is on its way somewhere - see glideStep. */
bool gliding() {
  return g_pitchNow.load(std::memory_order_relaxed) != g_pitchTarget.load(std::memory_order_relaxed) ||
         g_tempoNow.load(std::memory_order_relaxed) != g_tempoTarget.load(std::memory_order_relaxed);
}

/**
 * Seeks under way that run the tune again from its start. While there is
 * one, the stream is given silence and the decoder is not asked for sound.
 *
 * A chip tune, or a console's, is a program, and the way to a point in it is
 * to run it there: seconds of work for a point minutes in, with the
 * decoder's mutex held throughout. The audio callback, asking that decoder
 * for the next buffer, waited on the mutex for as long - no buffer handed
 * over in all that time, which the device counts as so many underruns and
 * some take for a stream that has died, and a pause that sat out the whole
 * of its own wait for the callback. Silence is what a seek sounds like
 * anyway.
 */
std::atomic<int> g_reruns{0};

/** Whether a seek in [kind] runs the tune from its start to where it is going. */
bool seeksByRerunning(EngineKind kind) {
  switch (kind) {
    case EngineKind::Gme:
    case EngineKind::Vgm:
    case EngineKind::Furnace:
    case EngineKind::CustomMade:
    case EngineKind::Gsf:
    case EngineKind::TwoSf:
    case EngineKind::Usf:
    case EngineKind::Psf1: return true;
    default: return false;
  }
}

/** No seek: a ticket the player never gives. */
constexpr int kNoSeek = INT32_MIN;
/** The ticket of the latest thing the player asked of the tune, and of the seek being carried out. */
std::atomic<int> g_seekWanted{0};
std::atomic<int> g_seekInHand{kNoSeek};

/** Before such a seek, on the thread that makes it. */
void beginRerun() {
  g_reruns.fetch_add(1, std::memory_order_acq_rel);
  // A callback that looked at the count before this and has not asked the
  // decoder yet would meet the seek at the mutex: it is let through first.
  // Every one after it sees the count.
  wait_for_render();
}

/** After it; [landed] when the decoder is somewhere new. */
void endRerun(bool landed) {
  // Faded in from where it lands - the fade asked for with the seek was
  // spent on the silence that lasted until now.
  if (landed) ramp_output_in(10);
  g_reruns.fetch_sub(1, std::memory_order_acq_rel);
}

/** Whether the decoder is being run to somewhere, and is not to be asked for sound meanwhile. */
bool rerunUnderWay() {
  // The SID decoder runs its seeks on a thread of its own, and says so.
  return g_reruns.load(std::memory_order_acquire) > 0 ||
         (engine_kind() == EngineKind::Sid && sid_decoder_seeking());
}

/**
 * Whether the loop's way back to its start, in [kind], is a run of the tune
 * from the top. The decoders that can be put anywhere are not. Nor are the
 * consoles that make a second one ahead of the loop (see
 * engine_setLoopRegion), whose way back is a swap when that one is ready -
 * and still a run from the top, in the callback, when it is not: a loop
 * shorter than the next console takes to make has them turn about, so what
 * the last jump took says the opposite of what the next will, and they are
 * left as they were until the decoders can say which it will be.
 */
bool jumpsByRerunning(EngineKind kind) {
  switch (kind) {
    case EngineKind::Gme:
    case EngineKind::Vgm:
    case EngineKind::Furnace:
    case EngineKind::Sid:
    case EngineKind::CustomMade:
    case EngineKind::Gsf: return true;
    default: return false;
  }
}

/**
 * The loop's way back, for those decoders, when it does not fit in the
 * buffer it falls in.
 *
 * The jump is made in the callback, in the middle of a buffer, so that the
 * loop has no gap in it - which holds for as long as getting back to the
 * start takes less time than the buffer plays for. A start point a minute
 * into a console's tune is that minute emulated again at every pass, in the
 * audio callback: a dropout as long as it takes, with the stream starved
 * the while. So each jump is timed, and one that takes too long is made on
 * the thread here, the stream given silence until it lands. The gap is the
 * same one; the callback no longer sits in it. One not timed yet is taken
 * to be long: the thread makes it, and what it took decides the next.
 */
struct LoopJumper {
  sem_t wake;
  std::once_flag once;
  std::atomic<bool> started{false};
  /** Where to, in ms; -1 when nothing is asked. */
  std::atomic<int> wanted{-1};
  /** Held while a jump is carried out; taken by whoever is about to let go of what is loaded. */
  std::mutex carrying;
};
LoopJumper g_jumper;

/** How long the last jump back took, in nanoseconds; 0 when none has been timed for this loop. */
std::atomic<int64_t> g_jumpCostNs{0};

/**
 * Jumps asked of the thread since anything inside the loop was last played,
 * and whether the loop's start has been found out of reach by them: two
 * jumps with nothing between them is a start point the tune cannot be put
 * at - one past its end. The tune then plays on rather than being sent back
 * for ever, in silence.
 */
std::atomic<int> g_jumpsUnheard{0};
std::atomic<bool> g_loopOutOfReach{false};

/** Which loop the timings are of: one more at every call of forgetLoopTimings. */
std::atomic<uint32_t> g_loopTimingsOf{0};

/**
 * Set for as long as a buffer is being made with a loop on. A tune that
 * comes to its end inside the loop says so from inside the decoder, a few
 * instructions before the way back is asked for and the count of reruns
 * raised; whoever asked in between (see engine_hasEnded) was told the track
 * had ended. Raised before the decoder is asked and lowered after the jump
 * is, there is no in between.
 */
std::atomic<bool> g_loopPass{false};

/** The loop is another, or the tune is: what was learnt about the last no longer holds. */
void forgetLoopTimings() {
  // Not left standing by a callback the device never let finish.
  g_loopPass.store(false, std::memory_order_release);
  g_loopTimingsOf.fetch_add(1, std::memory_order_acq_rel);
  g_jumpCostNs.store(0, std::memory_order_relaxed);
  g_jumpsUnheard.store(0, std::memory_order_relaxed);
  g_loopOutOfReach.store(false, std::memory_order_relaxed);
}

/**
 * loopSeek, timed where the time it takes is worth knowing - and counted
 * among the reruns for as long as it lasts, wherever it is made: a tune that
 * came to its end inside the loop says it has ended until it is back at the
 * start, and whoever asks meanwhile (see engine_hasEnded) took that for the
 * end of the track and went on to the next.
 */
void jumpHere(int ms) {
  g_reruns.fetch_add(1, std::memory_order_acq_rel);
  if (!jumpsByRerunning(engine_kind())) {
    loopSeek(ms);
  } else {
    const uint32_t of = g_loopTimingsOf.load(std::memory_order_acquire);
    const auto began = std::chrono::steady_clock::now();
    loopSeek(ms);
    const int64_t took =
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - began).count();
    // Kept only if the loop is still the one it was made for.
    if (g_loopTimingsOf.load(std::memory_order_acquire) == of) {
      g_jumpCostNs.store(std::max<int64_t>(1, took), std::memory_order_relaxed);
    }
  }
  g_reruns.fetch_sub(1, std::memory_order_acq_rel);
}

/** Whether the jump back, in a buffer of [frames] at [rate], is one for the thread. */
bool jumpIsTheThreads(int32_t frames, double rate) {
  // Recorded music is put somewhere by the thread that reads its file, and
  // the callback is not to wait for that thread and the storage behind it,
  // however short the wait has been so far.
  if (engine_kind() == EngineKind::Ffmpeg) return g_jumper.started.load(std::memory_order_acquire);
  if (!jumpsByRerunning(engine_kind()) || !g_jumper.started.load(std::memory_order_acquire)) return false;
  const int64_t cost = g_jumpCostNs.load(std::memory_order_relaxed);
  if (cost <= 0) return true;
  // Half the time the buffer plays for: the rest of it is still to render.
  const double budgetNs = static_cast<double>(frames) / rate * 1e9 * 0.5;
  return static_cast<double>(cost) > budgetNs;
}

/** Asks the thread for the jump. From the audio callback: nothing here waits. */
void askForJump(int ms) {
  g_reruns.fetch_add(1, std::memory_order_acq_rel);
  g_jumper.wanted.store(ms, std::memory_order_release);
  sem_post(&g_jumper.wake);
}

/**
 * Drops a jump asked for and not begun, and waits out one that has: for
 * whoever is about to let go of the tune it would be made in.
 */
void forgetJump() {
  g_jumper.wanted.store(-1, std::memory_order_release);
  std::lock_guard<std::mutex> lock(g_jumper.carrying);
}

void runJumps() {
  for (;;) {
    while (sem_wait(&g_jumper.wake) != 0) {
    }
    // Held to the end of the pass: whoever waits in forgetJump is then past
    // this thread's last touch of the stream - the fade - as well, and has
    // the count back.
    std::lock_guard<std::mutex> lock(g_jumper.carrying);
    const int to = g_jumper.wanted.exchange(-1, std::memory_order_acq_rel);
    const int end = g_loopEndMs.load(std::memory_order_relaxed);
    bool landed = false;
    // Still the loop it was asked for, in a tune still there.
    if (to >= 0 && end > to && g_engineRender.load(std::memory_order_acquire) != nullptr) {
      jumpHere(to);
      landed = true;
    }
    endRerun(landed);
  }
}

/** Starts the thread, the first time a loop is set. */
void startJumper() {
  std::call_once(g_jumper.once, [] {
    if (sem_init(&g_jumper.wake, 0, 0) != 0) return;
    try {
      std::thread(runJumps).detach();
      g_jumper.started.store(true, std::memory_order_release);
    } catch (const std::system_error&) {
      // No thread to be had: the jumps stay where they were made before.
      LOG_WARN("no thread for the loop's jumps");
    }
  });
}

/**
 * How much of a buffer a glide moves over at a time: about five milliseconds.
 * A whole buffer at once is up to fifty on some devices, and the first steps
 * of a glide - the largest - came out as a staircase of pitches rather than a
 * slide.
 */
constexpr int32_t kGlideChunkFrames = 256;

/**
 * The render callback the stream is given, in front of the decoder's own.
 *
 * With no loop set and nothing gliding it is a straight call through.
 * Otherwise the buffer is rendered in pieces. With a loop set they end
 * exactly where the loop does - the frames left to the end point worked out
 * from the decoder's own position and the tempo - and at the end point the
 * decoder is taken back to the start and the buffer filled on from there, so
 * the jump lands mid-buffer with no gap either side; a tune that ends before
 * reaching the end point goes back to the start too. While the pitch or the
 * tempo glides they are a few milliseconds long, and each goes a step further
 * along the glide.
 */
int loopingRender(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  const AudioRenderCallback inner = g_engineRender.load(std::memory_order_acquire);
  if (inner == nullptr) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * numChannels * bytesPerSample);
    return 0;
  }
  const double rate = std::max(1, get_stream_rate());
  const size_t frameBytes = static_cast<size_t>(numChannels) * bytesPerSample;
  // The decoder is on its way somewhere and has its mutex with it: see g_reruns.
  if (rerunUnderWay()) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * frameBytes);
    return 0;
  }
  const int start = g_loopStartMs.load(std::memory_order_relaxed);
  const int end = g_loopEndMs.load(std::memory_order_relaxed);
  bool looping = start >= 0 && end > start && !g_loopOutOfReach.load(std::memory_order_relaxed);
  if (!looping && !gliding()) return inner(audioData, numFrames, numChannels, bytesPerSample);

  // See g_loopPass: from here to wherever this buffer is handed back.
  struct LoopPass {
    const bool on;
    explicit LoopPass(bool on) : on(on) {
      if (on) g_loopPass.store(true, std::memory_order_release);
    }
    ~LoopPass() {
      if (on) g_loopPass.store(false, std::memory_order_release);
    }
  } pass(looping);

  // The way back asked of the thread, and the rest of the buffer silent -
  // unless it has been asked twice with nothing played since, which is a
  // start point out of reach: there is no loop then, and the tune plays on.
  const auto jumpElsewhere = [&](int32_t from) {
    if (g_jumpsUnheard.load(std::memory_order_relaxed) >= 2) {
      g_loopOutOfReach.store(true, std::memory_order_relaxed);
      looping = false;
      return false;
    }
    g_jumpsUnheard.fetch_add(1, std::memory_order_relaxed);
    // Asked first: from here the tune is on its way back, whatever it says
    // of itself meanwhile.
    askForJump(start);
    std::memset(static_cast<uint8_t*>(audioData) + static_cast<size_t>(from) * frameBytes, 0,
                static_cast<size_t>(numFrames - from) * frameBytes);
    return true;
  };

  int32_t written = 0;
  int jumps = 0;
  while (written < numFrames) {
    int32_t chunk = numFrames - written;
    if (looping) {
      const double left = end - renderPositionMs();
      if (left <= 0.0) {
        if (jumpIsTheThreads(numFrames, rate)) {
          if (jumpElsewhere(written)) return 0;
        } else if (jumps < 2) {
          // A position that will not come back before the end point after a
          // jump - a start point past the tune - plays on rather than spinning.
          jumpHere(start);
          jumps++;
          continue;
        }
      } else {
        const double tempo = std::max(10.0, g_tempoNow.load(std::memory_order_relaxed)) / 100.0;
        const double framesLeft = std::ceil(left / 1000.0 * rate / tempo);
        if (framesLeft < chunk) chunk = std::max<int32_t>(1, static_cast<int32_t>(framesLeft));
      }
    }
    if (gliding()) {
      chunk = std::min(chunk, kGlideChunkFrames);
      glideStep(chunk);
    }

    uint8_t* at = static_cast<uint8_t*>(audioData) + static_cast<size_t>(written) * frameBytes;
    set_render_frame_offset(written);
    const int result = inner(at, chunk, numChannels, bytesPerSample);
    set_render_frame_offset(0);
    written += chunk;
    if (result == 0) {
      if (looping) g_jumpsUnheard.store(0, std::memory_order_relaxed);
      continue;
    }
    if (looping && jumpIsTheThreads(numFrames, rate) && jumpElsewhere(written)) return 0;
    if (looping) {
      if (jumps < 2) {
        jumpHere(start);
        jumps++;
      }
    } else {
      // The tune ended partway through a glide's pieces: what is left of the
      // buffer is silence, and the end is passed on as a straight call would.
      std::memset(at + static_cast<size_t>(chunk) * frameBytes, 0,
                  static_cast<size_t>(numFrames - written) * frameBytes);
      return result;
    }
  }
  return 0;
}

/** Points the stream at [callback], through the loop. */
void installRender(AudioRenderCallback callback) {
  // Whatever loads starts where the pitch and tempo are going, not partway
  // there: a glide left over from the last track would bend the first notes
  // of this one. And the engine is told so itself. The engines that were not
  // playing were handed the targets when they were set, but the one that
  // was got them a step at a time, and a track changed in the middle of a
  // glide left it wherever the glide had got to - 137% of a 150% asked for -
  // for every track after, with nothing left moving to carry it on. The
  // stream is not on this engine yet, so nothing is rendering under it.
  const double pitch = g_pitchTarget.load(std::memory_order_relaxed);
  const double tempo = g_tempoTarget.load(std::memory_order_relaxed);
  pitchTo(engine_kind(), pitch);
  tempoTo(engine_kind(), tempo);
  g_pitchNow.store(pitch, std::memory_order_relaxed);
  g_tempoNow.store(tempo, std::memory_order_relaxed);
  forgetLoopTimings();
  g_engineRender.store(callback, std::memory_order_release);
  set_render_callback(loopingRender);
}

}  // namespace

namespace {

/**
 * Whether a call about libopenmpt's own state should reach it.
 *
 * True with nothing loaded as well as with a tracker loaded: these are
 * settings that live in libopenmpt whether or not it is playing, and the
 * settings screen reads them back before anything has been opened. False only
 * while another decoder holds the file, which is the case that used to end
 * with libopenmpt answering on its behalf.
 */
/** Player.kt's parameter id for master volume; see PARAM_MASTER_VOLUME. */
constexpr jint kParamMasterVolume = 1;

bool openMptOwns() {
  const EngineKind kind = engine_kind();
  return kind == EngineKind::OpenMpt || kind == EngineKind::None;
}

}  // namespace

bool engine_has_patterns() {
  switch (engine_kind()) {
    case EngineKind::OpenMpt:
    case EngineKind::Hvl:
    case EngineKind::Furnace:
      // All three are trackers: an order list, patterns, tracks per channel.
      return true;
    case EngineKind::AdPlug:
      // Most of its formats are trackers and hold a grid; the register logs
      // do not, and their players say so.
      return adplug_decoder_has_patterns();
    case EngineKind::Gme:
    case EngineKind::Vgm:
    case EngineKind::Sid:
    case EngineKind::CustomMade:
    case EngineKind::Gsf:
    case EngineKind::TwoSf:
    case EngineKind::Usf:
    case EngineKind::Psf1:
    case EngineKind::Dsp:
    case EngineKind::Ffmpeg:
    case EngineKind::None:
      return false;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Module lifetime
// ---------------------------------------------------------------------------

namespace {

/**
 * More than this and a file is not read at all: nothing played here is
 * larger, and whatever is - a film, a disc image, kept beside the music -
 * was read end to end into memory to be told it was not a tune, until there
 * was no memory left.
 */
constexpr off_t kMaxFileBytes = static_cast<off_t>(512) * 1024 * 1024;

/** Reads a descriptor whole, from the start. Empty for one too big to be a tune, or that there is no room for. */
std::vector<uint8_t> readWhole(int fd) {
  std::vector<uint8_t> data;
  struct stat info {};
  const bool sized = fstat(fd, &info) == 0 && S_ISREG(info.st_mode) && info.st_size > 0;
  if (sized && info.st_size > kMaxFileBytes) return data;
  // Not a reason to give up when it fails: a descriptor handed over fresh is
  // at the start already, and some document providers hand over a pipe,
  // which cannot seek and reads perfectly well from where it is.
  lseek(fd, 0, SEEK_SET);
  try {
    // All of its room at once where its size is known: grown as it was read,
    // the file was held twice over each time the room ran out.
    if (sized) data.reserve(static_cast<size_t>(info.st_size));
    uint8_t chunk[64 * 1024];
    for (;;) {
      const ssize_t got = read(fd, chunk, sizeof(chunk));
      if (got < 0 && errno == EINTR) continue;
      if (got <= 0) break;
      data.insert(data.end(), chunk, chunk + got);
      if (data.size() > static_cast<size_t>(kMaxFileBytes)) return {};
    }
  } catch (const std::bad_alloc&) {
    return {};
  }
  return data;
}

/** Fills a ModInfo from a decoder that is not libopenmpt. */
void setModInfo(JNIEnv* env, jobject modInfo, const char* name, const char* type) {
  if (!modInfo) return;
  jclass cls = env->GetObjectClass(modInfo);
  jfieldID nameField = env->GetFieldID(cls, "name", "Ljava/lang/String;");
  jfieldID typeField = env->GetFieldID(cls, "type", "Ljava/lang/String;");
  jstring nameStr = newJString(env, name ? name : "");
  jstring typeStr = newJString(env, type ? type : "");
  env->SetObjectField(modInfo, nameField, nameStr);
  env->SetObjectField(modInfo, typeField, typeStr);
  env->DeleteLocalRef(nameStr);
  env->DeleteLocalRef(typeStr);
  env->DeleteLocalRef(cls);
}

}  // namespace

/** Reads a Java string, or "" for null. */
std::string nativeString(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string out(chars ? chars : "");
  if (chars) env->ReleaseStringUTFChars(value, chars);
  return out;
}

jint engine_loadFd(JNIEnv* env, jint fd, jstring filename, jobject modInfo) {
  // Read once, here, and offered to each decoder in turn.
  //
  // This used to hand the descriptor to libopenmpt, which reads it and closes
  // it - so when libopenmpt declined, there was nothing left for anyone else
  // to read and every non-tracker format silently failed to load.
  //
  // Except recorded music, which is not read whole at all: a record is a
  // hundred times a tune, and is played from where it lies - see
  // decoder-ffmpeg.h. Told by its first bytes, before anything is read.
  const std::string soundName = nativeString(env, filename);
  uint8_t head[4096];
  const ssize_t headSize = pread(fd, head, sizeof(head), 0);
  if (headSize > 0 && ffmpeg_decoder_claims(head, static_cast<size_t>(headSize), soundName.c_str())) {
    reset_render_callback();
    engine_releaseModule(env);
    if (ffmpeg_decoder_load_fd(fd, soundName.c_str(), 0)) {
      close(fd);
      g_active.store(EngineKind::Ffmpeg, std::memory_order_relaxed);
      installRender(ffmpeg_decoder_render);
      setModInfo(env, modInfo, ffmpeg_decoder_title(), ffmpeg_decoder_type());
      LOG_INFO("loaded by FFmpeg: %s", ffmpeg_decoder_type());
      return 0;
    }
  }
  // A pipe cannot be read from anywhere but where it is: asked again below, once it is in memory.
  const bool piped = headSize < 0;
  const std::vector<uint8_t> data = readWhole(fd);
  close(fd);
  if (data.empty()) return -1;

  // Whoever held the last file lets go of it first, and the stream is left
  // pointing at nobody until someone accepts this one. Without this the
  // previous decoder stayed loaded behind the new one - an emulator's worth
  // of state kept alive by nothing, and a callback still pointing into it.
  reset_render_callback();
  engine_releaseModule(env);

  // libopenmpt first, because it is the one that reads trackers and a tracker
  // is what most files here are. The first to accept it owns playback until
  // something else is loaded.
  if (piped && ffmpeg_decoder_claims(data.data(), std::min<size_t>(data.size(), 4096), soundName.c_str()) &&
      ffmpeg_decoder_load_memory(data.data(), data.size(), soundName.c_str(), 0)) {
    g_active.store(EngineKind::Ffmpeg, std::memory_order_relaxed);
    installRender(ffmpeg_decoder_render);
    setModInfo(env, modInfo, ffmpeg_decoder_title(), ffmpeg_decoder_type());
    LOG_INFO("loaded by FFmpeg, from memory: %s", ffmpeg_decoder_type());
    return 0;
  }

  const jint openmptResult = openmpt_loadData(env, data.data(), data.size(), modInfo);
  if (openmptResult == 0) {
    g_active.store(EngineKind::OpenMpt, std::memory_order_relaxed);
    installRender(renderOpenMpt);
    LOG_INFO("loaded by libopenmpt");
    return 0;
  }

  if (hvl_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::Hvl, std::memory_order_relaxed);
    installRender(hvl_decoder_render);
    setModInfo(env, modInfo, hvl_decoder_title(), "AHX / Hively");
    LOG_INFO("loaded by HivelyTracker replayer");
    return 0;
  }

  // Furnace says what it is in its first sixteen bytes, or in the first
  // sixteen it inflates to, so the engine only sees a file that is its own.
  const std::string furnaceName = nativeString(env, filename);
  if (furnace_decoder_load(data.data(), data.size(), furnaceName.c_str(), 0)) {
    g_active.store(EngineKind::Furnace, std::memory_order_relaxed);
    installRender(furnace_decoder_render);
    setModInfo(env, modInfo, furnace_decoder_title(), furnace_decoder_type());
    LOG_INFO("loaded by Furnace: %s", furnace_decoder_type());
    return 0;
  }

  // A SID starts "PSID" or "RSID", and nothing else here reads one.
  if (sid_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::Sid, std::memory_order_relaxed);
    installRender(sid_decoder_render);
    setModInfo(env, modInfo, sid_decoder_title(), sid_decoder_type());
    LOG_INFO("loaded by libsidplayfp: %s", sid_decoder_type());
    return 0;
  }

  // CustomMade is the replayer's own code with its tables, and says so in
  // the instructions it starts with.
  if (cm_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::CustomMade, std::memory_order_relaxed);
    installRender(cm_decoder_render);
    setModInfo(env, modInfo, "", cm_decoder_type());
    LOG_INFO("loaded by the CustomMade replayer");
    return 0;
  }

  // A GSF says so in its first four bytes. A library of one is only ever
  // loaded by the file that names it, never played alone.
  if (!psf::isLibraryName(nativeString(env, filename)) && gsf_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::Gsf, std::memory_order_relaxed);
    installRender(gsf_decoder_render);
    setModInfo(env, modInfo, gsf_decoder_title(), gsf_decoder_type());
    LOG_INFO("loaded by mGBA: %s", gsf_decoder_title());
    return 0;
  }

  // And a 2SF, the same way: the Nintendo DS's, in the same container.
  if (!psf::isLibraryName(nativeString(env, filename)) && twosf_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::TwoSf, std::memory_order_relaxed);
    installRender(twosf_decoder_render);
    twosf_decoder_expect_loop(loopStartForDecoders());
    setModInfo(env, modInfo, twosf_decoder_title(), twosf_decoder_type());
    LOG_INFO("loaded by vio2sf: %s", twosf_decoder_title());
    return 0;
  }

  // And a USF, the Nintendo 64's, the same way again.
  if (!psf::isLibraryName(nativeString(env, filename)) && usf_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::Usf, std::memory_order_relaxed);
    installRender(usf_decoder_render);
    usf_decoder_expect_loop(loopStartForDecoders());
    setModInfo(env, modInfo, usf_decoder_title(), usf_decoder_type());
    LOG_INFO("loaded by lazyusf2: %s", usf_decoder_title());
    return 0;
  }

  // And a PSF, the PlayStation's own, the same way.
  if (!psf::isLibraryName(nativeString(env, filename)) && psf1_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::Psf1, std::memory_order_relaxed);
    installRender(psf1_decoder_render);
    psf1_decoder_expect_loop(loopStartForDecoders());
    setModInfo(env, modInfo, psf1_decoder_title(), psf1_decoder_type());
    LOG_INFO("loaded by aopsf: %s", psf1_decoder_title());
    return 0;
  }

  // A GameCube stream: by its header, or for the ones with none by its name
  // and its first frames.
  if (dsp_decoder_load(data.data(), data.size(), nativeString(env, filename).c_str(), 0)) {
    g_active.store(EngineKind::Dsp, std::memory_order_relaxed);
    installRender(dsp_decoder_render);
    setModInfo(env, modInfo, dsp_decoder_title(), dsp_decoder_type());
    LOG_INFO("loaded by the GameCube stream decoder: %s", dsp_decoder_type());
    return 0;
  }

  // Before GME, and cheaply: a VGM says so in its first four bytes, and the
  // only cost of asking is expanding a file that turned out to be gzipped.
  if (vgm_decoder_load(data.data(), data.size(), 0)) {
    g_active.store(EngineKind::Vgm, std::memory_order_relaxed);
    installRender(vgm_decoder_render);
    setModInfo(env, modInfo, vgm_decoder_title(), vgm_decoder_system());
    LOG_INFO("loaded by VGM: %s", vgm_decoder_system());
    return 0;
  }

  if (gme_decoder_load(data.data(), data.size())) {
    g_active.store(EngineKind::Gme, std::memory_order_relaxed);
    installRender(gme_decoder_render);
    setModInfo(env, modInfo, gme_decoder_title(), gme_decoder_system());
    LOG_INFO("loaded by GME: %s", gme_decoder_system());
    return 0;
  }

  // AdPlug last, because its answer costs the most: it has no magic number to
  // check, so finding out whether a file is one of its forty-odd formats means
  // handing the bytes to each of its players in turn until one accepts them.
  const std::string name = nativeString(env, filename);
  if (adplug_decoder_claims_extension(name.c_str()) &&
      adplug_decoder_load(data.data(), data.size(), name.c_str(), 0)) {
    g_active.store(EngineKind::AdPlug, std::memory_order_relaxed);
    installRender(adplug_decoder_render);
    setModInfo(env, modInfo, adplug_decoder_title(), adplug_decoder_type());
    LOG_INFO("loaded by AdPlug: %s", adplug_decoder_type());
    return 0;
  }

  g_active.store(EngineKind::None, std::memory_order_relaxed);
  return openmptResult;
}

jboolean engine_testFd(JNIEnv* env, jint fd, jstring filename, jobject modInfo) {
  // Deliberately does not touch g_active or load anything: this runs over
  // every file in a directory during indexing, while something else is
  // playing. It only answers "would one of us take this".
  //
  // Read once and closed here. The descriptor was detached from its
  // ParcelFileDescriptor on the way in, so nobody else will close it - and it
  // was never closed, one leaked for every file indexed, until a big enough
  // library ran the process out of descriptors and every file after that
  // failed to open and was filed away as not a module. It was also read
  // twice, once by libopenmpt and once for everyone else.
  //
  // Recorded music is told by its first bytes and asked what it is from where
  // it lies - its tags, a few kilobytes of a file of megabytes.
  const std::string soundName = nativeString(env, filename);
  uint8_t head[4096];
  const ssize_t headSize = pread(fd, head, sizeof(head), 0);
  if (headSize > 0 && ffmpeg_decoder_claims(head, static_cast<size_t>(headSize), soundName.c_str())) {
    std::string title;
    std::string type;
    if (ffmpeg_decoder_test_fd(fd, soundName.c_str(), title, type)) {
      close(fd);
      setModInfo(env, modInfo, title.c_str(), type.c_str());
      return JNI_TRUE;
    }
  }
  const std::vector<uint8_t> data = readWhole(fd);
  close(fd);
  if (data.empty()) return JNI_FALSE;
  if (headSize < 0 && ffmpeg_decoder_claims(data.data(), std::min<size_t>(data.size(), 4096), soundName.c_str())) {
    std::string title;
    std::string type;
    if (ffmpeg_decoder_test_memory(data.data(), data.size(), soundName.c_str(), title, type)) {
      setModInfo(env, modInfo, title.c_str(), type.c_str());
      return JNI_TRUE;
    }
  }
  if (openmpt_testData(env, data.data(), data.size(), modInfo) == JNI_TRUE) return JNI_TRUE;

  if (hvl_decoder_probe(data.data(), data.size())) {
    setModInfo(env, modInfo, "", "AHX / Hively");
    return JNI_TRUE;
  }
  if (furnace_decoder_probe(data.data(), data.size())) {
    setModInfo(env, modInfo, "", "Furnace");
    return JNI_TRUE;
  }
  if (sid_decoder_probe(data.data(), data.size())) {
    setModInfo(env, modInfo, "", "C64 SID");
    return JNI_TRUE;
  }
  if (cm_decoder_probe(data.data(), data.size())) {
    setModInfo(env, modInfo, "", "CustomMade");
    return JNI_TRUE;
  }
  if (gsf_decoder_probe(data.data(), data.size()) && !psf::isLibraryName(nativeString(env, filename))) {
    setModInfo(env, modInfo, "", "GSF");
    return JNI_TRUE;
  }
  if (twosf_decoder_probe(data.data(), data.size()) && !psf::isLibraryName(nativeString(env, filename))) {
    setModInfo(env, modInfo, "", "2SF");
    return JNI_TRUE;
  }
  if (usf_decoder_probe(data.data(), data.size()) && !psf::isLibraryName(nativeString(env, filename))) {
    setModInfo(env, modInfo, "", "USF");
    return JNI_TRUE;
  }
  if (psf1_decoder_probe(data.data(), data.size()) && !psf::isLibraryName(nativeString(env, filename))) {
    setModInfo(env, modInfo, "", "PSF");
    return JNI_TRUE;
  }
  if (const char* type = dsp_decoder_probe(data.data(), data.size(), nativeString(env, filename).c_str())) {
    setModInfo(env, modInfo, "", type);
    return JNI_TRUE;
  }
  if (vgm_decoder_probe(data.data(), data.size())) {
    setModInfo(env, modInfo, "", "VGM");
    return JNI_TRUE;
  }
  const char* format = gme_decoder_probe(data.data(), data.size());
  if (format && *format) {
    setModInfo(env, modInfo, "", format);
    return JNI_TRUE;
  }
  const std::string name = nativeString(env, filename);
  if (adplug_decoder_probe(data.data(), data.size(), name.c_str())) {
    setModInfo(env, modInfo, "", "AdLib");
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jint engine_releaseModule(JNIEnv* env) {
  // No jump back to a loop's start is left to be made in what is let go of here.
  forgetJump();
  jint result = 0;
  switch (engine_kind()) {
    case EngineKind::Gme:
      gme_decoder_release();
      break;
    case EngineKind::Vgm:
      vgm_decoder_release();
      break;
    case EngineKind::Hvl:
      hvl_decoder_release();
      break;
    case EngineKind::AdPlug:
      adplug_decoder_release();
      break;
    case EngineKind::Furnace:
      furnace_decoder_release();
      break;
    case EngineKind::Sid:
      sid_decoder_release();
      break;
    case EngineKind::CustomMade:
      cm_decoder_release();
      break;
    case EngineKind::Gsf:
      gsf_decoder_release();
      break;
    case EngineKind::TwoSf:
      twosf_decoder_release();
      break;
    case EngineKind::Usf:
      usf_decoder_release();
      break;
    case EngineKind::Psf1:
      psf1_decoder_release();
      break;
    case EngineKind::Dsp:
      dsp_decoder_release();
      break;
    case EngineKind::Ffmpeg:
      ffmpeg_decoder_release();
      break;
    default:
      result = openmpt_releaseModule(env);
      break;
  }
  g_active.store(EngineKind::None, std::memory_order_relaxed);
  return result;
}

jint engine_restartModule(JNIEnv* env) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      gme_decoder_seek_ms(0);
      return 0;
    case EngineKind::Vgm:
      vgm_decoder_seek_ms(0);
      return 0;
    case EngineKind::Hvl:
      hvl_decoder_seek_ms(0);
      return 0;
    case EngineKind::AdPlug:
      adplug_decoder_seek_ms(0);
      return 0;
    case EngineKind::Furnace:
      furnace_decoder_seek_ms(0);
      return 0;
    case EngineKind::Sid:
      sid_decoder_seek_ms(0);
      return 0;
    case EngineKind::CustomMade:
      cm_decoder_seek_ms(0);
      return 0;
    case EngineKind::Gsf:
      gsf_decoder_seek_ms(0);
      return 0;
    case EngineKind::TwoSf:
      twosf_decoder_seek_ms(0);
      return 0;
    case EngineKind::Usf:
      usf_decoder_seek_ms(0);
      return 0;
    case EngineKind::Psf1:
      psf1_decoder_seek_ms(0);
      return 0;
    case EngineKind::Dsp:
      dsp_decoder_seek_ms(0);
      return 0;
    case EngineKind::Ffmpeg:
      ffmpeg_decoder_seek_ms(0);
      return 0;
    default:
      return openmpt_restartModule(env);
  }
}

jint engine_stopModule(JNIEnv* env) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      gme_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Vgm:
      vgm_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Hvl:
      hvl_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::AdPlug:
      adplug_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Furnace:
      furnace_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Sid:
      sid_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::CustomMade:
      cm_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Gsf:
      gsf_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::TwoSf:
      twosf_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Usf:
      usf_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Psf1:
      psf1_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Dsp:
      dsp_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    case EngineKind::Ffmpeg:
      ffmpeg_decoder_release();
      g_active.store(EngineKind::None, std::memory_order_relaxed);
      return 0;
    default:
      return openmpt_stopModule(env);
  }
}

namespace {

/** Whether the loaded decoder says its tune has ended. */
jboolean saysItHasEnded(JNIEnv* env) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      return gme_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Vgm:
      return vgm_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Hvl:
      return hvl_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::AdPlug:
      return adplug_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Furnace:
      return furnace_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Sid:
      return sid_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::CustomMade:
      return cm_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Gsf:
      return gsf_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::TwoSf:
      return twosf_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Usf:
      return usf_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Psf1:
      return psf1_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Dsp:
      return dsp_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Ffmpeg:
      return ffmpeg_decoder_ended() ? JNI_TRUE : JNI_FALSE;
    default:
      return openmpt_hasEnded(env);
  }
}

}  // namespace

jboolean engine_hasEnded(JNIEnv* env) {
  if (saysItHasEnded(env) == JNI_FALSE) return JNI_FALSE;
  // Not while it is on its way somewhere. A tune that reached its end inside
  // a loop keeps saying so until it is back at the loop's start, and that
  // was taken for the end of the track: the next one was loaded in the
  // middle of the way back. Asked in this order - the decoder first, then
  // the buffer in the making, then the reruns - each is raised before the
  // one before it is lowered, so an end seen here is one that was let stand.
  if (g_loopPass.load(std::memory_order_acquire) || rerunUnderWay()) return JNI_FALSE;
  return JNI_TRUE;
}

void engine_setSampleRate(jint rate) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      gme_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Vgm:
      vgm_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Hvl:
      hvl_decoder_set_sample_rate(rate);
      break;
    case EngineKind::AdPlug:
      adplug_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Furnace:
      furnace_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Sid:
      sid_decoder_set_sample_rate(rate);
      break;
    case EngineKind::CustomMade:
      cm_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Gsf:
      gsf_decoder_set_sample_rate(rate);
      break;
    case EngineKind::TwoSf:
      twosf_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Usf:
      usf_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Psf1:
      psf1_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Dsp:
      dsp_decoder_set_sample_rate(rate);
      break;
    case EngineKind::Ffmpeg:
      ffmpeg_decoder_set_sample_rate(rate);
      break;
    default:
      break;
  }
}

void engine_setHivelyStereo(JNIEnv* env, jint percent) {
  hvl_decoder_set_stereo_separation(percent);
}

void engine_setHivelyGain(JNIEnv* env, jint percent) { hvl_decoder_set_gain(percent); }

void engine_setAdPlugGain(JNIEnv* env, jint percent) { adplug_decoder_set_gain(percent); }

void engine_setAdPlugCore(JNIEnv* env, jint core) { adplug_decoder_set_core(core); }

void engine_setGmeDefaultLength(JNIEnv* env, jint seconds) {
  gme_decoder_set_default_length_ms(seconds * 1000);
  // A SID has no length of its own either, and plays for the same; so does a
  // GSF, a 2SF, a USF or a PSF whose tags give none.
  sid_decoder_set_default_length_ms(seconds * 1000);
  gsf_decoder_set_default_length_ms(seconds * 1000);
  twosf_decoder_set_default_length_ms(seconds * 1000);
  usf_decoder_set_default_length_ms(seconds * 1000);
  psf1_decoder_set_default_length_ms(seconds * 1000);
}

void engine_setGsfStereo(JNIEnv* env, jint percent) { gsf_decoder_set_stereo(percent); }

void engine_setGsfLowpass(JNIEnv* env, jboolean on) { gsf_decoder_set_lowpass(on == JNI_TRUE); }

void engine_setGsfPan(JNIEnv* env, jint channel, jint value) { gsf_decoder_set_pan(channel, value); }

void engine_setTwoSfStereo(JNIEnv* env, jint percent) { twosf_decoder_set_stereo(percent); }

void engine_setTwoSfInterpolation(JNIEnv* env, jint mode) { twosf_decoder_set_interpolation(mode); }

void engine_setTwoSfPan(JNIEnv* env, jint channel, jint value) { twosf_decoder_set_pan(channel, value); }

void engine_setUsfStereo(JNIEnv* env, jint percent) { usf_decoder_set_stereo(percent); }

void engine_setUsfHle(JNIEnv* env, jboolean on) { usf_decoder_set_hle(on == JNI_TRUE); }

void engine_setPsfStereo(JNIEnv* env, jint percent) { psf1_decoder_set_stereo(percent); }

void engine_setPsfReverb(JNIEnv* env, jboolean on) { psf1_decoder_set_reverb(on == JNI_TRUE); }

void engine_setDspStereo(JNIEnv* env, jint percent) { dsp_decoder_set_stereo(percent); }

void engine_setAudioStereo(JNIEnv* env, jint percent) { ffmpeg_decoder_set_stereo(percent); }

jstring engine_getLyrics(JNIEnv* env) {
  if (engine_kind() != EngineKind::Ffmpeg) return nullptr;
  const std::string words = ffmpeg_decoder_lyrics();
  if (words.empty()) return nullptr;
  // Not NewStringUTF: tags are whatever their writer put there, and bytes
  // that are not its modified UTF-8 abort the process in a checked runtime.
  const size_t length = words.size();
  jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
  if (!bytes) return nullptr;
  env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(words.data()));
  jclass stringClass = env->FindClass("java/lang/String");
  jmethodID fromBytes = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
  jstring charset = env->NewStringUTF("UTF-8");
  auto text = static_cast<jstring>(env->NewObject(stringClass, fromBytes, bytes, charset));
  env->DeleteLocalRef(bytes);
  env->DeleteLocalRef(charset);
  env->DeleteLocalRef(stringClass);
  return text;
}

void engine_setGmeVolume(JNIEnv* env, jint console, jint percent) {
  gme_decoder_set_console_volume(console, percent);
}

void engine_setGmeStereo(JNIEnv* env, jint console, jint percent) {
  gme_decoder_set_console_stereo(console, percent);
}

void engine_setGmePan(JNIEnv* env, jint console, jint voice, jint pan) {
  gme_decoder_set_console_pan(console, voice, pan);
}

void engine_setGmeEcho(JNIEnv* env, jboolean enabled) {
  gme_decoder_set_snes_echo(enabled == JNI_TRUE);
}

void engine_setGmeInterpolation(JNIEnv* env, jint mode) {
  gme_decoder_set_snes_interpolation(mode);
}

namespace {

/** One decoder, by the id the app knows it by, and the extensions it opens. */
struct FormatGroup {
  const char* id;
  std::vector<std::string> extensions;
};

/**
 * Every decoder's extensions, in the order engine_loadFd offers a file to
 * them - which is also the order that settles an extension two of them
 * share, since the first to recognise the file's content plays it.
 */
std::vector<FormatGroup> formatGroups() {
  std::vector<FormatGroup> groups;
  // Recorded music, told by its first bytes before anything else is asked.
  // Not .mp4: what that name usually holds is a film.
  groups.push_back({"ffmpeg", {"mp3", "mp2", "flac", "wav", "aiff", "aif", "m4a", "m4b", "aac", "alac", "ogg", "oga",
                               "opus", "mpc", "mp+", "mpp", "wv", "w64"}});
  groups.push_back({"libopenmpt", openmpt_formatList()});
  groups.push_back({"hively", {"ahx", "thx", "hvl"}});
  groups.push_back({"furnace", {"fur", "dmf"}});
  groups.push_back({"libsidplayfp", {"sid", "psid", "rsid"}});
  groups.push_back({"custommade", {"cm", "rk", "rkb"}});
  groups.push_back({"mgba", {"gsf", "minigsf"}});
  groups.push_back({"vio2sf", {"2sf", "mini2sf"}});
  groups.push_back({"lazyusf2", {"usf", "miniusf"}});
  groups.push_back({"aopsf", {"psf", "minipsf"}});
  // A standard .dsp also comes as .wav and .dat, which are left off: what
  // those names usually hold is not this.
  groups.push_back({"dsp", {"dsp", "adx", "sfa", "hps", "ast", "afc", "stx", "adp", "dtk", "trk", "stm", "lstm", "idsp",
                            "mdsp", "mpdsp", "ste", "mss", "gcm", "ddsp", "itl", "ds2", "iadp", "ymf", "cfn", "caf", "gcub",
                            "str", "pdt", "rsm", "adpcm", "lwav", "ldat"}});
  // A .vgz is a gzipped .vgm and has no type of its own; the decoder expands
  // one before anything looks at it, so it belongs on the list all the same.
  // S98, DRO and GYM are libvgm's other three players - see decoder-vgm.cpp.
  groups.push_back({"libvgm", {"vgm", "vgz", "s98", "dro", "gym"}});

  FormatGroup gme{"gme", {}};
  for (gme_type_t const* t = gme_type_list(); *t; t++) {
    const char* ext = gme_type_extension(*t);
    if (ext && *ext) gme.extensions.emplace_back(lowercased(ext));
  }
  groups.push_back(std::move(gme));

  FormatGroup adplug{"adplug", {}};
  adplug_decoder_extensions(adplug.extensions);
  groups.push_back(std::move(adplug));

  for (FormatGroup& group : groups) {
    std::sort(group.extensions.begin(), group.extensions.end());
    group.extensions.erase(std::unique(group.extensions.begin(), group.extensions.end()),
                           group.extensions.end());
  }
  return groups;
}

jobjectArray toJStringArray(JNIEnv* env, const std::vector<std::string>& items) {
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(items.size()), stringClass, nullptr);
  for (size_t i = 0; i < items.size(); i++) {
    jstring s = newJString(env, items[i].c_str());
    env->SetObjectArrayElement(result, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  env->DeleteLocalRef(stringClass);
  return result;
}

}  // namespace

jobjectArray engine_getFormats(JNIEnv* env) {
  // The union, not libopenmpt's list. The formats screen was showing what one
  // decoder could open and calling it what the app supports.
  std::vector<std::string> items;
  for (const FormatGroup& group : formatGroups()) {
    items.insert(items.end(), group.extensions.begin(), group.extensions.end());
  }
  std::sort(items.begin(), items.end());
  items.erase(std::unique(items.begin(), items.end()), items.end());
  return toJStringArray(env, items);
}

jobjectArray engine_getFormatGroups(JNIEnv* env) {
  const std::vector<FormatGroup> groups = formatGroups();
  jclass arrayClass = env->FindClass("[Ljava/lang/String;");
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(groups.size()), arrayClass, nullptr);
  for (size_t i = 0; i < groups.size(); i++) {
    std::vector<std::string> row;
    row.reserve(groups[i].extensions.size() + 1);
    row.emplace_back(groups[i].id);
    row.insert(row.end(), groups[i].extensions.begin(), groups[i].extensions.end());
    jobjectArray item = toJStringArray(env, row);
    env->SetObjectArrayElement(result, static_cast<jsize>(i), item);
    env->DeleteLocalRef(item);
  }
  env->DeleteLocalRef(arrayClass);
  return result;
}

jboolean engine_init(JNIEnv* env, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  const int actual = open_audio(rate, ms, mode, channels, api, flags);
  if (actual < 0) return JNI_FALSE;

  // Shared field caches: every decoder fills the same Java objects.
  initAudioStatsFields(env);
  initFrameInfoFields(env);
  initModInfoFields(env);
  initModVarsFields(env);
  initSequenceFields(env);

  openmpt_notifyInit(actual);
  // Nothing is loaded, so nothing owns the render yet. Leaving a decoder's
  // callback installed here is how a stream ends up being fed by whichever
  // one happened to be compiled first.
  reset_render_callback();
  g_active.store(EngineKind::None, std::memory_order_relaxed);
  LOG_INFO("audio session open at %d Hz", actual);
  return JNI_TRUE;
}

jint engine_deinit(JNIEnv* env) {
  engine_releaseModule(env);
  openmpt_shutdown();
  set_playing(0);
  close_audio();
  return 0;
}

jint engine_endPlayer(JNIEnv* env) {
  set_playing(0);
  if (engine_kind() == EngineKind::OpenMpt) openmpt_notifyStop();
  return 0;
}

jint engine_playAudio(JNIEnv* env) { return play_audio(); }

jint engine_startPlayer(JNIEnv* env, jint rate, jint format) {
  // The rate the stream actually opened at, not the one that was asked for.
  // These two are rarely the same - the device runs at what it runs at - and
  // the emulators fix their rate when they are built, so handing them the
  // wrong one had them playing at the wrong speed to fill a buffer that
  // wanted another. The argument is kept because Player.startPlayer passes
  // one; it is only used if the stream cannot say.
  const jint actual = get_stream_rate();
  if (actual > 0) rate = actual;

  // Ready the decoder that actually owns playback, point the stream at it,
  // and only then start. Nothing here knows or cares which decoder that is
  // beyond asking it to get ready.
  switch (engine_kind()) {
    case EngineKind::OpenMpt:
      openmpt_prepareStart(rate);
      installRender(renderOpenMpt);
      break;
    case EngineKind::Gme:
      gme_decoder_prepare_start(rate);
      installRender(gme_decoder_render);
      break;
    case EngineKind::Vgm:
      vgm_decoder_prepare_start(rate);
      installRender(vgm_decoder_render);
      break;
    case EngineKind::Hvl:
      hvl_decoder_prepare_start(rate);
      installRender(hvl_decoder_render);
      break;
    case EngineKind::AdPlug:
      adplug_decoder_prepare_start(rate);
      installRender(adplug_decoder_render);
      break;
    case EngineKind::Furnace:
      furnace_decoder_prepare_start(rate);
      installRender(furnace_decoder_render);
      break;
    case EngineKind::Sid:
      sid_decoder_prepare_start(rate);
      installRender(sid_decoder_render);
      break;
    case EngineKind::CustomMade:
      cm_decoder_prepare_start(rate);
      installRender(cm_decoder_render);
      break;
    case EngineKind::Gsf:
      gsf_decoder_prepare_start(rate);
      installRender(gsf_decoder_render);
      break;
    case EngineKind::TwoSf:
      twosf_decoder_prepare_start(rate);
      installRender(twosf_decoder_render);
      break;
    case EngineKind::Usf:
      usf_decoder_prepare_start(rate);
      installRender(usf_decoder_render);
      break;
    case EngineKind::Psf1:
      psf1_decoder_prepare_start(rate);
      installRender(psf1_decoder_render);
      break;
    case EngineKind::Dsp:
      dsp_decoder_prepare_start(rate);
      installRender(dsp_decoder_render);
      break;
    case EngineKind::Ffmpeg:
      ffmpeg_decoder_prepare_start(rate);
      installRender(ffmpeg_decoder_render);
      break;
    case EngineKind::None:
      LOG_WARN("startPlayer with nothing loaded");
      reset_render_callback();
      return -1;
  }
  set_playing(1);
  return 0;
}

// ---------------------------------------------------------------------------
// What the views read
// ---------------------------------------------------------------------------

void engine_getModVars(JNIEnv* env, jobject modVars) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      gme_fillModVars(env, modVars);
      return;
    case EngineKind::Vgm:
      vgm_fillModVars(env, modVars);
      return;
    case EngineKind::Hvl:
      hvl_fillModVars(env, modVars);
      return;
    case EngineKind::AdPlug:
      adplug_fillModVars(env, modVars);
      return;
    case EngineKind::Furnace:
      furnace_fillModVars(env, modVars);
      return;
    case EngineKind::Sid:
      sid_fillModVars(env, modVars);
      return;
    case EngineKind::CustomMade:
      cm_fillModVars(env, modVars);
      return;
    case EngineKind::Gsf:
      gsf_fillModVars(env, modVars);
      return;
    case EngineKind::TwoSf:
      twosf_fillModVars(env, modVars);
      return;
    case EngineKind::Usf:
      usf_fillModVars(env, modVars);
      return;
    case EngineKind::Psf1:
      psf1_fillModVars(env, modVars);
      return;
    case EngineKind::Dsp:
      dsp_fillModVars(env, modVars);
      return;
    case EngineKind::Ffmpeg:
      ffmpeg_fillModVars(env, modVars);
      return;
    default:
      openmpt_getModVars(env, modVars);
  }
}

void engine_getFrameInfo(JNIEnv* env, jobject frameInfo) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      gme_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Vgm:
      vgm_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Hvl:
      hvl_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::AdPlug:
      adplug_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Furnace:
      furnace_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Sid:
      sid_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::CustomMade:
      cm_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Gsf:
      gsf_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::TwoSf:
      twosf_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Usf:
      usf_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Psf1:
      psf1_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Dsp:
      dsp_fillFrameInfo(env, frameInfo);
      return;
    case EngineKind::Ffmpeg:
      ffmpeg_fillFrameInfo(env, frameInfo);
      return;
    default:
      openmpt_getFrameInfo(env, frameInfo);
  }
}

jboolean engine_readVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      return gme_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Vgm:
      return vgm_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Hvl:
      return hvl_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::AdPlug:
      return adplug_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Furnace:
      return furnace_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Sid:
      return sid_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::CustomMade:
      return cm_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Gsf:
      return gsf_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::TwoSf:
      return twosf_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Usf:
      return usf_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Psf1:
      return psf1_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Dsp:
      return dsp_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    case EngineKind::Ffmpeg:
      return ffmpeg_fillVisualState(env, scalars, chData, scopes, scopeWidth);
    default:
      return openmpt_readVisualState(env, scalars, chData, scopes, scopeWidth);
  }
}

void engine_getPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm) {
  if (!engine_has_patterns()) return;
  if (engine_kind() == EngineKind::Hvl) {
    hvl_fillPatternData(
        env, pat, numRows, notes, instruments, fxType, fxParm, fx2Type, fx2Parm);
    return;
  }
  if (engine_kind() == EngineKind::AdPlug) {
    adplug_fillPatternData(
        env, pat, numRows, notes, instruments, fxType, fxParm, fx2Type, fx2Parm);
    return;
  }
  if (engine_kind() == EngineKind::Furnace) {
    furnace_fillPatternData(
        env, pat, numRows, notes, instruments, fxType, fxParm, fx2Type, fx2Parm);
    return;
  }
  openmpt_getPatternData(
      env, pat, numRows, notes, instruments, fxType, fxParm, fx2Type, fx2Parm);
}

jint engine_getPatternRows(JNIEnv* env, jint pat) {
  if (!engine_has_patterns()) return 0;
  if (engine_kind() == EngineKind::Hvl) return hvl_decoder_pattern_rows();
  if (engine_kind() == EngineKind::AdPlug) return adplug_decoder_pattern_rows();
  if (engine_kind() == EngineKind::Furnace) return furnace_decoder_pattern_rows();
  return openmpt_getPatternRows(env, pat);
}

// ---------------------------------------------------------------------------
// Transport and per-module settings
// ---------------------------------------------------------------------------

void engine_setSeekWanted(jint ticket) { g_seekWanted.store(ticket, std::memory_order_release); }

bool engine_seekOvertaken() {
  const int inHand = g_seekInHand.load(std::memory_order_acquire);
  return inHand != kNoSeek && inHand != g_seekWanted.load(std::memory_order_acquire);
}

jint engine_seek(JNIEnv* env, jint time, jint ticket) {
  g_seekInHand.store(ticket, std::memory_order_release);
  // Faded in rather than cut to, because a seek lands on a sample unrelated to
  // the one before it and the step between them is a click. See
  // ramp_output_in; ten milliseconds is under a frame of video.
  ramp_output_in(10);
  const EngineKind kind = engine_kind();
  // The listener's seek is the later word: a jump back to the loop's start
  // asked for and not begun is dropped.
  g_jumper.wanted.store(-1, std::memory_order_release);
  // Nor is a jump the seek itself made necessary - one past the loop's end -
  // a jump that failed to reach the start: three such seeks in a row had the
  // loop taken for one out of reach, and switched off.
  g_jumpsUnheard.store(0, std::memory_order_relaxed);
  // The stream is given silence for as long as the tune takes to be run to
  // where it is going, rather than its callback kept waiting - see g_reruns.
  const bool rerun = seeksByRerunning(kind);
  // A seek to where the loop starts - the one a loop is begun with - is the
  // way back the loop will take at every pass, and what it takes is what
  // that will, so long as it is a run from the top: a seek forwards only
  // runs on from where the tune was, and one to where it already is does
  // nothing at all - both say too little. Behind by a millisecond at least,
  // since a position can read a hair past the point it was just put at.
  const bool timed = rerun && jumpsByRerunning(kind) && time == loopStartForDecoders();
  const uint32_t timingsOf = g_loopTimingsOf.load(std::memory_order_acquire);
  const double from = timed ? renderPositionMs() : 0.0;
  if (rerun) beginRerun();
  const auto began = std::chrono::steady_clock::now();
  jint result = 0;
  switch (kind) {
    case EngineKind::Gme: gme_decoder_seek_ms(time); break;
    case EngineKind::Vgm: vgm_decoder_seek_ms(time); break;
    case EngineKind::Hvl: hvl_decoder_seek_ms(time); break;
    case EngineKind::AdPlug: adplug_decoder_seek_ms(time); break;
    case EngineKind::Furnace: furnace_decoder_seek_ms(time); break;
    case EngineKind::Sid: sid_decoder_seek_ms(time); break;
    case EngineKind::CustomMade: cm_decoder_seek_ms(time); break;
    case EngineKind::Gsf: gsf_decoder_seek_ms(time); break;
    case EngineKind::TwoSf: twosf_decoder_seek_ms(time); break;
    case EngineKind::Usf: usf_decoder_seek_ms(time); break;
    case EngineKind::Psf1: psf1_decoder_seek_ms(time); break;
    case EngineKind::Dsp: dsp_decoder_seek_ms(time); break;
    case EngineKind::Ffmpeg: ffmpeg_decoder_seek_ms(time); break;
    default: result = openmpt_seek(env, time); break;
  }
  // The fade asked for above was for a seek that lands at once; this one
  // was silent until the file's own thread had it there.
  if (kind == EngineKind::Ffmpeg) ramp_output_in(10);
  if (rerun) {
    // One given up on the way took less than the way takes.
    if (timed && from - static_cast<double>(time) >= 1.0 && !engine_seekOvertaken() &&
        g_loopTimingsOf.load(std::memory_order_acquire) == timingsOf) {
      const int64_t took =
          std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - began).count();
      g_jumpCostNs.store(std::max<int64_t>(1, took), std::memory_order_relaxed);
    }
    endRerun(true);
  }
  g_seekInHand.store(kNoSeek, std::memory_order_release);
  return result;
}

jint engine_time(JNIEnv* env) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      return gme_decoder_duration_ms();
    case EngineKind::Vgm:
      return vgm_decoder_duration_ms();
    case EngineKind::Hvl:
      return hvl_decoder_duration_ms();
    case EngineKind::AdPlug:
      return adplug_decoder_duration_ms();
    case EngineKind::Furnace:
      return furnace_decoder_duration_ms();
    case EngineKind::Sid:
      return sid_decoder_duration_ms();
    case EngineKind::CustomMade:
      return cm_decoder_duration_ms();
    case EngineKind::Gsf:
      return gsf_decoder_duration_ms();
    case EngineKind::TwoSf:
      return twosf_decoder_duration_ms();
    case EngineKind::Usf:
      return usf_decoder_duration_ms();
    case EngineKind::Psf1:
      return psf1_decoder_duration_ms();
    case EngineKind::Dsp:
      return dsp_decoder_duration_ms();
    case EngineKind::Ffmpeg:
      return ffmpeg_decoder_duration_ms();
    default:
      return openmpt_time(env);
  }
}
// Order navigation. Only a tracker has orders to move between; for the chip
// emulators there is nothing to move to, and saying so is better than calling
// into libopenmpt to have it say no on their behalf.
jint engine_setPosition(JNIEnv* env, jint n) {
  return openMptOwns() ? openmpt_setPosition(env, n) : JNI_FALSE;
}
jint engine_nextPosition(JNIEnv* env) {
  return openMptOwns() ? openmpt_nextPosition(env) : JNI_FALSE;
}
jint engine_prevPosition(JNIEnv* env) {
  return openMptOwns() ? openmpt_prevPosition(env) : JNI_FALSE;
}
jboolean engine_setSequence(JNIEnv* env, jint seq) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      return gme_decoder_start_track(seq) ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Hvl:
      return hvl_decoder_start_subsong(seq) ? JNI_TRUE : JNI_FALSE;
    case EngineKind::AdPlug:
      return adplug_decoder_start_subsong(seq) ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Furnace:
      return furnace_decoder_start_subsong(seq) ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Sid:
      return sid_decoder_start_subsong(seq) ? JNI_TRUE : JNI_FALSE;
    case EngineKind::CustomMade:
      return cm_decoder_start_subsong(seq) ? JNI_TRUE : JNI_FALSE;
    case EngineKind::Gsf:
      if (seq != 0) return JNI_FALSE;
      gsf_decoder_seek_ms(0);
      return JNI_TRUE;
    case EngineKind::TwoSf:
      if (seq != 0) return JNI_FALSE;
      twosf_decoder_seek_ms(0);
      return JNI_TRUE;
    case EngineKind::Usf:
      if (seq != 0) return JNI_FALSE;
      usf_decoder_seek_ms(0);
      return JNI_TRUE;
    case EngineKind::Psf1:
      if (seq != 0) return JNI_FALSE;
      psf1_decoder_seek_ms(0);
      return JNI_TRUE;
    case EngineKind::Dsp:
      if (seq != 0) return JNI_FALSE;
      dsp_decoder_seek_ms(0);
      return JNI_TRUE;
    case EngineKind::Ffmpeg:
      if (seq != 0) return JNI_FALSE;
      ffmpeg_decoder_seek_ms(0);
      return JNI_TRUE;
    default:
      return openmpt_setSequence(env, seq);
  }
}
jlong engine_resetMutes(JNIEnv* env) {
  // Only a module carries mutes of its own; for everything else, none.
  if (openMptOwns()) return openmpt_resetMutes(env);
  for (int chn = 0; chn < 64; chn++) engine_mute(env, chn, 0);
  return 0;
}

jint engine_mute(JNIEnv* env, jint chn, jint status) {
  // -1 asks rather than sets, which is how the views read back the state.
  switch (engine_kind()) {
    case EngineKind::Gme:
      if (status < 0) return gme_decoder_muted(chn);
      gme_decoder_mute(chn, status);
      return status;
    case EngineKind::Vgm:
      if (status < 0) return vgm_decoder_muted(chn);
      vgm_decoder_mute(chn, status);
      return status;
    case EngineKind::Hvl:
      if (status < 0) return hvl_decoder_muted(chn);
      hvl_decoder_mute(chn, status);
      return status;
    case EngineKind::AdPlug:
      if (status < 0) return adplug_decoder_muted(chn);
      adplug_decoder_mute(chn, status);
      return status;
    case EngineKind::Furnace:
      if (status < 0) return furnace_decoder_muted(chn);
      furnace_decoder_mute(chn, status);
      return status;
    case EngineKind::Sid:
      if (status < 0) return sid_decoder_muted(chn);
      sid_decoder_mute(chn, status);
      return status;
    case EngineKind::CustomMade:
      if (status < 0) return cm_decoder_muted(chn);
      cm_decoder_mute(chn, status);
      return status;
    case EngineKind::Gsf:
      if (status < 0) return gsf_decoder_muted(chn);
      gsf_decoder_mute(chn, status);
      return status;
    case EngineKind::TwoSf:
      if (status < 0) return twosf_decoder_muted(chn);
      twosf_decoder_mute(chn, status);
      return status;
    case EngineKind::Usf:
      if (status < 0) return usf_decoder_muted(chn);
      usf_decoder_mute(chn, status);
      return status;
    case EngineKind::Psf1:
      if (status < 0) return psf1_decoder_muted(chn);
      psf1_decoder_mute(chn, status);
      return status;
    case EngineKind::Dsp:
      if (status < 0) return dsp_decoder_muted(chn);
      dsp_decoder_mute(chn, status);
      return status;
    case EngineKind::Ffmpeg:
      if (status < 0) return ffmpeg_decoder_muted(chn);
      ffmpeg_decoder_mute(chn, status);
      return status;
    default:
      return openmpt_mute(env, chn, status);
  }
}
void engine_setLoopMode(JNIEnv* env, jboolean loop) {
  switch (engine_kind()) {
    case EngineKind::Gme:
      gme_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Vgm:
      vgm_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Hvl:
      hvl_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::AdPlug:
      adplug_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Furnace:
      furnace_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Sid:
      sid_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::CustomMade:
      cm_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Gsf:
      gsf_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::TwoSf:
      twosf_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Usf:
      usf_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Psf1:
      psf1_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Dsp:
      dsp_decoder_set_loop(loop == JNI_TRUE);
      return;
    case EngineKind::Ffmpeg:
      ffmpeg_decoder_set_loop(loop == JNI_TRUE);
      return;
    default:
      openmpt_setLoopMode(env, loop);
  }
}
/**
 * Pitch, to everyone who can honour it.
 *
 * libopenmpt resamples what it plays, the Hively replayer reads a wavetable
 * at a computed step, and the OPL is told a frequency outright - so all three
 * can be asked for another pitch without touching the tempo. GME cannot: an
 * emulator runs code that decides its own frequencies, and the only way to
 * shift those is to lie about the clock, which takes the tempo with it - so
 * it is left alone rather than made wrong.
 *
 * Sent to both whatever is loaded, because this is a property of playback
 * rather than of a module: a pitch set while a tracker plays has to be
 * waiting for the next .ahx and the other way round.
 */
void engine_setGlobalPitch(JNIEnv* env, jint n) {
  const double target = std::clamp<int>(n, -12, 12);
  g_pitchTarget.store(target, std::memory_order_relaxed);
  // While something plays, the engine playing it glides there - see
  // glideStep - and the rest are set now, for whatever loads next. With
  // nothing playing, every one is set now.
  const EngineKind playing = get_playing() ? engine_kind() : EngineKind::None;
  if (playing == EngineKind::None) g_pitchNow.store(target, std::memory_order_relaxed);
  for (const EngineKind kind : kShiftable) {
    if (kind != playing) pitchTo(kind, target);
  }
}

/**
 * The speed everything plays at, 10 to 200 percent, leaving the pitch alone.
 * Sent to every engine for the same reason as the pitch.
 */
void engine_setGlobalTempo(JNIEnv* env, jint percent) {
  const double target = std::clamp<int>(percent, 10, 200);
  g_tempoTarget.store(target, std::memory_order_relaxed);
  // As the pitch: the engine playing glides, the rest are set now.
  const EngineKind playing = get_playing() ? engine_kind() : EngineKind::None;
  if (playing == EngineKind::None) g_tempoNow.store(target, std::memory_order_relaxed);
  for (const EngineKind kind : kShiftable) {
    if (kind != playing) tempoTo(kind, target);
  }
}

void engine_setLoopRegion(JNIEnv* env, jint startMs, jint endMs) {
  // Both or neither: an end with no start, or one before it, is no loop.
  const bool valid = startMs >= 0 && endMs > startMs;
  g_loopEndMs.store(valid ? endMs : -1, std::memory_order_relaxed);
  g_loopStartMs.store(valid ? startMs : -1, std::memory_order_relaxed);
  forgetLoopTimings();
  if (valid) startJumper();
  // The Nintendo DS and 64 and the PlayStation get to the start a long way round, and go ahead of it.
  if (engine_kind() == EngineKind::TwoSf) twosf_decoder_expect_loop(loopStartForDecoders());
  if (engine_kind() == EngineKind::Usf) usf_decoder_expect_loop(loopStartForDecoders());
  if (engine_kind() == EngineKind::Psf1) psf1_decoder_expect_loop(loopStartForDecoders());
}

void engine_setVolumeBoost(JNIEnv* env, jint db) { set_output_boost_db(db); }

void engine_setDurationOverride(JNIEnv* env, jint ms) {
  switch (engine_kind()) {
    case EngineKind::Gme: gme_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Hvl: hvl_decoder_set_duration_override_ms(ms); break;
    case EngineKind::AdPlug: adplug_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Furnace: furnace_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Sid: sid_decoder_set_duration_override_ms(ms); break;
    case EngineKind::CustomMade: cm_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Gsf: gsf_decoder_set_duration_override_ms(ms); break;
    case EngineKind::TwoSf: twosf_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Usf: usf_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Psf1: psf1_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Dsp: dsp_decoder_set_duration_override_ms(ms); break;
    case EngineKind::Ffmpeg: ffmpeg_decoder_set_duration_override_ms(ms); break;
    // libopenmpt and libvgm always know how long a file is.
    default: break;
  }
}

void engine_setPlaying(JNIEnv* env, jboolean value) {
  // The stream is the engine's, so the engine gates it. libopenmpt is told
  // only so its own view of whether it is producing stays true.
  if (engine_kind() == EngineKind::OpenMpt) {
    openmpt_setPlaying(env, value);
    return;
  }
  set_playing(value == JNI_TRUE ? 1 : 0);
}

// libopenmpt's own knobs - interpolation, stereo separation, ramping. The
// emulators have none of them, and the settings screen says so.
jint engine_setPlayer(JNIEnv* env, jint parm, jint value) {
  // Master volume is the output's, whoever is feeding it - see
  // set_output_gain. Everything else on this call is a libopenmpt render
  // parameter and means nothing to the others.
  if (parm == kParamMasterVolume) {
    set_output_gain(value);
    return value;
  }
  return openMptOwns() ? openmpt_setPlayer(env, parm, value) : 0;
}
jint engine_getPlayer(JNIEnv* env, jint parm) {
  if (parm == kParamMasterVolume) return get_output_gain();
  return openMptOwns() ? openmpt_getPlayer(env, parm) : 0;
}
jint engine_setResampler(JNIEnv* env, jint mode) {
  return openMptOwns() ? openmpt_setResampler(env, mode) : 0;
}

// ---------------------------------------------------------------------------
// Loudness analysis
// ---------------------------------------------------------------------------

jint engine_analyzeLoudness(JNIEnv* env, jint fd, jstring filename, jint subsong, jint maxSeconds, jint budgetMs,
                            jobject modInfo, jfloatArray out) {
  // The field caches and libopenmpt's rate, as engine_init would have left
  // them: this runs in a process of its own, with no stream to open.
  constexpr int kRate = 48000;
  initAudioStatsFields(env);
  initFrameInfoFields(env);
  initModInfoFields(env);
  initModVarsFields(env);
  initSequenceFields(env);
  openmpt_notifyInit(kRate);

  const jint loaded = engine_loadFd(env, fd, filename, modInfo);
  if (loaded != 0) return loaded;
  // Once through, however the track would otherwise go round.
  engine_setLoopMode(env, JNI_FALSE);
  if (subsong > 0 && engine_setSequence(env, subsong) != JNI_TRUE) return -4;
  // Readies the decoder at the analysis rate. There is no stream here, so the
  // callback it installs is never called; the decoder's own render is, below.
  engine_startPlayer(env, kRate, 0);
  const AudioRenderCallback render = g_engineRender.load(std::memory_order_acquire);
  if (render == nullptr) return -5;

  LoudnessMeter meter;
  meter.reset(kRate);
  constexpr int kChunk = 2048;
  std::vector<int16_t> buffer(kChunk * 2);
  const int64_t limit = static_cast<int64_t>(std::max(1, static_cast<int>(maxSeconds))) * kRate;
  const auto started = std::chrono::steady_clock::now();
  int64_t frames = 0;
  bool ended = false;
  double frame[2];
  while (frames < limit) {
    // Non-zero is the end: libopenmpt says so with -1, the others with 1.
    const int result = render(buffer.data(), kChunk, 2, 2);
    for (int i = 0; i < kChunk; i++) {
      frame[0] = buffer[i * 2] / 32768.0;
      frame[1] = buffer[i * 2 + 1] / 32768.0;
      meter.addFrame(frame, 2);
    }
    frames += kChunk;
    if (result != 0) {
      ended = true;
      break;
    }
    if (budgetMs > 0) {
      const auto spent = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started);
      if (spent.count() >= budgetMs) break;
    }
  }

  float values[4] = {meter.integrated(), static_cast<float>(frames) / kRate, meter.gatedSeconds(), ended ? 1.0f : 0.0f};
  const jsize n = std::min<jsize>(env->GetArrayLength(out), 4);
  env->SetFloatArrayRegion(out, 0, n, values);
  return 0;
}
