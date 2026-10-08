//
// Furnace decoder. See decoder-furnace.h.
//
#include "decoder-furnace.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include "furnace/src/engine/engine.h"
#include "furnace/src/ta-log.h"
#include <zlib.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum Furnace";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define LOG_WARN(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

/**
 * Furnace's main.cpp provides this and the engine calls it; a host without
 * that main has to. Nothing here can put up a dialog, so it is a log line.
 */
void reportError(String what) {
  LOG_WARN("%s", what.c_str());
}

/**
 * What the decoder needs from DivEngine that its public interface does not
 * give: setting it up without the tracker's configuration file and log file,
 * running its tick clock silently to measure and to seek, and reading where
 * it is. A friend of DivEngine through patches/furnace-exilum.patch, so the
 * engine keeps its own bookkeeping rather than this file copying it.
 */
class ExilumFurnaceBridge {
 public:
  /**
   * An engine ready to load songs, at [rate]. What main.cpp and preInit do,
   * less what a player on a phone must not: no configuration directory made
   * under $HOME, no configuration file read, no log file opened. Every
   * setting reads as its default, which is what Furnace plays a song with on
   * a fresh install.
   */
  static void setUp(DivEngine& e, int rate) {
    e.configLoaded = true;
    e.configPath = "";
    if (!e.systemsRegistered) e.registerSystems();
    if (!e.romExportsRegistered) e.registerROMExports();
    e.setAudio(DIV_AUDIO_DUMMY);
    e.setConf("audioRate", rate);
    e.setConf("audioChans", 2);
    // Rendered inline on the audio thread, not handed to worker threads.
    e.setConf("renderPoolThreads", 0);
  }

  /** Song time, in seconds - the engine's own count, which the global tempo does not change. */
  static double songSeconds(const DivEngine& e) {
    return static_cast<double>(e.totalSeconds) + static_cast<double>(e.totalTicks) / 1000000.0;
  }

  /** The output rate the engine renders at. */
  static int rate(const DivEngine& e) { return static_cast<int>(e.got.rate); }

  /**
   * Renders at [rate] from now on. The chips are built for a rate, so this
   * rebuilds them - as the tracker does when it exports at another rate -
   * and playback starts over.
   */
  static void setRate(DivEngine& e, int rate) {
    e.stop();
    e.got.rate = rate;
    e.setConf("audioRate", rate);
    e.quitDispatch();
    e.initDispatch();
    e.renderSamplesP();
  }

  /** Plays the current subsong from its first row, as the tracker's play button does. */
  static void playFromTop(DivEngine& e, int loops) {
    e.stop();
    e.prevOrder = 0;
    e.prevRow = 0;
    e.play();
    // stop() cleared it; -1 goes round forever, 1 stops where it would loop.
    e.setLoops(loops);
  }

  /**
   * Runs the tick clock without making sound, the way the tracker walks to a
   * row when playback starts from the middle of a song: register writes are
   * dropped and nothing is rendered, only the song's own state moves on.
   *
   * Stops at [targetSeconds] of song time, or where the song goes round or
   * stops. Returns true in the second case. Leaves the chips ready to be
   * heard from there on: forceIns has each channel send its instrument again,
   * since the writes that would have set them up were dropped.
   */
  static bool skip(DivEngine& e, double targetSeconds, double* loopStartSeconds = nullptr,
                   int loopOrder = 0, int loopRow = 0) {
    bool wrapped = false;
    std::lock_guard<std::mutex> busy(e.isBusy);
    for (int i = 0; i < e.song.systemLen; i++) e.disCont[i].dispatch->setSkipRegisterWrites(true);
    e.skipping = true;
    while (e.playing && songSeconds(e) < targetSeconds) {
      const double before = songSeconds(e);
      const int order = e.prevOrder;
      const int row = e.prevRow;
      if (e.nextTick()) {
        wrapped = true;
        break;
      }
      // A new row started on this tick: if it is where the song loops back
      // to, that tick's start is the loop point's time.
      if (loopStartSeconds && *loopStartSeconds < 0 && (e.prevOrder != order || e.prevRow != row) &&
          e.prevOrder == loopOrder && e.prevRow == loopRow) {
        *loopStartSeconds = before;
      }
    }
    for (int i = 0; i < e.song.systemLen; i++) {
      e.disCont[i].dispatch->setSkipRegisterWrites(false);
      e.disCont[i].dispatch->forceIns();
    }
    e.cmdStream.clear();
    e.skipping = false;
    return wrapped;
  }

