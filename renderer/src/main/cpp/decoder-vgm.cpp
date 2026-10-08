//
// VGM and the other register logs, played by libvgm.
//
// The file is not a tune and not a program. It is the register traffic a real
// machine produced while the music played, with the gaps between writes
// measured in samples - so playing one means writing the same bytes to the
// same emulated chips at the same moments, and nothing here interprets a
// score. There is no pattern, no order list, no instrument and no note.
//
// What this holds is the container work and the bridge to the rest of the app.
// The chips, the command stream and the sample-rate conversion are libvgm's;
// see libvgm/ANDROID-NOTES.md for which cores are built and why one is not.
//
#include "decoder-vgm.h"

#include "audio.h"
#include "gzip.h"
#include "visual-publisher.h"
#include "visual-state.h"

#include "libvgm/emu/ChnOut.h"
#include "libvgm/emu/SoundDevs.h"
#include "libvgm/emu/SoundEmu.h"
#include "libvgm/player/droplayer.hpp"
#include "libvgm/player/gymplayer.hpp"
#include "libvgm/player/playera.hpp"
#include "libvgm/player/s98player.hpp"
#include "libvgm/player/vgmplayer.hpp"
#include "libvgm/utils/MemoryLoader.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char* TAG = "Exilum VGM";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/** Visual frames published per second. Twice a sixty hertz display. */
constexpr int kPublishHz = 120;

/**
 * Samples libvgm may be asked for in one go.
 *
 * Its own scratch buffer is sized once and it renders no more than that in a
 * single call however much is asked of it, so this has to be comfortably
 * larger than any output buffer the device is likely to hand the callback.
 */
constexpr int kRenderBufferSamples = 8192;

/** Length used when the file says nothing about how long it is. */
constexpr int kDefaultTrackMs = 150000;

/** Quietest a voice can be and still be normalised to fill its box. */
constexpr int32_t kScopeNoiseFloor = 256;

/**
 * How much of a voice a trace shows, in time rather than in samples.
 *
 * In time because libvgm runs every chip at its own native rate, from under
 * 8 kHz to nearly 450 kHz, and a fixed number of samples is a different length
 * of sound on each - the same note would draw as two cycles on one chip and
 * fifty on another. 32 ms is twice a 60 Hz display frame, which keeps two
 * frames in a row overlapping, and is what the fastest history holds - see
 * CHNOUT_MAX_RATE.
 */
constexpr int kScopeWindowMs = 32;

/** C0, so a note number lines up with the numbering used everywhere else. */
constexpr double kNoteC0Hz = 16.3516;

/**
 * The note a trace is playing, from how often it crosses zero.
 *
 * Measured off the waveform rather than read out of a register, because there
 * is no register to read: a chip tells its channel a period in its own clocks,
 * and thirty-odd chips count in thirty-odd different ways. Counting crossings
 * works the same on all of them.
 *
 * The threshold is half the peak rather than zero, and that is the whole
 * difficulty: a wave with a strong harmonic crosses zero twice per cycle in
 * some frames and four times in others, which reads as a note jumping an
 * octave back and forth. Requiring the wave to have got properly loud before
 * counting the next crossing throws the harmonic away.
 */
int estimateNote(const int16_t* window, int count, int rate) {
  if (window == nullptr || count < 8 || rate <= 0) return 0;

  int32_t peak = 0;
  for (int i = 0; i < count; i++) peak = std::max<int32_t>(peak, std::abs(window[i]));
  if (peak < kScopeNoiseFloor) return 0;

  const int32_t threshold = peak / 2;
  int crossings = 0;
  bool above = window[0] > threshold;
  for (int i = 1; i < count; i++) {
    if (above) {
      if (window[i] < -threshold) {
        crossings++;
        above = false;
      }
    } else if (window[i] > threshold) {
      crossings++;
      above = true;
    }
  }
  if (crossings < 2) return 0;

  const double hz = (crossings / 2.0) * rate / count;
  if (hz < 16.0 || hz > 8000.0) return 0;
  const int note = static_cast<int>(std::lround(1.0 + 12.0 * std::log2(hz / kNoteC0Hz)));
  return (note > 0 && note < 128) ? note : 0;
}

/**
 * One voice of one chip: where to read its trace, and where to mute it.
 *
 * Usually the same channel of the same chip, but not always, which is why the
 * two are kept apart - a T6W28's noise is played by one half of it and muted
 * through the other.
 */
struct VoiceRef {
  DEVFUNC_CHNOUT tap = nullptr;
  /** Where the channel sits between the speakers, or null for a centred chip. */
  DEVFUNC_CHNPAN pan = nullptr;
  void* chip = nullptr;
  int channel = 0;
  /**
   * The rate the chip produces at, read at the moment it is needed. Some chips
   * change it while playing, and a trace measured against the old rate reads
   * its notes in the wrong octave. Null only for a device not playing.
   */
  const UINT32* rate = nullptr;

