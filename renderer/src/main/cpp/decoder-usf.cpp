//
// USF decoder. See decoder-usf.h.
//
#include "decoder-usf.h"
#include "psf-ahead.h"
#include "psf-song.h"
#include "seek-ticket.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cfenv>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

// lazyusf2's interface, and its resampler under the name it is built with -
// see lazyusf2-symbols.h.
#include "lazyusf2/usf/usf.h"
#define RESAMPLER_DECORATE USF
extern "C" {
#include "lazyusf2/usf/resampler.h"
}

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum USF";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

constexpr int kVersion = 0x21;
/** What the console makes: the stereo pair its sound code mixes everything into. */
constexpr int kChannels = 2;
const char* const kChannelNames[kChannels] = {"Left", "Right"};
/** What a voice's row is labelled with while it sounds: the game's, playing whatever it is asked to. */
const char* const kVoiceName = "Voice";

/** As many voices as there are channels to show; any more are neither shown nor muted. */
constexpr int kMaxVoices = MAX_CHANNELS;
/** The console's frames kept of each voice: the window looked at, and what the console has mixed ahead of it. */
constexpr uint32_t kVoiceFrames = 8192;
/** Bytes of the signal processor's memory that the sound code mixes in: lazyusf2's alist_buffer. */
constexpr int kMixBytes = 0x1000;
/**
 * The sound code keeps its samples two to a 32-bit word, the halves
 * swapped: sample i is at i ^ 1 on a little-endian machine (S, in
 * rsp_hle/memory.h).
 */
constexpr int kSampleSwap = 1;

/** Frames asked of the console at a time, at its own rate: a couple of its sound blocks. */
constexpr int kNativeFrames = 1024;
/** Output frames made at a time, and what a seek throws away at a time. */
constexpr int kBlockFrames = 1024;

/** Output frames kept of each side, for its trace and its level. */
constexpr uint32_t kTapFrames = 16384;
/** How often a side is looked at for its trace and its level. */
constexpr int kLookHz = 16000;
constexpr int kLevelWindowMs = 30;
/** A side at its loudest, as a share of full scale: the whole song is in it. */
constexpr float kFullScale = 1.0f;

std::atomic<int> g_stereoPercent{100};
std::atomic<bool> g_hle{true};
std::atomic<int> g_defaultLengthMs{150000};

/**
 * What a console is made from: the reserved areas of the file and its
 * libraries in the order they overlay each other - the cartridge and the
 * saved state, in blocks - and how the tags want it run.
 */
struct Setup {
  const std::vector<std::vector<uint8_t>>* sections = nullptr;
  bool compare = false;
  bool fifoFull = false;
  bool hle = true;
  int sampleRate = 48000;
  /** For a console made ahead: the voices it keeps out of its mix, as they were muted when it was asked for. */
  std::vector<uint32_t> mutedVoices;
};

int16_t clampSample(int32_t value) { return static_cast<int16_t>(std::clamp<int32_t>(value, -32768, 32767)); }

/**
 * A voice of the song, shown as a channel: what it put into the mix, left
 * and right, a ring indexed by the console's frame - the frames it hands
 * over, counted from its start.
 */
struct VoiceRow {
  /** Where its state is kept, which no other voice shares: what tells it apart. */
  uint32_t id = 0;
  std::vector<int16_t> ring = std::vector<int16_t>(static_cast<size_t>(kVoiceFrames) * 2);
  /** The ring holds the voice up to this frame, and kVoiceFrames frames before it; elsewhere it was silent. */
  int64_t heldTo = 0;

  /** Frame [frame] of side [side]. */
  int16_t at(int64_t frame, int side) const {
    if (frame < 0 || frame >= heldTo || frame < heldTo - kVoiceFrames) return 0;
    return ring[(static_cast<uint32_t>(frame) & (kVoiceFrames - 1)) * 2 + side];
  }

  /** Silence from where the ring holds the voice up to [frame], for what is written there to follow on. */
  void silenceTo(int64_t frame) {
    for (int64_t f = std::max(heldTo, frame - kVoiceFrames); f < frame; f++) {
      int16_t* out = ring.data() + (static_cast<uint32_t>(f) & (kVoiceFrames - 1)) * 2;
      out[0] = 0;
      out[1] = 0;
    }
    heldTo = std::max(heldTo, frame);
  }
};

/**
 * The voices the song's sound code has mixed, in the order it first mixed
 * them: the channels, when its voices can be told apart. Kept for the song,
 * through seeks and starts again - the console runs the same way every
 * time, so the voices come in the same order whichever console meets them,
 * and each keeps its row.
 */
struct Voices {
  std::vector<VoiceRow> rows;
  /** The channels muted: voices, or when there are none to show the two sides. */
  std::array<uint8_t, kMaxVoices> muted{};
  /** Bumped when the channels change - see usf_decoder_channel_layout. */
  std::atomic<int> layout{0};