  /**
   * How long the current subsong plays before it goes round or stops, and
   * where in it the loop starts. Played silently from the top; the engine is
   * left stopped.
   *
   * @return whether the end was found within [capSeconds].
   */
  static bool measure(DivEngine& e, double capSeconds, double& lengthSeconds, double& loopStartSeconds) {
    int loopOrder = 0, loopRow = 0, loopEnd = 0;
    e.walkSong(loopOrder, loopRow, loopEnd);
    playFromTop(e, 1);
    loopStartSeconds = (loopOrder == 0 && loopRow == 0) ? 0.0 : -1.0;
    const bool ended = skip(e, capSeconds, &loopStartSeconds, loopOrder, loopRow);
    lengthSeconds = songSeconds(e);
    e.stop();
    if (loopStartSeconds < 0 || loopStartSeconds >= lengthSeconds) loopStartSeconds = 0.0;
    return ended;
  }

  /** Where in this block the song went round, in frames, or -1. Valid after nextBuf. */
  static int lastLoopPos(const DivEngine& e) { return e.lastLoopPos; }
};

namespace {

/** Output frames per nextBuf call, and so per published view state: 10 ms at 48 kHz. */
constexpr int kBlockFrames = 480;

/** Longest a subsong is measured for before its length is given up on. */
constexpr double kMaxMeasureSeconds = 30 * 60;

/** Song time all of one load's measuring may cover, across every subsong. */
constexpr double kMeasureBudgetSeconds = 2 * 60 * 60;

/** Stored magic numbers of the formats this decoder claims. */
constexpr const char* kMagics[] = {"-Furnace module-", "Furnace-B module", ".DelekDefleMask."};

bool hasMagic(const uint8_t* data, size_t size) {
  if (size < 16) return false;
  for (const char* magic : kMagics) {
    if (std::memcmp(data, magic, 16) == 0) return true;
  }
  return false;
}

struct FurnaceState {
  std::mutex mutex;
  /**
   * Made on the first load and kept: registering Furnace's hundred-odd
   * systems is the expensive part of starting it, and loading a song into a
   * running engine is what the tracker itself does.
   */
  DivEngine* engine = nullptr;
  bool initialised = false;
  bool loaded = false;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  int subsong = 0;
  std::string title, author, type;
  std::array<int, MAX_CHANNELS> muted{};

  /** The song's own A-4, which the global pitch is applied on top of. */
  double baseTuning = 440.0;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;

  /** Measured at load, per subsong: length to the loop point or the end, and where the loop starts. */
  std::vector<int> subsongMs, subsongLoopMs;
  std::vector<bool> subsongKnown;
  int durationOverrideMs = 0;

  VisualPublisher visuals;
  /** Song time at the end of what has been rendered. */
  double positionMs = 0.0;
  int totalMs = 0;

  std::vector<float> left = std::vector<float>(kBlockFrames);
  std::vector<float> right = std::vector<float>(kBlockFrames);