  uint32_t muteDevice = 0;
  /** 0 for the chip itself, 1 for the device linked to it - PLR_MUTE_OPTS::chnMute. */
  int muteSlot = 0;
  int muteChannel = 0;
};

struct VgmState {
  std::mutex mutex;

  /**
   * The file itself, which has to outlive the loader.
   *
   * MemoryLoader does not copy: it keeps the pointer it is given, and libvgm
   * reads the command stream straight out of it for as long as the track is
   * loaded.
   */
  std::vector<uint8_t> file;
  DATA_LOADER* loader = nullptr;

  PlayerA player;
  bool loaded = false;
  bool started = false;

  int sampleRate = 48000;
  int durationMs = kDefaultTrackMs;
  bool loop = false;
  double pitchSemitones = 0.0;
  /**
   * Global tempo, as a factor: libvgm's playback speed, which runs the
   * command stream faster or slower against chips that keep their rate.
   * Its positions in samples are playback samples, so a point in the song is
   * that many file seconds divided by this.
   */
  double tempoFactor = 1.0;

  /**
   * Where playback is, in milliseconds, without having to ask the player.
   *
   * The seek bar reads this several times a second and the render callback
   * writes it. Going through the mutex for it would mean the UI waiting on the
   * audio thread and, far worse, the audio thread waiting on a seek - which is
   * a parse of the whole command stream up to the target and takes as long as
   * it takes.
   */
  std::atomic<int> positionMs{0};

  std::string title;
  std::string system;

  int voices = 0;
  std::vector<VoiceRef> voiceMap;
  std::array<std::string, MAX_CHANNELS> voiceNames;
  std::array<uint8_t, MAX_CHANNELS> muted{};

  /** Stereo, sixteen bit, straight from libvgm before it is reshaped. */
  std::vector<int16_t> scratch;

  VisualPublisher visuals;

  static VgmState& instance() {
    static VgmState s;
    return s;
  }
};

/** Registers one of each player engine. PlayerA owns them from here on. */
void registerEngines(VgmState& state) {
  if (!state.player.GetRegisteredPlayers().empty()) return;
  // VGM is what this is for; the other three are register logs of the same
  // kind from elsewhere, already compiled into the same library, and cost a
  // line each to accept. S98 is the PC-98 one, DRO is a DOSBox capture of an
  // AdLib card, GYM is an older Mega Drive format.
  state.player.RegisterPlayerEngine(new VGMPlayer());
  state.player.RegisterPlayerEngine(new S98Player());
  state.player.RegisterPlayerEngine(new DROPlayer());
  state.player.RegisterPlayerEngine(new GYMPlayer());
}

/** Drops the loaded file and everything derived from it. Caller holds the mutex. */
void releaseLocked(VgmState& state) {
  if (state.started) {
    state.player.Stop();
    state.started = false;
  }
  if (state.loaded) {
    state.player.UnloadFile();
    state.loaded = false;
  }
  if (state.loader) {
    DataLoader_Deinit(state.loader);
    state.loader = nullptr;
  }
  state.file.clear();
  state.voices = 0;
  state.voiceMap.clear();
  for (auto& name : state.voiceNames) name.clear();
  state.visuals.reset();
}

/** What the tags say the tune is called and what it came off. */
void readTagsLocked(VgmState& state) {
  state.title.clear();
  state.system.clear();

  PlayerBase* engine = state.player.GetPlayer();
  if (!engine) return;

  std::string track;
  std::string game;
  const char* const* tags = engine->GetTags();
  for (const char* const* tag = tags; tag != nullptr && tag[0] != nullptr; tag += 2) {
    if (tag[1] == nullptr || tag[1][0] == '\0') continue;
    const std::string key = tag[0];
    if (key == "TITLE") {
      track = tag[1];
    } else if (key == "GAME") {
      game = tag[1];
    } else if (key == "SYSTEM") {
      state.system = tag[1];
    }
  }

  if (!track.empty()) {
    // The game beside it, because a track name on its own frequently says
    // nothing - half of these are called "Stage 1" and belong to something.
    state.title = (!game.empty() && game != track) ? track + " - " + game : track;
  } else if (!game.empty()) {
    state.title = game;
  }
  if (state.title.empty()) state.title = "VGM";
  if (state.system.empty()) state.system = engine->GetPlayerName();
}

/**
 * Names every voice of every chip the file uses.
 *
 * Linked devices are skipped as devices in their own right - the AY-3-8910
 * half of a YM2608 is one - because libvgm treats them as an implementation
 * detail of their parent, and so does this list.
 */