  /** The row of voice [id], a new one the first time; -1 when there is no room for another. */
  int rowOf(uint32_t id) {
    for (size_t i = 0; i < rows.size(); i++) {
      if (rows[i].id == id) return static_cast<int>(i);
    }
    if (rows.size() >= static_cast<size_t>(kMaxVoices)) return -1;
    if (rows.empty()) {
      // The first: the channels were the two sides till now, and a side muted is no voice.
      muted.fill(0);
    } else if (rows.size() > 1) {
      // A solo stays a solo over a voice met for the first time, from its
      // first note - as the app keeps it (PlayerEngine.rereadChannels), only
      // a poll later, and the app's word is the last.
      size_t playing = 0;
      for (size_t i = 0; i < rows.size(); i++) playing += muted[i] ? 0 : 1;
      if (playing == 1) muted[rows.size()] = 1;
    }
    rows.emplace_back();
    rows.back().id = id;
    layout.fetch_add(1, std::memory_order_relaxed);
    return static_cast<int>(rows.size()) - 1;
  }

  /** The voices muted, by what tells them apart. */
  std::vector<uint32_t> mutedIds() const {
    std::vector<uint32_t> ids;
    for (size_t i = 0; i < rows.size(); i++) {
      if (muted[i]) ids.push_back(rows[i].id);
    }
    return ids;
  }

  /** A console started from the top: its frames count from nought again, so what is held of them goes. */
  void restart() {
    for (VoiceRow& row : rows) row.heldTo = 0;
  }
};

/**
 * One console's mixing, watched a voice at a time - see usf_voice_watch -
 * and where each voice's part of it lands among the frames the console
 * hands over.
 *
 * The sound code makes a block of sound at a time, an audio list, which is
 * handed over afterwards: a list's sound starts where the last one's ended,
 * or where the sound handed over has got to if that is further. It mixes
 * its block a piece at a time, every voice into each piece, and a voice
 * whose envelope changes part-way through a piece in more than one go, each
 * further along the buses - so where a go starts on the buses is where it
 * is in the piece, and a voice met again no further along is the next piece.
 *
 * A console made ahead is watched once it is the one heard: [voices] is
 * null till then. What it hands over is counted all along, and the voices
 * muted when it was asked for are kept out of its mix all along too - some
 * of what it mixes is still to be heard when it is swapped in.
 */
struct Watch {
  Voices* voices = nullptr;
  /** While [voices] is null: the voices kept out, by what tells them apart. */
  std::vector<uint32_t> mutedIds;
  /** Whether the voice being mixed is one of them, to be put back. */
  bool keepingOut = false;
  /** Frames handed over since the console started. */
  int64_t pushed = 0;
  /** Where the next list's sound goes, at the earliest. */
  int64_t next = 0;

  bool inList = false;
  int64_t listAt = 0;
  int pieceAt = 0;
  int pieceFrames = 0;
  /** The rows mixed into the piece so far, a bit each, and where on the buses each last went. */
  uint64_t inPiece = 0;
  std::array<int, kMaxVoices> lastOn{};
  /** Where the buses start: the furthest back any voice has been mixed into them. */
  int busStart = -1;

  /** The voice being mixed, -1 for none watched, and its buses as they were before. */
  int row = -1;
  int busCount = 0;
  int busBytes = 0;
  int frames = 0;
  std::array<uint16_t, 4> buses{};
  std::array<std::array<int16_t, kMixBytes / 2>, 4> before{};
  /** A bus as it is after, and each side's buses less what they were, in the sound code's order. */
  std::array<int16_t, kMixBytes / 2> after{};
  std::array<std::array<int32_t, kMixBytes / 2>, 2> sides{};

  void restart() {
    pushed = 0;
    next = 0;
    inList = false;
    row = -1;
    keepingOut = false;
    busStart = -1;
  }

  void beginMix(uint32_t voice, const uint8_t* buffer, const uint16_t* at, int count, int bytes) {
    row = -1;
    keepingOut = false;
    if (count < 1 || count > 4 || bytes <= 0) return;
    // Mixed eight samples at a time, so up to the next sixteen bytes.
    const int span = (bytes + 15) & ~15;
    for (int b = 0; b < count; b++) {
      if (at[b] + span > kMixBytes) return;
    }
    int found = -1;
    if (voices) {
      found = voices->rowOf(voice);
      if (found < 0) return;
    } else if (std::find(mutedIds.begin(), mutedIds.end(), voice) == mutedIds.end()) {
      return;
    }
    for (int b = 0; b < count; b++) {
      buses[b] = at[b];
      std::memcpy(before[b].data(), buffer + at[b], span);
    }
    row = found;
    keepingOut = !voices;
    busCount = count;
    busBytes = span;
    frames = bytes / 2;
  }

