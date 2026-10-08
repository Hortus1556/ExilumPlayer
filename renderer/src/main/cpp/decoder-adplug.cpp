//
// AdPlug decoder. See decoder-adplug.h.
//
#include "decoder-adplug.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include "audio.h"
#include "psf.h"

#include <adplug/adplug.h>
#include <adplug/emuopl.h>
#include <adplug/fprovide.h>
#include <adplug/kemuopl.h>
#include <adplug/wemuopl.h>
#include <adplug/opl.h>
#include <adplug/player.h>
#include <adplug/players.h>
#include <adplug/silentopl.h>
extern "C" {
#include <adplug/nukedopl.h>
}
#include <binio.h>
#include <binstr.h>

#include <android/log.h>
#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

constexpr const char* TAG = "Exilum AdPlug";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

/** An OPL3 has eighteen channels; an OPL2 uses the first nine of them. */
constexpr int kOplChannels = 18;

/** The rate a Yamaha OPL runs at, and the one every note calculation assumes. */
constexpr double kOplRate = 49716.0;

/** C-0 in the note numbering libopenmpt uses, where 61 is middle C. */
constexpr double kNoteC0Hz = 8.1758;

/** Samples of history each channel keeps for its trace. */
constexpr int kScopeHistory = 2048;

/**
 * Output samples one trace spans.
 *
 * About eleven milliseconds, which shows a handful of cycles of a mid note
 * rather than the forty of the whole buffer - at that density every channel
 * reads as a solid band whatever it is playing.
 */
constexpr int kScopeSpan = 512;

/** Quietest a channel can be and still be normalised to fill its box. */
constexpr int32_t kScopeNoiseFloor = 800;

/**
 * How loud one channel has to be to be drawn full height.
 *
 * Not the chip's own full scale, which for a single carrier is about 4084.
 * Nine to eighteen channels mix into one sixteen-bit output, so a channel
 * that reached full scale on its own would clip the moment anything else
 * played - which is why an AdLib tune keeps every carrier well attenuated,
 * and why measuring against full scale drew every one of them as a sliver.
 * A ninth of it is what a channel in a full mix actually runs at, and a
 * trace carries both sides of the pan, so the figure is that doubled.
 */
constexpr int32_t kOplChannelReference = 4084 / 4;

/** The same reference as an attenuation, in the chip's own 0.1875dB units. */
constexpr int kReferenceAttenuation = 96;

/**
 * What one carrier at no attenuation puts out, on either side - the most one
 * two-operator channel can. The level a channel's volume bar shows is
 * measured against this.
 */
constexpr int32_t kOplCarrierFullScale = 4084;

/** How far back a channel's level looks - longer than one cycle of a low note. */
constexpr int kLevelWindowMs = 30;

/**
 * The emulators a tune can be heard through, in the order the setting lists
 * them. See ScopeOpl for what each one changes and what it does not.
 */
enum OplCore : int {
  kCoreNuked = 0,   // Nuked OPL3 - cycle-accurate, and the one the views read
  kCoreDosBox = 1,  // DOSBox's OPL3 emulator ("Woody")
  kCoreMame = 2,    // MAME's YM3812, two of them - OPL2 only
  kCoreKen = 3,     // Ken Silverman's AdLibEmu, two of them - OPL2 only
  kOplCoreCount = 4,
};

/** Longest a subsong is measured for. Matches AdPlug's own limit. */
constexpr int kMaxSongMs = 600000;

/** Distinct patches a tune can be told apart by before they stop being named. */
constexpr int kMaxPatches = 64;

/** Register bytes that make up one OPL voice: five per operator, plus C0. */
constexpr int kPatchBytes = 11;

/**
 * The two operators of each of the nine channels in an OPL register bank.
 *
 * The chip lays its operators out in threes with a gap, which is why this is
 * a table rather than a multiplication.
 */
constexpr uint8_t kOperator1[9] = {0x00, 0x01, 0x02, 0x08, 0x09, 0x0a, 0x10, 0x11, 0x12};