/** The Neo Geo Pocket's PSG, which libvgm plays as two SN76489s side by side. */
bool isT6W28(const PLR_DEV_INFO& device) {
  return device.type == DEVID_SN76496 && device.parentIdx == static_cast<UINT32>(-1) &&
         device.devCfg != nullptr && device.devCfg->flags != 0;
}

/** A YM2610 as opposed to a YM2610B, which is the same chip with two more FM channels. */
bool isPlainYm2610(const PLR_DEV_INFO& device) {
  return device.type == DEVID_YM2610 && device.parentIdx == static_cast<UINT32>(-1) &&
         device.devCfg != nullptr && device.devCfg->flags == 0;
}

/**
 * Whether a channel a chip declares is one it can actually sound.
 *
 * libvgm declares some channels only so that its mute masks line up between
 * variants of a chip, and a row for one of those would sit flat for the whole
 * file - which reads as a fault, not as an absence.
 */
bool voiceExists(const PLR_DEV_INFO& device, int channel) {
  // The plain YM2610 has four FM channels, wired to the YM2610B's second,
  // third, fifth and sixth. libvgm keeps six so that one mask fits both.
  if (isPlainYm2610(device)) return channel != 0 && channel != 3;
  // The T6W28's three squares are played by its first half, and its noise by
  // its second. Each half declares all four.
  if (isT6W28(device)) return device.instance == 0 ? channel < 3 : channel == 3;
  return true;
}

/** What a voice is called on its row, after the name of its chip. */
std::string voiceLabel(const PLR_DEV_INFO& device, int channel, const char* const* channelNames) {
  // An OPN's SSG half is a whole AY8910 of its own to libvgm, which numbers its
  // channels 1 to 3 - fine on a chip by itself, confusing beside the FM 1 to 6
  // of the chip it belongs to.
  if (device.parentIdx != static_cast<UINT32>(-1) && device.type == DEVID_AY8910) {
    return "SSG " + std::to_string(channel + 1);
  }
  // Named as the chip's own documentation numbers them, 1 to 4, rather than by
  // which of the YM2610B's six they happen to occupy.
  if (isPlainYm2610(device) && channel < 6) {
    static const char* const kFm[6] = {"", "FM 1", "FM 2", "", "FM 3", "FM 4"};
    return kFm[channel];
  }
  if (channelNames != nullptr && channelNames[channel] != nullptr &&
      channelNames[channel][0] != '\0') {
    return channelNames[channel];
  }
  return std::to_string(channel + 1);
}