  void endMix(uint8_t* buffer) {
    if (keepingOut) {
      for (int b = 0; b < busCount; b++) std::memcpy(buffer + buses[b], before[b].data(), busBytes);
      keepingOut = false;
      return;
    }
    if (row < 0) return;
    if (!inList) {
      inList = true;
      // Never further ahead of what is handed over than the ring reaches:
      // lists whose pieces were not followed rightly would carry it off.
      listAt = next > pushed + kVoiceFrames / 2 ? pushed : std::max(next, pushed);
      pieceAt = 0;
      pieceFrames = 0;
      inPiece = 0;
    }
    const int on = buses[0];
    if (busStart < 0 || on < busStart) busStart = on;
    const uint64_t bit = uint64_t{1} << row;
    if ((inPiece & bit) && on <= lastOn[static_cast<size_t>(row)]) {
      pieceAt += pieceFrames;
      pieceFrames = 0;
      inPiece = 0;
    }
    inPiece |= bit;
    lastOn[static_cast<size_t>(row)] = on;
    const int within = (on - busStart) / 2;
    pieceFrames = std::max(pieceFrames, within + frames);

    // Its part: the dry and wet buses of a side added up, as they are after
    // less as they were. Older than the ring reaches, it is let go.
    VoiceRow& voice = voices->rows[static_cast<size_t>(row)];
    const int64_t from = listAt + pieceAt + within;
    if (from >= voice.heldTo - static_cast<int64_t>(kVoiceFrames)) {
      const int held = busBytes / 2;
      for (std::array<int32_t, kMixBytes / 2>& side : sides) std::fill_n(side.data(), held, 0);
      for (int b = 0; b < busCount; b++) {
        std::memcpy(after.data(), buffer + buses[b], busBytes);
        int32_t* side = sides[static_cast<size_t>(b & 1)].data();
        const int16_t* was = before[static_cast<size_t>(b)].data();
        for (int k = 0; k < held; k++) side[k] += after[static_cast<size_t>(k)] - was[k];
      }
      voice.silenceTo(from);
      for (int i = 0; i < frames; i++) {
        const size_t k = static_cast<size_t>(i ^ kSampleSwap);
        int16_t* out = voice.ring.data() + (static_cast<uint32_t>(from + i) & (kVoiceFrames - 1)) * 2;
        out[0] = clampSample(sides[0][k]);
        out[1] = clampSample(sides[1][k]);
      }
      voice.heldTo = std::max(voice.heldTo, from + frames);
    }
    // Muted: the buses as they were, as though it had not been mixed.
    if (voices->muted[static_cast<size_t>(row)]) {
      for (int b = 0; b < busCount; b++) std::memcpy(buffer + buses[b], before[b].data(), busBytes);
    }
    row = -1;
  }

  void listDone() {
    if (!inList) return;
    next = listAt + pieceAt + pieceFrames;
    inList = false;
  }
};

void watchMix(void* opaque, uint32_t voice, uint8_t* buffer, const uint16_t* buses, int busCount, uint16_t bytes,
              int after) {
  Watch& watch = *static_cast<Watch*>(opaque);
  if (after) {
    watch.endMix(buffer);
  } else {
    watch.beginMix(voice, buffer, buses, busCount, bytes);
  }
}

void watchListDone(void* opaque) { static_cast<Watch*>(opaque)->listDone(); }

void watchPushed(void* opaque, size_t frames) {
  static_cast<Watch*>(opaque)->pushed += static_cast<int64_t>(frames);
}

/**
 * One console: lazyusf2's state, which it lays out in place and which must
 * never move, with the resampler that brings its sound to the output rate
 * and what it has made and not yet been resampled. Freed with its memory.
 */
struct Usf {
  uint8_t* state = nullptr;
  void* resampler = nullptr;
  std::vector<int16_t> native = std::vector<int16_t>(static_cast<size_t>(kNativeFrames) * 2);
  size_t nativeAt = 0;
  size_t nativeCount = 0;
  int nativeRate = 0;
  /** Why it stopped making sound, when it did: the song is over then. */
  bool failed = false;
  /**
   * The console's frame coming out of the resampler now: a console frame for
   * each output frame at the ratio of their rates, counted as they come out
   * - so the same at the same place in the song, however it was asked for.
   */
  double heard = 0.0;
  /** Its mixing, watched; where lazyusf2 is told to send it, so it does not move either. */
  Watch watch;

  ~Usf() {
    if (resampler) USF_resampler_delete(resampler);
    if (state) {
      usf_shutdown(state);
      std::free(state);
    }
  }
};
using Console = std::unique_ptr<Usf>;

/**
 * The emulated processor sets its own rounding, and the thread it runs on
 * keeps it: put back, for the stretch and everything else after it.
 */
class Rounding {
 public:
  Rounding() : mode_(std::fegetround()) {}
  ~Rounding() { std::fesetround(mode_); }

 private:
  int mode_;
};

/** A fresh resampler at the console's rate, so what comes after a start is always the same. */
void restartResampler(Usf& usf, int outRate) {
  if (usf.resampler) USF_resampler_delete(usf.resampler);
  usf.resampler = USF_resampler_create();
  usf.nativeAt = 0;
  usf.nativeCount = 0;
  if (usf.resampler) USF_resampler_set_rate(usf.resampler, static_cast<double>(usf.nativeRate) / outRate);
}

/**
 * Starts the console from its saved state and learns the rate its game set,
 * which the samples it made for that stay to be heard. False when it has no
 * sound to give.
 */
bool startUsf(Usf& usf, int outRate) {
  Rounding rounding;
  // From the top: its frames count from nought, and so do those it is watched by.
  usf.heard = 0.0;
  usf.watch.restart();
  if (usf.watch.voices) usf.watch.voices->restart();
  int32_t rate = 0;
  const char* error = usf_render(usf.state, nullptr, 0, &rate);
  if (error != nullptr || rate <= 0) {
    LOG_INFO("the console did not start: %s", error ? error : "no rate");
    return false;
  }
  usf.nativeRate = rate;
  usf.failed = false;
  restartResampler(usf, outRate);
  return usf.resampler != nullptr;
}

