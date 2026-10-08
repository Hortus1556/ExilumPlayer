//
// PSF decoder. See decoder-psf.h.
//
#include "decoder-psf.h"
#include "psf-ahead.h"
#include "psf-song.h"
#include "seek-ticket.h"
#include "sinc-resampler.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "aopsf/psx_external.h"

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum PSF";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

constexpr int kVersion = 0x01;
constexpr int kChannels = PSX_VOICES;
/** What a voice can be playing: a recording, the noise generator, or a recording bent by the voice before it. */
const char* const kVoiceKinds[] = {"ADPCM", "Noise", "ADPCM FM"};
constexpr int kVoiceKindCount = 3;
/** The rate the sound unit makes its sound at, whatever the output's. */
constexpr int kEngineRate = 44100;

/**
 * The console's frames kept around the place being interpolated, by their
 * number: a power of two, and many times the kernel, so the console can be
 * run a block at a time.
 */
constexpr int kWindow = 2048;
/** Frames of each voice kept for its trace and its level, by their number. */
constexpr uint32_t kTapFrames = 8192;
/** How often a voice is looked at for its trace and its level. */
constexpr int kLookHz = 16000;
constexpr int kLevelWindowMs = 30;
/**
 * A voice at its loudest alone, as a share of full scale: a recording at the
 * loudest games set a voice reaches half, and they play most at a quarter of
 * that or less.
 */
constexpr float kFullScale = 0.125f;
/** The volume games set a voice to at its loudest: half of what the register holds. */
constexpr int kVoiceVolumeFull = 0x3fff;

/** Frames asked of the console at a time, and handed out at a time. */
constexpr int kBlockFrames = 256;
/**
 * The most a part's program is expanded to: the console's memory and the
 * header before it, with room for a rip that pads it. What does not fit the
 * memory is left out.
 */
constexpr size_t kMaxProgramBytes = 8u << 20;
/** A program's header, which says where the rest goes; and the console's memory, which is where. */
constexpr size_t kHeaderBytes = 2048;
constexpr size_t kMemoryBytes = 2u << 20;
/** In the header: where the program's text goes and how long it is, and the region it was sold in. */
constexpr size_t kTextPlaceAt = 0x18;
constexpr size_t kTextLengthAt = 0x1c;
constexpr size_t kRegionAt = 113;
/** The regions the engine knows the screen's rate of, and the longest of their names. */
const char* const kRegions[] = {"Japan", "Europe", "North America"};
constexpr size_t kRegionBytes = 13;
/** How far before the place a seek starts keeping the voices, for their traces to be there when it lands. */
constexpr int kLeadInMs = 100;

std::atomic<int> g_stereoPercent{100};
std::atomic<bool> g_reverb{true};
std::atomic<int> g_defaultLengthMs{150000};

/**
 * A console, and what has come out of it: the engine's block, which must
 * stay where it is, how many frames it has made, and the last of them - the
 * interpolator's window, which belongs to the console and goes with it when
 * one made ahead is swapped in.
 */
struct Console {
  PSX_STATE* psx = nullptr;
  /** Frames made since the top of the song. */
  int64_t made = 0;
  /** The program has stopped the processor: nothing more comes of it. */
  bool stopped = false;
  std::array<int16_t, static_cast<size_t>(kWindow) * 2> window{};

  Console() = default;
  Console(const Console&) = delete;
  Console& operator=(const Console&) = delete;
  ~Console() {
    if (psx == nullptr) return;
    psf_stop(psx);
    std::free(psx);
  }
};
using Core = std::unique_ptr<Console>;

/** What a console is made from. The program is the state's, which outlives every console. */
struct Setup {
  const std::vector<uint8_t>* program = nullptr;
  int refresh = 0;
  int sampleRate = 48000;
  /** The voices left out of the mix: a console made ahead is run under the same as the one playing. */
  uint32_t muted = 0;
};

