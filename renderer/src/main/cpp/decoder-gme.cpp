//
// Game Music Emu decoder. See decoder-gme.h.
//
#include "decoder-gme.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include "gme/gme.h"
#include "gzip.h"
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

constexpr const char* TAG = "Exilum GME";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

/** GME renders a fixed eight voices when splitting them, however many the chip has. */
/**
 * Voices the multi-channel buffer is built for, and the most any of these
 * machines has: an NES APU's five plus the eight a Namco 163 adds to them.
 * Only the voices a track really has are mixed - see gme_set_used_voices -
 * so a three-voice Game Boy costs no more than it did at eight.
 */
constexpr int kMultiVoices = 16;

/** Samples of history each voice keeps for its trace. */
constexpr int kScopeHistory = 2048;

/**
 * Samples one trace spans when the voices are read out of a chip.
 *
 * Shorter than the ring, and it has to be. A voice read from a chip is asked
 * for a window that ends where the *audio* is, not where the emulator has got
 * to, and the emulator can be most of a ring ahead - so asking for the whole
 * ring leaves nowhere to look back from and the offset is quietly clamped to
 * nothing. Half the ring leaves half of it to look back through.
 *
 * At the SNES DSP's rate this is about thirty milliseconds, still comfortably
 * wider than the sixteen a display frame advances - which is the number that
 * matters, because a window narrower than that cannot overlap between frames
 * and a trace that cannot overlap doubles.
 */
constexpr int kScopeVoiceSpan = kScopeHistory / 2;

/** How long the fade at the end of a track runs. */
constexpr int kFadeMs = 500;

/**
 * Visual frames published per second.
 *
 * Twice a sixty hertz display. The publisher keeps a fixed number of slots, so
 * going faster only shortens how far back the latency compensation can reach.
 */
constexpr int kPublishHz = 120;

/** Quietest a voice can be and still be normalised to fill its box. */
constexpr int32_t kScopeNoiseFloor = 1200;

/** Samples of the trace the pitch estimate looks at. */
constexpr int kPitchWindow = 1024;

/** C-0 in the note numbering libopenmpt uses, where 61 is middle C. */
constexpr double kNoteC0Hz = 8.1758;

/**
 * Names the note a voice is playing by listening to it.
 *
 * GME reports a voice's name and its output and nothing at all about its
 * pitch: the cores know what was written to the chip, but none of that
 * reaches gme.h, and there is no honest way to read it back out. What does
 * reach here is the voice's own audio - and these are chip voices, squares
 * and triangles and pulses, so counting how often one crosses zero is enough
 * to name what it is playing.
 *
 * A Schmitt trigger a quarter of the way up either side of zero, so a
 * harmonic that dips back across the middle is not counted as a second
 * period, and the measurement is taken between the first and last rising edge
 * so a part-period at either end of the window does not skew it.
 *
 * Returns 0 for "could not tell" - too quiet, too few periods to measure, or
 * a result outside the range a chip voice plays in. Drawn as no note, which
 * is what it is; a guess printed as a note would be worse than a blank.
 */
int estimateNote(const std::array<int16_t, kScopeHistory>& ring, int write, int sampleRate) {
  if (sampleRate <= 0) return 0;
  const int start = (write + kScopeHistory - kPitchWindow) % kScopeHistory;

  int32_t peak = 0;
  for (int i = 0; i < kPitchWindow; i++) {
    peak = std::max<int32_t>(peak, std::abs(ring[(start + i) % kScopeHistory]));
  }
  if (peak < kScopeNoiseFloor) return 0;

  const int32_t high = peak / 4;
  const int32_t low = -high;
  int edges = 0;
  int firstEdge = -1;
  int lastEdge = -1;
  bool above = false;
  for (int i = 0; i < kPitchWindow; i++) {
    const int32_t s = ring[(start + i) % kScopeHistory];
    if (!above && s > high) {
      above = true;
      if (firstEdge < 0) firstEdge = i;
      lastEdge = i;
      edges++;
    } else if (above && s < low) {
      above = false;
    }
  }
  if (edges < 3 || lastEdge <= firstEdge) return 0;

  const double freq =
      static_cast<double>(edges - 1) * sampleRate / static_cast<double>(lastEdge - firstEdge);
  if (freq < 16.0 || freq > 8000.0) return 0;

  const int note = static_cast<int>(std::lround(1.0 + 12.0 * std::log2(freq / kNoteC0Hz)));
  return (note > 0 && note < 128) ? note : 0;
}

/**
 * What is playing, as far as the settings are concerned.
 *
 * Not a detail of the emulator: these are three different machines with three
 * different sets of things worth adjusting, and a setting made for one has no
 * business following the listener onto another. Same order as GmeConsole in
 * Player.kt.
 */
enum GmeConsole { kConsoleNes = 0, kConsoleGameBoy = 1, kConsoleSnes = 2, kConsoleCount = 3 };

/**
 * How each console is set up to be mixed.
 *
 * Read by the render callback and written by whichever thread a preference
 * arrives on, so the fields are atomic rather than the whole thing sitting
 * behind the render mutex: a callback that waits on a settings screen is a
 * callback that misses its deadline.
 *
 * volume and separation are percentages of the mix as the chip made it - 100
 * and 100 change nothing. pan is per voice, -100 hard left through 0 centred
 * to +100 hard right, and only means anything where the voices arrive apart.
 */
struct ConsoleAudio {
  std::atomic<int> volume{100};
  std::atomic<int> separation{100};
  std::array<std::atomic<int>, kMultiVoices> pan{};
};

ConsoleAudio g_console[kConsoleCount];