/**
 * A console from cold at the top of the song; null when the file is no good
 * or there is no memory. Its voices go to [voices] when it is the one heard,
 * and to nowhere when it is made ahead.
 */
Console makeUsf(const Setup& setup, Voices* voices) {
  Console usf(new (std::nothrow) Usf());
  if (!usf) return nullptr;
  usf->state = static_cast<uint8_t*>(std::malloc(usf_get_state_size()));
  if (!usf->state) return nullptr;
  usf_clear(usf->state);
  usf->watch.voices = voices;
  if (!voices) usf->watch.mutedIds = setup.mutedVoices;
  const usf_voice_watch watch = {watchMix, watchListDone, watchPushed, &usf->watch};
  usf_set_voice_watch(usf->state, &watch);
  usf_set_compare(usf->state, setup.compare ? 1 : 0);
  usf_set_fifo_full(usf->state, setup.fifoFull ? 1 : 0);
  usf_set_hle_audio(usf->state, setup.hle ? 1 : 0);
  for (const std::vector<uint8_t>& section : *setup.sections) {
    if (usf_upload_section(usf->state, section.data(), section.size()) < 0) {
      LOG_INFO("a section of the file is no good");
      return nullptr;
    }
  }
  if (!startUsf(*usf, setup.sampleRate)) return nullptr;
  return usf;
}

/**
 * Up to [frames] frames of the console's sound at the output rate into
 * [out], interleaved; fewer when it stops. The console runs as the
 * resampler needs it to.
 */
int pull(Usf& usf, int16_t* out, int frames, int outRate) {
  Rounding rounding;
  int done = 0;
  while (done < frames) {
    while (!usf.failed && USF_resampler_get_free_count(usf.resampler) > 0) {
      if (usf.nativeAt >= usf.nativeCount) {
        int32_t rate = 0;
        if (usf_render(usf.state, usf.native.data(), kNativeFrames, &rate) != nullptr) {
          usf.failed = true;
          break;
        }
        usf.nativeAt = 0;
        usf.nativeCount = kNativeFrames;
        // A game can change its rate between two of its sound blocks.
        if (rate > 0 && rate != usf.nativeRate) {
          usf.nativeRate = rate;
          USF_resampler_set_rate(usf.resampler, static_cast<double>(rate) / outRate);
        }
      }
      const int16_t* frame = usf.native.data() + usf.nativeAt * 2;
      USF_resampler_write_sample(usf.resampler, frame[0], frame[1]);
      usf.nativeAt++;
    }
    if (USF_resampler_get_sample_count(usf.resampler) == 0) break;
    while (done < frames && USF_resampler_get_sample_count(usf.resampler) > 0) {
      short left = 0;
      short right = 0;
      USF_resampler_get_sample(usf.resampler, &left, &right);
      USF_resampler_remove_sample(usf.resampler);
      out[done * 2] = left;
      out[done * 2 + 1] = right;
      done++;
      usf.heard += static_cast<double>(usf.nativeRate) / outRate;
    }
  }
  return done;
}

struct UsfState {
  std::mutex mutex;
  /** The song's voices, and what is muted; before the console, which watches into them. */
  Voices voices;
  /** The console playing; null before a file is loaded. */
  Console usf;
  /** The reserved areas of the file and its libraries, which the console is made from. */
  std::vector<std::vector<uint8_t>> sections;
  bool compare = false;
  bool fifoFull = false;

  bool loaded = false;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  std::string title;
  std::string comment;
  psf::SongLength length;

  /** Frames handed out since the top of the song. */
  int64_t rendered = 0;
  std::vector<int16_t> block = std::vector<int16_t>(static_cast<size_t>(kBlockFrames) * 2);

  /** Pitch and tempo, which the console cannot change without each other. */
  ShiftStage stage;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;
  std::array<std::vector<float>, 2> out{std::vector<float>(1024), std::vector<float>(1024)};

  /** Each side as the console made it, a ring a side, indexed by the frame of the song. */
  std::vector<int16_t> tap;

  /** Where the A-B loop starts, -1 for none: see usf_decoder_expect_loop. */
  int loopStartMs = -1;
  /** The console made ahead for the next jump; last, so it stops before what it reads goes. */
  psf::Ahead<Console> ahead;

  VisualPublisher visuals;

  static UsfState& instance() {
    static UsfState s;
    return s;
  }
};

/** Whether the channels are the voices: the sound code done over, and voices of it told apart. */
bool voiceChannelsLocked(const UsfState& state) {
  return g_hle.load(std::memory_order_relaxed) && !state.voices.rows.empty();
}

int channelCountLocked(const UsfState& state) {
  return voiceChannelsLocked(state) ? static_cast<int>(state.voices.rows.size()) : kChannels;
}

/** The voices to keep out of a console's mix from its start: those muted, when the voices are the channels. */
std::vector<uint32_t> mutedVoicesLocked(const UsfState& state) {
  return voiceChannelsLocked(state) ? state.voices.mutedIds() : std::vector<uint32_t>();
}