void buildVoicesLocked(VgmState& state) {
  state.voices = 0;
  state.voiceMap.clear();
  for (auto& name : state.voiceNames) name.clear();

  PlayerBase* engine = state.player.GetPlayer();
  if (engine == nullptr) {
    LOG_WARN("no voices: no player engine");
    return;
  }

  // Two kinds of answer, both of them answers: 0x00 means it was read out of
  // the file header, 0x01 that it was read off the running chips. Only 0x80
  // and above are failures, which is libvgm's convention throughout.
  //
  // Treating any non-zero as a failure is what emptied the channel list. It
  // went unnoticed for as long as this ran before Start, when the header
  // answer - 0x00, and no chips behind it - was the one that came back; moving
  // it after Start to get the live answer meant getting 0x01, and dropping it.
  std::vector<PLR_DEV_INFO> devices;
  const UINT8 devResult = engine->GetSongDeviceInfo(devices);
  if (devResult >= 0x80) {
    LOG_WARN("no voices: the engine will not describe its devices (0x%02X)", devResult);
    return;
  }
  LOG_INFO("%zu devices to look at", devices.size());

  // What only the whole list can say about one entry in it: how many of each
  // chip there are, so that a second can be told from the first, and which
  // half of a T6W28 holds the mute register for all four of its voices.
  std::array<int, 256> sameType{};
  uint32_t t6w28Tone = UINT32_MAX;
  for (const PLR_DEV_INFO& device : devices) {
    if (device.parentIdx != static_cast<UINT32>(-1)) continue;
    sameType[device.type]++;
    if (isT6W28(device) && device.instance == 0) t6w28Tone = device.id;
  }

  // Each row is labelled with its chip. A VGM may carry two of nearly any chip,
  // and without a number the two would list identical rows; a T6W28 is two
  // halves of one chip, not two chips, and gets none. A linked device is part
  // of its parent and is labelled as its parent.
  std::vector<std::string> labels(devices.size());
  for (size_t index = 0; index < devices.size(); index++) {
    const PLR_DEV_INFO& device = devices[index];
    if (device.parentIdx != static_cast<UINT32>(-1)) continue;
    const char* name = SndEmu_GetDevName(device.type, 0x01, device.devCfg);
    labels[index] = name != nullptr ? name : "?";
    if (sameType[device.type] > 1 && !isT6W28(device)) {
      labels[index] += " #" + std::to_string(device.instance + 1);
    }
  }
  for (size_t index = 0; index < devices.size(); index++) {
    const PLR_DEV_INFO& device = devices[index];
    if (device.parentIdx == static_cast<UINT32>(-1)) continue;
    labels[index] = device.parentIdx < labels.size() ? labels[device.parentIdx] : "?";
  }

  for (size_t index = 0; index < devices.size(); index++) {
    const PLR_DEV_INFO& device = devices[index];
    const bool linked = device.parentIdx != static_cast<UINT32>(-1);

    // Said out loud, per device, because every way this can go wrong looks the
    // same from outside - the channel view is simply not there - while the
    // causes are opposites: a chip with no tap is a feature not written yet, a
    // chip that is not running is a fault.
    const char core[5] = {static_cast<char>(device.core >> 24), static_cast<char>(device.core >> 16),
                          static_cast<char>(device.core >> 8), static_cast<char>(device.core), '\0'};
    LOG_INFO("device %u: %s%s core '%s' rate=%u decl=%d def=%d tap=%d data=%d", device.id,
             labels[index].c_str(), linked ? " (linked)" : "", core,
             device.smplRateLive != nullptr ? *device.smplRateLive : 0,
             device.devDecl != nullptr, device.devDef != nullptr,
             device.devDef != nullptr && device.devDef->GetChannelOutput != nullptr,
             device.dataPtr != nullptr);

    // Linked devices included, against libvgm's own advice about them being an
    // implementation detail. For some chips they are not: the SSG half of a
    // YM2203, YM2608 or YM2610 is a linked device, and it is part of what the
    // machine plays.
    if (device.devDecl == nullptr || device.devDecl->channelCount == nullptr) continue;

    // A chip whose emulation cannot hand back its voices separately is left
    // out of the list entirely, rather than listed with nothing to draw. That
    // is what takes the channel view away for the chips nobody plays: there is
    // no list to decide from, only whether a core has been taught the tap.
    if (device.devDef == nullptr || device.devDef->GetChannelOutput == nullptr ||
        device.dataPtr == nullptr) {
      continue;
    }

    const int channels = device.devDecl->channelCount(device.devCfg);
    if (channels <= 0) continue;

    const char* const* channelNames = device.devDecl->channelNames != nullptr
        ? device.devDecl->channelNames(device.devCfg)
        : nullptr;

    for (int channel = 0; channel < channels; channel++) {
      if (!voiceExists(device, channel)) continue;
      if (state.voices >= MAX_CHANNELS) {
        LOG_WARN("more voices than the views can show; stopping at %d", MAX_CHANNELS);
        return;
      }
      state.voiceNames[state.voices] =
          labels[index] + " " + voiceLabel(device, channel, channelNames);

      VoiceRef ref;
      ref.tap = device.devDef->GetChannelOutput;
      ref.pan = device.devDef->GetChannelPan;
      ref.chip = device.dataPtr;
      ref.channel = channel;
      ref.rate = device.smplRateLive;
      // libvgm mutes a chip and the device linked to it through one call with
      // a mask for each; a linked device's bits in the chip's own mask would
      // silence whichever of its channels shares the number. It has one slot
      // for linked devices, and no chip here links more than one.
      ref.muteDevice = device.id;
      ref.muteSlot = linked ? 1 : 0;
      ref.muteChannel = channel;
      // The T6W28's noise is played by its second half and gated by the first
      // half's mask - see the noise branch of sn76496.c.
      if (isT6W28(device) && device.instance != 0 && t6w28Tone != UINT32_MAX) {
        ref.muteDevice = t6w28Tone;
      }
      state.voiceMap.push_back(ref);
      state.voices++;
    }
  }
}

/** Hands the muting for one device back to libvgm. Caller holds the mutex. */
void applyMutingLocked(VgmState& state, uint32_t deviceId) {
  PlayerBase* engine = state.player.GetPlayer();
  if (engine == nullptr) return;

  PLR_MUTE_OPTS options;
  if (engine->GetDeviceMuting(deviceId, options)) return;

  // Rebuilt whole from every voice muted through this device, both masks at
  // once: the chip's own channels and its linked device's.
  UINT32 masks[2] = {0, 0};
  for (int v = 0; v < state.voices; v++) {
    const VoiceRef& ref = state.voiceMap[v];
    if (ref.muteDevice != deviceId || state.muted[v] == 0) continue;
    if (ref.muteChannel < 32 && ref.muteSlot >= 0 && ref.muteSlot < 2) {
      masks[ref.muteSlot] |= (1u << ref.muteChannel);
    }
  }
  options.chnMute[0] = masks[0];
  options.chnMute[1] = masks[1];
  engine->SetDeviceMuting(deviceId, options);
}