/** A file's own name, without its folders, in lower case: what two names for one file have in common. */
std::string leafLower(const std::string& path) {
  const size_t slash = path.find_last_of("/\\");
  std::string leaf = slash == std::string::npos ? path : path.substr(slash + 1);
  std::transform(leaf.begin(), leaf.end(), leaf.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return leaf;
}

/**
 * Hands AdPlug the bytes it was given rather than a path.
 *
 * The factory tries every player in turn and each one opens the file for
 * itself, so this has to return a fresh stream every time. The name is
 * carried along because several players check the extension before they will
 * even look at the contents.
 *
 * And because the tune is not the only file a player asks for. Some keep
 * their instruments in another - a .ksm's insts.dat, a .rol's standard.bnk,
 * the bank beside a .mus - and ask for that by its name. Asked for anything,
 * this used to hand over the tune: a .ksm read its two hundred instruments
 * out of its own notes, and was listed and played as a tune, in whatever
 * voices that made. Another file is now looked for among those that came
 * with the tune, and is not there when it is not - which a player takes as
 * the file it cannot load.
 */
class MemoryProvider : public CFileProvider {
 public:
  MemoryProvider(const uint8_t* data, size_t size, const std::string& name)
      : data_(data), size_(size), name_(leafLower(name)) {}

  binistream* open(std::string name) const override {
    const uint8_t* bytes = data_;
    size_t length = size_;
    if (leafLower(name) != name_) {
      auto other = std::make_unique<std::vector<uint8_t>>();
      if (!psf::companionFile(name, *other)) return nullptr;
      bytes = other->data();
      length = other->size();
      // Kept for as long as this is: the stream reads it where it lies.
      others_.push_back(std::move(other));
    }
    auto* stream = new binisstream(const_cast<uint8_t*>(bytes), length);
    stream->setFlag(binio::BigEndian, false);
    stream->setFlag(binio::FloatIEEE);
    return stream;
  }

  void close(binistream* stream) const override { delete stream; }

 private:
  const uint8_t* data_;
  size_t size_;
  std::string name_;
  mutable std::vector<std::unique_ptr<std::vector<uint8_t>>> others_;
};

/**
 * The OPL chip the players write to.
 *
 * A Copl in front of Nuked's OPL3 core, which keeps f_num, block and the key
 * bit per channel, so the notes shown are the ones written to the chip. The
 * per-channel audio is the core's own too: it hands back what each channel
 * put into the mix, resampled with the mix and summing to exactly it, so a
 * trace is that channel's output and muting one is subtracting what it
 * contributed. See adplug/ANDROID-CHANGES.md.
 *
 * This used to read the slot outputs behind the resampler's back and rebuild
 * the mix from them whenever anything was muted - two different sums, one of
 * them unresampled, chosen between per sample. There is one now.
 *
 * With another core chosen, that core is what is heard, and Nuked keeps
 * running beside it on the same writes for everything the views read - the
 * notes, volumes and pans, and the per-channel traces no other core can
 * produce. What that costs the other cores: a mute is done at the chip, by
 * holding the channel's two operators at full attenuation in what that core
 * is sent, since there is no per-channel output to subtract. The two OPL2
 * cores are a pair of OPL2s, so an OPL3 tune's upper bank goes to the second
 * chip.
 */
class ScopeOpl : public Copl {
 public:
  ScopeOpl(int rate, int core) : core_(std::clamp(core, 0, kOplCoreCount - 1)) { reset(rate); }

  void reset(int rate) {
    rate_ = rate > 0 ? rate : 48000;
    OPL3_Reset(&chip_, static_cast<uint32_t>(rate_));
    sound_ = makeCore(core_, rate_);
    for (auto& bank : operatorLevel_) bank.fill(0);
    clearScopes();
    currType = TYPE_OPL3;
  }

  /** Switches the core that is heard, from silence - the caller replays the tune to where it was. */
  void setCore(int core) {
    core_ = std::clamp(core, 0, kOplCoreCount - 1);
    reset(rate_);
  }

  void init() override { reset(rate_); }

  void write(int reg, int val) override {
    // The second chip of a dual-OPL2 pair and the OPL3's own upper bank are
    // the same 0x100 register window.
    const int bank = currChip ? 9 : 0;
    const int masked = reg & 0xff;

    // A0 and B0 carry the note. They are held rather than passed straight
    // through, because a global pitch shift is a change to exactly these two
    // and the pair has to be rewritten together - the f-number spans both.
    if (masked >= 0xa0 && masked <= 0xa8) {
      const int channel = bank + (masked - 0xa0);
      freqLow_[channel] = static_cast<uint8_t>(val);
      writeFrequency(channel);
      return;
    }
    if (masked >= 0xb0 && masked <= 0xb8) {
      const int channel = bank + (masked - 0xb0);
      freqHigh_[channel] = static_cast<uint8_t>(val);
      writeFrequency(channel);
      return;
    }

    const auto address = static_cast<uint16_t>((currChip ? 0x100 : 0) | masked);
    emit(address, static_cast<uint8_t>(val));
    recordPatchByte(bank, masked, static_cast<uint8_t>(val));
  }

  void setPitch(double semitones) {
    pitch_ = std::clamp(semitones, -12.0, 12.0);
    pitchFactor_ = std::pow(2.0, pitch_ / 12.0);
    for (int ch = 0; ch < kOplChannels; ch++) writeFrequency(ch);
  }

  /** Which patch this channel is currently loaded with, or 0 for none yet. */
  int patchId(int channel) const {
    return (channel >= 0 && channel < kOplChannels) ? patchId_[channel] : 0;
  }

  int patchCount() const { return patchCount_; }

  /** Renders [samples] frames of stereo, recording each channel on the way. */
  void update(short* buf, int samples) override {
    if (sound_) {
      // The other core's whole block first; Nuked is clocked below all the
      // same, so the traces keep time with it.
      if (soundBuf_.size() < static_cast<size_t>(samples) * 2) {
        soundBuf_.resize(static_cast<size_t>(samples) * 2);
      }
      sound_->update(soundBuf_.data(), samples);
    }
    std::array<int16_t, kOplChannels * 2> voices{};
    for (int i = 0; i < samples; i++) {
      int16_t mix[2] = {0, 0};
      OPL3_GenerateResampledChannels(&chip_, mix, voices.data());

      if (sound_) {
        for (int ch = 0; ch < kOplChannels; ch++) {
          scope_[ch][scopeWrite_] =
              muted_[ch] ? 0 : clip(static_cast<int32_t>(voices[ch * 2]) + voices[ch * 2 + 1]);
          level_[ch][scopeWrite_] = muted_[ch] ? 0 : louderSide(voices[ch * 2], voices[ch * 2 + 1]);
        }
        scopeWrite_ = (scopeWrite_ + 1) % kScopeHistory;
        buf[i * 2] = clip(static_cast<int32_t>(soundBuf_[i * 2]) * gain_ / 100);
        buf[i * 2 + 1] = clip(static_cast<int32_t>(soundBuf_[i * 2 + 1]) * gain_ / 100);
        continue;
      }

      // Muting is a subtraction, because it cannot be done at the chip: an
      // OPL channel's registers are shared state the player rewrites
      // constantly, so anything switched off there is switched straight back
      // on. What comes back here is exactly what each channel put into the
      // mix, so taking one out leaves the mix the core would have made
      // without it - and with nothing muted, the core's own output, untouched.
      int32_t left = mix[0];
      int32_t right = mix[1];
      for (int ch = 0; ch < kOplChannels; ch++) {
        const int16_t l = voices[ch * 2];
        const int16_t r = voices[ch * 2 + 1];
        if (muted_[ch]) {
          left -= l;
          right -= r;
          scope_[ch][scopeWrite_] = 0;
          level_[ch][scopeWrite_] = 0;
          continue;
        }
        scope_[ch][scopeWrite_] = clip(static_cast<int32_t>(l) + r);
        level_[ch][scopeWrite_] = louderSide(l, r);
      }
      scopeWrite_ = (scopeWrite_ + 1) % kScopeHistory;

      buf[i * 2] = clip(left * gain_ / 100);
      buf[i * 2 + 1] = clip(right * gain_ / 100);
    }
  }

  void clearScopes() {
    for (auto& channel : scope_) channel.fill(0);
    for (auto& channel : level_) channel.fill(0);
    scopeWrite_ = 0;
  }

  /**
   * Sends a channel's note to the chip, shifted by the global pitch.
   *
   * The OPL says what to play as a ten-bit f-number and a three-bit block,
   * which is a frequency and the octave it sits in - so a pitch shift is a
   * multiplication and a renormalisation, and the tempo never enters into it.
   * Rewriting B0 is safe at any time: the key bit is a level, and Nuked's
   * key-on only ors it in.
   */
  void writeFrequency(int channel) {
    if (channel < 0 || channel >= kOplChannels) return;
    const int bank = channel >= 9 ? 0x100 : 0;
    const int reg = channel % 9;

    int fnum = freqLow_[channel] | ((freqHigh_[channel] & 0x03) << 8);
    int block = (freqHigh_[channel] >> 2) & 0x07;

    if (pitch_ != 0 && fnum > 0) {
      double shifted = fnum * pitchFactor_;
      while (shifted > 1023.0 && block < 7) {
        shifted /= 2.0;
        block++;
      }
      while (shifted < 512.0 && block > 0) {
        shifted *= 2.0;
        block--;
      }
      fnum = std::clamp(static_cast<int>(std::lround(shifted)), 1, 1023);
    }

    emit(static_cast<uint16_t>(bank | (0xa0 + reg)), static_cast<uint8_t>(fnum & 0xff));
    emit(static_cast<uint16_t>(bank | (0xb0 + reg)),
         static_cast<uint8_t>((freqHigh_[channel] & 0xe0) | (block << 2) | ((fnum >> 8) & 0x03)));
  }

  void clearPatches() {
    for (auto& p : patch_) p.fill(0);
    patchId_.fill(0);
    patches_.clear();
    patchCount_ = 0;
  }

  void setMuted(int channel, bool muted) {
    if (channel < 0 || channel >= kOplChannels) return;
    if (muted_[channel] == muted) return;
    muted_[channel] = muted;
    if (!sound_) return;
    // The other core has no output of its own per channel to take away, so
    // the channel is silenced where it is made: both operators' levels are
    // sent again, held at full attenuation while muted - see levelFor.
    const int bank = channel >= 9 ? 1 : 0;
    const int base = kOperator1[channel % 9];
    for (const int op : {base, base + 3}) {
      sound_->setchip(bank);
      sound_->write(0x40 + op, levelFor(bank, op, operatorLevel_[bank][op]));
    }
  }

  void setGain(int percent) { gain_ = std::clamp(percent, 10, 400); }

  /**
   * Where this channel sits in the stereo field, 0..255: the chip's own
   * placement, left, right or the middle - which is where it is heard.
   */
  int panOf(int channel) const {
    if (channel < 0 || channel >= kOplChannels) return 128;
    const opl3_channel& c = chip_.channel[channel];
    const bool left = c.cha != 0;
    const bool right = c.chb != 0;
    if (left == right) return 128;
    return left ? 0 : 255;
  }

  bool isMuted(int channel) const {
    return channel >= 0 && channel < kOplChannels && muted_[channel];
  }

  const opl3_chip& chip() const { return chip_; }
  const std::array<int16_t, kScopeHistory>& scope(int ch) const { return scope_[ch]; }
  /** The louder side of each sample the channel put out, written alongside its trace. */
  const std::array<int16_t, kScopeHistory>& level(int ch) const { return level_[ch]; }
  int scopeWrite() const { return scopeWrite_; }

 private:
  static int16_t clip(int32_t v) {
    return static_cast<int16_t>(std::clamp<int32_t>(v, -32768, 32767));
  }

  static std::unique_ptr<Copl> makeCore(int core, int rate) {
    switch (core) {
      case kCoreDosBox: return std::make_unique<CWemuopl>(rate, true, true);
      case kCoreMame: return std::make_unique<CEmuopl>(rate, true, true);
      case kCoreKen: return std::make_unique<CKemuopl>(rate, true, true);
      default: return nullptr;
    }
  }

  /** One register write, to Nuked and to the core being heard when that is another. */
  void emit(uint16_t address, uint8_t value) {
    OPL3_WriteRegBuffered(&chip_, address, value);
    if (!sound_) return;
    const int bank = address >> 8;
    const int reg = address & 0xff;
    if ((reg & 0xe0) == 0x40) {
      operatorLevel_[bank & 1][reg & 0x1f] = value;
      value = levelFor(bank & 1, reg & 0x1f, value);
    }
    sound_->setchip(bank & 1);
    sound_->write(reg, value);
  }

  /**
   * An operator's key scaling and level register as the heard core gets it:
   * as written, or at full attenuation while its channel is muted.
   */
  uint8_t levelFor(int bank, int op, uint8_t value) const {
    for (int ch = 0; ch < 9; ch++) {
      if (op != kOperator1[ch] && op != kOperator1[ch] + 3) continue;
      return muted_[bank * 9 + ch] ? static_cast<uint8_t>((value & 0xc0) | 0x3f) : value;
    }
    return value;
  }

  opl3_chip chip_{};
  int core_ = kCoreNuked;
  std::unique_ptr<Copl> sound_;
  std::vector<int16_t> soundBuf_;
  /** The 0x40-0x55 register of each operator, per bank, as the tune wrote it. */
  std::array<std::array<uint8_t, 0x20>, 2> operatorLevel_{};
  int rate_ = 48000;
  std::array<std::array<int16_t, kScopeHistory>, kOplChannels> scope_{};
  std::array<std::array<int16_t, kScopeHistory>, kOplChannels> level_{};

  static int16_t louderSide(int16_t l, int16_t r) {
    return static_cast<int16_t>(std::min(32767, std::max(std::abs(static_cast<int>(l)), std::abs(static_cast<int>(r)))));
  }
  /**
   * Notes which voice a register write belongs to, and gives that voice's
   * settings an identity.
   *
   * AdPlug names a tune's instruments but never says which one is playing on
   * a channel: that mapping lives inside each of its forty-odd players, in a
   * shape private to the format, and nothing in CPlayer hands it over.
   * Reaching into a player's own state is not an option either - CmodPlayer
   * keeps a per-channel instrument but the count of channels it allocated is
   * private, and some of its formats read that count from the file.
   *
   * What is visible is the voice itself. An OPL instrument *is* these eleven
   * register bytes, so channels loaded with the same eleven bytes are playing
   * the same instrument, and numbering them in the order they first appear
   * gives every channel a label that is true and that two channels sharing an
   * instrument agree on.
   *
   * Total level is masked out of the two 0x40 bytes, keeping only the key
   * scaling. TL is where a tracker puts the note's volume, so leaving it in
   * would make every change of loudness look like a different instrument.
   */
  void recordPatchByte(int bank, int reg, uint8_t value) {
    const int group = reg & 0xe0;
    int channel = -1;
    int slot = -1;

    if (reg >= 0xc0 && reg <= 0xc8) {
      channel = bank + (reg - 0xc0);
      slot = 10;
      value &= 0x0f;  // feedback and connection; the OPL3 pan bits are not the voice
    } else if (group == 0x20 || group == 0x40 || group == 0x60 || group == 0x80 ||
               group == 0xe0) {
      const int offset = reg & 0x1f;
      for (int ch = 0; ch < 9; ch++) {
        if (offset == kOperator1[ch]) {
          channel = bank + ch;
          slot = 0;
          break;
        }
        if (offset == kOperator1[ch] + 3) {
          channel = bank + ch;
          slot = 1;
          break;
        }
      }
      if (slot < 0) return;
      static constexpr int kGroupIndex[] = {0, 2, 4, 6, 8};
      const int which = group == 0x20 ? 0 : group == 0x40 ? 1 : group == 0x60 ? 2
                        : group == 0x80 ? 3 : 4;
      if (group == 0x40) value &= 0xc0;  // key scaling only, not the volume
      slot = kGroupIndex[which] + slot;
    }

    if (channel < 0 || channel >= kOplChannels || slot < 0) return;
    if (patch_[channel][slot] == value) return;
    patch_[channel][slot] = value;
    patchId_[channel] = idFor(patch_[channel]);
  }

  int idFor(const std::array<uint8_t, kPatchBytes>& patch) {
    for (int i = 0; i < patchCount_; i++) {
      if (patches_[i] == patch) return i + 1;
    }
    if (patchCount_ >= kMaxPatches) return 0;
    patches_.push_back(patch);
    return ++patchCount_;
  }

  std::array<bool, kOplChannels> muted_{};
  std::array<uint8_t, kOplChannels> freqLow_{};
  std::array<uint8_t, kOplChannels> freqHigh_{};
  double pitch_ = 0.0;
  double pitchFactor_ = 1.0;
  int gain_ = 100;
  int scopeWrite_ = 0;

  std::array<std::array<uint8_t, kPatchBytes>, kOplChannels> patch_{};
  std::array<int, kOplChannels> patchId_{};
  std::vector<std::array<uint8_t, kPatchBytes>> patches_;
  int patchCount_ = 0;
};

struct AdPlugState {
  std::mutex mutex;
  std::unique_ptr<ScopeOpl> opl;
  CPlayer* player = nullptr;

  std::vector<uint8_t> fileData;
  std::string filename;
  std::string title;
  std::string type;
  std::string author;
  std::vector<std::string> instruments;
  std::vector<int> subsongMs;

  /** A length the listener set for this subsong, or 0 - see adplug_decoder_set_duration_override_ms. */
  int durationOverrideMs = 0;

  int sampleRate = 48000;
  /**
   * Held here as well as in the chip, because a preference changed while a
   * tracker is playing has to be waiting for the next AdLib file - and the
   * chip is rebuilt on every load.
   */
  int gainPercent = 100;
  int core = kCoreNuked;
  double pitchSemitones = 0.0;
  /**
   * Global tempo, as a factor. The replay code runs at its own refresh rate;
   * this spaces those ticks closer or further apart in the output, and the
   * chip keeps its rate, so notes keep their pitch.
   */
  double tempoFactor = 1.0;
  int subsong = 0;
  int positionMs = 0;
  int totalMs = 0;
  bool ended = false;
  bool loop = false;

  /**
   * The row the chip is playing, which is not the row the player reports.
   *
   * Every AdPlug player with a pattern grid - CmodPlayer and the formats built
   * on it, HSC, RAD, AdLib Tracker II - plays a row and moves its position on
   * in the same tick, so for every tick until the next row its getrow() names
   * a row nobody has heard yet. Published as it was, the pattern view's play
   * line sat one row ahead and showed notes in channels that were still
   * silent. Measured on 48 AMD, HSC and RAD tunes: a channel's key-on found a
   * note in the reported row 0-90% of the time, and in the row read before the
   * tick 99-100%. So these hold the position from before the last tick that
   * moved it. See tickLocked.
   */
  unsigned playingOrder = 0;
  unsigned playingPattern = 0;
  unsigned playingRow = 0;

  /** Frames still owed to the current player tick. */
  double framesToNextTick = 0.0;

  /** Frames rendered since the views were last shown the chip - see adplug_decoder_render. */
  int framesSincePublish = 0;

  /** Elapsed time, kept in full so a fractional tick length cannot drift. */
  double elapsedMs = 0.0;

  /**
   * When the tune first reached its end and went round again, or -1.
   *
   * The clock keeps running across a loop - the tune is still playing, and
   * the position is still true - but the seek bar has a total to sit inside,
   * so what is reported past this point is wrapped back into the stretch
   * between here and the end. That stretch is the loop.
   */
  double loopedAtMs = -1.0;

  std::vector<int16_t> mixBuffer;
  VisualPublisher visuals;

  static AdPlugState& instance() {
    static AdPlugState s;
    return s;
  }
};

void releaseLocked(AdPlugState& state) {
  delete state.player;
  state.player = nullptr;
  state.opl.reset();
  state.ended = false;
  state.framesToNextTick = 0.0;
  state.elapsedMs = 0.0;
  state.loopedAtMs = -1.0;
  state.positionMs = 0;
}

/**
 * How loud a channel is being told to be, 0..64.
 *
 * From the envelope generator's own attenuation, which is where the note's
 * volume, its instrument's total level and the key scaling all end up. The
 * unit is 0.1875dB, so this is that turned back into a linear scale.
 *
 * Only the slots whose output is actually routed to the channel count -
 * out[] points a modulator's entry at the chip's zero, so comparing against
 * the slot's own output is how a carrier is told from a modulator without
 * having to work out the algorithm.
 */
int volumeFor(const opl3_channel& c) {
  uint16_t attenuation = 0x1ff;
  bool routed = false;
  for (int s = 0; s < 2; s++) {
    const opl3_slot* slot = c.slotz[s];
    if (!slot) continue;
    bool feeds = false;
    for (int o = 0; o < 4; o++) {
      if (c.out[o] == &slot->out) feeds = true;
    }
    if (!feeds) continue;
    routed = true;
    attenuation = std::min(attenuation, slot->eg_out);
  }
  if (!routed) return 0;

  const double dB = (static_cast<int>(attenuation) - kReferenceAttenuation) * 0.1875;
  const double linear = std::min(1.0, std::pow(10.0, -dB / 20.0));
  return std::clamp(static_cast<int>(std::lround(64.0 * linear)), 0, 64);
}

/** What a channel is sounding, in hertz, or 0 when it is not keyed. */
double frequencyFor(const opl3_channel& c) {
  const bool keyed = c.slotz[0]->key != 0 || c.slotz[1]->key != 0;
  if (!keyed || c.f_num == 0) return 0.0;

  // The OPL's own formula: f_num counts in units of the chip rate over two to
  // the twenty, shifted up by the block.
  const double freq = c.f_num * kOplRate / std::pow(2.0, 20.0 - c.block);
  return (freq >= 16.0 && freq <= 8000.0) ? freq : 0.0;
}

/** Names the note a channel is playing, from the numbers written to the chip. */
int noteFor(double freq) {
  if (freq <= 0.0) return 0;
  const int note = static_cast<int>(std::lround(1.0 + 12.0 * std::log2(freq / kNoteC0Hz)));
  return (note > 0 && note < 128) ? note : 0;
}

/**
 * How far off that note the channel actually is, in cents.
 *
 * There is no period here to subtract one from another the way a tracker
 * does: an OPL channel is an f-number and a block, and what a slide or a
 * vibrato moves is the f-number. So the bend is the distance between the
 * frequency the chip was given and the frequency of the nearest semitone,
 * which is the same quantity a tracker's period difference stands for and is
 * readable without knowing the format. Within a semitone it stays inside
 * plus or minus fifty.
 */
int bendCentsFor(double freq, int note) {
  if (freq <= 0.0 || note <= 0) return 0;
  const double nominal = kNoteC0Hz * std::pow(2.0, (note - 1) / 12.0);
  return static_cast<int>(std::lround(1200.0 * std::log2(freq / nominal)));
}

/** Elapsed time, folded back into the loop once the tune has gone round. */
double reportedMs(const AdPlugState& state) {
  if (state.loopedAtMs < 0.0 || state.elapsedMs <= state.loopedAtMs) return state.elapsedMs;
  const double loop = state.loopedAtMs - std::max(0.0, state.loopedAtMs - state.totalMs);
  if (loop <= 0.0) return state.loopedAtMs;
  return state.loopedAtMs - loop + std::fmod(state.elapsedMs - state.loopedAtMs, loop);
}

/**
 * Takes the player's position as the playing one. Only right after a rewind,
 * when the row it names is the next to play and nothing has played before it.
 * Caller holds the mutex.
 */
void markPositionLocked(AdPlugState& state) {
  state.playingOrder = state.player->getorder();
  state.playingPattern = state.player->getpattern();
  state.playingRow = state.player->getrow();
}

/**
 * One tick of the replay code. A tick that moves the position has just played
 * the row it moved from, so that is the row now sounding - see playingRow.
 * Caller holds the mutex.
 */
bool tickLocked(AdPlugState& state) {
  const unsigned order = state.player->getorder();
  const unsigned pattern = state.player->getpattern();
  const unsigned row = state.player->getrow();
  const bool more = state.player->update();
  if (state.player->getorder() != order || state.player->getpattern() != pattern ||
      state.player->getrow() != row) {
    state.playingOrder = order;
    state.playingPattern = pattern;
    state.playingRow = row;
  }
  return more;
}

/**
 * AdPlug's CPlayer::seek, with the ticks counted through tickLocked so the
 * playing row is known where it lands. Rewinds to the current subsong once;
 * CPlayer::seek rewound a second time, to whichever subsong the player chose.
 * Caller holds the mutex.
 */
/**
 * The rate the player ticks at, in hertz: its own, however slow - a register
 * log waits as long as it says between two writes - and a usual one where it
 * gives none worth having. It was held to one a second at least, which cut
 * every wait longer than that to a second: a tune with a long rest in it ran
 * ahead of its own length, and a seek, which was not held so, landed
 * somewhere else than playing to the same place did.
 */
double refreshOf(AdPlugState& state) {
  const double refresh = state.player->getrefresh();
  return (refresh > 0.0 && std::isfinite(refresh)) ? refresh : 70.0;
}

void seekLocked(AdPlugState& state, unsigned long ms) {
  state.player->rewind(state.subsong);
  markPositionLocked(state);
  double pos = 0.0;
  while (pos < ms && tickLocked(state)) pos += 1000.0 / refreshOf(state);
}

/**
 * How many channels the tune drives: its own count when the player knows
 * it, and the whole chip otherwise. Caller holds the mutex - the render does,
 * which is why this is not adplug_decoder_channels: that takes the mutex, and
 * asked from under it stopped the tune at its first tick.
 */
int channelsLocked(const AdPlugState& state) {
  if (!state.player) return kOplChannels;
  const int declared = static_cast<int>(state.player->getchannels());
  return declared > 0 ? std::min(declared, kOplChannels) : kOplChannels;
}

/** Fills and publishes the frame the views read. Caller holds the mutex. */
void publishVisuals(AdPlugState& state, int64_t frameStamp) {
  if (!state.player || !state.opl) return;
  VisualFrame& frame = state.visuals.back();
  frame.clear();

  int32_t* scalars = frame.scalars.data();
  scalars[kScalarOrder] = static_cast<int32_t>(state.playingOrder);
  scalars[kScalarPattern] = static_cast<int32_t>(state.playingPattern);
  scalars[kScalarRow] = static_cast<int32_t>(state.playingRow);
  scalars[kScalarSpeed] = static_cast<int32_t>(state.player->getspeed());
  scalars[kScalarNumRows] = static_cast<int32_t>(state.player->getpatternrows());
  scalars[kScalarNumChannels] = channelsLocked(state);
  scalars[kScalarTimeMs] = state.positionMs;
  scalars[kScalarTotalTimeMs] = state.totalMs;
  scalars[kScalarSequence] = state.subsong;
  // bpm stays zero: an OPL tune has a refresh rate rather than a tempo in
  // beats, and speed already says what that rate is being divided by.

  const opl3_chip& chip = state.opl->chip();
  // The newest kScopeSpan samples, oldest first.
  const int start = (state.opl->scopeWrite() + kScopeHistory - kScopeSpan) % kScopeHistory;
  const int step = std::max(1, kScopeSpan / kScopeColumns);

  int playing = 0;
  for (int ch = 0; ch < kOplChannels; ch++) {
    const opl3_channel& c = chip.channel[ch];
    int32_t* f = frame.channels.data() + ch * kChannelFields;
    const bool muted = state.opl->isMuted(ch);

    const double freq = muted ? 0.0 : frequencyFor(c);
    const int note = noteFor(freq);
    f[kChNote] = note;
    f[kChKey] = note > 0 ? note - 1 : -1;
    f[kChPitchBend] = bendCentsFor(freq, note);
    // The f-number itself, which is the closest thing this chip has to the
    // period a tracker would report.
    f[kChPeriod] = note > 0 ? c.f_num : 0;
    f[kChPan] = state.opl->panOf(ch);
    // The tune's own instrument when the player tracks one, which is what
    // the channel list wants to name. Failing that - the register-log
    // formats, where there is no instrument list to name anything from - the
    // identity of the voice loaded on the chip, so two channels playing the
    // same thing still read the same. See recordPatchByte.
    // Only while the channel is actually sounding. A player keeps the last
    // instrument a channel was given for as long as it lives, so asking an
    // idle channel names whatever it played last - or, before it has played
    // at all, instrument one on every channel at once.
    const bool sounding = note > 0 || f[kChPeak] > 0;
    const int tuneInstrument =
        (state.player && sounding) ? static_cast<int>(state.player->getchannelinstrument(ch)) : 0;
    f[kChInstrument] = tuneInstrument > 0 ? tuneInstrument - 1 : -1;
    f[kChSample] = tuneInstrument > 0 ? 0 : state.opl->patchId(ch);

    int8_t* out = frame.scopes.data() + ch * kScopeColumns;
    const auto& src = state.opl->scope(ch);

    int32_t loudest = 1;
    for (int x = 0; x < kScopeColumns; x++) {
      loudest = std::max<int32_t>(loudest, std::abs(src[(start + x * step) % kScopeHistory]));
    }
    const int32_t peak = loudest;
    // Floored so near-silence is not amplified into something that looks like
    // signal - below this a channel is drawn as the quiet thing it is.
    loudest = std::max(loudest, kScopeNoiseFloor);
    for (int x = 0; x < kScopeColumns; x++) {
      const int32_t sample = src[(start + x * step) % kScopeHistory];
      out[x] = static_cast<int8_t>(std::clamp<int32_t>(sample * 127 / loudest, -127, 127));
    }

    // The trace above is normalised to fill its box, so what scales it back
    // down on screen is the volume - and that has to be the chip's own, not
    // something derived from the same peak the trace was normalised by. It
    // was, and every channel came out drawn a fraction of its height.
    f[kChVolume] = muted ? 0 : volumeFor(c);
    f[kChFinalVolume] = f[kChVolume];

    // How loud the channel really is: the louder side of every sample it put
    // out over the last kLevelWindowMs, against one carrier at full volume.
    //
    // This was the trace's own peak - both sides added together, every other
    // sample of a 10 ms window - against a quarter of that full scale, and it
    // sat at the top of the bar for most of any tune: the first channel of
    // one measured tune was pinned there for 2998 of 3000 blocks, so the bar
    // said nothing about how loud anything was.
    (void)peak;
    const auto& levels = state.opl->level(ch);
    const int levelSpan = std::clamp(state.sampleRate * kLevelWindowMs / 1000, 1, kScopeHistory);
    const int levelStart = (state.opl->scopeWrite() + kScopeHistory - levelSpan) % kScopeHistory;
    int32_t loudestSide = 0;
    for (int i = 0; i < levelSpan; i++) {
      loudestSide = std::max<int32_t>(loudestSide, levels[(levelStart + i) % kScopeHistory]);
    }
    f[kChPeak] = std::clamp<int32_t>(loudestSide * kPeakScale / kOplCarrierFullScale, 0, kPeakScale);
    if (f[kChPeak] > 0) playing++;
  }
  scalars[kScalarPlayingChannels] = playing;
  state.visuals.publish(frameStamp);
}

/**
 * Held for as long as a file is inside AdPlug's loaders, by whoever puts it
 * there. Its unpackers - the LZH, LZSS and LZW that AdLib Tracker II files
 * and others are packed with - keep where they have got to in variables the
 * whole program shares, and a library scan tests three files at once, beside
 * whatever is being loaded to play: two of them unpacking at the same time
 * wrote through each other's buffers. A tune declared not a tune, or one
 * loaded with another's bytes in it, or a crash while a folder was listed.
 * Taken after AdPlugState's own lock where both are held.
 */
std::mutex g_factoryMutex;

bool openLocked(AdPlugState& state, int subsong) {
  state.opl = std::make_unique<ScopeOpl>(state.sampleRate, state.core);
  state.opl->setGain(state.gainPercent);
  state.opl->setPitch(state.pitchSemitones);
  MemoryProvider provider(state.fileData.data(), state.fileData.size(), state.filename);
  {
    std::lock_guard<std::mutex> loading(g_factoryMutex);
    state.player = CAdPlug::factory(state.filename, state.opl.get(), CAdPlug::players, provider);
  }
  if (!state.player) {
    state.opl.reset();
    return false;
  }

  state.title = state.player->gettitle();
  state.author = state.player->getauthor();
  state.type = state.player->gettype();
  if (state.title.empty()) state.title = state.filename;

  const int instruments = static_cast<int>(state.player->getinstruments());
  state.instruments.clear();
  state.instruments.reserve(instruments);
  for (int i = 0; i < instruments; i++) {
    state.instruments.push_back(state.player->getinstrument(i));
  }

  // AdPlug finds a length by playing the tune into a silent chip, which is
  // both exact and the same answer its own tools give. It caps itself at ten
  // minutes, and so does this.
  const int subsongs = std::max<int>(1, static_cast<int>(state.player->getsubsongs()));
  state.subsongMs.assign(subsongs, 0);
  for (int i = 0; i < subsongs; i++) {
    state.subsongMs[i] =
        static_cast<int>(std::min<unsigned long>(state.player->songlength(i), kMaxSongMs));
  }

  const int wanted = std::clamp(subsong, 0, subsongs - 1);
  state.opl->init();
  state.player->rewind(wanted);
  markPositionLocked(state);
  state.subsong = wanted;
  state.totalMs = state.subsongMs[wanted];
  state.positionMs = 0;
  state.elapsedMs = 0.0;
  state.loopedAtMs = -1.0;
  state.framesToNextTick = 0.0;
  state.ended = false;
  state.visuals.reset();
  return true;
}

}  // namespace