struct PsfState {
  std::mutex mutex;
  /** The console playing; null before a file is loaded or when there was no memory for one. */
  Core core;
  /**
   * The program: the libraries' and the file's laid over each other in the
   * console's memory, as one program that fills it, under the first's header.
   */
  std::vector<uint8_t> program;
  /** The screen's rate the tags ask for, 50 or 60; 0 for the one the program's header names. */
  int refresh = 0;

  bool loaded = false;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  std::string title;
  std::string comment;
  psf::SongLength length;

  /** Frames handed out since the top of the song, at the output's rate. */
  int64_t rendered = 0;
  SincKernel kernel;
  /** What the console is asked for, a block at a time, and its voices with it. */
  std::vector<int16_t> block;
  std::vector<int16_t> voices;

  /** Pitch and tempo, which the console cannot change without each other. */
  ShiftStage stage;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;
  std::array<std::vector<float>, 2> out{std::vector<float>(1024), std::vector<float>(1024)};

  std::array<int, kChannels> muted{};
  /** Each voice's own sound, a ring a voice, by the console's frames. */
  std::vector<int16_t> tap;
  /** The voices that have played since the file was loaded: the others are shown as unused. */
  std::array<bool, kChannels> used{};

  /** Where the A-B loop starts, -1 for none: see psf1_decoder_expect_loop. */
  int loopStartMs = -1;
  /** The console made ahead for the next jump; last, so it stops before what it reads goes. */
  psf::Ahead<Core> ahead;

  VisualPublisher visuals;

  static PsfState& instance() {
    static PsfState s;
    return s;
  }
};

uint32_t muteMask(const PsfState& state) {
  uint32_t mask = 0;
  for (int c = 0; c < kChannels; c++) {
    if (state.muted[static_cast<size_t>(c)] != 0) mask |= 1u << c;
  }
  return mask;
}

/** The console's frame an output frame falls on: whole, and how far on to the next. */
void place(int rate, int64_t frame, int64_t& whole, double& fraction) {
  const int64_t scaled = frame * kEngineRate;
  whole = scaled / rate;
  fraction = static_cast<double>(scaled % rate) / rate;
}

/** The last of the console's frames output frame [frame] is made from. */
int64_t lastNeeded(int rate, int64_t frame) {
  int64_t whole = 0;
  double fraction = 0.0;
  place(rate, frame, whole, fraction);
  return whole + SincKernel::kTaps / 2;
}

/**
 * A console from cold at the top of the song; null when there is no memory
 * to make it from, or the engine will not have the program. Starting again
 * is making another: the engine's own restart leaves some of the last run
 * behind.
 */
Core makeCore(const Setup& setup) {
  Core core(new (std::nothrow) Console());
  if (!core) return nullptr;
  // Cleared, which the engine counts on and has no call for.
  core->psx = static_cast<PSX_STATE*>(std::calloc(1, psx_get_state_size(1)));
  if (core->psx == nullptr) {
    LOG_INFO("no memory for the console");
    return nullptr;
  }
  const std::vector<uint8_t>& program = *setup.program;
  if (psf_load_section(core->psx, program.data(), static_cast<uint32>(program.size()), 1) != 0) {
    const char* why = psx_get_last_error(core->psx);
    LOG_INFO("the engine will not have the program: %.200s", why != nullptr ? why : "-");
    return nullptr;
  }
  if (setup.refresh != 0) psx_set_refresh(core->psx, static_cast<uint32>(setup.refresh));
  psf_start(core->psx);
  return core;
}

/**
 * Runs [console] on until it has made frame [last], a block at a time
 * through [block]. With [taps] each voice's own sound is kept as it goes,
 * through [voices]: the louder of its two sides, which is the voice whatever
 * its place - and whichever way round the game has turned one side to make
 * it sound wide.
 */
