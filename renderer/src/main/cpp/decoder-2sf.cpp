//
// 2SF decoder. See decoder-2sf.h.
//
#include "decoder-2sf.h"
#include "psf-ahead.h"
#include "psf-song.h"
#include "seek-ticket.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

// vio2sf's own headers, after everything else: its types.h names things the
// way a C header of 2006 did. SPU.h is the sound unit's insides, which only
// its own file is meant to see; the channels are read from it here.
#define _SPU_CPP_
#include "vio2sf/desmume/SPU.h"
#include "vio2sf/desmume/state.h"

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum 2SF";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

constexpr int kVersion = 0x24;
constexpr int kChannels = 16;
/** What a voice can be playing: the three ways a recording is kept, and the two the console makes itself. */
const char* const kVoiceKinds[] = {"PCM 8-bit", "PCM 16-bit", "ADPCM", "Square", "Noise"};
constexpr int kVoiceKindCount = 5;
/** The last two voices make noise where the six before them make a square wave. */
constexpr int kFirstNoiseChannel = 14;
/** The console's sound clock, which a voice's timer divides: half the ARM7's. */
constexpr double kSoundClockHz = 33513982.0 / 2.0;

/**
 * Frames of each voice kept, as the console mixes them. It mixes ahead of
 * what it hands out, by a frame of the screen at most, and the trace and the
 * level look back from there: both fit many times over.
 */
constexpr uint32_t kTapFrames = 16384;
/** How often a voice is looked at for its trace and its level. */
constexpr int kLookHz = 16000;
constexpr int kLevelWindowMs = 30;
/** A voice at its loudest alone, as a share of full scale: few are played at full volume. */
constexpr float kFullScale = 0.5f;

/** Frames asked of the console at a time. */
constexpr int kBlockFrames = 1024;
/** The largest cartridge taken, which is far more than a rip keeps of one. */
constexpr uint64_t kMaxRomBytes = 256u << 20;
/** A program: a cartridge, after the eight bytes that say where it goes and how long it is. */
constexpr size_t kMaxProgramBytes = kMaxRomBytes + 8;
/** The smallest: the header the console starts from. */
constexpr size_t kMinRomBytes = 0x200;
/** A saved state is the console's memory, twenty-odd megabytes of it. */
constexpr uint64_t kMaxSaveBytes = 64u << 20;
/** Past the cartridge's end, for the words read from its last bytes. */
constexpr size_t kRomPad = 16;
/** "SAVE", which a saved state in the reserved area starts with. */
constexpr uint32_t kSaveTag = 0x45564153;
/**
 * The most a file may ask to be run before it is heard: a minute of the
 * console's time, which is already seconds of the phone's. Rips that ask at
 * all ask for a few seconds.
 */
constexpr int kMaxInitialFrames = 60 * 60;
/** Each level halves a processor's speed; past this there is no processor left. */
constexpr int kMaxClockdown = 8;
/** Zero-order hold to sinc, as vio2sf's resampler numbers them. */
constexpr int kMaxInterpolation = 5;

std::atomic<int> g_stereoPercent{100};
std::atomic<int> g_interpolation{4};
std::atomic<int> g_defaultLengthMs{150000};
/** Each voice's pan from where its game puts it, -100 to 100. */
std::atomic<int> g_pan[kChannels] = {};

/** vio2sf's console, on the heap: its sound unit keeps the address. Freed with its memory. */
struct CoreDeleter {
  void operator()(NDS_state* core) const {
    state_deinit(core);
    delete core;
  }
};
using Core = std::unique_ptr<NDS_state, CoreDeleter>;

/** What a console is made from: everything state_init clears that the file and the settings fill in. */
struct Setup {
  const uint8_t* rom = nullptr;
  size_t romBytes = 0;
  const uint8_t* save = nullptr;
  size_t saveBytes = 0;
  int initialFrames = -1;
  int syncType = 0;
  int arm9Clockdown = 0;
  int arm7Clockdown = 0;
  int sampleRate = 48000;
  int interpolation = 4;
};