  static FurnaceState& instance() {
    static FurnaceState s;
    return s;
  }
};

DivEngine& engineLocked(FurnaceState& state) {
  if (!state.engine) {
    // The engine's log writes to whatever it is given before anything else.
    initLog(stderr);
    logLevel = LOGLEVEL_ERROR;
    state.engine = new DivEngine;
    ExilumFurnaceBridge::setUp(*state.engine, state.sampleRate);
  }
  return *state.engine;
}

int loopsFor(const FurnaceState& state) { return state.loop ? -1 : 1; }

void applyMutesLocked(FurnaceState& state) {
  DivEngine& e = *state.engine;
  const int channels = std::min(e.getTotalChannelCount(), MAX_CHANNELS);
  for (int i = 0; i < channels; i++) e.muteChannel(i, state.muted[i] != 0);
}

void applyPitchLocked(FurnaceState& state) {
  if (!state.engine || !state.loaded) return;
  state.engine->song.tuning =
      static_cast<float>(state.baseTuning * std::pow(2.0, state.pitchSemitones / 12.0));
}

/** Starts the current subsong over from its first row. Caller holds the mutex. */
void restartLocked(FurnaceState& state, bool keepVisuals) {
  DivEngine& e = *state.engine;
  ExilumFurnaceBridge::playFromTop(e, loopsFor(state));
  state.positionMs = 0.0;
  state.ended = false;
  if (!keepVisuals) state.visuals.reset();
}

void seekLocked(FurnaceState& state, int ms, bool keepVisuals) {
  if (!state.loaded) return;
  DivEngine& e = *state.engine;
  // Played up to, silently, from the top: a song's state at a moment is
  // everything before it, and Furnace's own seek works the same way. Kept
  // inside the song's length - past its end the clock would go round.
  const int length = state.subsong < static_cast<int>(state.subsongMs.size()) ? state.subsongMs[state.subsong] : 0;
  const int target = std::clamp(ms, 0, std::max(0, length - 1));
  restartLocked(state, keepVisuals);
  if (target > 0) ExilumFurnaceBridge::skip(e, target / 1000.0);
  state.positionMs = ExilumFurnaceBridge::songSeconds(e) * 1000.0;
  state.ended = false;
}

void startSubsongLocked(FurnaceState& state, int index) {
  DivEngine& e = *state.engine;
  e.changeSongP(static_cast<size_t>(index));
  state.subsong = index;
  state.durationOverrideMs = 0;
  state.totalMs = index < static_cast<int>(state.subsongMs.size()) ? state.subsongMs[index] : 0;
  restartLocked(state, false);
}

void measureLocked(FurnaceState& state) {
  DivEngine& e = *state.engine;
  const int count = static_cast<int>(e.song.subsong.size());
  state.subsongMs.assign(count, 0);
  state.subsongLoopMs.assign(count, 0);
  state.subsongKnown.assign(count, false);
  double budget = kMeasureBudgetSeconds;
  for (int i = 0; i < count; i++) {
    e.changeSongP(static_cast<size_t>(i));
    double length = 0.0, loopStart = 0.0;
    const bool ended = ExilumFurnaceBridge::measure(e, std::min(kMaxMeasureSeconds, std::max(1.0, budget)),
                                                  length, loopStart);
    budget -= length;
    state.subsongMs[i] = static_cast<int>(std::lround(length * 1000.0));
    state.subsongLoopMs[i] = static_cast<int>(std::lround(loopStart * 1000.0));
    state.subsongKnown[i] = ended;
  }
}

std::string typeOf(DivEngine& e) {
  std::string type = "Furnace";
  for (int i = 0; i < e.song.systemLen && i < 4; i++) {
    type += i == 0 ? ": " : " + ";
    type += e.getSystemName(e.song.system[i]);
  }
  if (e.song.systemLen > 4) type += " + ...";
  return type;
}

}  // namespace

bool furnace_decoder_probe(const uint8_t* data, size_t size) {
  // Under 21 bytes the engine refuses a file outright.
  if (!data || size < 21) return false;
  if (hasMagic(data, size)) return true;

  // Both formats are usually stored deflated, which zlib's two-byte header
  // says - and only the first sixteen bytes of what it inflates to are needed.
  if (data[0] != 0x78 || ((data[0] << 8) | data[1]) % 31 != 0) return false;
  z_stream zs{};
  if (inflateInit(&zs) != Z_OK) return false;
  uint8_t head[32];
  zs.next_in = const_cast<Bytef*>(data);
  zs.avail_in = static_cast<uInt>(std::min<size_t>(size, 1 << 20));
  zs.next_out = head;
  zs.avail_out = sizeof(head);
  const int result = inflate(&zs, Z_SYNC_FLUSH);
  const size_t produced = sizeof(head) - zs.avail_out;
  inflateEnd(&zs);
  return (result == Z_OK || result == Z_STREAM_END) && hasMagic(head, produced);
}