Setup setupLocked(const UsfState& state) {
  Setup setup;
  setup.sections = &state.sections;
  setup.compare = state.compare;
  setup.fifoFull = state.fifoFull;
  setup.hle = g_hle.load(std::memory_order_relaxed);
  setup.sampleRate = state.sampleRate;
  setup.mutedVoices = mutedVoicesLocked(state);
  return setup;
}

/**
 * Runs the console on [frames] frames, heard by nobody - or, where it
 * [mayGiveUp], as many of them as it has run when the seek it is for is
 * overtaken. Caller holds the mutex.
 */
void discardLocked(UsfState& state, int64_t frames, bool mayGiveUp = false) {
  while (frames > 0 && state.usf && !state.usf->failed) {
    if (mayGiveUp && engine_seekOvertaken()) break;
    const int chunk = static_cast<int>(std::min<int64_t>(frames, kBlockFrames));
    const int got = pull(*state.usf, state.block.data(), chunk, state.sampleRate);
    state.rendered += got;
    frames -= got;
    if (got < chunk) break;
  }
}

/** The console at the top of the song: its own started again, or a new one. False when that fails. */
bool startLocked(UsfState& state) {
  state.rendered = 0;
  state.ended = false;
  std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
  if (state.usf) {
    usf_restart(state.usf->state);
    usf_set_hle_audio(state.usf->state, g_hle.load(std::memory_order_relaxed) ? 1 : 0);
    if (startUsf(*state.usf, state.sampleRate)) return true;
  }
  state.usf = makeUsf(setupLocked(state), &state.voices);
  return state.usf != nullptr;
}

/** A console made ahead: made, and run to [targetMs] - exactly, as playing there would have. */
psf::Ahead<Console>::Made makeAhead(const Setup& setup, int targetMs, const std::function<bool()>& wanted) {
  psf::Ahead<Console>::Made made;
  // Watched once it is the one heard, not while it is made.
  Console usf = makeUsf(setup, nullptr);
  if (!usf) return made;
  const int64_t frame = psf::msToFrames(targetMs, setup.sampleRate);
  std::vector<int16_t> block(static_cast<size_t>(kBlockFrames) * 2);
  int64_t done = 0;
  while (done < frame) {
    if (!wanted()) return made;
    const int chunk = static_cast<int>(std::min<int64_t>(frame - done, kBlockFrames));
    const int got = pull(*usf, block.data(), chunk, setup.sampleRate);
    done += got;
    if (got < chunk) return made;
  }
  made.console = std::move(usf);
  made.frame = done;
  return made;
}

/** Where a console made ahead is wanted, if anywhere: the A-B loop's start, or the top when the song repeats. */
int aheadWantedLocked(const UsfState& state) {
  if (!state.loaded) return -1;
  return state.loopStartMs >= 0 ? state.loopStartMs : state.loop ? 0 : -1;
}

/**
 * Asks for a console ahead where one is wanted, dropping one made for
 * anywhere else. [again] asks even when one is made or on its way for the
 * same place: when the one there was just used, or no longer fits. Caller
 * holds the mutex.
 */
void askAheadLocked(UsfState& state, bool again) {
  const int wanted = aheadWantedLocked(state);
  if (!again && wanted == state.ahead.wantedMs()) return;
  const Setup setup = setupLocked(state);
  state.ahead.ask(wanted, [setup, wanted](const std::function<bool()>& stillWanted) {
    return makeAhead(setup, wanted, stillWanted);
  });
}

/** Swaps in the console made ahead for [ms], when there is one at [target] exactly. Caller holds the mutex. */
bool takeAheadLocked(UsfState& state, int ms, int64_t target) {
  psf::Ahead<Console>::Made made;
  if (!state.ahead.take(ms, target, made)) return false;
  // Made with other voices kept out than are muted now, some of what it has
  // mixed is wrong to hear.
  if (made.frame != target || made.console->watch.mutedIds != mutedVoicesLocked(state)) {
    state.ahead.discard(std::move(made.console));
    // Another, for the voices as they are now. Nothing asked again, and
    // every pass after this one started the console from the top where the
    // sound is made - or ran it, silent, all the way to a loop's start.
    askAheadLocked(state, true);
    return false;
  }
  // The one going is freed on the worker's thread, watching nothing.
  if (state.usf) state.usf->watch.voices = nullptr;
  state.ahead.discard(std::move(state.usf));
  state.usf = std::move(made.console);
  // Its frames are counted from its own start, where what is held of the last one's are not.
  state.usf->watch.voices = &state.voices;
  state.usf->watch.mutedIds.clear();
  state.voices.restart();
  state.rendered = made.frame;
  state.ended = false;
  std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
  return true;
}

/**
 * The console has no way to start part-way, so a seek back starts it again
 * and every seek runs it up to the place, as fast as it goes. The console
 * made ahead stands in when it is for the place.
 */