/**
 * Where the bar is: the place within one pass of the tune. Caller holds the
 * mutex.
 *
 * With repeat off a tune that loops is played through its loop once and
 * faded out, and for that half second the place within a pass is back at the
 * loop's start - the bar and the clock dropped from the end to somewhere
 * near the beginning, and then the track was over. It is at its end by then.
 */
int shownMsLocked(VgmState& state) {
  if (!state.loop && state.player.GetCurLoop() > 0) return state.durationMs;
  return static_cast<int>(state.player.GetCurTime(PLAYTIME_LOOP_EXCL | PLAYTIME_TIME_FILE) * 1000.0);
}

/** Fills the published frame. Caller holds the mutex. */
void publishVisuals(VgmState& state, int32_t mixPeak, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();

  frame.scalars[kScalarNumChannels] = state.voices;
  frame.scalars[kScalarTimeMs] = shownMsLocked(state);
  frame.scalars[kScalarTotalTimeMs] = state.durationMs;
  // Order, pattern, row, speed and bpm stay zero: a register log has none of
  // them, and a made-up value would be drawn as though it meant something.

  // Every voice from the chip itself. A voice only reaches this list at all
  // when its core can hand its own output back - see buildVoicesLocked - so
  // there is never a row here with nothing behind it.
  (void)mixPeak;
  static thread_local std::array<int16_t, CHNOUT_LEN> window;
  int playing = 0;
  for (int v = 0; v < state.voices && v < MAX_CHANNELS; v++) {
    int32_t* f = frame.channels.data() + v * kChannelFields;
    const bool muted = state.muted[v] != 0;
    const VoiceRef& ref = state.voiceMap[v];

    // The rate the history recorded at: the chip's own, as it is now, divided
    // the way ChnOut divides it. Everything below is measured against this.
    const UINT32 chipRate = ref.rate != nullptr ? *ref.rate : 0;
    const int recorded = chipRate > 0 ? static_cast<int>(chipRate / ChnOut_Divider(chipRate)) : 0;
    const int want = recorded > 0
        ? std::clamp(recorded * kScopeWindowMs / 1000, kScopeColumns, CHNOUT_LEN)
        : CHNOUT_LEN;

    const int got = muted ? 0
        : static_cast<int>(ref.tap(ref.chip, static_cast<UINT8>(ref.channel), window.data(),
                                   static_cast<UINT32>(want)));

    int32_t peak = 0;
    for (int i = 0; i < got; i++) peak = std::max<int32_t>(peak, std::abs(window[i]));
    const int32_t scaled = (peak * kPeakScale) / 32768;

    f[kChPeak] = scaled;
    f[kChVolume] = muted ? 0 : 64;
    f[kChFinalVolume] = (scaled * 64) / kPeakScale;
    // Read off the chip's own panning state each frame, as its register
    // writes move it - so a Game Gear square switched to one side, or an OPN
    // channel hard left, draws there rather than in the middle.
    f[kChPan] = ref.pan != nullptr ? ref.pan(ref.chip, static_cast<UINT8>(ref.channel)) : 128;
    f[kChInstrument] = v;
    f[kChSample] = v + 1;
    if (scaled > 0) playing++;

    const int note = estimateNote(window.data(), got, recorded);
    f[kChNote] = note;
    f[kChKey] = note > 0 ? note - 1 : -1;

    // Decimated to the published width and scaled so the loudest sample in the
    // window reaches the edge of the box. Each column is the average of the
    // samples it stands for rather than the first of them: the window is wider
    // than the trace, so several samples share a column, and taking one and
    // dropping the rest is sampling the voice at a fraction of its rate -
    // everything above half of that comes back as a frequency that was never
    // played. See the same loop in decoder-gme.cpp.
    int8_t* out = frame.scopes.data() + v * kScopeColumns;
    if (got < kScopeColumns) continue;
    const int step = std::max(1, got / kScopeColumns);
    // From the newest end: whatever does not divide evenly into the columns is
    // dropped from the oldest, so the right edge of the trace is now.
    const int first = got - step * kScopeColumns;
    int32_t column[kScopeColumns];
    int32_t loudest = kScopeNoiseFloor;
    for (int x = 0; x < kScopeColumns; x++) {
      int32_t sum = 0;
      for (int i = 0; i < step; i++) sum += window[first + x * step + i];
      column[x] = sum / step;
      loudest = std::max<int32_t>(loudest, std::abs(column[x]));
    }
    for (int x = 0; x < kScopeColumns; x++) {
      out[x] = static_cast<int8_t>(std::clamp(column[x] * 127 / loudest, -127, 127));
    }
  }
  frame.scalars[kScalarPlayingChannels] = playing;

  state.visuals.publish(frameStamp);
}