bool furnace_decoder_load(const uint8_t* data, size_t size, const char* filename, int sampleRate) {
  if (!furnace_decoder_probe(data, size)) return false;

  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (sampleRate > 0) state.sampleRate = sampleRate;
  DivEngine& e = engineLocked(state);

  if (state.loaded) e.stop();
  state.loaded = false;
  state.muted.fill(0);

  // The engine takes the buffer and frees it, whether or not it loads.
  auto* copy = new unsigned char[size];
  std::memcpy(copy, data, size);
  if (!e.load(copy, size, filename ? filename : "")) {
    LOG_WARN("load failed: %s", e.getLastError().c_str());
    return false;
  }
  if (!state.initialised) {
    // The first song is loaded before the engine is started, as the tracker
    // does it; later ones go into the running engine.
    e.init();
    state.initialised = true;
  } else if (ExilumFurnaceBridge::rate(e) != state.sampleRate) {
    ExilumFurnaceBridge::setRate(e, state.sampleRate);
  }
  state.loaded = true;

  state.title = e.song.name;
  state.author = e.song.author;
  state.type = typeOf(e);
  state.baseTuning = e.song.tuning > 0 ? e.song.tuning : 440.0;
  applyPitchLocked(state);
  e.exilumTempo = state.tempoFactor;

  measureLocked(state);
  startSubsongLocked(state, 0);
  LOG_INFO("loaded '%s' (%s): %d channels, %d subsongs, %d ms", state.title.c_str(), state.type.c_str(),
           e.getTotalChannelCount(), static_cast<int>(e.song.subsong.size()), state.totalMs);
  return true;
}

void furnace_decoder_release() {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  // The engine and the song stay until the next load replaces the song:
  // unloading one means building an empty song to put in its place, which is
  // what loading does anyway.
  if (state.loaded) state.engine->stop();
  state.loaded = false;
  state.ended = false;
  state.visuals.reset();
}

bool furnace_decoder_loaded() { return FurnaceState::instance().loaded; }
const char* furnace_decoder_title() { return FurnaceState::instance().title.c_str(); }
const char* furnace_decoder_author() { return FurnaceState::instance().author.c_str(); }
const char* furnace_decoder_type() { return FurnaceState::instance().type.c_str(); }
const char* furnace_decoder_engine_name() { return "Furnace " DIV_VERSION; }

void furnace_decoder_prepare_start(int rate) {
  furnace_decoder_set_sample_rate(rate);
  FurnaceState::instance().visuals.reset();
}

void furnace_decoder_set_sample_rate(int rate) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate) return;
  state.sampleRate = rate;
  if (!state.loaded) return;
  ExilumFurnaceBridge::setRate(*state.engine, rate);
  applyMutesLocked(state);
  restartLocked(state, false);
  LOG_INFO("re-opened at %d Hz", rate);
}

int furnace_decoder_duration_ms() { return FurnaceState::instance().totalMs; }

int furnace_decoder_subsong_duration_ms(int index) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (index < 0 || index >= static_cast<int>(state.subsongMs.size())) return 0;
  return state.subsongMs[index];
}

const char* furnace_decoder_subsong_name(int index) {
  FurnaceState& state = FurnaceState::instance();
  if (!state.loaded || index < 0 || index >= static_cast<int>(state.engine->song.subsong.size())) return "";
  return state.engine->song.subsong[index]->name.c_str();
}

int furnace_decoder_position_ms() {
  // From the audible frame, as every decoder here does - see
  // hvl_decoder_position_ms.
  FurnaceState& state = FurnaceState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : static_cast<int>(state.positionMs);
}

void furnace_decoder_seek_ms(int ms) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, false);
}

bool furnace_decoder_ended() { return FurnaceState::instance().ended; }

void furnace_decoder_set_loop(bool loop) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop;
  if (state.loaded) state.engine->setLoops(loopsFor(state));
}

void furnace_decoder_set_pitch(double semitones) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  applyPitchLocked(state);
}

void furnace_decoder_set_tempo(double percent) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  if (state.engine) state.engine->exilumTempo = state.tempoFactor;
}

double furnace_decoder_render_position_ms() {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  // Rendered a whole request at a time, with nothing held back, so the end of
  // what was rendered is where the song is.
  return state.positionMs;
}

void furnace_decoder_loop_seek_ms(int ms) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void furnace_decoder_set_duration_override_ms(int ms) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.durationOverrideMs = ms > 0 ? ms : 0;
  const int native = state.subsong < static_cast<int>(state.subsongMs.size()) ? state.subsongMs[state.subsong] : 0;
  state.totalMs = ms > 0 ? ms : native;
}

bool furnace_decoder_duration_known() {
  FurnaceState& state = FurnaceState::instance();
  return state.subsong < static_cast<int>(state.subsongKnown.size()) && state.subsongKnown[state.subsong];
}