void runTo(Console& console, int64_t last, int16_t* block, int16_t* voices, int16_t* taps) {
  while (console.made <= last) {
    const int chunk = static_cast<int>(std::min<int64_t>(last + 1 - console.made, kBlockFrames));
    if (taps != nullptr) psx_spu_set_voice_buffer(console.psx, voices, static_cast<uint32>(chunk));
    // It fills the block whether or not the program still runs: with silence, once it has stopped.
    if (psf_gen(console.psx, block, static_cast<uint32>(chunk)) != AO_SUCCESS) console.stopped = true;
    for (int i = 0; i < chunk; i++) {
      const size_t slot = static_cast<size_t>((console.made + i) & (kWindow - 1)) * 2;
      console.window[slot] = block[i * 2];
      console.window[slot + 1] = block[i * 2 + 1];
    }
    if (taps != nullptr) {
      for (int i = 0; i < chunk; i++) {
        const uint32_t at = static_cast<uint32_t>(console.made + i) & (kTapFrames - 1);
        const int16_t* frame = voices + static_cast<size_t>(i) * PSX_VOICE_SAMPLES;
        for (int c = 0; c < kChannels; c++) {
          const int16_t l = frame[c * 2];
          const int16_t r = frame[c * 2 + 1];
          taps[static_cast<size_t>(c) * kTapFrames + at] = std::abs(static_cast<int>(l)) >= std::abs(static_cast<int>(r)) ? l : r;
        }
      }
    }
    console.made += chunk;
  }
}

Setup setupLocked(const PsfState& state) {
  Setup setup;
  setup.program = &state.program;
  setup.refresh = state.refresh;
  setup.sampleRate = state.sampleRate;
  setup.muted = muteMask(state);
  return setup;
}

/** What the settings and the mutes say, to the console playing: a console starts with neither. */
void applySettingsLocked(PsfState& state) {
  psx_spu_set_voice_mute(state.core->psx, muteMask(state));
  psx_spu_set_reverb(state.core->psx, g_reverb.load(std::memory_order_relaxed) ? 1 : 0);
}

/** [core] as the one playing, from output frame [frame] of the song. */
void installLocked(PsfState& state, Core core, int64_t frame) {
  state.core = std::move(core);
  state.rendered = frame;
  state.ended = false;
  std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
  applySettingsLocked(state);
}

/** The console at the top of the song; false when there is no memory to make it from. */
bool startCoreLocked(PsfState& state) {
  // The one playing goes first: two at once is twice the memory.
  state.core.reset();
  Core core = makeCore(setupLocked(state));
  if (!core) return false;
  installLocked(state, std::move(core), 0);
  return true;
}

/**
 * A console made ahead: made, and run to a lead-in short of [targetMs] - the
 * rest is run where it is swapped in, its voices kept, as a seek does. Run
 * as playing runs it: the engine asked for no sound is not the same engine.
 */
psf::Ahead<Core>::Made makeAhead(const Setup& setup, int targetMs, const std::function<bool()>& wanted) {
  psf::Ahead<Core>::Made made;
  const int64_t frame =
      std::max<int64_t>(0, psf::msToFrames(targetMs, setup.sampleRate) - psf::msToFrames(kLeadInMs, setup.sampleRate));
  Core core = makeCore(setup);
  if (!core) return made;
  // A voice left out is left out of the reverb too, which would carry it past the jump.
  psx_spu_set_voice_mute(core->psx, setup.muted);
  std::vector<int16_t> block(static_cast<size_t>(kBlockFrames) * 2);
  // Short of what the frame is made from, so nothing it needs has been made without its voices kept.
  const int64_t last = lastNeeded(setup.sampleRate, frame) - SincKernel::kTaps;
  while (core->made <= last) {
    if (!wanted()) return made;
    runTo(*core, std::min<int64_t>(last, core->made + kBlockFrames * 16 - 1), block.data(), nullptr, nullptr);
  }
  made.console = std::move(core);
  made.frame = frame;
  return made;
}

/** Where a console made ahead is wanted, if anywhere: the A-B loop's start, or the top when the song repeats. */
int aheadWantedLocked(const PsfState& state) {
  if (!state.loaded) return -1;
  return state.loopStartMs >= 0 ? state.loopStartMs : state.loop ? 0 : -1;
}

/**
 * Asks for a console ahead where one is wanted, dropping one made for
 * anywhere else. [again] asks even when one is made or on its way for the
 * same place: when the one there was just used, or no longer fits - another
 * rate. Caller holds the mutex.
 */