/** Tells the player the pitch, which it spreads over its devices. Caller holds the mutex. */
void applyPitchLocked(VgmState& state) {
  PlayerBase* engine = state.player.GetPlayer();
  if (engine == nullptr) return;
  engine->SetPitch(std::pow(2.0, state.pitchSemitones / 12.0));
}

/** Tells the player the tempo. Caller holds the mutex. */
void applyTempoLocked(VgmState& state) { state.player.SetPlaybackSpeed(state.tempoFactor); }

/** Applies the output format and the one-pass length. Caller holds the mutex. */
void applyOutputLocked(VgmState& state) {
  state.player.SetOutputSettings(static_cast<UINT32>(state.sampleRate), 2, 16,
                                 kRenderBufferSamples);
  // One pass, then stop. These formats loop by nature - the stream jumps back
  // and runs for ever - so the count is the only thing that says when to stop,
  // and playing a tune twice before fading is a repeat nobody asked for.
  state.player.SetLoopCount(state.loop ? 0 : 1);
  state.player.SetFadeSamples(static_cast<UINT32>(state.sampleRate / 2));
  state.player.SetEndSilenceSamples(0);
}

}  // namespace

// ---------------------------------------------------------------------------

/** How much of a file the engines read to say whether it is theirs. */
constexpr size_t kProbeBytes = 0x100;

bool vgm_decoder_probe(const uint8_t* data, size_t size) {
  if (data == nullptr || size < 4) return false;

  // The engines look at the first 0x100 bytes and no further, so of a
  // compressed file that much is unpacked and no more. A .vgz was unpacked
  // whole for this - megabytes, for every file of a folder being listed.
  uint8_t head[kProbeBytes];
  const uint8_t* bytes = data;
  size_t length = size;
  if (gzip_is_member(data, size)) {
    length = gzip_inflate_head(data, size, head, sizeof(head));
    if (length < 4) return false;
    bytes = head;
  }

  // Asked of the engines themselves rather than by checking magic numbers
  // here, so that a format they learn to open is one this opens too. They
  // answer 0 for yes, the way everything else in libvgm reports success.
  DATA_LOADER* loader = MemoryLoader_Init(bytes, static_cast<UINT32>(length));
  if (loader == nullptr) return false;
  DataLoader_SetPreloadBytes(loader, kProbeBytes);

  bool accepted = false;
  if (!DataLoader_Load(loader)) {
    accepted = !VGMPlayer::PlayerCanLoadFile(loader) || !S98Player::PlayerCanLoadFile(loader) ||
               !DROPlayer::PlayerCanLoadFile(loader) || !GYMPlayer::PlayerCanLoadFile(loader);
  }
  DataLoader_Deinit(loader);
  return accepted;
}

bool vgm_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  registerEngines(state);
  releaseLocked(state);

  // Expanded once, here, so everything downstream sees the log itself. A .vgz
  // is a gzipped .vgm, and no header check can see through the wrapper.
  if (gzip_is_member(data, size)) {
    state.file = gzip_inflate(data, size);
  } else if (data != nullptr && size > 0) {
    state.file.assign(data, data + size);
  }
  if (state.file.size() < 4) {
    state.file.clear();
    return false;
  }

  state.loader = MemoryLoader_Init(state.file.data(), static_cast<UINT32>(state.file.size()));
  if (state.loader == nullptr) {
    state.file.clear();
    return false;
  }
  DataLoader_SetPreloadBytes(state.loader, 0x100);
  if (DataLoader_Load(state.loader)) {
    releaseLocked(state);
    return false;
  }

  if (sampleRate > 0) state.sampleRate = sampleRate;
  applyOutputLocked(state);

  if (state.player.LoadFile(state.loader)) {
    releaseLocked(state);
    return false;
  }
  state.loaded = true;

  state.muted.fill(0);
  readTagsLocked(state);

  // One pass through the tune, whatever the repeat setting is.
  //
  // LOOP_EXCL asks for the length of a single time round rather than of the
  // whole session, so the bar means the same thing with repeat on as with it
  // off - and with repeat on there is no whole session to measure.
  const double total = state.player.GetTotalTime(PLAYTIME_LOOP_EXCL | PLAYTIME_TIME_FILE);
  state.durationMs = total > 0.0 ? static_cast<int>(total * 1000.0) : kDefaultTrackMs;
  state.positionMs.store(0, std::memory_order_relaxed);

  if (state.player.Start()) {
    releaseLocked(state);
    return false;
  }
  state.started = true;
  // Both of these have to wait for Start, because starting is what builds the
  // devices they reach into. Before it, GetSongDeviceInfo describes the file
  // rather than the machine: it can say which chips are wanted, but not where
  // they live, so every channel would be rejected for having no tap behind it
  // and the channel view would be dropped from a file that has one.
  buildVoicesLocked(state);
  applyPitchLocked(state);
  applyTempoLocked(state);

  LOG_INFO("loaded %s: %d voices, %d ms", state.system.c_str(), state.voices, state.durationMs);
  return true;
}