/**
 * The SNES DSP settings, which live in the emulator rather than in the mix.
 *
 * Echo is a delay line the tune itself writes into and reads back, and the
 * interpolation is how a voice's samples are read - neither is something that
 * can be undone afterwards from a finished stereo pair, so both have to be
 * told to the chip.
 *
 * And told again after every track. Loading an SPC re-initialises the DSP,
 * which puts the echo back on and nothing carries the interpolation across the
 * emulator being rebuilt at another sample rate - so applyEmuSettingsLocked
 * runs wherever a track starts.
 */
std::atomic<bool> g_snesEcho{true};
std::atomic<int> g_snesInterpolation{2};  // Spc_Dsp::interp_sinc, Player.DEFAULT_GME_INTERPOLATION

/** Semitones everything is shifted by, which the emulator also has to be told. */
std::atomic<double> g_pitchSemitones{0.0};

/**
 * Global tempo, in percent. GME has a tempo of its own that leaves the pitch
 * alone, and keeps a second clock scaled by it (tell_scaled, seek_scaled)
 * that counts the song's own time - which is what the length, the bar and a
 * seek are all in. Its fade is not: that is in output time, so it is worked
 * out again whenever the tempo or the position changes - see armFadeLocked.
 */
std::atomic<double> g_tempoPercent{100.0};

/** Which of the three a file belongs to, or -1 for none of them. */
int consoleOf(gme_type_t type) {
  if (type == gme_nsf_type || type == gme_nsfe_type) return kConsoleNes;
  if (type == gme_gbs_type) return kConsoleGameBoy;
  if (type == gme_spc_type) return kConsoleSnes;
  return -1;
}

/**
 * A console's settings turned into the numbers the mix loop wants.
 *
 * Fixed point over 256, so a chunk of audio is mixed in integers. Resolved
 * once per chunk rather than per sample, and `plain` says the answer is to
 * leave everything exactly as it arrived - which is what it says for a file
 * that is none of the three consoles, and for any of them until somebody
 * moves a slider.
 */
struct Mixing {
  bool plain = true;
  std::array<int, kMultiVoices> panL{};
  std::array<int, kMultiVoices> panR{};
  int side = 256;
  int volume = 256;
};

/** What a track with no declared length gets, so the seek bar has a scale. */
constexpr int kDefaultTrackMs = 150000;

/**
 * The length used when the file does not give one, in milliseconds.
 *
 * A preference, because for some of these formats there is nothing to derive.
 * A plain NSF and a GBS carry no timing whatsoever - they are a ROM and an
 * entry point, and the console would go round for ever - so any number here is
 * somebody's decision about how long to listen, and it may as well be the
 * listener's. GME's own is the 150 seconds this starts at.
 */
std::atomic<int> g_defaultTrackMs{kDefaultTrackMs};

/**
 * How long a track runs, in the order the answer is worth having.
 *
 * length is the file saying so outright and is the end of the matter. Failing
 * that, loop_length is the file saying where the tune comes round, and the
 * length that means anything then is one pass: the intro and one time round.
 * GME's own play_length is deliberately not used - it is this same question
 * answered with two times round, and then a flat 150 seconds, and playing a
 * tune twice before fading is a repeat nobody asked for.
 *
 * @param fromFile set to whether the answer came out of the file at all.
 */
int trackLengthMs(const gme_info_t* info, bool* fromFile = nullptr) {
  auto answer = [&](int ms, bool known) {
    if (fromFile) *fromFile = known;
    return ms;
  };
  if (!info) return answer(g_defaultTrackMs.load(std::memory_order_relaxed), false);
  if (info->length > 0) return answer(static_cast<int>(info->length), true);
  if (info->loop_length > 0) {
    return answer(std::max(0, static_cast<int>(info->intro_length)) +
                      static_cast<int>(info->loop_length),
                  true);
  }
  if (info->intro_length > 0) return answer(static_cast<int>(info->intro_length), true);
  return answer(g_defaultTrackMs.load(std::memory_order_relaxed), false);
}

struct GmeState {
  std::mutex mutex;
  Music_Emu* emu = nullptr;
  bool multi = false;
  bool ended = false;
  bool loop = false;
  int voices = 0;

  /** Which console's settings apply, or -1 when the file is none of the three. */
  int console = -1;
  int durationMs = 0;

  /** Whether durationMs came out of the file, or is the fallback length. */
  bool durationFromFile = false;

  /** A length the listener set for this track, or 0 - see gme_decoder_set_duration_override_ms. */
  int durationOverrideMs = 0;
  /** The track's own length, which an override is taken back off to. */
  int nativeDurationMs = 0;
  int trackCount = 1;
  int currentTrack = 0;
  int sampleRate = 48000;
  std::string title;
  std::string system;
  std::array<std::string, MAX_CHANNELS> voiceNames;
  std::array<int, MAX_CHANNELS> muted{};

  /** Per-voice history, kept so a trace covers more than one callback. */
  std::array<std::array<int16_t, kScopeHistory>, kMultiVoices> scope{};
  int scopeWrite = 0;

  /**
   * Whether the emulator can hand over a voice's own output without
   * rendering it separately.
   *
   * The SNES can: its DSP mixes eight voices into one output and has no
   * per-voice buffer to reroute, so gme_voice_history reads what each one
   * put out straight from the chip - see gme/ANDROID-CHANGES.md. That is the
   * whole of what the channel view needs, and none of it has to go through
   * the audio path.
   */
  bool voiceHistory = false;

  /** Rate those samples come at, which is the chip's rather than the stream's. */
  int voiceRate = 0;

  /** Samples one trace spans: the whole ring, or less when read from a chip. */
  int scopeSpan = kScopeHistory;

  /** Where the last seek asked for, so the bar has an answer until the first publish. */
  int seekedToMs = 0;

  /** What the views read. Filled by the render callback - see VisualPublisher. */
  VisualPublisher visuals;