void adplug_decoder_extensions(std::vector<std::string>& out) {
  for (const CPlayerDesc* desc : CAdPlug::players) {
    if (!desc) continue;
    for (unsigned int i = 0; const char* ext = desc->get_extension(i); i++) {
      std::string item(ext);
      // AdPlug spells them with the dot; the app's list does not.
      if (!item.empty() && item.front() == '.') item.erase(0, 1);
      if (item.empty()) continue;
      for (char& c : item) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
      out.push_back(item);
    }
  }
}

bool adplug_decoder_claims_extension(const char* filename) {
  if (!filename || !*filename) return false;
  const std::string name(filename);
  const size_t dot = name.find_last_of('.');
  if (dot == std::string::npos || dot + 1 >= name.size()) return false;

  std::string ext = name.substr(dot + 1);
  for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));

  // Built once: the player list is fixed at link time.
  static const std::vector<std::string> known = [] {
    std::vector<std::string> items;
    adplug_decoder_extensions(items);
    std::sort(items.begin(), items.end());
    items.erase(std::unique(items.begin(), items.end()), items.end());
    return items;
  }();
  return std::binary_search(known.begin(), known.end(), ext);
}

bool adplug_decoder_probe(const uint8_t* data, size_t size, const char* filename) {
  if (!data || size < 4 || !adplug_decoder_claims_extension(filename)) return false;

  // Asking the factory is the only honest answer here. AdPlug has forty-odd
  // players and no magic number they share, so whether a file is one of theirs
  // is whatever their own load routines say. A silent chip, because nothing
  // from this is going to be heard.
  CSilentopl silent;
  MemoryProvider provider(data, size, filename ? filename : "");
  std::lock_guard<std::mutex> loading(g_factoryMutex);
  CPlayer* player =
      CAdPlug::factory(filename ? filename : "", &silent, CAdPlug::players, provider);
  if (!player) return false;
  delete player;
  return true;
}