void vgm_decoder_release() {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool vgm_decoder_loaded() { return VgmState::instance().loaded; }

void vgm_decoder_set_sample_rate(int rate) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate || !state.loaded) return;

  // The chips are rebuilt at the new rate and the stream replayed up to here:
  // this is the output reopening under the music, not the music starting
  // again. libvgm does the replaying itself when it is seeked.
  //
  // Stopped first. PlayerA::SetSampleRate skips the player that is playing -
  // see playera.cpp - so setting the rate on a started player changed what
  // PlayerA believed and not what the chips produced, and the file played at
  // the ratio of the two: 9 % slow on a 44.1 kHz stream. Stopping and starting
  // again is what makes the new rate the chips' own. The render rate is fixed
  // now (see kRenderRate in oboe.cpp), so this is not normally reached.
  // LOOP_INCL here and nowhere else: this figure is handed straight back to
  // Seek, which counts absolute samples through the whole session. The bar
  // wants the position within one time round; this wants the real one.
  const double at = state.player.GetCurTime(PLAYTIME_LOOP_INCL | PLAYTIME_TIME_FILE);
  state.sampleRate = rate;
  state.player.Stop();
  applyOutputLocked(state);
  if (state.player.Start()) {
    LOG_WARN("could not restart at %d Hz", rate);
    state.started = false;
    // The voices still point at the chips Stop just freed.
    buildVoicesLocked(state);
    return;
  }
  state.player.Seek(PLAYPOS_SAMPLE, static_cast<UINT32>(at / state.tempoFactor * rate));
  // The devices were rebuilt at the new rate, so everything that points at
  // them is stale - the pitch, and the chip pointers each voice reads its
  // trace through. Those would otherwise be left addressing chips that no
  // longer exist, which the render callback would walk into.
  buildVoicesLocked(state);
  // The new devices come up unmuted, so whatever the listener had silenced
  // has to be said again.
  for (int v = 0; v < state.voices; v++) {
    if (state.muted[v]) applyMutingLocked(state, state.voiceMap[v].muteDevice);
  }
  applyPitchLocked(state);
  LOG_INFO("re-opened at %d Hz", rate);
}

void vgm_decoder_prepare_start(int rate) {
  vgm_decoder_set_sample_rate(rate);
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.visuals.reset();
}

const char* vgm_decoder_title() { return VgmState::instance().title.c_str(); }
const char* vgm_decoder_system() { return VgmState::instance().system.c_str(); }
int vgm_decoder_duration_ms() { return VgmState::instance().durationMs; }

int vgm_decoder_position_ms() {
  return VgmState::instance().positionMs.load(std::memory_order_relaxed);
}

void vgm_decoder_seek_ms(int ms) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.loaded) return;

  // No random access: the file is a stream of writes and the state at any
  // point is everything that came before it, so seeking backwards means
  // running it again from the start. libvgm does that itself.
  const int at = std::max(0, ms);
  // Set before the seek rather than after it: the bar should follow the finger
  // immediately, and this call is about to spend a while parsing.
  state.positionMs.store(at, std::memory_order_relaxed);
  state.player.Seek(PLAYPOS_SAMPLE,
                    static_cast<UINT32>(at / state.tempoFactor * state.sampleRate / 1000.0));
  state.visuals.reset();
}

bool vgm_decoder_ended() {
  VgmState& state = VgmState::instance();
  if (!state.loaded) return false;
  return (state.player.GetState() & PLAYSTATE_FIN) != 0;
}

void vgm_decoder_set_loop(bool loop) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.loop == loop) return;
  state.loop = loop;
  if (state.loaded) state.player.SetLoopCount(loop ? 0 : 1);
}

int vgm_decoder_voice_count() { return VgmState::instance().voices; }

bool vgm_decoder_has_channels() { return VgmState::instance().voices > 0; }

const char* vgm_decoder_voice_name(int index) {
  VgmState& state = VgmState::instance();
  if (index < 0 || index >= state.voices) return "";
  return state.voiceNames[index].c_str();
}

void vgm_decoder_mute(int voice, int muted) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (voice < 0 || voice >= state.voices) return;
  state.muted[voice] = muted ? 1 : 0;
  applyMutingLocked(state, state.voiceMap[voice].muteDevice);
}

int vgm_decoder_muted(int voice) {
  VgmState& state = VgmState::instance();
  if (voice < 0 || voice >= state.voices) return 0;
  return state.muted[voice];
}