  // Kept so the emulator can be rebuilt at the stream's real rate - see
  // gme_decoder_set_sample_rate. These files are small: an SPC carries the
  // whole of the SNES sound chip's memory and is still under seventy kilobytes.
  std::vector<uint8_t> fileData;

  // Scratch for the split render, sized on first use.
  std::vector<int16_t> mixBuffer;

  static GmeState& instance() {
    static GmeState s;
    return s;
  }
};

/**
 * Muting happens in the mix here, not in the emulator.
 *
 * gme_mute_voices does it by unhooking a voice's output buffer inside the
 * core, and putting it back does not put back what the core knew about it.
 * On the NES that is visible: the APU mixes the triangle, noise and DMC
 * together on a nonlinear curve, and the DMC's DAC sits at whatever level the
 * sample left it at. Unhook it and its last known amplitude is dropped;
 * rehook it and the difference arrives as one enormous step, which then has
 * to bleed off through the buffer's high-pass. That is the several seconds of
 * silence after un-soloing DMC - the music was there, riding on a DC offset
 * far outside the output range.
 *
 * The emulator is never told. Where the voices are separate the mix is built
 * here anyway, so leaving one out of the sum is exact and instant; where they
 * are not there is nothing to leave out, and muting one of them was never
 * going to mean anything.
 */

Mixing mixingFor(const GmeState& state) {
  Mixing m;
  m.panL.fill(256);
  m.panR.fill(256);
  if (state.console < 0) return m;

  const ConsoleAudio& c = g_console[state.console];
  m.volume = std::clamp(c.volume.load(std::memory_order_relaxed), 0, 400) * 256 / 100;
  m.side = std::clamp(c.separation.load(std::memory_order_relaxed), 0, 200) * 256 / 100;
  m.plain = m.volume == 256 && m.side == 256;

  // A balance rather than a pan law: centred leaves a voice exactly as the
  // chip made it, and moving it one way takes the other side down without
  // lifting anything up. That is what the slider in Android's own
  // accessibility settings does, which is the thing this is modelled on, and
  // it is the only version that can be dragged back to the middle and be
  // certain of having changed nothing.
  for (int v = 0; v < kMultiVoices; v++) {
    const int p = std::clamp(c.pan[v].load(std::memory_order_relaxed), -100, 100);
    if (p == 0) continue;
    m.panL[v] = p <= 0 ? 256 : (100 - p) * 256 / 100;
    m.panR[v] = p >= 0 ? 256 : (100 + p) * 256 / 100;
    m.plain = false;
  }
  return m;
}

/** Hands the emulator the settings a start_track resets. Caller holds the mutex. */
void applyEmuSettingsLocked(GmeState& state) {
  if (!state.emu) return;
  gme_set_tempo(state.emu, g_tempoPercent.load(std::memory_order_relaxed) / 100.0);
  const double semitones = g_pitchSemitones.load(std::memory_order_relaxed);
  gme_set_pitch(state.emu, std::pow(2.0, semitones / 12.0));
  if (state.console != kConsoleSnes) return;
  gme_disable_echo(state.emu, g_snesEcho.load(std::memory_order_relaxed) ? 0 : 1);
  gme_set_interpolation(state.emu, g_snesInterpolation.load(std::memory_order_relaxed));
}

/** Latest track position, in the song's own milliseconds, or 0 when nothing is loaded. */
int positionLocked(GmeState& state) {
  return state.emu ? gme_tell_scaled(state.emu) : 0;
}

/**
 * Sets the fade to begin where the track's length runs out.
 *
 * The length is song time and GME's fade is output time, so the start is
 * wherever the output now is plus what is left of the song at this tempo.
 * Caller holds the mutex.
 */
void armFadeLocked(GmeState& state, Music_Emu* emu) {
  if (!emu) return;
  const double tempo = g_tempoPercent.load(std::memory_order_relaxed) / 100.0;
  const long remaining = std::max<long>(0, state.durationMs - gme_tell_scaled(emu));
  gme_set_fade_msecs(emu, static_cast<int>(gme_tell(emu) + remaining / tempo), kFadeMs);
}

/** Tears down the emulator. Caller holds the mutex. */
void releaseLocked(GmeState& state);

void clearScopes(GmeState& state) {
  for (auto& voice : state.scope) voice.fill(0);
  state.scopeWrite = 0;
  state.visuals.reset();
}

}  // namespace

namespace {

/** Builds the emulator from state.fileData at state.sampleRate. Caller holds the mutex. */
bool openLocked(GmeState& state) {
  if (state.fileData.size() < 4) return false;
  const char* header = gme_identify_header(state.fileData.data());
  if (!header || !*header) return false;

  gme_type_t type = gme_identify_extension(header);
  if (!type) return false;

  // Split voices where the emulator can, since that is the only way the
  // channel view gets a trace per voice. The Classic_Emu-derived cores - NSF,
  // NSFE and GBS here - can; SPC cannot, and falls back to a mix plus the
  // per-voice history the DSP was taught to keep.
  Music_Emu* emu = gme_new_emu_multi_channel(type, state.sampleRate);
  bool multi = emu != nullptr;
  if (!emu) emu = gme_new_emu(type, state.sampleRate);
  if (!emu) return false;

  if (gme_err_t err = gme_load_data(emu, state.fileData.data(),
                                    static_cast<long>(state.fileData.size()))) {
    LOG_WARN("load failed: %s", err);
    gme_delete(emu);
    return false;
  }

  if (multi && !gme_multi_channel(emu)) multi = false;
  // What this track has, so the buffers past it are mixed as silence rather
  // than read three blip buffers at a time for nothing.
  if (multi) gme_set_used_voices(emu, std::min(gme_voice_count(emu), kMultiVoices));

  if (gme_err_t err = gme_start_track(emu, 0)) {
    LOG_WARN("start_track failed: %s", err);
    gme_delete(emu);
    return false;
  }

  state.emu = emu;
  state.multi = multi;
  state.console = consoleOf(type);
  applyEmuSettingsLocked(state);
  // Asked once: an emulator either answers this or it does not.
  int16_t probe = 0;
  state.voiceHistory = !multi && gme_voice_history(emu, 0, &probe, 1) > 0;
  state.voiceRate = state.voiceHistory ? gme_voice_history_rate(emu) : 0;
  state.scopeSpan = state.voiceHistory ? kScopeVoiceSpan : kScopeHistory;
  state.ended = false;
  state.voices = std::min(gme_voice_count(emu), MAX_CHANNELS);
  clearScopes(state);

  for (int i = 0; i < state.voices; i++) {
    const char* name = gme_voice_name(emu, i);
    state.voiceNames[i] = name ? name : "";
  }
  return true;
}

}  // namespace