/** Whether the tune is one of patterns, to show as one. Caller holds the mutex. */
static bool hasPatternsLocked(const AdPlugState& state) {
  return state.player && state.player->getpatternrows() > 0 && state.player->getchannels() > 0 &&
         state.player->getpatterns() > 0;
}

bool adplug_decoder_load(const uint8_t* data, size_t size, const char* filename, int sampleRate) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  releaseLocked(state);
  state.sampleRate = sampleRate > 0 ? sampleRate : state.sampleRate;
  state.fileData.assign(data, data + size);
  state.filename = filename ? filename : "";

  if (!openLocked(state, 0)) {
    state.fileData.clear();
    LOG_WARN("no player took '%s'", state.filename.c_str());
    return false;
  }
  // Logged in full because what a player is willing to say about itself is
  // the whole of what the views can show: a player that reports no rows gets
  // no pattern view, and one that does not track a channel's instrument gets
  // chip-voice labels instead of names.
  LOG_INFO("loaded '%s' as %s: %d subsongs, %d ms, %u channels, %u rows, %u patterns, "
           "%u orders, %u instruments, patterns=%s",
           state.title.c_str(), state.type.c_str(),
           static_cast<int>(state.subsongMs.size()), state.totalMs,
           state.player->getchannels(), state.player->getpatternrows(),
           state.player->getpatterns(), state.player->getorders(),
           state.player->getinstruments(),
           hasPatternsLocked(state) ? "yes" : "no");
  return true;
}