struct TwoSfState {
  std::mutex mutex;
  /** The console playing; null before a file is loaded or when there was no memory for one. */
  Core core;
  /** The cartridge the file and its libraries make, which the console reads in place. */
  std::vector<uint8_t> rom;
  size_t romBytes = 0;
  /** The saved state the file starts from, when it has one. */
  std::vector<uint8_t> save;
  /** How the file's tags want the console run. */
  int initialFrames = -1;
  int syncType = 0;
  int arm9Clockdown = 0;
  int arm7Clockdown = 0;

  bool loaded = false;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  std::string title;
  std::string comment;
  psf::SongLength length;

  /** Frames handed out since the top of the song. */
  int64_t rendered = 0;
  /** What the console is asked for, a block at a time. */
  std::vector<int16_t> block;

  /** Pitch and tempo, which the console cannot change without each other. */
  ShiftStage stage;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;
  std::array<std::vector<float>, 2> out{std::vector<float>(1024), std::vector<float>(1024)};

  std::array<int, kChannels> muted{};
  /** Each voice's own sound, a ring a voice, written by the console as it mixes. */
  std::vector<int16_t> tap;

  /** Where the A-B loop starts, -1 for none: see twosf_decoder_expect_loop. */
  int loopStartMs = -1;
  /** The console made ahead for the next jump; last, so it stops before what it reads goes. */
  psf::Ahead<Core> ahead;

  VisualPublisher visuals;

  static TwoSfState& instance() {
    static TwoSfState s;
    return s;
  }
};