void seekLocked(UsfState& state, int ms, bool keepVisuals, bool mayGiveUp = false) {
  if (!state.loaded) return;
  const auto started = std::chrono::steady_clock::now();
  const int total = state.length.totalMs();
  const int64_t target = psf::msToFrames(std::clamp(ms, 0, std::max(0, total - 1)), state.sampleRate);
  const int64_t from = target < state.rendered || !state.usf ? 0 : state.rendered;
  if (target < state.rendered || !state.usf) {
    if (takeAheadLocked(state, ms, target)) {
      askAheadLocked(state, true);
    } else if (!startLocked(state)) {
      state.ended = true;
      return;
    }
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
int produceLocked(UsfState& state, float* left, float* right, int frames) {
  int done = 0;
  while (done < frames) {
    const int64_t end = state.length.endFrame(state.sampleRate);
    if (state.rendered >= end || !state.usf || state.usf->failed) {
      // A console that stopped making sound before its time was up would stop
      // in the same place again, as it runs the same way every time: that is
      // the end of the song, repeated or not.
      if (!state.loop || !state.usf || state.usf->failed) break;
      // Round again from the top: the console made ahead for it if there is
      // one, at once, and if not this one started again.
      if (takeAheadLocked(state, 0, 0)) {
        askAheadLocked(state, true);
      } else if (!startLocked(state)) {
        break;
      }
    }
    const int chunk = static_cast<int>(
        std::min<int64_t>({static_cast<int64_t>(frames - done), end - state.rendered, kBlockFrames}));
    if (chunk <= 0) break;
    // Read as the console runs its sound code: the setting takes at once.
    usf_set_hle_audio(state.usf->state, g_hle.load(std::memory_order_relaxed) ? 1 : 0);
    const int got = pull(*state.usf, state.block.data(), chunk, state.sampleRate);
    const int16_t* in = state.block.data();
    // The sides apart by the setting: as they are at 100, one at 0.
    const float width = g_stereoPercent.load(std::memory_order_relaxed) / 100.0f;
    // A side muted, when the sides are the channels; a voice is muted as it is mixed.
    const bool sides = !voiceChannelsLocked(state);
    const float keep[kChannels] = {sides && state.voices.muted[0] ? 0.0f : 1.0f,
                                   sides && state.voices.muted[1] ? 0.0f : 1.0f};
    for (int i = 0; i < got; i++) {
      const uint32_t at = static_cast<uint32_t>(state.rendered + i) & (kTapFrames - 1);
      state.tap[at] = in[i * 2];
      state.tap[kTapFrames + at] = in[i * 2 + 1];
      const float gain = state.length.gainAt(state.rendered + i, state.sampleRate) / 32768.0f;
      float sides[2] = {in[i * 2] * gain * keep[0], in[i * 2 + 1] * gain * keep[1]};
      psf::spread(sides[0], sides[1], width);
      left[done + i] = sides[0];
      right[done + i] = sides[1];
    }
    state.rendered += got;
    done += got;
    // The console stopped making sound: the song is over.
    if (got < chunk) state.usf->failed = true;
  }
  return done;
}

/** A channel's trace into [out]: [look](back) is the look [back] before the newest, and the loudest reaches the edge. */
template <typename Look>
void drawScope(int8_t* out, const Look& look) {
  float span = kFullScale / 64.0f;
  for (int x = 0; x < kScopeColumns; x++) span = std::max(span, std::fabs(look(x)));
  for (int x = 0; x < kScopeColumns; x++) {
    const float sample = look(kScopeColumns - 1 - x);
    out[x] = static_cast<int8_t>(std::clamp(static_cast<int>(sample / span * 127.0f), -127, 127));
  }
}

int32_t peakOf(float loudest, bool muted) {
  return muted ? 0 : std::clamp(static_cast<int32_t>(loudest / kFullScale * kPeakScale), 0, kPeakScale);
}

/** The two sides as the console made them, for when they are the channels. Returns how many sound. */
int publishSides(UsfState& state, VisualFrame& frame) {
  const uint32_t heard = static_cast<uint32_t>(state.rendered);
  const uint32_t step = static_cast<uint32_t>(std::max(1, state.sampleRate / kLookHz));
  const int levelLooks = kLookHz * kLevelWindowMs / 1000;
  int sounding = 0;
  for (int c = 0; c < kChannels; c++) {
    int32_t* f = frame.channels.data() + c * kChannelFields;
    const bool muted = state.voices.muted[static_cast<size_t>(c)] != 0;
    const int16_t* ring = state.tap.data() + static_cast<size_t>(c) * kTapFrames;
    const auto look = [&](int back) {
      return ring[(heard - 1 - static_cast<uint32_t>(back) * step) & (kTapFrames - 1)] / 32768.0f;
    };

    float loudest = 0.0f;
    for (int i = 0; i < levelLooks; i++) loudest = std::max(loudest, std::fabs(look(i)));
    f[kChPeak] = peakOf(loudest, muted);
    if (f[kChPeak] > 0) sounding++;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    f[kChVolume] = muted ? 0 : 64;
    f[kChPan] = c == 0 ? 0 : 255;
    f[kChInstrument] = c;
    f[kChSample] = 0;
    if (!muted) drawScope(frame.scopes.data() + c * kScopeColumns, look);
  }
  return sounding;
}

/**
 * Each voice as it went into the mix, for when the voices are the channels:
 * looked at where the console's sound is coming out of the resampler, at
 * the console's own rate. Returns how many sound.
 */
int publishVoices(UsfState& state, VisualFrame& frame) {
  const Usf* usf = state.usf.get();
  const int64_t heard = usf ? static_cast<int64_t>(usf->heard) : 0;
  const int rate = usf && usf->nativeRate > 0 ? usf->nativeRate : state.sampleRate;
  const int step = std::max(1, rate / kLookHz);
  const int levelLooks = std::max(1, rate * kLevelWindowMs / 1000 / step);
  // The furthest back anything is looked at: a voice heard of last before it is silent throughout.
  const int64_t oldest = heard - static_cast<int64_t>(std::max(levelLooks, kScopeColumns)) * step;
  int sounding = 0;
  for (size_t c = 0; c < state.voices.rows.size(); c++) {
    const VoiceRow& voice = state.voices.rows[c];
    int32_t* f = frame.channels.data() + c * kChannelFields;
    const bool muted = state.voices.muted[c] != 0;
    if (voice.heldTo <= oldest) {
      f[kChVolume] = muted ? 0 : 64;
      f[kChPan] = 128;
      f[kChInstrument] = -1;
      continue;
    }
    const auto side = [&](int back, int which) {
      return voice.at(heard - 1 - static_cast<int64_t>(back) * step, which) / 32768.0f;
    };

    float loudest = 0.0f;
    double energy[2] = {0.0, 0.0};
    for (int i = 0; i < levelLooks; i++) {
      const float left = side(i, 0);
      const float right = side(i, 1);
      loudest = std::max({loudest, std::fabs(left), std::fabs(right)});
      energy[0] += left * left;
      energy[1] += right * right;
    }
    f[kChPeak] = peakOf(loudest, muted);
    if (f[kChPeak] > 0) sounding++;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    f[kChVolume] = muted ? 0 : 64;
    // Where it sits between the sides, by how much of it is in each; the middle while it is silent.
    f[kChPan] = energy[0] + energy[1] > 0.0
                    ? static_cast<int32_t>(std::lround(std::atan2(std::sqrt(energy[1]), std::sqrt(energy[0])) /
                                                       (M_PI / 2.0) * 255.0))
                    : 128;
    f[kChInstrument] = f[kChPeak] > 0 ? 0 : -1;
    f[kChSample] = 0;
    if (!muted) {
      drawScope(frame.scopes.data() + c * kScopeColumns,
                [&](int back) { return (side(back, 0) + side(back, 1)) * 0.5f; });
    }
  }
  return sounding;
}

void publishVisuals(UsfState& state, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  const bool voices = voiceChannelsLocked(state);
  frame.scalars[kScalarNumChannels] = channelCountLocked(state);
  frame.scalars[kScalarTimeMs] = static_cast<int32_t>(state.rendered * 1000 / std::max(1, state.sampleRate));
  frame.scalars[kScalarTotalTimeMs] = state.length.totalMs();
  frame.scalars[kScalarGlobalVolume] = kGlobalVolumeUnity;
  frame.scalars[kScalarPlayingChannels] = voices ? publishVoices(state, frame) : publishSides(state, frame);
  state.visuals.publish(frameStamp);
}

void releaseLocked(UsfState& state) {
  state.loaded = false;
  // The worker reads the sections, so it stops before they go.
  state.ahead.stop();
  state.usf.reset();
  state.sections.clear();
  state.sections.shrink_to_fit();
  state.tap.clear();
  state.tap.shrink_to_fit();
  state.voices.rows.clear();
  state.voices.rows.shrink_to_fit();
  state.voices.muted.fill(0);
  state.visuals.reset();
}

/** A tag that is set at all: lazyusf2's own players take any value as yes. */
bool flagTag(const psf::File& file, const char* key) { return !file.tag(key).empty(); }

/** The reserved areas of the file and its libraries in the order they overlay each other, and its flags. */
bool readFileLocked(UsfState& state, const psf::File& file) {
  bool compare = false;
  bool fifoFull = false;
  std::vector<std::vector<uint8_t>> sections;
  const bool whole = psf::loadWithLibraries(file, [&](const psf::File& part) {
    // A file of tags alone adds nothing; lazyusf2 takes no empty section.
    if (part.reserved.size() >= 4) sections.push_back(part.reserved);
    compare = compare || flagTag(part, "_enablecompare");
    fifoFull = fifoFull || flagTag(part, "_enablefifofull");
    return true;
  });
  if (!whole || sections.empty()) {
    LOG_INFO("no console: a library the tags name is missing or unreadable");
    return false;
  }
  state.sections = std::move(sections);
  state.compare = compare;
  state.fifoFull = fifoFull;
  state.tap.assign(static_cast<size_t>(kChannels) * kTapFrames, 0);
  return true;
}

}  // namespace

bool usf_decoder_probe(const uint8_t* data, size_t size) { return psf::probe(data, size, kVersion); }

bool usf_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
  if (!psf::probe(data, size, kVersion)) return false;
  psf::File file;
  bool read = false;
  try {
    read = psf::parse(data, size, file) && readFileLocked(state, file);
  } catch (const std::bad_alloc&) {
    LOG_INFO("no memory for the file");
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
  // Room for every voice, so the rows never move under the console watching into them.
  state.voices.rows.reserve(kMaxVoices);
  state.voices.layout.store(0, std::memory_order_relaxed);
  if (!startLocked(state)) {
    releaseLocked(state);
    return false;
  }
  state.visuals.reset();
  state.loaded = true;
  askAheadLocked(state, true);
  LOG_INFO("loaded: %zu sections, the console at %d Hz, %d ms and %d ms of fade", state.sections.size(),
           state.usf->nativeRate, state.length.lengthMs, state.length.fadeMs);
  return true;
}

void usf_decoder_release() {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool usf_decoder_loaded() { return UsfState::instance().loaded; }
const char* usf_decoder_type() { return "USF"; }
const char* usf_decoder_title() { return UsfState::instance().title.c_str(); }
const char* usf_decoder_comment() { return UsfState::instance().comment.c_str(); }

void usf_decoder_prepare_start(int rate) {
  usf_decoder_set_sample_rate(rate);
  UsfState::instance().visuals.reset();
}

void usf_decoder_set_sample_rate(int rate) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate) return;
  // The place kept in time, not in frames, which change with the rate.
  const int atMs = static_cast<int>(state.rendered * 1000 / std::max(1, state.sampleRate));
  state.sampleRate = rate;
  state.stage.configure(rate);
  if (!state.loaded) return;
  // Started again and run up to the place, resampled for the new rate - and
  // one made ahead is no good.
  askAheadLocked(state, true);
  state.rendered = 0;
  if (!startLocked(state)) {
    state.ended = true;
    return;
  }
  seekLocked(state, atMs, true);
}