void adplug_decoder_release() {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
  state.fileData.clear();
}

bool adplug_decoder_loaded() { return AdPlugState::instance().player != nullptr; }
const char* adplug_decoder_title() { return AdPlugState::instance().title.c_str(); }
const char* adplug_decoder_type() { return AdPlugState::instance().type.c_str(); }
const char* adplug_decoder_author() { return AdPlugState::instance().author.c_str(); }
int adplug_decoder_channels() {
  // The tune's channels when the player knows how many it drives, and the
  // whole chip otherwise. CmodPlayer maps its channel n to OPL channel n
  // (see set_opl_chip), so the two agree and a mute, a trace and a pattern
  // column all mean the same channel.
  //
  // Under the mutex, as the four below are: they ask the player, and the
  // player is deleted - on the thread that loads the next tune - under it.
  // Asked without it by a view fetching a pattern as the track changed, the
  // answer came from a player half gone.
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return channelsLocked(state);
}

bool adplug_decoder_has_patterns() {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return hasPatternsLocked(state);
}

int adplug_decoder_pattern_count() {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.player ? static_cast<int>(state.player->getpatterns()) : 0;
}

int adplug_decoder_pattern_rows() {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.player ? static_cast<int>(state.player->getpatternrows()) : 0;
}

int adplug_decoder_order_count() {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.player ? static_cast<int>(state.player->getorders()) : 0;
}