uint32_t readLe32(const uint8_t* p) {
  return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

uint32_t muteMask(const TwoSfState& state) {
  uint32_t mask = 0;
  for (int c = 0; c < kChannels; c++) {
    if (state.muted[c] != 0) mask |= 1u << c;
  }
  return mask;
}

/**
 * A pan of -100 to 100 in the console's own steps, of which there are 128
 * from one side to the other: a voice in the middle is at a side at 100, and
 * one at a side is in the middle.
 */
int panSteps(int percent) { return static_cast<int>(std::lround(percent * 63.5 / 100.0)); }

/** What the settings say, to the console: read by it as it mixes, a voice's interpolation as it starts. */
void applySettingsLocked(TwoSfState& state) {
  state.core->dwInterpolation = static_cast<unsigned long>(g_interpolation.load(std::memory_order_relaxed));
  for (int c = 0; c < kChannels; c++) {
    state.core->channel_pan[c] = static_cast<signed char>(panSteps(g_pan[c].load(std::memory_order_relaxed)));
  }
}

void listenLocked(TwoSfState& state, bool listening) {
  state.core->dwChannelMute = listening ? muteMask(state) : 0xFFFFu;
  state.core->channel_tap = listening ? state.tap.data() : nullptr;
  state.core->channel_tap_mask = kTapFrames - 1;
}

/**
 * A console from cold at the top of the song, mixing nothing until it is
 * listened to; null when there is no memory to make it from. It has no reset
 * that leaves nothing behind, so starting again is making another.
 */
Core makeCore(const Setup& setup) {
  Core core(new (std::nothrow) NDS_state());
  if (!core) return nullptr;
  // Clears the whole of it, the settings included: they go in after.
  if (state_init(core.get()) != 0) {
    LOG_INFO("no memory for the console");
    return nullptr;
  }
  core->dwSampleRate = static_cast<unsigned long>(setup.sampleRate);
  core->dwInterpolation = static_cast<unsigned long>(setup.interpolation);
  core->dwChannelMute = 0xFFFFu;
  core->initial_frames = setup.initialFrames;
  core->sync_type = setup.syncType;
  core->arm9_clockdown_level = setup.arm9Clockdown;
  core->arm7_clockdown_level = setup.arm7Clockdown;
  // Read in place, and never written: the console's cartridge is read only.
  state_setrom(core.get(), const_cast<uint8_t*>(setup.rom), static_cast<u32>(setup.romBytes), 0);
  state_loadstate(core.get(), setup.saveBytes > 0 ? setup.save : nullptr, static_cast<u32>(setup.saveBytes));
  return core;
}

Setup setupLocked(const TwoSfState& state) {
  Setup setup;
  setup.rom = state.rom.data();
  setup.romBytes = state.romBytes;
  setup.save = state.save.data();
  setup.saveBytes = state.save.size();
  setup.initialFrames = state.initialFrames;
  setup.syncType = state.syncType;
  setup.arm9Clockdown = state.arm9Clockdown;
  setup.arm7Clockdown = state.arm7Clockdown;
  setup.sampleRate = state.sampleRate;
  setup.interpolation = g_interpolation.load(std::memory_order_relaxed);
  return setup;
}

/** [core] as the one playing, from frame [frame] of the song, listened to. */
void installLocked(TwoSfState& state, Core core, int64_t frame) {
  state.core = std::move(core);
  state.rendered = frame;
  state.ended = false;
  std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
  listenLocked(state, true);
  applySettingsLocked(state);
}

/** The console at the top of the song; false when there is no memory to make it from. */
bool startCoreLocked(TwoSfState& state) {
  // The one playing goes first: two at once is twice the memory.
  state.core.reset();
  Core core = makeCore(setupLocked(state));
  if (!core) return false;
  installLocked(state, std::move(core), 0);
  return true;
}

/**
 * A console made ahead: made, and run in silence to a lead-in short of
 * [targetMs] - the rest is run where it is swapped in, listened to, as a
 * seek does.
 */
psf::Ahead<Core>::Made makeAhead(const Setup& setup, int targetMs, const std::function<bool()>& wanted) {
  psf::Ahead<Core>::Made made;
  const int64_t frame = std::max<int64_t>(0, psf::msToFrames(targetMs, setup.sampleRate) - setup.sampleRate / 10);
  Core core = makeCore(setup);
  if (!core) return made;
  std::vector<int16_t> block(static_cast<size_t>(kBlockFrames) * 2);
  int64_t done = 0;
  while (done < frame) {
    if (!wanted()) return made;
    const int chunk = static_cast<int>(std::min<int64_t>(frame - done, kBlockFrames));
    state_render(core.get(), block.data(), static_cast<unsigned int>(chunk));
    done += chunk;
  }
  made.console = std::move(core);
  made.frame = done;
  return made;
}

/** Where a console made ahead is wanted, if anywhere: the A-B loop's start, or the top when the song repeats. */
int aheadWantedLocked(const TwoSfState& state) {
  if (!state.loaded) return -1;
  return state.loopStartMs >= 0 ? state.loopStartMs : state.loop ? 0 : -1;
}

/**
 * Asks for a console ahead where one is wanted, dropping one made for
 * anywhere else. [again] asks even when one is made or on its way for the
 * same place: when the one there was just used, or no longer fits - another
 * rate. Caller holds the mutex.
 */
void askAheadLocked(TwoSfState& state, bool again) {
  const int wanted = aheadWantedLocked(state);
  if (!again && wanted == state.ahead.wantedMs()) return;
  const Setup setup = setupLocked(state);
  state.ahead.ask(wanted, [setup, wanted](const std::function<bool()>& stillWanted) {
    return makeAhead(setup, wanted, stillWanted);
  });
}

/** Lays a block - where it goes, how long it is, then that much data - over what is there already. */
bool place(std::vector<uint8_t>& into, const uint8_t* block, size_t size, uint64_t most) {
  if (size < 8) return false;
  const uint64_t at = readLe32(block);
  const uint64_t length = std::min<uint64_t>(readLe32(block + 4), size - 8);
  if (at + length > most) return false;
  if (into.size() < at + length) into.resize(static_cast<size_t>(at + length));
  if (length > 0) std::memcpy(into.data() + at, block + 8, static_cast<size_t>(length));
  return true;
}

/** A tag that is a number, into [value]; left alone when the tag is absent or is no number. */
void numberTag(const psf::File& file, const char* key, int& value) {
  const std::string& text = file.tag(key);
  if (text.empty()) return;
  char* end = nullptr;
  const long long number = std::strtoll(text.c_str(), &end, 10);
  if (end == text.c_str()) return;
  value = static_cast<int>(std::clamp<long long>(number, -1, INT_MAX));
}

/**
 * Builds the cartridge and the saved state from the file and its libraries,
 * in the order they overlay each other, and reads how they want to be run:
 * each part can say, and the last to say wins.
 */
bool assemble(const psf::File& file, TwoSfState& state, std::vector<uint8_t>& image) {
  int frames = -1;
  int sync = 0;
  int clockdown = 0;
  int arm9 = 0;
  int arm7 = 0;
  const bool whole = psf::loadWithLibraries(file, [&](const psf::File& part) {
    // A file of tags alone adds nothing.
    if (part.program.size() >= 8 && !place(image, part.program.data(), part.program.size(), kMaxRomBytes)) {
      return false;
    }
    // The reserved area: saved states, each compressed and laid over the last.
    const std::vector<uint8_t>& reserved = part.reserved;
    uint64_t at = 0;
    while (at + 12 < reserved.size()) {
      const uint64_t length = readLe32(reserved.data() + at + 4);
      if (readLe32(reserved.data() + at) == kSaveTag) {
        if (at + 12 + length > reserved.size()) return false;
        std::vector<uint8_t> block;
        if (!psf::inflate(reserved.data() + at + 12, static_cast<size_t>(length), block, kMaxSaveBytes + 8)) {
          return false;
        }
        if (!place(state.save, block.data(), block.size(), kMaxSaveBytes)) return false;
      }
      at += 12 + length;
    }
    numberTag(part, "_frames", frames);
    numberTag(part, "_clockdown", clockdown);
    numberTag(part, "_vio2sf_sync_type", sync);
    numberTag(part, "_vio2sf_arm9_clockdown_level", arm9);
    numberTag(part, "_vio2sf_arm7_clockdown_level", arm7);
    return true;
  }, kMaxProgramBytes);
  if (!whole) return false;
  // One clockdown for both processors, unless each has its own.
  if (arm9 == 0) arm9 = clockdown;
  if (arm7 == 0) arm7 = clockdown;
  state.initialFrames = std::min(frames, kMaxInitialFrames);
  state.syncType = sync == 1 ? 1 : 0;
  state.arm9Clockdown = std::clamp(arm9, 0, kMaxClockdown);
  state.arm7Clockdown = std::clamp(arm7, 0, kMaxClockdown);
  return true;
}

/**
 * Whether the header's two programs lie inside the cartridge. The console
 * copies them into its memory as it starts, from wherever the header says.
 */
bool programsInside(const std::vector<uint8_t>& rom, size_t romBytes) {
  const auto inside = [&](size_t sourceAt, size_t sizeAt) {
    return static_cast<uint64_t>(readLe32(rom.data() + sourceAt)) + readLe32(rom.data() + sizeAt) <= romBytes;
  };
  return inside(0x20, 0x2C) && inside(0x30, 0x3C);
}

/**
 * Runs the console on [frames] frames, heard by nobody - or, where it
 * [mayGiveUp], as many of them as it has run when the seek it is for is
 * overtaken. Caller holds the mutex.
 */
void discardLocked(TwoSfState& state, int64_t frames, bool mayGiveUp = false) {
  while (frames > 0) {
    if (mayGiveUp && engine_seekOvertaken()) break;
    const int chunk = static_cast<int>(std::min<int64_t>(frames, kBlockFrames));
    state_render(state.core.get(), state.block.data(), static_cast<unsigned int>(chunk));
    state.rendered += chunk;
    frames -= chunk;
  }
}

/**
 * Swaps in the console made ahead for [ms], when there is one, and runs it
 * on to the place, listened to. False when there is none to swap in. Caller
 * holds the mutex.
 */
bool takeAheadLocked(TwoSfState& state, int ms, int64_t target) {
  psf::Ahead<Core>::Made made;
  if (!state.ahead.take(ms, target, made)) return false;
  state.ahead.discard(std::move(state.core));
  installLocked(state, std::move(made.console), made.frame);
  discardLocked(state, target - state.rendered);
  return true;
}

/**
 * The console has no way to start part-way, so a seek back starts it again
 * and every seek runs it up to the place, as fast as it goes. Nothing is
 * mixed on the way but the last stretch: the console mixes ahead of what it
 * hands out, and what it has mixed by the time the place is reached has to
 * be what is heard from there.
 */
void seekLocked(TwoSfState& state, int ms, bool keepVisuals, bool mayGiveUp = false) {
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
  const int64_t leadIn = state.sampleRate / 10;
  if (state.rendered < target - leadIn) {
    listenLocked(state, false);
    discardLocked(state, target - leadIn - state.rendered, mayGiveUp);
    std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
    // Listened to again whether it got there or gave up on the way.
    listenLocked(state, true);
  }
  discardLocked(state, target - state.rendered, mayGiveUp);
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
 * Up to [frames] frames of the console's output from where the song has got
 * to, faded as its tags say; fewer when it ends. Caller holds the mutex.
 */
int produceLocked(TwoSfState& state, float* left, float* right, int frames) {
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
    if (!state.core) break;
    const int chunk = static_cast<int>(
        std::min<int64_t>({static_cast<int64_t>(frames - done), end - state.rendered, kBlockFrames}));
    if (chunk <= 0) break;
    applySettingsLocked(state);
    state_render(state.core.get(), state.block.data(), static_cast<unsigned int>(chunk));
    const int16_t* in = state.block.data();
    // The sides apart by the setting: as they are at 100, one at 0.
    const float width = g_stereoPercent.load(std::memory_order_relaxed) / 100.0f;
    for (int i = 0; i < chunk; i++) {
      const float gain = state.length.gainAt(state.rendered + i, state.sampleRate) / 32768.0f;
      float sides[2] = {in[i * 2] * gain, in[i * 2 + 1] * gain};
      psf::spread(sides[0], sides[1], width);
      left[done + i] = sides[0];
      right[done + i] = sides[1];
    }
    state.rendered += chunk;
    done += chunk;
  }
  return done;
}

void publishVisuals(TwoSfState& state, int64_t frameStamp) {
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

  const SPU_struct& unit = *state.core->SPU_core;
  // Where the frame last handed out is in the rings: what the console has
  // mixed and still holds is ahead of it.
  const uint32_t heard = state.core->channel_tap_pos - static_cast<uint32_t>(state.core->sample_pointer);
  const uint32_t step = static_cast<uint32_t>(std::max(1, state.sampleRate / kLookHz));
  const int levelLooks = kLookHz * kLevelWindowMs / 1000;

  int sounding = 0;
  for (int c = 0; c < kChannels; c++) {
    int32_t* f = frame.channels.data() + c * kChannelFields;
    const channel_struct& voice = unit.channels[c];
    const bool muted = state.muted[c] != 0;
    // A voice has a resampler from the first time it is started.
    const bool used = voice.resampler != nullptr;
    const bool playing = voice.status != CHANSTAT_STOPPED;
    const int16_t* ring = state.tap.data() + static_cast<size_t>(c) * kTapFrames;
    const auto look = [&](uint32_t back) { return ring[(heard - 1 - back * step) & (kTapFrames - 1)] / 32768.0f; };

    float loudest = 0.0f;
    for (int i = 0; i < levelLooks; i++) loudest = std::max(loudest, std::fabs(look(static_cast<uint32_t>(i))));
    f[kChPeak] = muted ? 0 : std::clamp(static_cast<int32_t>(loudest / kFullScale * kPeakScale), 0, kPeakScale);
    if (f[kChPeak] > 0) sounding++;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    f[kChVolume] = !muted && playing ? (voice.vol >> voice.datashift) * 64 / 127 : 0;
    // Where the voice is heard: the game's placing of it, moved by the pan.
    f[kChPan] = used ? std::clamp(voice.pan + state.core->channel_pan[c], 0, 127) * 255 / 127 : 128;
    // Labelled by what it plays: a recording as it is kept, or what the console makes.
    const bool made = voice.format == 3;
    f[kChInstrument] = !used ? -1 : !made ? voice.format : c >= kFirstNoiseChannel ? 4 : 3;
    f[kChSample] = 0;
    // The tone the console is asked for, where it has one: a square wave's
    // eight steps to a cycle. A recording is whatever note was recorded.
    if (!muted && playing && made && c < kFirstNoiseChannel && f[kChPeak] > 0) {
      const double hz = kSoundClockHz / (0x10000 - voice.timer) / 8.0;
      const double midi = 69.0 + 12.0 * std::log2(hz / 440.0);
      f[kChNote] = std::clamp(static_cast<int>(std::lround(midi)) - 11, 1, 127);
      f[kChKey] = f[kChNote] - 1;
      f[kChPeriod] = static_cast<int32_t>(std::lround(hz));
      f[kChPitchBend] = static_cast<int32_t>(std::lround((midi - std::round(midi)) * 100.0));
    }

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

void releaseLocked(TwoSfState& state) {
  state.loaded = false;
  // The worker reads the cartridge, so it stops before the cartridge goes.
  state.ahead.stop();
  state.core.reset();
  for (std::vector<uint8_t>* held : {&state.rom, &state.save}) {
    held->clear();
    held->shrink_to_fit();
  }
  state.romBytes = 0;
  state.tap.clear();
  state.tap.shrink_to_fit();
  state.visuals.reset();
}

/** The cartridge and the saved state from the file, ready for the console; false when the file is no good. */
bool readFileLocked(TwoSfState& state, const psf::File& file) {
  std::vector<uint8_t> image;
  if (!assemble(file, state, image) || image.size() < kMinRomBytes) {
    LOG_INFO("no cartridge: a library the tags name is missing, unreadable or too large");
    return false;
  }
  // The console masks its addresses to the cartridge's size, so a power of two.
  size_t romBytes = kMinRomBytes;
  while (romBytes < image.size()) romBytes <<= 1;
  image.resize(romBytes + kRomPad);
  if (!programsInside(image, romBytes)) {
    LOG_INFO("no cartridge: its header points outside it");
    return false;
  }
  state.rom = std::move(image);
  state.romBytes = romBytes;
  state.tap.assign(static_cast<size_t>(kChannels) * kTapFrames, 0);
  state.block.assign(static_cast<size_t>(kBlockFrames) * 2, 0);
  return true;
}

}  // namespace

bool twosf_decoder_probe(const uint8_t* data, size_t size) { return psf::probe(data, size, kVersion); }

bool twosf_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
  if (!psf::probe(data, size, kVersion)) return false;
  psf::File file;
  bool read = false;
  try {
    read = psf::parse(data, size, file, kMaxProgramBytes) && readFileLocked(state, file);
  } catch (const std::bad_alloc&) {
    LOG_INFO("no memory for the cartridge");
  }
  if (!read) {
    releaseLocked(state);
    return false;
  }
  if (sampleRate > 0) state.sampleRate = sampleRate;
  state.stage.configure(state.sampleRate);
  state.stage.setPitch(state.pitchSemitones);
  state.stage.setTempo(state.tempoFactor);

  state.title = file.tag("title");
  state.comment = psf::describe(file);
  state.length.fromTags(file, g_defaultLengthMs.load(std::memory_order_relaxed));
  state.muted.fill(0);
  if (!startCoreLocked(state)) {
    releaseLocked(state);
    return false;
  }
  state.visuals.reset();
  state.loaded = true;
  askAheadLocked(state, true);
  LOG_INFO("loaded: %zu bytes of cartridge, %zu of saved state, %d ms and %d ms of fade", state.romBytes,
           state.save.size(), state.length.lengthMs, state.length.fadeMs);
  return true;
}

void twosf_decoder_release() {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool twosf_decoder_loaded() { return TwoSfState::instance().loaded; }
const char* twosf_decoder_type() { return "2SF"; }
const char* twosf_decoder_title() { return TwoSfState::instance().title.c_str(); }
const char* twosf_decoder_comment() { return TwoSfState::instance().comment.c_str(); }

void twosf_decoder_prepare_start(int rate) {
  twosf_decoder_set_sample_rate(rate);
  TwoSfState::instance().visuals.reset();
}

void twosf_decoder_set_sample_rate(int rate) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate) return;
  // The place kept in time, not in frames, which change with the rate.
  const int atMs = static_cast<int>(state.rendered * 1000 / std::max(1, state.sampleRate));
  state.sampleRate = rate;
  state.stage.configure(rate);
  if (!state.loaded) return;
  // The console makes its sound at the rate it was started at: started
  // again, then, and run up to the place - and one made ahead is no good.
  askAheadLocked(state, true);
  state.rendered = 0;
  state.core.reset();
  seekLocked(state, atMs, true);
}