int usf_decoder_duration_ms() { return UsfState::instance().length.totalMs(); }
bool usf_decoder_duration_known() { return UsfState::instance().length.known; }

int usf_decoder_position_ms() {
  UsfState& state = UsfState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

void usf_decoder_seek_ms(int ms) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  // The listener's own: given up on the way if something overtakes it.
  seekLocked(state, ms, false, true);
}

bool usf_decoder_ended() { return UsfState::instance().ended; }

void usf_decoder_set_loop(bool loop) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop;
  askAheadLocked(state, false);
}

void usf_decoder_expect_loop(int startMs) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loopStartMs = startMs >= 0 ? startMs : -1;
  askAheadLocked(state, false);
}

double usf_decoder_render_position_ms() {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return (state.rendered - state.stage.latencyFrames()) * 1000.0 / std::max(1, state.sampleRate);
}

void usf_decoder_loop_seek_ms(int ms) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void usf_decoder_set_duration_override_ms(int ms) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.length.overrideMs = ms > 0 ? ms : 0;
}

void usf_decoder_set_pitch(double semitones) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  state.stage.setPitch(state.pitchSemitones);
}

void usf_decoder_set_tempo(double percent) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  state.stage.setTempo(state.tempoFactor);
}

void usf_decoder_set_stereo(int percent) {
  g_stereoPercent.store(std::clamp(percent, 0, 200), std::memory_order_relaxed);
}