void adplug_decoder_pattern_data(
    int pattern, int numRows, int8_t* notes, int8_t* instruments, int8_t* fxType,
    int8_t* fxParm, int8_t* fx2Type, int8_t* fx2Parm) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.player || pattern < 0) return;

  const int channels = std::min<int>(state.player->getchannels(), MAX_CHANNELS);
  const int rows = std::min<int>(numRows, static_cast<int>(state.player->getpatternrows()));

  for (int row = 0; row < rows; row++) {
    for (int ch = 0; ch < channels; ch++) {
      CPlayer::Cell cell{};
      if (!state.player->getcell(pattern, row, ch, cell)) continue;
      const int idx = row * MAX_CHANNELS + ch;

      // The note numbering is already the one the views use: one is C-0, and
      // (note - 1) gives the pitch class and the octave. 127 is the key off
      // every one of these formats spells the same way, and the views spell
      // it 0xFF.
      notes[idx] = cell.note == 127 ? static_cast<int8_t>(0xff)
                                    : static_cast<int8_t>(cell.note);
      instruments[idx] = static_cast<int8_t>(cell.inst);

      // A slot is empty when both the command and its parameter are zero.
      // Command zero on its own is a real effect in these formats - arpeggio
      // in the MOD-derived ones, a global effect in HSC - so testing the
      // command alone would hide them. The parameter is stored as its two
      // digits, which is how a tracker displays it whether the format counts
      // in hex or in decimal, so putting them back together as nibbles reads
      // correctly either way.
      const int param = (cell.param1 << 4) | (cell.param2 & 0x0f);
      const bool hasEffect = cell.command != 0 || param != 0;
      fxType[idx] = hasEffect ? static_cast<int8_t>(cell.command) : -1;
      fxParm[idx] = hasEffect ? static_cast<int8_t>(param) : -1;
      // The second effect column, where the format has one, on the same rule.
      const int param2 = (cell.param3 << 4) | (cell.param4 & 0x0f);
      const bool hasEffect2 = cell.command2 != 0 || param2 != 0;
      fx2Type[idx] = hasEffect2 ? static_cast<int8_t>(cell.command2) : -1;
      fx2Parm[idx] = hasEffect2 ? static_cast<int8_t>(param2) : -1;
    }
  }
}