int twosf_decoder_duration_ms() { return TwoSfState::instance().length.totalMs(); }
bool twosf_decoder_duration_known() { return TwoSfState::instance().length.known; }

int twosf_decoder_position_ms() {
  TwoSfState& state = TwoSfState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

void twosf_decoder_seek_ms(int ms) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  // The listener's own: given up on the way if something overtakes it.
  seekLocked(state, ms, false, true);
}

bool twosf_decoder_ended() { return TwoSfState::instance().ended; }

void twosf_decoder_set_loop(bool loop) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop;
  askAheadLocked(state, false);
}

void twosf_decoder_expect_loop(int startMs) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loopStartMs = startMs >= 0 ? startMs : -1;
  askAheadLocked(state, false);
}

double twosf_decoder_render_position_ms() {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return (state.rendered - state.stage.latencyFrames()) * 1000.0 / std::max(1, state.sampleRate);
}

void twosf_decoder_loop_seek_ms(int ms) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void twosf_decoder_set_duration_override_ms(int ms) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.length.overrideMs = ms > 0 ? ms : 0;
}

void twosf_decoder_set_pitch(double semitones) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  state.stage.setPitch(state.pitchSemitones);
}

void twosf_decoder_set_tempo(double percent) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  state.stage.setTempo(state.tempoFactor);
}