int furnace_decoder_subsong_count() {
  FurnaceState& state = FurnaceState::instance();
  return state.loaded ? std::max(1, static_cast<int>(state.engine->song.subsong.size())) : 1;
}

bool furnace_decoder_start_subsong(int index) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.loaded || index < 0 || index >= static_cast<int>(state.engine->song.subsong.size())) return false;
  startSubsongLocked(state, index);
  return true;
}

int furnace_decoder_current_subsong() { return FurnaceState::instance().subsong; }

int furnace_decoder_channels() {
  FurnaceState& state = FurnaceState::instance();
  return state.loaded ? std::min(state.engine->getTotalChannelCount(), MAX_CHANNELS) : 0;
}

int furnace_decoder_instrument_count() {
  FurnaceState& state = FurnaceState::instance();
  return state.loaded ? static_cast<int>(state.engine->song.ins.size()) : 0;
}

const char* furnace_decoder_instrument_name(int index) {
  FurnaceState& state = FurnaceState::instance();
  if (!state.loaded || index < 0 || index >= static_cast<int>(state.engine->song.ins.size())) return "";
  return state.engine->song.ins[index]->name.c_str();
}

int furnace_decoder_sample_count() {
  FurnaceState& state = FurnaceState::instance();
  return state.loaded ? static_cast<int>(state.engine->song.sample.size()) : 0;
}

const char* furnace_decoder_sample_name(int index) {
  FurnaceState& state = FurnaceState::instance();
  if (!state.loaded || index < 0 || index >= static_cast<int>(state.engine->song.sample.size())) return "";
  return state.engine->song.sample[index]->name.c_str();
}

void furnace_decoder_mute(int channel, int muted) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= MAX_CHANNELS) return;
  state.muted[channel] = muted ? 1 : 0;
  // The chip's channel is silenced and the song goes on driving it, so its
  // effects - speed, tempo, jumps - still apply.
  if (state.loaded && channel < state.engine->getTotalChannelCount()) {
    state.engine->muteChannel(channel, muted != 0);
  }
}

int furnace_decoder_muted(int channel) {
  FurnaceState& state = FurnaceState::instance();
  if (channel < 0 || channel >= MAX_CHANNELS) return 0;
  return state.muted[channel];
}

int furnace_decoder_pattern_rows() {
  FurnaceState& state = FurnaceState::instance();
  return state.loaded ? state.engine->curSubSong->patLen : 0;
}

int furnace_decoder_order_count() {
  FurnaceState& state = FurnaceState::instance();
  return state.loaded ? state.engine->curSubSong->ordersLen : 0;
}

void furnace_decoder_pattern_data(
    int order, int numRows, int8_t* notes, int8_t* instruments,
    int8_t* fxType, int8_t* fxParm, int8_t* fx2Type, int8_t* fx2Parm) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.loaded) return;
  DivEngine& e = *state.engine;
  DivSubSong* sub = e.curSubSong;
  if (!sub || order < 0 || order >= sub->ordersLen) return;

  const int channels = std::min(e.getTotalChannelCount(), MAX_CHANNELS);
  const int rows = std::min(numRows, sub->patLen);
  for (int ch = 0; ch < channels; ch++) {
    // An order names one pattern per channel; each channel's patterns are its own.
    DivPattern* pat = sub->pat[ch].getPattern(sub->orders.ord[ch][order], false);
    const int effectCols = std::clamp<int>(sub->pat[ch].effectCols, 1, DIV_MAX_EFFECTS);
    for (int row = 0; row < rows; row++) {
      const short* cell = pat->data[row];
      const int idx = row * MAX_CHANNELS + ch;

      // Note and octave, with the octave signed: C-0 is note 12 of octave -1,
      // so the pair adds up to a semitone count from C-0, which is the view's
      // note 1. Off, release and macro release are Furnace's own three.
      const short note = cell[0];
      const auto octave = static_cast<signed char>(cell[1]);
      if (note == 100) {
        notes[idx] = static_cast<int8_t>(0xFE);  // OFF, a cut: "^^"
      } else if (note == 101) {
        notes[idx] = static_cast<int8_t>(0xFF);  // ===, note release: "=="
      } else if (note == 102) {
        notes[idx] = static_cast<int8_t>(0xFD);  // REL, macro release: "~~"
      } else if (note == 0 && octave == 0) {
        notes[idx] = 0;
      } else {
        notes[idx] = static_cast<int8_t>(std::clamp(note + octave * 12 + 1, 1, 127));
      }

      // -1 for none; one up otherwise, since the view reads 0 as empty.
      instruments[idx] = cell[2] >= 0 ? static_cast<int8_t>(std::min(cell[2] + 1, 255)) : 0;

      // The first effect column with something in it - see the header for
      // how these two columns are laid out for this decoder.
      int flags = 0;
      fxType[idx] = 0;
      fxParm[idx] = 0;
      for (int k = 0; k < effectCols; k++) {
        const short effect = cell[4 + k * 2];
        if (effect < 0) continue;
        const short value = cell[5 + k * 2];
        fxType[idx] = static_cast<int8_t>(effect & 0xFF);
        fxParm[idx] = static_cast<int8_t>(value < 0 ? 0 : value & 0xFF);
        flags |= 1;
        break;
      }
      if (cell[3] >= 0) {
        fx2Parm[idx] = static_cast<int8_t>(std::min<int>(cell[3], 255));
        flags |= 2;
      } else {
        fx2Parm[idx] = 0;
      }
      fx2Type[idx] = static_cast<int8_t>(flags);
    }
  }
}