int adplug_decoder_instrument_count() {
  return static_cast<int>(AdPlugState::instance().instruments.size());
}

const char* adplug_decoder_instrument_name(int index) {
  AdPlugState& state = AdPlugState::instance();
  if (index < 0 || index >= static_cast<int>(state.instruments.size())) return "";
  return state.instruments[index].c_str();
}

/**
 * The labels channels are tagged with: one per distinct voice a tune uses.
 *
 * Numbers rather than names, because the tune's own instrument names cannot
 * be attached to a channel - see recordPatchByte. What these do say is which
 * channels are playing the same thing as each other, which is the part of an
 * instrument name that is actually being read off the screen.
 */
int adplug_decoder_patch_count() { return kMaxPatches; }

const char* adplug_decoder_patch_name(int index) {
  static const std::vector<std::string> names = [] {
    std::vector<std::string> items;
    items.reserve(kMaxPatches);
    for (int i = 1; i <= kMaxPatches; i++) {
      items.push_back((i < 10 ? "Patch 0" : "Patch ") + std::to_string(i));
    }
    return items;
  }();
  if (index < 0 || index >= static_cast<int>(names.size())) return "";
  return names[index].c_str();
}

int adplug_decoder_duration_ms() { return AdPlugState::instance().totalMs; }

int adplug_decoder_position_ms() {
  // From the audible frame, not from the player: the player is already a
  // buffer ahead of the speaker. Except right after a seek, when the history
  // has been dropped and nothing audible describes the new position yet.
  AdPlugState& state = AdPlugState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : state.positionMs;
}

void adplug_decoder_seek_ms(int ms) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.player || !state.opl) return;

  // AdPlug's own seek: rewind and run the replay code forward, which is the
  // only way there for a format whose state is the sum of everything before
  // it. The chip is reset first so a note left sounding at the old position
  // does not carry across.
  state.opl->init();
  seekLocked(state, static_cast<unsigned long>(std::max(0, ms)));

  state.positionMs = std::max(0, ms);
  state.elapsedMs = state.positionMs;
  state.loopedAtMs = -1.0;
  state.framesToNextTick = 0.0;
  state.opl->clearScopes();
  state.visuals.reset();
  state.ended = false;
}

int adplug_decoder_subsong_count() {
  return static_cast<int>(AdPlugState::instance().subsongMs.size());
}

int adplug_decoder_current_subsong() { return AdPlugState::instance().subsong; }

int adplug_decoder_subsong_duration_ms(int index) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (index < 0 || index >= static_cast<int>(state.subsongMs.size())) return 0;
  return state.subsongMs[index];
}

bool adplug_decoder_start_subsong(int index) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.player || index < 0 || index >= static_cast<int>(state.subsongMs.size())) return false;

  state.opl->init();
  state.player->rewind(index);
  markPositionLocked(state);
  state.subsong = index;
  state.totalMs = state.subsongMs[index];
  state.positionMs = 0;
  state.elapsedMs = 0.0;
  state.loopedAtMs = -1.0;
  state.framesToNextTick = 0.0;
  state.opl->clearScopes();
  state.opl->clearPatches();
  state.visuals.reset();
  state.ended = false;
  return true;
}

bool adplug_decoder_ended() { return AdPlugState::instance().ended; }
void adplug_decoder_set_loop(bool loop) { AdPlugState::instance().loop = loop; }

void adplug_decoder_mute(int channel, int muted) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.opl) state.opl->setMuted(channel, muted != 0);
}

int adplug_decoder_muted(int channel) {
  AdPlugState& state = AdPlugState::instance();
  return (state.opl && state.opl->isMuted(channel)) ? 1 : 0;
}

void adplug_decoder_set_tempo(double percent) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
}

void adplug_decoder_set_core(int core) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  const int clamped = std::clamp(core, 0, kOplCoreCount - 1);
  if (clamped == state.core) return;
  state.core = clamped;
  if (!state.player || !state.opl) return;

  // A new chip starts silent and knows nothing of the voices already set up,
  // so the tune is played back up to where it was, the way a rate change is.
  const int position = state.positionMs;
  state.opl->setCore(clamped);
  seekLocked(state, static_cast<unsigned long>(std::max(0, position)));
  state.framesToNextTick = 0.0;
  state.opl->clearScopes();
  state.visuals.reset();
  LOG_INFO("OPL core %d", clamped);
}

void adplug_decoder_set_gain(int percent) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.gainPercent = std::clamp(percent, 10, 400);
  if (state.opl) state.opl->setGain(state.gainPercent);
}