void gme_decoder_prepare_start(int rate) {
  gme_decoder_set_sample_rate(rate);
  GmeState::instance().visuals.reset();
}

void gme_decoder_set_sample_rate(int rate) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate || !state.emu) return;

  const int position = positionLocked(state);
  gme_delete(state.emu);
  state.emu = nullptr;
  state.sampleRate = rate;
  if (!openLocked(state)) return;
  if (position > 0) gme_seek_scaled(state.emu, position);
  armFadeLocked(state, state.emu);
  LOG_INFO("re-opened at %d Hz", rate);
}

const char* gme_decoder_probe(const uint8_t* data, size_t size) {
  if (!data || size < 4) return "";
  // A compressed one is known by the first bytes of what is in it, and only
  // those are unpacked: the whole file was, megabytes of it, to read four.
  uint8_t head[32] = {};
  if (gzip_is_member(data, size)) {
    if (gzip_inflate_head(data, size, head, sizeof(head)) < 4) return "";
    data = head;
  }
  const char* header = gme_identify_header(data);
  // Only what this build has an emulator for, which is the test opening it
  // makes. The library names formats it was built without - AY, HES, KSS,
  // SAP here - and a file of one of those was listed as a tune, put in
  // queues and shuffles, and failed to load every time it was reached.
  return (header && *header && gme_identify_extension(header)) ? header : "";
}

bool gme_decoder_load(const uint8_t* data, size_t size) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  releaseLocked(state);

  state.muted.fill(0);
  state.currentTrack = 0;
  // Expanded once, here, so everything downstream - identifying it, loading
  // it, re-opening it at another rate - sees the tune itself.
  if (gzip_is_member(data, size)) {
    state.fileData = gzip_inflate(data, size);
    if (state.fileData.empty()) return false;
  } else {
    state.fileData.assign(data, data + size);
  }
  if (!openLocked(state)) {
    state.fileData.clear();
    return false;
  }
  Music_Emu* emu = state.emu;
  gme_type_t type = gme_type(emu);

  state.title.clear();
  state.system.clear();
  state.durationMs = kDefaultTrackMs;
  gme_info_t* info = nullptr;
  if (!gme_track_info(emu, &info, 0) && info) {
    if (info->song && *info->song) state.title = info->song;
    if (info->game && *info->game && state.title.empty()) state.title = info->game;
    if (info->system) state.system = info->system;
    // Logged in full, because for some of these formats there is no length in
    // the file to be right about. An SPC is a snapshot of a console's memory
    // that would play for ever; what it has is a tag saying how long someone
    // thought it should run, and a plain NSF or a GBS does not have even that.
    // A bar that does not match the music is that, and no amount of measuring
    // here can find a length the file does not contain - see trackLengthMs.
    state.durationMs = trackLengthMs(info, &state.durationFromFile);
    state.nativeDurationMs = state.durationMs;
    state.durationOverrideMs = 0;
    LOG_INFO("length: tag=%d intro=%d loop=%d play=%d -> %d ms (%s)",
             static_cast<int>(info->length), static_cast<int>(info->intro_length),
             static_cast<int>(info->loop_length), static_cast<int>(info->play_length),
             state.durationMs, state.durationFromFile ? "from file" : "fallback");
    gme_free_info(info);
  }
  if (state.system.empty()) state.system = gme_type_system(type);
  state.trackCount = std::max(1, gme_track_count(emu));

  // Fade at the end rather than stopping dead, and only once we know how long
  // the track is meant to be. Short, and deliberately: gme_set_fade defaults
  // to eight seconds, which is eight seconds of playing past the end of a bar
  // that says the track is over - and for SPC it also overwrote the fifty
  // milliseconds the format itself asked for.
  armFadeLocked(state, emu);

  LOG_INFO("loaded %s: %d voices, %d ms, multi=%d",
           state.system.c_str(), state.voices, state.durationMs, state.multi ? 1 : 0);
  return true;
}