void askAheadLocked(PsfState& state, bool again) {
  const int wanted = aheadWantedLocked(state);
  if (!again && wanted == state.ahead.wantedMs()) return;
  const Setup setup = setupLocked(state);
  state.ahead.ask(wanted, [setup, wanted](const std::function<bool()>& stillWanted) {
    return makeAhead(setup, wanted, stillWanted);
  });
}

/**
 * Runs the console on to where output frame [target] starts, the voices
 * kept, and counts the song as there. Caller holds the mutex.
 */
void runUpLocked(PsfState& state, int64_t target) {
  runTo(*state.core, lastNeeded(state.sampleRate, target) - 1, state.block.data(), state.voices.data(), state.tap.data());
  state.rendered = target;
}

/**
 * Swaps in the console made ahead for [ms], when there is one, and runs it
 * on to the place. False when there is none to swap in. Caller holds the
 * mutex.
 */
bool takeAheadLocked(PsfState& state, int ms, int64_t target) {
  psf::Ahead<Core>::Made made;
  if (!state.ahead.take(ms, target, made)) return false;
  state.ahead.discard(std::move(state.core));
  installLocked(state, std::move(made.console), made.frame);
  runUpLocked(state, target);
  return true;
}

/**
 * The console has no way to start part-way, so a seek back starts it again
 * and every seek runs it up to the place, as fast as it goes and as playing
 * runs it - the sound thrown away. The voices are kept for the last stretch
 * only, which is what their traces show when it lands.
 */
void seekLocked(PsfState& state, int ms, bool keepVisuals, bool mayGiveUp = false) {
  if (!state.loaded) return;
  const auto started = std::chrono::steady_clock::now();
  const int total = state.length.totalMs();
  const int64_t target = psf::msToFrames(std::clamp(ms, 0, std::max(0, total - 1)), state.sampleRate);
  if (target < state.rendered || !state.core) {
    // The console made ahead, when it is for here: the A-B loop's start.
    if (takeAheadLocked(state, ms, target)) {
      askAheadLocked(state, true);
    } else if (!startCoreLocked(state)) {
      state.ended = true;
      return;
    }
  }
  const int64_t from = state.rendered;
  const int64_t leadIn = psf::msToFrames(kLeadInMs, state.sampleRate);
  bool gaveUp = false;
  if (state.rendered < target - leadIn) {
    // A stretch at a time, so that the listener's own seek can stop on the
    // way when something overtakes it.
    const int64_t last = lastNeeded(state.sampleRate, target - leadIn) - 1;
    while (state.core->made <= last) {
      if (mayGiveUp && engine_seekOvertaken()) {
        gaveUp = true;
        break;
      }
      runTo(*state.core, std::min<int64_t>(last, state.core->made + kBlockFrames * 16 - 1), state.block.data(), nullptr,
            nullptr);
    }
    std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
  }
  if (gaveUp) {
    // The song is where the console has got to: the output frame its last
    // frames make, so that what is played or sought next goes on from it.
    state.rendered = std::max<int64_t>(
        0, (state.core->made - SincKernel::kTaps / 2) * state.sampleRate / kEngineRate);
  } else {
    runUpLocked(state, target);
  }
  state.ended = false;
  state.stage.reset();
  if (!keepVisuals) state.visuals.reset();
  const double tookMs =
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
  if (tookMs > 50.0) {
    LOG_INFO("sought to %d ms in %.0f ms: %.1fx", ms, tookMs,
             (target - from) * 1000.0 / state.sampleRate / std::max(tookMs, 1.0));
  }
}

/**
 * Up to [frames] frames from where the song has got to, each worked out
 * from where it falls among the console's, faded as the tags say; fewer when
 * the song ends. Caller holds the mutex.
 */