void adplug_decoder_set_pitch(double semitones) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  if (state.opl) state.opl->setPitch(state.pitchSemitones);
}

void adplug_decoder_set_sample_rate(int rate) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate || !state.player || !state.opl) return;

  // Only the chip cares about the rate; the player above it does not. So this
  // rebuilds the chip and puts the tune back where it was.
  const int position = state.positionMs;
  state.sampleRate = rate;
  state.opl->reset(rate);
  seekLocked(state, static_cast<unsigned long>(position));
  state.framesToNextTick = 0.0;
  LOG_INFO("re-opened at %d Hz", rate);
}

void adplug_decoder_prepare_start(int rate) {
  adplug_decoder_set_sample_rate(rate);
  AdPlugState::instance().visuals.reset();
}

bool adplug_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes,
                                int scopeWidth) {
  // No lock: the render callback publishes, this copies. See VisualPublisher.
  return AdPlugState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int adplug_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels,
                          int32_t bytesPerSample) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  const size_t outSamples = static_cast<size_t>(numFrames) * numChannels;
  if (!state.player || !state.opl) {
    std::memset(audioData, 0, outSamples * bytesPerSample);
    return 0;
  }

  const int64_t base = get_frames_written();
  if (state.mixBuffer.size() < static_cast<size_t>(numFrames) * 2) {
    state.mixBuffer.resize(static_cast<size_t>(numFrames) * 2);
  }

  int32_t written = 0;
  while (written < numFrames) {
    if (state.framesToNextTick < 1.0) {
      // One tick of the replay code. These players run at a refresh rate of
      // their own choosing - anything from eighteen hertz to several hundred,
      // and some change it as they go - which is why the gap to the next one
      // is counted in frames rather than assumed.
      const bool more = tickLocked(state);
      const double refresh = refreshOf(state);
      // Song time moves on by a whole tick whatever the tempo; the audio it
      // takes up is what the tempo shortens or stretches.
      state.framesToNextTick += state.sampleRate / refresh / state.tempoFactor;
      // Accumulated as a double: a refresh rate of 70Hz makes a tick 14.28ms
      // long, and rounding that down every time would run the clock two
      // percent slow.
      state.elapsedMs += 1000.0 / refresh;
      state.positionMs = static_cast<int>(reportedMs(state));
      // Not for every tick. Some of these tunes tick several hundred times
      // a second - an IMF seven hundred - and a frame built for each was
      // more of them in one buffer than the publisher keeps: the views ran
      // ahead of the sound and moved a buffer at a time, on frames most of
      // which nobody saw. A hundred and twenty a second is every one a
      // screen can show; the first after a seek or another subsong at once.
      if (state.framesSincePublish >= state.sampleRate / 120 || !state.visuals.published()) {
        publishVisuals(state, base + written);
        state.framesSincePublish = 0;
      }

      // A length the listener set ends the tune there, as its own end would.
      if (state.durationOverrideMs > 0 && state.elapsedMs >= state.durationOverrideMs) {
        if (!state.loop) {
          state.ended = true;
          break;
        }
        // Repeating, it is where the tune goes round, from the top: it used
        // to be passed by, and the bar held at the end of a length the tune
        // had left behind.
        state.opl->init();
        state.player->rewind(state.subsong);
        markPositionLocked(state);
        state.positionMs = 0;
        state.elapsedMs = 0.0;
        state.loopedAtMs = -1.0;
        state.framesToNextTick = 0.0;
        continue;
      }

      if (!more) {
        if (state.loop) {
          // Nothing to do but keep going. A player reports the end by
          // returning false, and by then it has already moved itself to
          // wherever the tune says to come back to - CmodPlayer sets ord to
          // restartpos in the same breath. Rewinding here threw that away and
          // started the tune from the top, which is not what its loop point
          // is for.
          //
          // The first time round only. reportedMs folds the clock back into
          // the song from where it first looped; moving that point on every
          // loop moved the fold with it, so the reported position gained a
          // loop's length each time round, passed the song's total within a
          // few loops and pinned the bar at the end for good.
          if (state.loopedAtMs < 0.0) state.loopedAtMs = state.elapsedMs;
        } else {
          state.ended = true;
          break;
        }
      }
    }

    const auto chunk = static_cast<int32_t>(
        std::min<double>(numFrames - written, std::floor(state.framesToNextTick)));
    if (chunk <= 0) {
      // A refresh rate faster than the output cannot give a whole frame per
      // tick. The remainder is kept rather than dropped, so several replay
      // ticks add up to one frame instead of the loop turning forever without
      // ever producing anything.
      continue;
    }
    state.opl->update(state.mixBuffer.data(), chunk);
    state.framesToNextTick -= chunk;
    state.framesSincePublish += chunk;

    for (int32_t i = 0; i < chunk; i++) {
      const int16_t l = state.mixBuffer[static_cast<size_t>(i) * 2];
      const int16_t r = state.mixBuffer[static_cast<size_t>(i) * 2 + 1];
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = l / 32768.0f;
        if (numChannels > 1) out[1] = r / 32768.0f;
      } else {
        int16_t* out =
            static_cast<int16_t*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = l;
        if (numChannels > 1) out[1] = r;
      }
    }
    written += chunk;
  }

  if (written < numFrames) {
    std::memset(static_cast<uint8_t*>(audioData) +
                    static_cast<size_t>(written) * numChannels * bytesPerSample,
                0, static_cast<size_t>(numFrames - written) * numChannels * bytesPerSample);
    return 1;
  }
  return 0;
}

double adplug_decoder_render_position_ms() {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.player) return 0.0;
  // elapsedMs is moved on a whole tick at the tick's start; what of that tick
  // is still owed to the output has not been heard as rendered yet.
  return state.elapsedMs -
         state.framesToNextTick * 1000.0 * state.tempoFactor / std::max(1, state.sampleRate);
}

void adplug_decoder_loop_seek_ms(int ms) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.player || !state.opl) return;
  // adplug_decoder_seek_ms, keeping the traces and the published history.
  state.opl->init();
  seekLocked(state, static_cast<unsigned long>(std::max(0, ms)));
  state.positionMs = std::max(0, ms);
  state.elapsedMs = state.positionMs;
  state.loopedAtMs = -1.0;
  state.framesToNextTick = 0.0;
  state.ended = false;
}

void adplug_decoder_set_duration_override_ms(int ms) {
  AdPlugState& state = AdPlugState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.durationOverrideMs = ms > 0 ? ms : 0;
  const int native = state.subsong < static_cast<int>(state.subsongMs.size())
      ? state.subsongMs[state.subsong] : 0;
  state.totalMs = ms > 0 ? ms : native;
}

bool adplug_decoder_duration_known() {
  AdPlugState& state = AdPlugState::instance();
  // AdPlug measures a length by playing the tune to its end, and gives up at
  // ten minutes: a length at that cap is where it gave up, not where it ended.
  return state.subsong < static_cast<int>(state.subsongMs.size()) &&
         state.subsongMs[state.subsong] < kMaxSongMs;
}