namespace {

void releaseLocked(GmeState& state) {
  if (!state.emu) return;
  gme_delete(state.emu);
  state.emu = nullptr;
  state.voices = 0;
  state.console = -1;
  state.ended = false;
  state.fileData.clear();
  clearScopes(state);
}

/**
 * Copies each voice's own output out of the emulator into the traces.
 *
 * For the emulators that render one mix and keep their voices to themselves.
 * The samples come at the chip's rate rather than the stream's, which is
 * right for a trace - it is a picture of the wave, not a piece of the audio,
 * and resampling it would only blur it.
 */
void readVoiceHistory(GmeState& state) {
  const int voices = std::min(state.voices, kMultiVoices);
  for (int v = 0; v < voices; v++) {
    const int got = gme_voice_history(state.emu, v, state.scope[v].data(), kScopeVoiceSpan);
    if (got <= 0) continue;
    if (state.muted[v]) std::fill_n(state.scope[v].data(), got, 0);
  }
  // A span, not the ring: it arrives oldest-first at the front, so the read
  // starts at nought rather than following a write index.
  state.scopeWrite = 0;
}

/**
 * Fills and publishes the frame the views will read.
 *
 * Called from the render callback, which already holds the lock and has just
 * measured everything this needs.
 *
 * [mixPeak] stands in for the voice peaks on emulators that cannot split
 * their voices - SPC is the one that matters. Every voice then reads the same
 * level, which is true of the output and not of the voice, but it is what
 * makes those channels appear at all, listed and mutable, instead of the view
 * showing nothing.
 */
void publishVisuals(GmeState& state, const std::array<int32_t, kMultiVoices>& voicePeak,
                    int32_t mixPeak, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();

  const int voices = state.voices;
  frame.scalars[kScalarNumChannels] = voices;
  frame.scalars[kScalarTimeMs] = state.emu ? gme_tell_scaled(state.emu) : 0;
  frame.scalars[kScalarTotalTimeMs] = state.durationMs;
  frame.scalars[kScalarSequence] = state.currentTrack;
  // Order, pattern, row, speed and bpm stay zero: this format has none of
  // them, and a made-up value would be drawn as though it meant something.

  const int32_t sharedPeak = (mixPeak * kPeakScale) / 32768;
  const bool separate = state.multi || state.voiceHistory;
  int playing = 0;
  for (int v = 0; v < voices; v++) {
    int32_t* f = frame.channels.data() + v * kChannelFields;
    const bool muted = state.muted[v] != 0;

    // Measured off the voice's own trace when there is one, and off the mix
    // when there is not - where every channel reads the same because that is
    // genuinely all that is known about them.
    // Over the span that was filled, not the whole ring: past the span is
    // whatever was there before, and reading it made the level jump about on
    // whatever the voice happened to be playing a moment ago.
    int32_t ownPeak = 0;
    if (state.voiceHistory && v < kMultiVoices) {
      for (int x = 0; x < state.scopeSpan; x++) {
        ownPeak = std::max<int32_t>(ownPeak, std::abs(state.scope[v][x]));
      }
    }
    const int32_t peak = muted ? 0
        : state.multi && v < kMultiVoices ? (voicePeak[v] * kPeakScale) / 32768
        : state.voiceHistory ? (ownPeak * kPeakScale) / 32768
        : sharedPeak;

    f[kChPeak] = peak;

    // The volume and the panning the chip is applying, when it tracks them
    // per voice. The SNES pans by giving a voice a different volume on each
    // side, so the two come out of the same pair of numbers. Where they are
    // not tracked there is nothing honest to say: full volume, centred.
    int volumeLeft = 0;
    int volumeRight = 0;
    if (state.emu && gme_voice_volume(state.emu, v, &volumeLeft, &volumeRight)) {
      const int left = std::abs(volumeLeft);
      const int right = std::abs(volumeRight);
      const int loudest = std::max(left, right);
      f[kChVolume] = muted ? 0 : std::clamp(loudest / 2, 0, 64);
      f[kChPan] = (left + right) > 0 ? (255 * right) / (left + right) : 128;
    } else {
      // No panning inside the chip - an NES mixes to one output and that is
      // all it has ever done - so what the view shows is whatever the listener
      // has asked for, and dead centre until they ask for something.
      const int asked = (state.console >= 0 && v < kMultiVoices)
          ? std::clamp(g_console[state.console].pan[v].load(std::memory_order_relaxed), -100, 100)
          : 0;
      f[kChVolume] = muted ? 0 : 64;
      f[kChPan] = 128 + asked * 127 / 100;
    }
    f[kChFinalVolume] = muted ? 0 : (peak * 64) / kPeakScale;
    f[kChInstrument] = v;
    f[kChSample] = v + 1;
    if (peak > 0) playing++;

    if (!separate || v >= kMultiVoices) continue;

    // Against the rate those samples were taken at. A voice read straight
    // from the chip arrives at the chip's rate, and measuring it against the
    // stream's would name a note a fifth out.
    const int traceRate = state.voiceRate > 0 ? state.voiceRate : state.sampleRate;
    // Where the newest sample sits. For a voice read out of a chip the window
    // is at the front of the ring and ends at scopeSpan; for one mixed here it
    // ends where the next write would go. Passing the write position for both
    // had the note measured from the half of the ring that is no longer
    // filled, which is whatever was in it before.
    const int newest = state.voiceHistory ? state.scopeSpan : state.scopeWrite;
    const int note = muted ? 0 : estimateNote(state.scope[v], newest, traceRate);
    f[kChNote] = note;
    f[kChKey] = note > 0 ? note - 1 : -1;

    // Newest kScopeHistory samples, decimated to the published width and
    // scaled so the loudest sample in the window reaches the edge of the box.
    //
    // Fixed scaling made these unreadable: a single chip voice is a fraction
    // of full scale even when it is the only thing playing, so >> 8 left a
    // line barely off the centre. Normalising per voice is what a tracker's
    // scope does, and it is why a quiet channel still shows its shape.
    // Each column is the average of the samples it stands for, not the first
    // of them.
    //
    // The window is wider than the trace, so several samples share a column -
    // eight of them here. Taking one and dropping the rest is sampling the
    // voice at an eighth of its rate, and everything above half of *that*
    // comes back as a frequency that was never played: a wave drawn correctly
    // from a signal that does not exist. Averaging first is what makes
    // dropping the rest honest, and it is the whole of the difference between
    // a shape and a shimmer.
    //
    // Widening the window instead would only trade this for a worse fault -
    // one narrow enough not to need averaging cannot overlap between display
    // frames, and a trace that cannot overlap doubles. See renderScopeTraces
    // in player-libopenmpt.cpp.
    int8_t* out = frame.scopes.data() + v * kScopeColumns;
    const auto& src = state.scope[v];
    const int step = std::max(1, state.scopeSpan / kScopeColumns);

    // The window is the whole ring starting at scopeWrite, so it wraps at
    // most once - and when scopeWrite is nought, which is every voice read
    // straight from a chip, it does not wrap at all. Worth knowing before
    // taking a remainder two thousand times per voice per frame.
    int32_t column[kScopeColumns];
    int32_t loudest = 1;
    const int origin = state.scopeWrite;
    for (int x = 0; x < kScopeColumns; x++) {
      const int at = origin + x * step;
      int32_t sum = 0;
      if (at + step <= kScopeHistory) {
        for (int i = 0; i < step; i++) sum += src[at + i];
      } else {
        for (int i = 0; i < step; i++) sum += src[(at + i) % kScopeHistory];
      }
      column[x] = sum / step;
      loudest = std::max<int32_t>(loudest, std::abs(column[x]));
    }
    // Floored so near-silence is not amplified into noise that looks like
    // signal - below this a voice is drawn as the quiet thing it is.
    loudest = std::max(loudest, kScopeNoiseFloor);

    for (int x = 0; x < kScopeColumns; x++) {
      out[x] = static_cast<int8_t>(std::clamp<int32_t>(column[x] * 127 / loudest, -127, 127));
    }
  }
  frame.scalars[kScalarPlayingChannels] = playing;
  state.visuals.publish(frameStamp);
}

}  // namespace