void vgm_decoder_set_tempo(double percent) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  // Safe mid-song: libvgm rescales its sample position to the new rate.
  applyTempoLocked(state);
}

void vgm_decoder_set_pitch(double semitones) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  const double wanted = std::clamp(semitones, -12.0, 12.0);
  if (wanted == state.pitchSemitones) return;
  state.pitchSemitones = wanted;
  // Every chip at once, and none of them told about it: the shift is in what
  // rate their output is believed to have been made at. See
  // PlayerBase::SetPitch.
  if (state.loaded) applyPitchLocked(state);
}

int vgm_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels,
                       int32_t bytesPerSample) {
  VgmState& state = VgmState::instance();
  const size_t outSamples = static_cast<size_t>(numFrames) * numChannels;

  // Tried, not waited for.
  //
  // Seeking here means parsing the command stream from wherever it is to
  // wherever it is going - and from the beginning when going backwards -
  // which takes as long as the file is long. Blocking the audio thread on
  // that is a missed deadline and a burst of underruns, which is what a seek
  // sounded like: several pops rather than one. A buffer of silence while the
  // decoder is busy is not audible against the fade that follows the seek
  // anyway, and it cannot underrun.
  std::unique_lock<std::mutex> lock(state.mutex, std::try_to_lock);
  if (!lock.owns_lock()) {
    std::memset(audioData, 0, outSamples * bytesPerSample);
    return 0;
  }

  if (!state.loaded || !state.started) {
    std::memset(audioData, 0, outSamples * bytesPerSample);
    return 0;
  }
  if ((state.player.GetState() & PLAYSTATE_FIN) != 0) {
    std::memset(audioData, 0, outSamples * bytesPerSample);
    return 1;
  }

  const int64_t base = get_frames_written();
  const int32_t chunkFrames = std::max(1, state.sampleRate / kPublishHz);

  int32_t written = 0;
  while (written < numFrames) {
    const int32_t count = std::min(chunkFrames, numFrames - written);
    const size_t want = static_cast<size_t>(count) * 2;
    if (state.scratch.size() < want) state.scratch.resize(want);

    // libvgm renders stereo sixteen bit; whatever the stream wants is made
    // from that here rather than asked of it, so there is one shape of buffer
    // to reason about.
    const UINT32 bytes = state.player.Render(
        static_cast<UINT32>(want * sizeof(int16_t)), state.scratch.data());
    const int32_t got = static_cast<int32_t>(bytes / (2 * sizeof(int16_t)));
    if (got < count) {
      std::fill(state.scratch.begin() + static_cast<ptrdiff_t>(got) * 2,
                state.scratch.begin() + static_cast<ptrdiff_t>(want), 0);
    }

    int32_t mixPeak = 0;
    for (int32_t frame = 0; frame < count; frame++) {
      const int16_t l = state.scratch[static_cast<size_t>(frame) * 2];
      const int16_t r = state.scratch[static_cast<size_t>(frame) * 2 + 1];
      mixPeak = std::max(mixPeak, std::max<int32_t>(std::abs(l), std::abs(r)));

      const size_t at = static_cast<size_t>(written + frame) * numChannels;
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData) + at;
        out[0] = l / 32768.0f;
        if (numChannels > 1) out[1] = r / 32768.0f;
      } else {
        int16_t* out = static_cast<int16_t*>(audioData) + at;
        out[0] = l;
        if (numChannels > 1) out[1] = r;
      }
    }

    written += count;
    // Stamped where this piece's audio ends, which is where the traces end.
    publishVisuals(state, mixPeak, base + written);
    state.positionMs.store(shownMsLocked(state), std::memory_order_relaxed);

    if ((state.player.GetState() & PLAYSTATE_FIN) != 0) {
      const size_t from = static_cast<size_t>(written) * numChannels * bytesPerSample;
      std::memset(static_cast<uint8_t*>(audioData) + from, 0,
                  static_cast<size_t>(numFrames - written) * numChannels * bytesPerSample);
      return 1;
    }
  }
  return 0;
}

bool vgm_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  // No lock: the render callback publishes, this copies. See VisualPublisher.
  return VgmState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

double vgm_decoder_render_position_ms() {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.loaded) return 0.0;
  return state.player.GetCurTime(PLAYTIME_LOOP_EXCL | PLAYTIME_TIME_FILE) * 1000.0;
}

void vgm_decoder_loop_seek_ms(int ms) {
  VgmState& state = VgmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.loaded) return;
  // vgm_decoder_seek_ms, keeping the published history: a loop is not a jump
  // the views need to forget the past over.
  const int at = std::max(0, ms);
  state.positionMs.store(at, std::memory_order_relaxed);
  state.player.Seek(PLAYPOS_SAMPLE,
                    static_cast<UINT32>(at / state.tempoFactor * state.sampleRate / 1000.0));
}