namespace {

/**
 * One channel's trace, from the oscilloscope buffer the engine fills for the
 * tracker's own channel scopes.
 *
 * That buffer is a ring of 65536 slots written at 65536 per second whatever
 * the chip's own rate, with the slots between two of a slower chip's samples
 * left at -1 - so it is read back over the last 256 output samples' worth of
 * slots, holding the last value across the gaps. Returns the peak.
 */
int32_t readScope(DivDispatchOscBuffer* buf, int outputRate, int8_t* out) {
  if (!buf) {
    std::memset(out, 0, kScopeColumns);
    return 0;
  }
  const int span = std::max(kScopeColumns, 65536 * kScopeColumns / std::max(1, outputRate));
  const unsigned short end = static_cast<unsigned short>(buf->needle >> 16);
  const unsigned short start = static_cast<unsigned short>(end - span);

  // What the trace starts from: the last sample written before the window.
  short last = 0;
  for (int back = 1; back <= 4096; back++) {
    const short v = buf->data[static_cast<unsigned short>(start - back)];
    if (v != -1) {
      last = v;
      break;
    }
  }

  int32_t peak = 0;
  int slot = 0;
  for (int x = 0; x < kScopeColumns; x++) {
    const int next = (x + 1) * span / kScopeColumns;
    for (; slot < next; slot++) {
      const short v = buf->data[static_cast<unsigned short>(start + slot)];
      if (v != -1) last = v;
    }
    const int32_t sample = std::clamp<int32_t>(last / 256, -127, 127);
    peak = std::max(peak, std::abs(sample));
    out[x] = static_cast<int8_t>(sample);
  }
  return peak;
}

/** Fills and publishes the frame the views read. Caller holds the mutex. */
void publishVisuals(FurnaceState& state, int64_t frameStamp) {
  DivEngine& e = *state.engine;
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  int32_t* scalars = frame.scalars.data();
  const int channels = std::min(e.getTotalChannelCount(), MAX_CHANNELS);

  int order = 0, row = 0, tick = 0, speed = 0;
  e.getPlayPosTick(order, row, tick, speed);
  scalars[kScalarOrder] = order;
  // The order stands in for the pattern: each channel has its own pattern
  // numbers, and what the pattern view asks for is one order's rows.
  scalars[kScalarPattern] = order;
  scalars[kScalarRow] = row;
  scalars[kScalarNumRows] = e.curSubSong ? e.curSubSong->patLen : 0;
  scalars[kScalarSpeed] = speed;
  // Ticks per second in the MOD way of counting, where 50 is 125 BPM.
  scalars[kScalarBpm] = static_cast<int32_t>(std::lround(e.getCurHz() * 2.5));
  scalars[kScalarNumChannels] = channels;
  scalars[kScalarTimeMs] = static_cast<int32_t>(state.positionMs);
  scalars[kScalarTotalTimeMs] = state.totalMs;
  scalars[kScalarSequence] = state.subsong;

  int playing = 0;
  for (int ch = 0; ch < channels; ch++) {
    int32_t* f = frame.channels.data() + ch * kChannelFields;
    const DivChannelState* cs = e.getChanState(ch);
    if (!cs) continue;

    const int volMax = std::max(1, cs->volMax >> 8);
    const int volume = state.muted[ch] ? 0 : std::clamp((cs->volume >> 8) * 64 / volMax, 0, 64);
    f[kChVolume] = volume;
    f[kChFinalVolume] = volume;

    // Left level in the top byte and right in the bottom one - on each chip's
    // own scale, which for most of them is one bit a side. Read the way the
    // tracker's own channel meters read it; halving the difference, which
    // this did, turned a 1 on one side and 0 on the other into no difference
    // at all, and every channel sat in the middle. A chip that cannot pan
    // reports nothing, which comes out as the middle too.
    f[kChPan] = std::clamp(e.convertPanSplitToLinear(e.getChanPan(ch), 8, 256), 0, 255);

    // Semitones from C-0, which is the view's note 1 - the pattern's count.
    f[kChNote] = cs->keyOn ? std::clamp(cs->note + 1, 1, 127) : 0;
    f[kChKey] = f[kChNote] > 0 ? f[kChNote] - 1 : -1;
    f[kChInstrument] = cs->lastIns;
    f[kChSample] = cs->lastIns + 1;
    f[kChPitchBend] = cs->pitch;

    const int32_t peak = readScope(e.getOscBuffer(ch), state.sampleRate, frame.scopes.data() + ch * kScopeColumns);
    if (state.muted[ch]) std::memset(frame.scopes.data() + ch * kScopeColumns, 0, kScopeColumns);
    f[kChPeak] = state.muted[ch] ? 0 : std::clamp<int32_t>(peak * kPeakScale / 127, 0, kPeakScale);
    if (f[kChPeak] > 0) playing++;
  }
  scalars[kScalarPlayingChannels] = playing;
  state.visuals.publish(frameStamp);
}

}  // namespace