void gme_decoder_release() {
  // Takes the lock, unlike the internal one: this is called from the loader
  // thread on a track change, and the render callback reads state.emu.
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool gme_decoder_loaded() { return GmeState::instance().emu != nullptr; }
const char* gme_decoder_title() { return GmeState::instance().title.c_str(); }
const char* gme_decoder_system() { return GmeState::instance().system.c_str(); }
int gme_decoder_duration_ms() { return GmeState::instance().durationMs; }

int gme_decoder_position_ms() {
  // Read from the published frame rather than from the emulator, so the
  // media session's position poll cannot end up waiting on the audio thread.
  // Except in the moment after a seek, when the history has been dropped and
  // nothing audible describes the new position yet - reporting zero there
  // made the bar jump back to the start and look like the seek had missed.
  GmeState& state = GmeState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : state.seekedToMs;
}

void gme_decoder_seek_ms(int ms) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu) return;
  gme_seek_scaled(state.emu, ms);
  // A seek backwards restarts the track, and a restart puts the DSP back the
  // way it was found - so these go on again with the fade.
  applyEmuSettingsLocked(state);
  // Re-armed, every time. Seeking backwards restarts the track inside GME, and
  // starting a track clears the fade - so one drag back down the bar left the
  // tune with no end at all, going round for as long as the app was open.
  // Seeking forward does not clear it and re-setting it there costs nothing,
  // so this does not bother telling the two apart.
  armFadeLocked(state, state.emu);
  state.seekedToMs = std::max(0, ms);
  state.ended = false;
  clearScopes(state);
}

void gme_decoder_set_default_length_ms(int ms) {
  const int clamped = std::clamp(ms, 10000, 1800000);
  g_defaultTrackMs.store(clamped, std::memory_order_relaxed);

  // Applied to what is playing, not only to the next thing loaded. The reason
  // to reach for this setting is a tune going round too many times right now,
  // and being told to load it again to hear the answer is not one.
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu || state.durationFromFile) return;
  state.nativeDurationMs = clamped;
  // A length the listener gave this track outranks the default for all tracks.
  if (state.durationOverrideMs > 0) return;
  state.durationMs = clamped;
  armFadeLocked(state, state.emu);
}

void gme_decoder_set_console_volume(int console, int percent) {
  if (console < 0 || console >= kConsoleCount) return;
  g_console[console].volume.store(std::clamp(percent, 0, 400), std::memory_order_relaxed);
}

void gme_decoder_set_console_stereo(int console, int percent) {
  if (console < 0 || console >= kConsoleCount) return;
  g_console[console].separation.store(std::clamp(percent, 0, 200), std::memory_order_relaxed);
}

void gme_decoder_set_console_pan(int console, int voice, int pan) {
  if (console < 0 || console >= kConsoleCount) return;
  if (voice < 0 || voice >= kMultiVoices) return;
  g_console[console].pan[voice].store(std::clamp(pan, -100, 100), std::memory_order_relaxed);
}

void gme_decoder_set_pitch(double semitones) {
  g_pitchSemitones.store(std::clamp(semitones, -12.0, 12.0), std::memory_order_relaxed);
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  applyEmuSettingsLocked(state);
}

void gme_decoder_set_tempo(double percent) {
  g_tempoPercent.store(std::clamp(percent, 10.0, 200.0), std::memory_order_relaxed);
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu) return;
  applyEmuSettingsLocked(state);
  armFadeLocked(state, state.emu);
}

void gme_decoder_set_snes_echo(bool enabled) {
  g_snesEcho.store(enabled, std::memory_order_relaxed);
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  applyEmuSettingsLocked(state);
}

void gme_decoder_set_snes_interpolation(int mode) {
  g_snesInterpolation.store(std::clamp(mode, 0, 2), std::memory_order_relaxed);
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  applyEmuSettingsLocked(state);
}

bool gme_decoder_ended() { return GmeState::instance().ended; }
void gme_decoder_set_loop(bool loop) { GmeState::instance().loop = loop; }

int gme_decoder_track_count() { return GmeState::instance().trackCount; }
int gme_decoder_current_track() { return GmeState::instance().currentTrack; }

int gme_decoder_track_duration_ms(int track) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu || track < 0 || track >= state.trackCount) return 0;

  gme_info_t* info = nullptr;
  int ms = 0;
  if (!gme_track_info(state.emu, &info, track) && info) {
    ms = trackLengthMs(info);
    gme_free_info(info);
  }
  return ms > 0 ? ms : g_defaultTrackMs.load(std::memory_order_relaxed);
}