void usf_decoder_set_hle(bool hle) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (g_hle.exchange(hle, std::memory_order_relaxed) == hle) return;
  // The voices that were the channels are not watched the other way: the
  // sides are, until it comes back - and what was muted was the others.
  if (!state.voices.rows.empty()) {
    state.voices.muted.fill(0);
    state.voices.layout.fetch_add(1, std::memory_order_relaxed);
  }
  // A console made ahead was made the other way.
  if (state.loaded) askAheadLocked(state, true);
}

void usf_decoder_set_default_length_ms(int ms) {
  g_defaultLengthMs.store(std::max(1000, ms), std::memory_order_relaxed);
  // To what is playing as well, when its file gave it no length.
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.loaded && !state.length.known) {
    state.length.lengthMs = g_defaultLengthMs.load(std::memory_order_relaxed);
  }
}

int usf_decoder_channels() {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.loaded ? channelCountLocked(state) : 0;
}

int usf_decoder_channel_names() {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return voiceChannelsLocked(state) ? 1 : kChannels;
}

const char* usf_decoder_channel_name(int index) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (voiceChannelsLocked(state)) return index == 0 ? kVoiceName : "";
  return index >= 0 && index < kChannels ? kChannelNames[index] : "";
}

int usf_decoder_channel_layout() {
  return UsfState::instance().voices.layout.load(std::memory_order_relaxed);
}

void usf_decoder_mute(int channel, int muted) {
  UsfState& state = UsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= kMaxVoices) return;
  uint8_t& slot = state.voices.muted[static_cast<size_t>(channel)];
  if (slot == (muted ? 1 : 0)) return;
  slot = muted ? 1 : 0;
  // A console made ahead keeps out the voices muted when it was asked for.
  if (state.loaded && voiceChannelsLocked(state) && static_cast<size_t>(channel) < state.voices.rows.size()) {
    askAheadLocked(state, true);
  }
}

int usf_decoder_muted(int channel) {
  const UsfState& state = UsfState::instance();
  return channel >= 0 && channel < kMaxVoices ? state.voices.muted[static_cast<size_t>(channel)] : 0;
}

bool usf_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return UsfState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int usf_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  UsfState& state = UsfState::instance();
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