int produceLocked(PsfState& state, float* left, float* right, int frames) {
  // As many at a time as the window holds the console's frames for.
  const int most = static_cast<int>(
      std::clamp<int64_t>(static_cast<int64_t>(kWindow / 2) * state.sampleRate / kEngineRate, 1, kBlockFrames));
  float weights[SincKernel::kTaps];
  int done = 0;
  while (done < frames) {
    const int64_t end = state.length.endFrame(state.sampleRate);
    if (state.rendered >= end) {
      if (!state.loop) break;
      // Round again from the top: the console made ahead for it if there is
      // one, at once, and if not one made now.
      if (takeAheadLocked(state, 0, 0)) {
        askAheadLocked(state, true);
      } else if (!startCoreLocked(state)) {
        break;
      }
    }
    // A program that has stopped its processor has no more to play.
    if (!state.core || state.core->stopped) break;
    const int chunk =
        static_cast<int>(std::min<int64_t>({static_cast<int64_t>(frames - done), end - state.rendered, most}));
    if (chunk <= 0) break;
    Console& console = *state.core;
    runTo(console, lastNeeded(state.sampleRate, state.rendered + chunk - 1), state.block.data(), state.voices.data(),
          state.tap.data());
    // The sides apart by the setting: as they are at 100, one at 0.
    const float width = g_stereoPercent.load(std::memory_order_relaxed) / 100.0f;
    for (int i = 0; i < chunk; i++) {
      const int64_t k = state.rendered + i;
      int64_t whole = 0;
      double fraction = 0.0;
      place(state.sampleRate, k, whole, fraction);
      state.kernel.weights(fraction, weights);
      // Frames before the top of the song are the silence the window starts as.
      const int64_t first = whole - (SincKernel::kTaps / 2 - 1);
      float sides[2] = {0.0f, 0.0f};
      for (int t = 0; t < SincKernel::kTaps; t++) {
        const size_t slot = static_cast<size_t>((first + t) & (kWindow - 1)) * 2;
        sides[0] += weights[t] * console.window[slot];
        sides[1] += weights[t] * console.window[slot + 1];
      }
      const float gain = state.length.gainAt(k, state.sampleRate) / 32768.0f;
      sides[0] *= gain;
      sides[1] *= gain;
      psf::spread(sides[0], sides[1], width);
      left[done + i] = sides[0];
      right[done + i] = sides[1];
    }
    state.rendered += chunk;
    done += chunk;
  }
  return done;
}

void publishVisuals(PsfState& state, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  frame.scalars[kScalarNumChannels] = kChannels;
  frame.scalars[kScalarTimeMs] = static_cast<int32_t>(state.rendered * 1000 / std::max(1, state.sampleRate));
  frame.scalars[kScalarTotalTimeMs] = state.length.totalMs();
  frame.scalars[kScalarGlobalVolume] = kGlobalVolumeUnity;
  if (!state.core) {
    state.visuals.publish(frameStamp);
    return;
  }

  // The console's frame the one last handed out falls on: it has made a little past it.
  int64_t whole = 0;
  double fraction = 0.0;
  place(state.sampleRate, state.rendered, whole, fraction);
  const uint32_t heard = static_cast<uint32_t>(std::min(whole, state.core->made));
  const uint32_t step = static_cast<uint32_t>(std::max(1, kEngineRate / kLookHz));
  const int levelLooks = kLookHz * kLevelWindowMs / 1000;

  int sounding = 0;
  for (int c = 0; c < kChannels; c++) {
    int32_t* f = frame.channels.data() + c * kChannelFields;
    PSX_VOICE voice;
    psx_spu_get_voice(state.core->psx, static_cast<uint32>(c), &voice);
    const bool muted = state.muted[static_cast<size_t>(c)] != 0;
    const bool playing = voice.phase != 0;
    if (playing) state.used[static_cast<size_t>(c)] = true;
    const int16_t* ring = state.tap.data() + static_cast<size_t>(c) * kTapFrames;
    const auto look = [&](uint32_t back) { return ring[(heard - 1 - back * step) & (kTapFrames - 1)] / 32768.0f; };

    float loudest = 0.0f;
    for (int i = 0; i < levelLooks; i++) loudest = std::max(loudest, std::fabs(look(static_cast<uint32_t>(i))));
    f[kChPeak] = muted ? 0 : std::clamp(static_cast<int32_t>(loudest / kFullScale * kPeakScale), 0, kPeakScale);
    if (f[kChPeak] > 0) sounding++;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    // Either side's volume can be turned over, which is still that loud.
    const int volumeLeft = std::abs(static_cast<int>(voice.volume_left));
    const int volumeRight = std::abs(static_cast<int>(voice.volume_right));
    f[kChVolume] = !muted && playing ? std::min(64, std::max(volumeLeft, volumeRight) * 64 / kVoiceVolumeFull) : 0;
    // Where the voice is heard: how its two volumes share it.
    f[kChPan] = playing && volumeLeft + volumeRight > 0 ? volumeRight * 255 / (volumeLeft + volumeRight) : 128;
    f[kChInstrument] = !state.used[static_cast<size_t>(c)] ? -1 : voice.noise != 0 ? 1 : voice.modulated != 0 ? 2 : 0;
    f[kChSample] = 0;

    if (muted) continue;
    // Scaled so the loudest look in the window reaches the edge, as a
    // tracker's scope does: a single voice is a fraction of full scale.
    float span = kFullScale / 8.0f;
    for (int x = 0; x < kScopeColumns; x++) span = std::max(span, std::fabs(look(static_cast<uint32_t>(x))));
    int8_t* out = frame.scopes.data() + c * kScopeColumns;
    for (int x = 0; x < kScopeColumns; x++) {
      const float sample = look(static_cast<uint32_t>(kScopeColumns - 1 - x));
      out[x] = static_cast<int8_t>(std::clamp(static_cast<int>(sample / span * 127.0f), -127, 127));
    }
  }
  frame.scalars[kScalarPlayingChannels] = sounding;
  state.visuals.publish(frameStamp);
}