std::string gme_decoder_track_name(int track) {
  // A copy, to whoever asks: it was one buffer for every caller, and two
  // threads asking at once - a subsong tapped as the track changed by itself
  // - read each other's name, or one let go of.
  std::string name;
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu || track < 0 || track >= state.trackCount) return name;

  gme_info_t* info = nullptr;
  if (!gme_track_info(state.emu, &info, track) && info) {
    if (info->song && *info->song) name = info->song;
    gme_free_info(info);
  }
  return name;
}

bool gme_decoder_start_track(int track) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu || track < 0 || track >= state.trackCount) return false;

  if (gme_err_t err = gme_start_track(state.emu, track)) {
    LOG_WARN("start_track %d failed: %s", track, err);
    return false;
  }
  state.currentTrack = track;
  state.ended = false;
  applyEmuSettingsLocked(state);

  // Each track has its own length, and the fade has to move with it or the
  // seek bar and the fade disagree about where the end is.
  gme_info_t* info = nullptr;
  if (!gme_track_info(state.emu, &info, track) && info) {
    state.durationMs = trackLengthMs(info, &state.durationFromFile);
    state.nativeDurationMs = state.durationMs;
    state.durationOverrideMs = 0;
    if (info->song && *info->song) state.title = info->song;
    gme_free_info(info);
  }
  armFadeLocked(state, state.emu);
  clearScopes(state);
  LOG_INFO("track %d/%d, %d ms", track + 1, state.trackCount, state.durationMs);
  return true;
}

int gme_decoder_voice_count() { return GmeState::instance().voices; }

const char* gme_decoder_voice_name(int index) {
  GmeState& state = GmeState::instance();
  if (index < 0 || index >= state.voices) return "";
  return state.voiceNames[index].c_str();
}

void gme_decoder_mute(int voice, int muted) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (voice < 0 || voice >= MAX_CHANNELS) return;
  state.muted[voice] = muted ? 1 : 0;

  // Where the voices arrive separately the mix is built here, so muting is
  // done here too - see the note above the render loop. Where they do not,
  // the emulator's own muting is the only one there is. The SNES has it as a
  // mask inside its DSP, which is nothing like the buffer the NES unhooks,
  // and works cleanly.
  if (state.emu && !state.multi) {
    int mask = 0;
    for (int i = 0; i < state.voices && i < 32; i++) {
      if (state.muted[i]) mask |= (1 << i);
    }
    // A voice silenced by the listener is not the tune going quiet, and GME
    // cannot tell the two apart: it watches its own output for silence, and
    // muting inside the emulator makes that output silent. On seeing silence
    // it emulates ahead at up to three times speed into a buffer to find where
    // the silence ends - so an unmute took effect only once that buffer, made
    // with the old mask, had played out, and toggling quickly made the audio
    // jump between the two. Past six seconds it also decided the track was
    // over. Neither is wanted while the listener has anything muted.
    gme_ignore_silence(state.emu, mask != 0 ? 1 : 0);
    gme_mute_voices(state.emu, mask);
  }
}

int gme_decoder_muted(int voice) {
  GmeState& state = GmeState::instance();
  if (voice < 0 || voice >= state.voices) return 0;
  return state.muted[voice];
}

bool gme_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  // No lock: the render callback publishes, this copies. See VisualPublisher.
  return GmeState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

/**
 * How much of the audio deadline the render callback is using.
 *
 * A callback has to finish inside the time the buffer it is filling will play
 * for; past that the stream underruns however fast the device is. Reported as
 * a share of that budget rather than a duration, because the duration on its
 * own says nothing - two hundred microseconds is comfortable in a twenty
 * millisecond buffer and hopeless in a one millisecond one.
 *
 * Split between emulating and drawing, because those are the two things that
 * can be worked on and they are worked on differently.
 */
struct RenderCost {
  int64_t emulateUs = 0;
  int64_t visualUs = 0;
  int64_t budgetUs = 0;
  int64_t worstUs = 0;
  int calls = 0;
  std::chrono::steady_clock::time_point since = std::chrono::steady_clock::now();

  void report(const char* what) {
    const auto now = std::chrono::steady_clock::now();
    if (now - since < std::chrono::seconds(5) || calls == 0) return;
    const int64_t total = emulateUs + visualUs;
    LOG_INFO("%s: %d callbacks, %lld%% of budget (emulate %lld%%, draw %lld%%), worst %lld%%",
             what, calls, static_cast<long long>(total * 100 / std::max<int64_t>(1, budgetUs)),
             static_cast<long long>(emulateUs * 100 / std::max<int64_t>(1, budgetUs)),
             static_cast<long long>(visualUs * 100 / std::max<int64_t>(1, budgetUs)),
             static_cast<long long>(worstUs));
    emulateUs = visualUs = budgetUs = worstUs = 0;
    calls = 0;
    since = now;
  }
};