void twosf_decoder_set_stereo(int percent) {
  g_stereoPercent.store(std::clamp(percent, 0, 200), std::memory_order_relaxed);
}

void twosf_decoder_set_interpolation(int mode) {
  g_interpolation.store(std::clamp(mode, 0, kMaxInterpolation), std::memory_order_relaxed);
}

void twosf_decoder_set_pan(int channel, int value) {
  if (channel < 0 || channel >= kChannels) return;
  g_pan[channel].store(std::clamp(value, -100, 100), std::memory_order_relaxed);
}

void twosf_decoder_set_default_length_ms(int ms) {
  g_defaultLengthMs.store(std::max(1000, ms), std::memory_order_relaxed);
  // To what is playing as well, when its file gave it no length.
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.loaded && !state.length.known) {
    state.length.lengthMs = g_defaultLengthMs.load(std::memory_order_relaxed);
  }
}

int twosf_decoder_channels() { return TwoSfState::instance().loaded ? kChannels : 0; }

int twosf_decoder_voice_kinds() { return kVoiceKindCount; }

const char* twosf_decoder_voice_kind_name(int index) {
  return index >= 0 && index < kVoiceKindCount ? kVoiceKinds[index] : "";
}

void twosf_decoder_mute(int channel, int muted) {
  TwoSfState& state = TwoSfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= kChannels) return;
  state.muted[channel] = muted ? 1 : 0;
  if (state.core) state.core->dwChannelMute = muteMask(state);
}

int twosf_decoder_muted(int channel) {
  const TwoSfState& state = TwoSfState::instance();
  return channel >= 0 && channel < kChannels ? state.muted[channel] : 0;
}

bool twosf_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return TwoSfState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int twosf_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  TwoSfState& state = TwoSfState::instance();
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