bool furnace_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return FurnaceState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int furnace_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  FurnaceState& state = FurnaceState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  const size_t frameBytes = static_cast<size_t>(numChannels) * bytesPerSample;
  if (!state.loaded || state.ended) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * frameBytes);
    return state.loaded ? 1 : 0;
  }
  DivEngine& e = *state.engine;
  const double msPerFrame = 1000.0 * state.tempoFactor / std::max(1, state.sampleRate);
  const int64_t base = get_frames_written();
  const int loopMs = state.subsong < static_cast<int>(state.subsongLoopMs.size()) ? state.subsongLoopMs[state.subsong] : 0;

  int32_t written = 0;
  while (written < numFrames) {
    // A length the listener set ends the song there, as its own end would.
    const bool overLength = state.durationOverrideMs > 0 && state.positionMs >= state.durationOverrideMs;
    if (!e.isPlaying() || overLength) {
      if (state.loop) {
        restartLocked(state, true);
      } else {
        state.ended = true;
        break;
      }
    }

    const int32_t chunk = std::min<int32_t>(numFrames - written, kBlockFrames);
    float* outs[2] = {state.left.data(), state.right.data()};
    e.nextBuf(nullptr, outs, 0, 2, static_cast<unsigned int>(chunk));

    // Going round puts the song back at its loop point part way through the
    // block; the engine says where.
    const int loopedAt = ExilumFurnaceBridge::lastLoopPos(e);
    if (loopedAt >= 0 && loopedAt <= chunk && e.isPlaying()) {
      state.positionMs = loopMs + (chunk - loopedAt) * msPerFrame;
    } else {
      state.positionMs += chunk * msPerFrame;
    }

    for (int32_t i = 0; i < chunk; i++) {
      const float l = std::clamp(state.left[i], -1.0f, 1.0f);
      const float r = std::clamp(state.right[i], -1.0f, 1.0f);
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = l;
        if (numChannels > 1) out[1] = r;
      } else {
        int16_t* out = static_cast<int16_t*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = static_cast<int16_t>(std::lround(l * 32767.0f));
        if (numChannels > 1) out[1] = static_cast<int16_t>(std::lround(r * 32767.0f));
      }
    }
    publishVisuals(state, base + written);
    written += chunk;
  }

  if (written < numFrames) {
    std::memset(static_cast<uint8_t*>(audioData) + static_cast<size_t>(written) * frameBytes, 0,
                static_cast<size_t>(numFrames - written) * frameBytes);
    return 1;
  }
  return 0;
}