int gme_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  static RenderCost cost;
  const auto started = std::chrono::steady_clock::now();

  const size_t outSamples = static_cast<size_t>(numFrames) * numChannels;
  if (!state.emu) {
    std::memset(audioData, 0, outSamples * bytesPerSample);
    return 0;
  }

  if (gme_track_ended(state.emu)) {
    if (state.loop) {
      gme_start_track(state.emu, state.currentTrack);
      applyEmuSettingsLocked(state);
      armFadeLocked(state, state.emu);
    } else {
      state.ended = true;
      std::memset(audioData, 0, outSamples * bytesPerSample);
      return 1;
    }
  }

  // Rendered in pieces, and a frame published after each one.
  //
  // Not for the emulator's sake - it is a percent of the deadline either way.
  // For the views. A buffer here is a thirtieth of a second, so publishing
  // once per buffer gave them thirty new pictures a second to draw at a
  // hundred and twenty, which is one picture held for four frames and then
  // replaced. That is the whole of why these looked heavier than the other
  // decoders: libopenmpt publishes per sub-chunk, the Hively replayer per
  // interrupt, AdPlug per tick, and only this one published per buffer.
  //
  // Twice a sixty hertz display and no more: the publisher keeps a fixed
  // number of slots, so publishing faster shortens how far back the latency
  // compensation can reach for nothing in return.
  const int64_t base = get_frames_written();
  const int32_t chunkFrames = std::max(1, state.sampleRate / kPublishHz);

  // GME always produces stereo int16, or eight stereo voices when it splits
  // them. Everything below turns that into whatever the stream asked for.
  const int gmeChannels = state.multi ? kMultiVoices * 2 : 2;
  const size_t want = static_cast<size_t>(chunkFrames) * gmeChannels;
  if (state.mixBuffer.size() < want) state.mixBuffer.resize(want);

  int64_t visualUs = 0;
  int32_t written = 0;
  while (written < numFrames) {
    const int32_t count = std::min(chunkFrames, numFrames - written);

    if (gme_err_t err = gme_play(state.emu, static_cast<long>(count) * gmeChannels,
                                 state.mixBuffer.data())) {
      LOG_WARN("play failed: %s", err);
      std::memset(static_cast<uint8_t*>(audioData) +
                      static_cast<size_t>(written) * numChannels * bytesPerSample,
                  0, static_cast<size_t>(numFrames - written) * numChannels * bytesPerSample);
      state.ended = true;
      return 1;
    }

    std::array<int32_t, kMultiVoices> voicePeak{};
    int32_t mixPeak = 0;
    const int scopeBase = state.scopeWrite;
    const Mixing mix = mixingFor(state);

    for (int32_t frame = 0; frame < count; frame++) {
      int32_t left = 0;
      int32_t right = 0;

      if (state.multi) {
        const int16_t* in = state.mixBuffer.data() + static_cast<size_t>(frame) * kMultiVoices * 2;
        for (int v = 0; v < kMultiVoices; v++) {
          const int16_t l = in[v * 2];
          const int16_t r = in[v * 2 + 1];
          if (state.muted[v]) {
            state.scope[v][(scopeBase + frame) % kScopeHistory] = 0;
            continue;
          }
          left += l * mix.panL[v] / 256;
          right += r * mix.panR[v] / 256;
          // The trace is the voice, not the listening. Panned or not, the
          // channel view is showing what the chip is doing, and a wave that
          // shrank because it was moved to one side would be saying something
          // about the mix rather than about the music.
          const int16_t mono = static_cast<int16_t>((l + r) / 2);
          state.scope[v][(scopeBase + frame) % kScopeHistory] = mono;
          voicePeak[v] = std::max<int32_t>(voicePeak[v], std::abs(mono));
        }
      } else {
        left = state.mixBuffer[static_cast<size_t>(frame) * 2];
        right = state.mixBuffer[static_cast<size_t>(frame) * 2 + 1];
      }

      if (!mix.plain) {
        // Width first, then volume. Widening is done on the middle and the
        // sides rather than on left and right: what is common to both is the
        // part that was never stereo to begin with, and leaving it alone is
        // what keeps a narrowed mix from going quiet and a widened one from
        // turning into two channels that no longer belong together.
        if (mix.side != 256) {
          const int32_t mid = (left + right) / 2;
          const int32_t side = ((left - right) / 2) * mix.side / 256;
          left = mid + side;
          right = mid - side;
        }
        left = left * mix.volume / 256;
        right = right * mix.volume / 256;
      }

      const int16_t l = static_cast<int16_t>(std::clamp<int32_t>(left, -32768, 32767));
      const int16_t r = static_cast<int16_t>(std::clamp<int32_t>(right, -32768, 32767));
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

    const auto drawFrom = std::chrono::steady_clock::now();
    if (state.multi) {
      state.scopeWrite = (scopeBase + count) % kScopeHistory;
    } else if (state.voiceHistory) {
      readVoiceHistory(state);
    }
    // Stamped where this piece's audio ends: gme_tell is the time after
    // rendering, and the traces end at the last sample produced.
    publishVisuals(state, voicePeak, mixPeak, base + written);
    visualUs += std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - drawFrom).count();
  }

  const auto finished = std::chrono::steady_clock::now();
  const int64_t total =
      std::chrono::duration_cast<std::chrono::microseconds>(finished - started).count();
  const int64_t budget = state.sampleRate > 0 ? numFrames * 1000000LL / state.sampleRate : 0;
  cost.emulateUs += std::max<int64_t>(0, total - visualUs);
  cost.visualUs += visualUs;
  cost.budgetUs += budget;
  cost.calls++;
  if (budget > 0) cost.worstUs = std::max(cost.worstUs, total * 100 / budget);
  cost.report(state.system.c_str());
  return 0;
}

double gme_decoder_render_position_ms() {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.emu ? gme_tell_scaled(state.emu) : 0.0;
}

void gme_decoder_loop_seek_ms(int ms) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu) return;
  // gme_decoder_seek_ms without the parts that are for a listener's seek: the
  // traces are left running, since a loop is the same music going on.
  gme_seek_scaled(state.emu, std::max(0, ms));
  applyEmuSettingsLocked(state);
  armFadeLocked(state, state.emu);
  state.ended = false;
}

void gme_decoder_set_duration_override_ms(int ms) {
  GmeState& state = GmeState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.emu) return;
  state.durationOverrideMs = ms > 0 ? ms : 0;
  state.durationMs = ms > 0 ? ms : state.nativeDurationMs;
  armFadeLocked(state, state.emu);
}

bool gme_decoder_duration_known() { return GmeState::instance().durationFromFile; }