void releaseLocked(PsfState& state) {
  state.loaded = false;
  // The worker reads the program, so it stops before it goes.
  state.ahead.stop();
  state.core.reset();
  state.program.clear();
  state.program.shrink_to_fit();
  state.refresh = 0;
  for (std::vector<int16_t>* held : {&state.tap, &state.block, &state.voices}) {
    held->clear();
    held->shrink_to_fit();
  }
  state.visuals.reset();
}

/** A "_refresh" tag's rate, 0 when there is none or it is no number. */
int refreshOf(const psf::File& file) {
  const std::string& text = file.tag("_refresh");
  return text.empty() ? 0 : std::atoi(text.c_str());
}

uint32_t readLe32(const uint8_t* p) {
  return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

void writeLe32(uint8_t* p, uint32_t value) {
  for (int i = 0; i < 4; i++) p[i] = static_cast<uint8_t>(value >> (8 * i));
}

/** Whether a header names a region the engine knows the screen's rate of. */
bool regionKnown(const uint8_t* header) {
  for (const char* region : kRegions) {
    const size_t length = std::strlen(region);
    bool same = true;
    for (size_t i = 0; i < length && same; i++) {
      same = std::tolower(header[kRegionAt + i]) == std::tolower(static_cast<unsigned char>(region[i]));
    }
    if (same) return true;
  }
  return false;
}

/**
 * The programs of the file and its libraries, laid over each other in the
 * order the format gives into one that fills the console's memory, and the
 * screen's rate the tags ask for: the file's own word first, a library's
 * where it has none. False when the file is no good.
 *
 * Laid here as the engine lays them - each where its header says, cut to the
 * memory; where the program starts from the first; the region from the first
 * that names one the engine knows - so that what is kept is one program of a
 * known size, however many libraries the tags name, and a console is made
 * from it in one step.
 */
bool readFileLocked(PsfState& state, const psf::File& file) {
  std::vector<uint8_t> image(kHeaderBytes + kMemoryBytes, 0);
  bool first = true;
  bool region = false;
  int libraryRefresh = 0;
  const bool whole = psf::loadWithLibraries(file, [&](const psf::File& part) {
    if (libraryRefresh == 0) libraryRefresh = refreshOf(part);
    // A file of tags alone adds nothing.
    if (part.program.empty()) return true;
    const uint8_t* program = part.program.data();
    if (part.program.size() < kHeaderBytes || std::memcmp(program, "PS-X EXE", 8) != 0) return false;
    if (first) {
      std::memcpy(image.data(), program, kHeaderBytes);
      region = regionKnown(program);
      first = false;
    } else if (!region && regionKnown(program)) {
      std::memcpy(image.data() + kRegionAt, program + kRegionAt, kRegionBytes);
      region = true;
    }
    const uint32_t at = readLe32(program + kTextPlaceAt) & (kMemoryBytes - 1) & ~3u;
    const size_t length = std::min<size_t>(
        {readLe32(program + kTextLengthAt), part.program.size() - kHeaderBytes, kMemoryBytes - at});
    std::memcpy(image.data() + kHeaderBytes + at, program + kHeaderBytes, length);
    return true;
  }, kMaxProgramBytes);
  if (!whole || first) {
    LOG_INFO("no program: a library the tags name is missing, unreadable or too large, or it is no PlayStation program");
    return false;
  }
  // One program now, which is the whole of memory from its start.
  writeLe32(image.data() + kTextPlaceAt, 0x80000000u);
  writeLe32(image.data() + kTextLengthAt, static_cast<uint32_t>(kMemoryBytes));
  state.program = std::move(image);
  const int own = refreshOf(file);
  state.refresh = own != 0 ? own : libraryRefresh;
  state.tap.assign(static_cast<size_t>(kChannels) * kTapFrames, 0);
  state.block.assign(static_cast<size_t>(kBlockFrames) * 2, 0);
  state.voices.assign(static_cast<size_t>(kBlockFrames) * PSX_VOICE_SAMPLES, 0);
  return true;
}

}  // namespace

bool psf1_decoder_probe(const uint8_t* data, size_t size) { return psf::probe(data, size, kVersion); }

bool psf1_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
  if (!psf::probe(data, size, kVersion)) return false;
  psf::File file;
  bool read = false;
  try {
    read = psf::parse(data, size, file, kMaxProgramBytes) && readFileLocked(state, file);
  } catch (const std::bad_alloc&) {
    LOG_INFO("no memory for the program");
  }
  if (!read) {
    releaseLocked(state);
    return false;
  }
  if (sampleRate > 0) state.sampleRate = sampleRate;
  state.kernel.design(kEngineRate, state.sampleRate);
  state.stage.configure(state.sampleRate);
  state.stage.setPitch(state.pitchSemitones);
  state.stage.setTempo(state.tempoFactor);

  state.title = file.tag("title");
  state.comment = psf::describe(file);
  state.length.fromTags(file, g_defaultLengthMs.load(std::memory_order_relaxed));
  state.muted.fill(0);
  state.used.fill(false);
  if (!startCoreLocked(state)) {
    releaseLocked(state);
    return false;
  }
  state.visuals.reset();
  state.loaded = true;
  askAheadLocked(state, true);
  LOG_INFO("loaded: the screen's rate %d (0: the program's own), %d ms and %d ms of fade", state.refresh,
           state.length.lengthMs, state.length.fadeMs);
  return true;
}

void psf1_decoder_release() {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool psf1_decoder_loaded() { return PsfState::instance().loaded; }
const char* psf1_decoder_type() { return "PSF"; }
const char* psf1_decoder_title() { return PsfState::instance().title.c_str(); }
const char* psf1_decoder_comment() { return PsfState::instance().comment.c_str(); }

void psf1_decoder_prepare_start(int rate) {
  psf1_decoder_set_sample_rate(rate);
  PsfState::instance().visuals.reset();
}

void psf1_decoder_set_sample_rate(int rate) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate) return;
  // The same place in the song, in the new rate's frames: the console makes
  // its sound at its own rate whatever the output's, so it plays on from
  // where it is, and only one made ahead, counted in the old frames, is no
  // good.
  state.rendered = state.rendered * rate / std::max(1, state.sampleRate);
  state.sampleRate = rate;
  state.kernel.design(kEngineRate, rate);
  state.stage.configure(rate);
  if (!state.loaded) return;
  state.stage.reset();
  askAheadLocked(state, true);
}

int psf1_decoder_duration_ms() { return PsfState::instance().length.totalMs(); }
bool psf1_decoder_duration_known() { return PsfState::instance().length.known; }

int psf1_decoder_position_ms() {
  PsfState& state = PsfState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

void psf1_decoder_seek_ms(int ms) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, false, true);
}

bool psf1_decoder_ended() { return PsfState::instance().ended; }

void psf1_decoder_set_loop(bool loop) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop;
  askAheadLocked(state, false);
}

void psf1_decoder_expect_loop(int startMs) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loopStartMs = startMs >= 0 ? startMs : -1;
  askAheadLocked(state, false);
}

double psf1_decoder_render_position_ms() {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return (state.rendered - state.stage.latencyFrames()) * 1000.0 / std::max(1, state.sampleRate);
}

void psf1_decoder_loop_seek_ms(int ms) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void psf1_decoder_set_duration_override_ms(int ms) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.length.overrideMs = ms > 0 ? ms : 0;
}

void psf1_decoder_set_pitch(double semitones) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  state.stage.setPitch(state.pitchSemitones);
}

void psf1_decoder_set_tempo(double percent) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  state.stage.setTempo(state.tempoFactor);
}

void psf1_decoder_set_stereo(int percent) {
  g_stereoPercent.store(std::clamp(percent, 0, 200), std::memory_order_relaxed);
}

void psf1_decoder_set_reverb(bool heard) {
  g_reverb.store(heard, std::memory_order_relaxed);
  // To what is playing as well.
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.core) psx_spu_set_reverb(state.core->psx, heard ? 1 : 0);
}

void psf1_decoder_set_default_length_ms(int ms) {
  g_defaultLengthMs.store(std::max(1000, ms), std::memory_order_relaxed);
  // To what is playing as well, when its file gave it no length.
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.loaded && !state.length.known) {
    state.length.lengthMs = g_defaultLengthMs.load(std::memory_order_relaxed);
  }
}

int psf1_decoder_channels() { return PsfState::instance().loaded ? kChannels : 0; }

int psf1_decoder_voice_kinds() { return kVoiceKindCount; }

const char* psf1_decoder_voice_kind_name(int index) {
  return index >= 0 && index < kVoiceKindCount ? kVoiceKinds[index] : "";
}

void psf1_decoder_mute(int channel, int muted) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= kChannels) return;
  const int now = muted ? 1 : 0;
  if (state.muted[static_cast<size_t>(channel)] == now) return;
  state.muted[static_cast<size_t>(channel)] = now;
  if (state.core) psx_spu_set_voice_mute(state.core->psx, muteMask(state));
  // A console made ahead under the mutes as they were is no good: another.
  if (aheadWantedLocked(state) > 0) askAheadLocked(state, true);
}

int psf1_decoder_muted(int channel) {
  const PsfState& state = PsfState::instance();
  return channel >= 0 && channel < kChannels ? state.muted[static_cast<size_t>(channel)] : 0;
}

bool psf1_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return PsfState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int psf1_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  PsfState& state = PsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  if (!state.loaded || state.ended) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * numChannels * bytesPerSample);
    return state.loaded ? 1 : 0;
  }

  const int64_t base = get_frames_written();
  const ShiftStage::Source source = [&state](float* left, float* right, int frames) {
    return produceLocked(state, left, right, frames);
  };
  // What the console is doing now is heard once the stage has let it
  // through, and is shown then.
  const auto publish = [&state, base](int32_t written) {
    const double delay = state.stage.latencyFrames() / std::max(state.tempoFactor, 0.1);
    publishVisuals(state, base + written + static_cast<int64_t>(delay));
  };
  if (psf::renderSong(state.stage, state.out, state.sampleRate, source, publish, audioData, numFrames, numChannels,
                      bytesPerSample)) {
    state.ended = true;
    return 1;
  }
  return 0;
}
