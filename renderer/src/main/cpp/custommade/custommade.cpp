//
// CustomMade replay routine and the Paula it plays through. See custommade.h.
//
// Labels in the comments (lbC0000C0 and so on) are the routine's own in
// CustomMade_v1.asm, so each part here can be read against it.
//
#include "custommade.h"
#include <algorithm>
#include <cstring>

namespace custommade {
namespace {

/** PAL Paula clock: a period counts these, per byte of sample data. */
constexpr double kPaulaClock = 3546895.0;
/** PAL CIA E-clock, which the timer the routine is driven by counts. */
constexpr double kCiaClock = 709379.0;
/** The timer value for 50 Hz, for modules that do not give one. */
constexpr uint16_t kDefaultTimer = 0x376B;
/**
 * From DMA switched on to the first byte out: to the next line, then one
 * more - the half line to the next being the average, as the beam could be
 * anywhere. Switched off again in this time, a channel never starts.
 */
constexpr double kStartClocks = 227.0 * 1.5;
/**
 * How long after the one before each voice's part of a tick reaches Paula.
 * Measured against UADE, where the 68000 takes this long over a voice; it
 * varies with what the voice does, and this is the average.
 */
constexpr double kVoiceClocks = 400.0;

/** lbL0009F4: the routine's periods, the last row added by the Eagleplayer for modules that read past it. */
constexpr uint16_t kPeriods[] = {
    0x1AC0, 0x1940, 0x17D0, 0x1680, 0x1530, 0x1400, 0x12E0, 0x11D0, 0x10D0, 0x0FE0, 0x0F00, 0x0E20,
    0x0D60, 0x0CA0, 0x0BE8, 0x0B40, 0x0A98, 0x0A00, 0x0970, 0x08E8, 0x0868, 0x07F0, 0x0780, 0x0710,
    0x06B0, 0x0650, 0x05F4, 0x05A0, 0x054C, 0x0500, 0x04B8, 0x0474, 0x0434, 0x03F8, 0x03C0, 0x0388,
    0x0358, 0x0328, 0x02FA, 0x02D0, 0x02A6, 0x0280, 0x025C, 0x023A, 0x021A, 0x01FC, 0x01E0, 0x01C4,
    0x01AC, 0x0194, 0x017D, 0x0168, 0x0153, 0x0140, 0x012E, 0x011D, 0x010D, 0x00FE, 0x00F0, 0x00E2,
    0x00D6, 0x00CA, 0x00BE, 0x00B4, 0x00AA, 0x00A0, 0x0097, 0x008F, 0x0087, 0x007F,
    0x0078, 0x0071, 0x006B, 0x0065, 0x005F, 0x005A, 0x0055, 0x0050, 0x004B, 0x0047, 0x0043, 0x003F,
    0x003C, 0x0038};
constexpr int kPeriodCount = sizeof(kPeriods) / sizeof(kPeriods[0]);

/** A pattern that loops on itself with no note in it would hang a tick. */
constexpr int kMaxCommandsPerTick = 4096;

// 68000 register arithmetic: a byte or word operation changes only that
// part of the register.
inline uint32_t B(uint32_t r) { return r & 0xFF; }
inline uint32_t W(uint32_t r) { return r & 0xFFFF; }
inline uint32_t setB(uint32_t r, uint32_t v) { return (r & ~0xFFu) | (v & 0xFF); }
inline uint32_t setW(uint32_t r, uint32_t v) { return (r & ~0xFFFFu) | (v & 0xFFFF); }
inline bool negB(uint32_t v) { return (v & 0x80) != 0; }
inline bool negW(uint32_t v) { return (v & 0x8000) != 0; }
inline int32_t sW(uint32_t r) { return static_cast<int16_t>(r & 0xFFFF); }

inline uint32_t readL(const uint8_t* d, size_t n, int64_t at) {
  if (at < 0 || static_cast<size_t>(at) + 4 > n) return 0;
  return (static_cast<uint32_t>(d[at]) << 24) | (d[at + 1] << 16) | (d[at + 2] << 8) | d[at + 3];
}
inline uint32_t readW(const uint8_t* d, size_t n, int64_t at) {
  if (at < 0 || static_cast<size_t>(at) + 2 > n) return 0;
  return (static_cast<uint32_t>(d[at]) << 8) | d[at + 1];
}
inline uint8_t readB(const uint8_t* d, size_t n, int64_t at) {
  return at < 0 || static_cast<size_t>(at) >= n ? 0 : d[at];
}

}  // namespace

// DTP_Check2
bool check(const uint8_t* d, size_t n) {
  if (!d || n <= 3000) return false;
  const uint32_t first = readW(d, n, 0);
  if (first == 0x4EF9 || first == 0x4EB9) {
    if (readW(d, n, 6) != 0x4EF9) return false;
  } else if (first == 0x6000) {
    if (readW(d, n, 4) != 0x6000) return false;
  } else {
    return false;
  }
  for (int64_t a1 = 8; a1 < 408; a1 += 2) {
    if (readL(d, n, a1) == 0x42280030 && readL(d, n, a1 + 4) == 0x42280031 &&
        readL(d, n, a1 + 8) == 0x42280032) {
      return true;
    }
  }
  return false;
}

// DTP_InitPlayer: every address below is a file offset, as if the module had
// been loaded at 0.
bool parse(const uint8_t* d, size_t n, Module& out) {
  if (!check(d, n)) return false;
  int64_t origin = 0, songs = 0, table = 0, samples = 0;
  bool haveSongs = false, haveTable = false, haveSamples = false;
  uint16_t timer = 0;
  int64_t d5 = 0, a3 = 0, a4 = 0;
  int32_t d1 = 0;

  int64_t a0 = 0;
  for (int i = 0; i < 0x800; i++) {
    const uint32_t v = readL(d, n, a0);
    if (v == 0xD04149FA) {  // table
      table = a0 + 4 + static_cast<int16_t>(readW(d, n, a0 + 4));
      haveTable = true;
    } else if (v == 0x48E7F8FC || v == 0x48E7FFFE) {  // the play routine
      if (a3 != 0 && origin == 0) {
        d5 -= a0 - a3;
        origin = d5;
      }
      for (int k = 0; k <= 20; k++) {
        const uint32_t w = readW(d, n, a0);
        a0 += 2;
        if (w == 0x41FA) {  // lea songs(pc)
          songs = a0 + static_cast<int16_t>(readW(d, n, a0));
          haveSongs = true;
          break;
        }
      }
    } else if (v == 0xE94847F0) {  // an offset into the song table
      d1 = static_cast<int16_t>(readW(d, n, a0 + 4));
    } else if (v == 0x00BFD500) {  // the CIA timer, and the old interrupt handler
      timer = static_cast<uint16_t>((readB(d, n, a0 - 1) << 8) | readB(d, n, a0 + 7));
      if (readW(d, n, a0 + 20) != 0x4E71) {
        if (readW(d, n, a0 + 28) == 0x21FC) {
          a3 = readL(d, n, a0 + 30);
        } else if (readW(d, n, a0 + 32) == 0xC000) {
          a3 = readL(d, n, a0 + 22);
        } else {
          a3 = readL(d, n, a0 + 32);
        }
      }
    } else if (v == 0x42A8001C) {  // the instrument records
      samples = a0 + 6 + static_cast<int16_t>(readW(d, n, a0 + 6));
      haveSamples = true;
    } else if (v == 0xE44843FA || v == 0xE448207B) {  // the voices, from their old address
      if (a4 != 0) {
        const int16_t disp = static_cast<int16_t>(readW(d, n, a0 + 4));
        a4 -= readL(d, n, a0 + 4 + disp);
        if (origin == 0) {
          d5 -= a4;
          origin = d5;
        }
      }
    } else if (v == 0x48E700F0) {  // the voices
      a4 = a0 + 6 + static_cast<int16_t>(readW(d, n, a0 + 6));
    }
    a0 += 2;
    if (readL(d, n, a0) == 0x1AC01940) break;
  }
  if (!haveSongs || !haveTable || !haveSamples) return false;
  songs += d1;

  const uint32_t org = static_cast<uint32_t>(origin);
  int subsongs = 0;
  for (int64_t o = songs; subsongs < 64; o += 16) {
    if (o < 0 || static_cast<size_t>(o) + 16 > n) return false;
    if ((readL(d, n, o) & 0xFFFFFF) == 0xDFF0A0) break;
    subsongs++;
  }
  int instruments = 0;
  for (int64_t o = samples; instruments < 256; o += 32) {
    if (o < 0 || static_cast<size_t>(o) + 32 > n) break;
    if (readL(d, n, o + 28) != 0) break;
    instruments++;
  }
  if (subsongs == 0 || instruments == 0) return false;

  // The first subsong's track lists have to be in the file.
  for (int v = 0; v < 4; v++) {
    const uint32_t list = readL(d, n, songs + 4 * v) - org;
    if (static_cast<size_t>(list) + 12 > n) return false;
  }

  out.image.assign(d, d + n);
  out.origin = org;
  out.songs = static_cast<uint32_t>(songs);
  out.table = static_cast<uint32_t>(table);
  out.samples = static_cast<uint32_t>(samples);
  out.timer = timer;
  out.subsongs = subsongs;
  out.instruments = instruments;
  return true;
}

Player::Player(const Module& module) : module_(module) { start(0); }

uint16_t Player::periodAt(int32_t index) const {
  // An index is a byte offset into a table of words.
  const int32_t at = index / 2;
  if (index < 0 || at >= kPeriodCount) return at == kPeriodCount ? 0x003F : 0;
  return kPeriods[at];
}

int Player::instrumentOf(int v) const {
  return static_cast<int>((voices_[v].instrument - module_.samples) / 32);
}

bool Player::isSynth(int n) const {
  // SampleInit: a sample two words long is an empty one, and anything up to
  // sixteen words is a waveform.
  const uint32_t sample = rel(rl(module_.samples + 32 * static_cast<uint32_t>(n)));
  const uint16_t words = rw(sample + 4);
  return words != 2 && words <= 16;
}

// DTP_InitSound and Init, with lbC000036 for each voice.
void Player::start(int index) {
  mem_ = module_.image;
  voices_ = {};
  paula_ = {};
  for (auto& ch : paula_) ch.per = 65535;
  d0_ = d1_ = d2_ = d3_ = 0;
  endFlags_.fill(true);
  stopped_ = false;
  songEnds_ = 0;
  ticks_ = 0;
  nextVoice_ = 0;
  tickStopped_ = false;
  untilEvent_ = 0.0;
  // A CIA timer interrupts once per its value plus one E-clock ticks.
  const double timer = (module_.timer ? module_.timer : kDefaultTimer) + 1.0;
  tickRate_ = kCiaClock / timer;
  setSpeed(pitch_, tempo_);

  const int song = std::clamp(index, 0, std::max(0, module_.subsongs - 1));
  const uint32_t a3 = module_.songs + 16 * static_cast<uint32_t>(song);
  for (int v = 0; v < 4; v++) {
    Voice& voice = voices_[v];
    voice.dmaBit = static_cast<uint16_t>(1 << v);
    voice.trackStart = rel(rl(a3 + 4 * v));
    voice.oneShot = 1;
    voice.instrument = module_.samples;
    const uint32_t sample = rel(rl(module_.samples));
    paula_[v].lc = rel(rl(sample));
    paula_[v].len = rw(sample + 4);
    voice.trackEntry = voice.trackStart;
    const uint32_t entry = voice.trackEntry;
    voice.position = rel(rl(entry));
    voice.transpose = rw(entry + 6);
    if (rw(entry + 10) == 0x270F) {  // for the song end of the BS intro
      wb(entry + 10, 0x00);
      wb(entry + 11, 0x3E);
    }
    voice.repeat = static_cast<uint16_t>(rw(entry + 10) - 1);
  }
}

// Paula: DMACON, for the audio bits.
void Player::dmacon(uint16_t value) {
  const bool set = (value & 0x8000) != 0;
  for (int c = 0; c < 4; c++) {
    if (!(value & (1 << c))) continue;
    PaulaChannel& ch = paula_[c];
    if (!set) {
      ch.dmaOn = false;
      // Still starting, it never does. Playing, it stops at the end of the
      // word it is on.
      if (ch.starting) {
        ch.starting = false;
        ch.playing = false;
        ch.sample = 0;
      }
      continue;
    }
    if (ch.dmaOn) continue;
    ch.dmaOn = true;
    if (ch.playing) continue;  // switched back on before it had stopped
    ch.pt = ch.lc;
    ch.remaining = ch.len ? ch.len : 65536;
    ch.sample = 0;
    ch.starting = true;
    ch.left = kStartClocks;
    ch.playing = true;
  }
}

void Player::fetch(PaulaChannel& ch) {
  ch.dat = rw(ch.pt);
  ch.pt += 2;
  if (--ch.remaining == 0) {
    // The address and length the routine has latched by now.
    ch.pt = ch.lc;
    ch.remaining = ch.len ? ch.len : 65536;
  }
}

namespace {

/** Runs [ch] for [clocks] Paula clock ticks; returns its output summed over them. */
template <typename Fetch>
double advance(PaulaChannel& ch, double clocks, Fetch&& fetch) {
  double sum = 0.0;
  while (clocks > 0.0 && ch.playing) {
    const double step = std::min(clocks, ch.left);
    sum += ch.sample * ch.vol * step;
    ch.left -= step;
    clocks -= step;
    if (ch.left > 1e-9) continue;
    if (ch.starting) {
      ch.starting = false;
      fetch(ch);
      ch.lowByte = false;
      ch.sample = static_cast<int8_t>(ch.dat >> 8);
    } else if (!ch.lowByte) {
      ch.lowByte = true;
      ch.sample = static_cast<int8_t>(ch.dat & 0xFF);
    } else {
      if (!ch.dmaOn) {
        ch.playing = false;
        ch.sample = 0;
        break;
      }
      fetch(ch);
      ch.lowByte = false;
      ch.sample = static_cast<int8_t>(ch.dat >> 8);
    }
    // AUDxPER: 0 is the longest period, and UADE holds anything shorter than 16 at 16.
    ch.left += std::max<int>(ch.per ? ch.per : 65535, 16);
  }
  return sum;
}

}  // namespace

double Player::run(double clocks, double* sums) {
  auto fetcher = [this](PaulaChannel& ch) { fetch(ch); };
  while (clocks > 1e-9) {
    if (untilEvent_ <= 1e-9) {
      event();
      continue;
    }
    const double step = std::min(clocks, untilEvent_);
    for (int c = 0; c < 4; c++) {
      const double sum = advance(paula_[c], step, fetcher);
      if (sums) sums[c] += sum;
    }
    clocks -= step;
    untilEvent_ -= step;
  }
  return clocks;
}

void Player::setSpeed(double pitch, double tempo) {
  pitch_ = pitch > 0.0 ? pitch : 1.0;
  tempo_ = tempo > 0.0 ? tempo : 1.0;
  // Time here is counted in Paula's clock, so a faster clock has the timer
  // count more of it to keep the tempo where it was.
  clocksPerTick_ = kPaulaClock * pitch_ / (tickRate_ * tempo_);
}

void Player::render(float* left, float* right, int frames, int rate, float* channels[4]) {
  const double clocks = kPaulaClock * pitch_ / rate;
  for (int i = 0; i < frames; i++) {
    // Each channel's average over the frame, as UADE's default resampler takes it.
    double out[4] = {0.0, 0.0, 0.0, 0.0};
    run(clocks, out);
    for (double& o : out) o /= clocks;
    // As UADE mixes: two channels a side, doubled into 16 bits.
    left[i] = static_cast<float>((out[0] + out[3]) * 2.0 / 32768.0);
    right[i] = static_cast<float>((out[1] + out[2]) * 2.0 / 32768.0);
    if (channels) {
      for (int c = 0; c < 4; c++) {
        if (channels[c]) channels[c][i] = static_cast<float>(out[c] * 2.0 / 32768.0);
      }
    }
  }
}

void Player::skipTicks(long count) {
  const long target = ticks_ + count;
  while (ticks_ < target) run(clocksPerTick_, nullptr);
}

void Player::skipMs(double ms) {
  if (ms > 0.0) run(ms / 1000.0 * tickRate_ * clocksPerTick_, nullptr);
}

void Player::skipTicksSilently(long count) {
  for (long i = 0; i < count; i++) {
    ticks_++;
    const bool stopped = stopped_;
    d0_ = d1_ = d2_ = d3_ = 0;
    for (int v = 0; v < 4 && !stopped; v++) voiceTick(v);
  }
  for (auto& ch : paula_) {
    ch.dmaOn = false;
    ch.playing = false;
    ch.starting = false;
    ch.sample = 0;
  }
}

// DTP_Interrupt, one voice at a time: each voice's writes reach Paula a
// little after the one before, as the 68000 works through the routine.
void Player::event() {
  if (nextVoice_ == 0) {
    ticks_++;
    tickStopped_ = stopped_;
    // D0 is returned cleared, and D1-D3 put back as the interrupt found them.
    d0_ = d1_ = d2_ = d3_ = 0;
  }
  // All four, even when one of them ends the song: that stops the next tick.
  if (!tickStopped_) voiceTick(nextVoice_);
  nextVoice_ = (nextVoice_ + 1) % 4;
  const double voiceClocks = kVoiceClocks * pitch_ / tempo_;
  untilEvent_ += nextVoice_ == 0 ? clocksPerTick_ - 3 * voiceClocks : voiceClocks;
}

void Player::songEndTest(int v) {
  endFlags_[v] = false;
  if (endFlags_[0] || endFlags_[1] || endFlags_[2] || endFlags_[3]) return;
  endFlags_.fill(true);
  songEnds_++;
}

// lbC0000C0: one voice, one tick.
void Player::voiceTick(int v) {
  Voice& a0 = voices_[v];
  PaulaChannel& a3 = paula_[v];
  const uint32_t a1 = a0.instrument;

  // lbC000108
  dmacon(static_cast<uint16_t>(0x8000 | a0.dmaBit));
  if (a0.oneShot) {
    a0.oneShot = 0;
    a3.len = 1;
  }

  // lbC000132
  if (a0.duration == 0) {
    readPattern(v, a1, a0.position);
    return;
  }
  a0.duration--;

  // lbC000146: the synth instruments rewrite their own waveform.
  d0_ = setW(d0_, 0);
  if (rb(a1 + 9)) {
    if (a0.synthDelay) {
      a0.synthDelay--;
    } else {
      d0_ = setB(d0_, rb(a1 + 9));
      d0_ = setB(d0_, B(d0_) - 1);
      a0.synthDelay = static_cast<uint8_t>(B(d0_));
      uint32_t a4 = rel(rl(a1));
      d0_ = setW(d0_, rw(a4 + 6));
      d0_ = setB(d0_, B(d0_) - rb(a1 + 10));
      a4 = rel(rl(a4));
      a4 += static_cast<uint32_t>(sW(d0_));
      d0_ = setW(d0_, 0);
      if (!negB(rb(a1 + 0x17))) {
        d0_ = setB(d0_, rb(a1 + 0x18));
        wb(a4 + static_cast<uint32_t>(sW(d0_)), rb(a1 + 0x16));
        d0_ = setB(d0_, B(d0_) + 1);
        d1_ = setB(d1_, rb(a1 + 10));
        d1_ = setB(d1_, B(d1_) + B(d1_));
        if (B(d0_) == B(d1_)) {
          wb(a1 + 0x17, static_cast<uint8_t>(~rb(a1 + 0x17)));
          wb(a1 + 0x16, static_cast<uint8_t>(~rb(a1 + 0x16)));
        }
      } else {
        d0_ = setB(d0_, rb(a1 + 0x18));
        wb(a4 + static_cast<uint32_t>(sW(d0_)), rb(a1 + 0x16));
        d0_ = setB(d0_, B(d0_) - 1);
        if (B(d0_) == 0) {
          wb(a1 + 0x17, static_cast<uint8_t>(~rb(a1 + 0x17)));
          wb(a1 + 0x16, static_cast<uint8_t>(~rb(a1 + 0x16)));
        }
      }
      wb(a1 + 0x18, static_cast<uint8_t>(B(d0_)));
    }
  }

  // lbC0001BA
  if (a0.portaSpeed) {
    d0_ = setW(d0_, 0);
    d0_ = setB(d0_, a0.portaSpeed);
    d1_ = setW(d1_, a0.target);
    d2_ = setW(d2_, a0.period);
    if (!negW(W(d1_) - W(d2_))) {  // lbC0001DA: up to the target
      d2_ = setW(d2_, W(d2_) + W(d0_));
      if (negW(W(d1_) - W(d2_))) d2_ = setW(d2_, W(d1_));
    } else {
      d2_ = setW(d2_, W(d2_) - W(d0_));
      if (!negW(W(d1_) - W(d2_))) d2_ = setW(d2_, W(d1_));
    }
    a0.period = static_cast<uint16_t>(W(d2_));
    a3.per = a0.period;
  } else {
    // lbC0001F2: the arpeggio, which steps backwards through its twelve.
    d1_ = setW(d1_, a0.arpPos);
    d0_ = setB(d0_, a0.arpeggio[static_cast<size_t>(W(d1_)) % 12]);
    d0_ = setB(d0_, B(d0_) + a0.note);
    d0_ = setW(d0_, W(d0_) + a0.transpose);
    d0_ = setW(d0_, W(d0_) + W(d0_));
    d0_ = setW(d0_, periodAt(sW(d0_)));
    a0.period = static_cast<uint16_t>(W(d0_));
    a3.per = a0.period;
    d1_ = setW(d1_, W(d1_) - 1);
    if (negW(d1_)) d1_ = setW(d1_, W(d1_) + 12);
    a0.arpPos = static_cast<uint16_t>(W(d1_));
  }

  // lbC00022A: vibrato
  if (rb(a1 + 11)) {
    if (a0.vibDelay) {
      a0.vibDelay--;
    } else {
      d0_ = setW(d0_, a0.vibPos);
      uint32_t a4 = rel(rl(rel(rl(a1 + 4))));
      d2_ = setB(d2_, rb(a4 + static_cast<uint32_t>(sW(d0_))));
      d1_ = setW(d1_, 0);
      d2_ = setW(d2_, static_cast<uint32_t>(static_cast<int8_t>(B(d2_))));
      d1_ = setB(d1_, rb(a1 + 12));
      d2_ = static_cast<uint32_t>(static_cast<int32_t>(sW(d1_)) * sW(d2_));
      d2_ = setW(d2_, W(d2_) + a0.period);
      a3.per = static_cast<uint16_t>(W(d2_));
      d0_ = setW(d0_, W(d2_));  // SetPer takes it in D0, and D0 is not put back
      // lbC000266
      d0_ = setW(d0_, 0);
      d0_ = setB(d0_, rb(a1 + 11));
      a0.vibPos = static_cast<uint16_t>(a0.vibPos - W(d0_));
      if (negW(a0.vibPos)) {
        a4 = rel(rl(a1 + 4));
        d0_ = setW(d0_, rw(a4 + 4));
        d0_ = setW(d0_, W(d0_) + W(d0_));
        a0.vibPos = static_cast<uint16_t>(W(d0_));
      }
    }
  }

  // lbC000280: the volume envelope, four stages of target and step.
  a0.envCount = static_cast<uint8_t>(a0.envCount - 1);
  if (!negB(a0.envCount)) return;
  a0.envCount = a0.envSpeed;
  d0_ = setW(d0_, a0.envStage);
  const uint32_t a4 = a1 + 14;
  d1_ = setB(d1_, rb(a4 + static_cast<uint32_t>(sW(d0_))));
  d2_ = setB(d2_, rb(a4 + 4 + static_cast<uint32_t>(sW(d0_))));
  d3_ = setB(d3_, a0.volume);
  bool advanceStage = false;
  if (!negB(B(d1_) - B(d3_))) {
    // lbC0002B6: rising, to the target or the ceiling of $3F
    d3_ = setB(d3_, B(d3_) + B(d2_));
    if (negB(B(d3_) - 0x3F)) {
      // lbC0002C8
      if (negB(B(d1_) - B(d3_))) {
        d3_ = setB(d3_, B(d1_));
        advanceStage = true;
      }
    } else {
      d3_ = setB(d3_, 0x3F);
      advanceStage = true;
    }
  } else {
    d3_ = setB(d3_, B(d3_) - B(d2_));
    if (!negB(B(d1_) - B(d3_))) {
      d3_ = setB(d3_, B(d1_));
      advanceStage = true;
    }
  }
  // lbC0002AC
  if (advanceStage && W(d0_) != 3) d0_ = setW(d0_, W(d0_) + 1);
  // lbC0002D0
  a0.envStage = static_cast<uint16_t>(W(d0_));
  a0.volume = static_cast<uint8_t>(B(d3_));
  d3_ = setW(d3_, W(d3_) & 0x3F);
  // ChangeVolume, at full volume and centre balance: D3 * 64 >> 6.
  d3_ = W(d3_) * 64u;
  d3_ = setW(d3_, W(d3_) >> 6);
  a3.vol = (W(d3_) & 64) ? 63 : static_cast<int>(W(d3_) & 63);
}

// lbC0002E8: the voice's note has run out; read on in its pattern.
void Player::readPattern(int v, uint32_t a1, uint32_t a2) {
  Voice& a0 = voices_[v];
  PaulaChannel& a3 = paula_[v];

  a0.portaSpeed = 0;
  d0_ = setB(d0_, rb(a1 + 13));
  if (B(d0_)) {
    d0_ = setB(d0_, B(d0_) << 2);
    d0_ = setB(d0_, B(d0_) - 1);
    a0.vibDelay = static_cast<uint8_t>(B(d0_));
  }

  for (int guard = 0; guard < kMaxCommandsPerTick; guard++) {
    // lbC000304: $80 nn - arpeggio nn
    if (rb(a2) == 0x80) {
      d0_ = 0;
      d1_ = setW(d1_, 0);
      a0.arpPos = 0;
      d0_ = setB(d0_, rb(a2 + 1));
      d1_ = setW(d1_, W(d0_));
      d0_ = setW(d0_, W(d0_) << 3);
      d1_ = setW(d1_, W(d1_) << 2);
      d0_ = setW(d0_, W(d0_) + W(d1_));
      const uint32_t a4 = module_.table + d0_;
      for (int i = 0; i < 12; i++) a0.arpeggio[i] = rb(a4 + i);
      a2 += 2;
    }
    // lbC000338: $81 note speed length - portamento to note
    if (rb(a2) == 0x81) {
      d0_ = setW(d0_, 0);
      a0.envStage = 0;
      d0_ = setB(d0_, rb(a2 + 1));
      a0.portaNote = static_cast<uint8_t>(B(d0_));
      d0_ = setW(d0_, W(d0_) + a0.transpose);
      d0_ = setW(d0_, W(d0_) + W(d0_));
      a0.target = periodAt(sW(d0_));
      a0.portaSpeed = rb(a2 + 2);
      d0_ = setB(d0_, rb(a2 + 3));
      d0_ = setB(d0_, B(d0_) << 2);
      d0_ = setB(d0_, B(d0_) - 1);
      a0.duration = static_cast<uint8_t>(B(d0_));
      a2 += 4;
      a0.position = a2;  // lbC0004C4
      a3.per = a0.period;
      return;
    }
    // lbC000374: $82 nn - instrument nn
    if (rb(a2) == 0x82) {
      d0_ = setW(d0_, 0);
      a0.volume = 0;
      a0.envStage = 0;
      d0_ = setB(d0_, rb(a2 + 1));
      d0_ = setW(d0_, W(d0_) << 5);
      a1 = module_.samples + static_cast<uint32_t>(sW(d0_));
      a0.instrument = a1;
      dmacon(a0.dmaBit);
      const uint32_t a4 = rel(rl(a1));
      a3.lc = rel(rl(a4));
      a3.len = rw(a4 + 4);
      a2 += 2;
    }
    // lbC0003BC: $83 - the end
    if (rb(a2) == 0x83) {
      stopped_ = true;
      songEnds_++;
      return;
    }
    // lbC0003D8: $84 nn - envelope speed
    if (rb(a2) == 0x84) {
      a0.envSpeed = rb(a2 + 1);
      a2 += 2;
    }
    // lbC0003E6: $85 - also the end
    if (rb(a2) == 0x85) {
      stopped_ = true;
      songEnds_++;
      return;
    }

    if (negB(rb(a2))) {
      // Any other command ends the pattern: again, or the next in the track list.
      if (a0.repeat) {
        a0.repeat--;
        const uint32_t a4 = a0.trackEntry;
        a2 = rel(rl(a4));
        a0.position = a2;
        a0.transpose = rw(a4 + 6);
        continue;
      }
      // lbC000408
      a0.trackEntry += 12;
      uint32_t a4 = a0.trackEntry;
      a0.repeat = static_cast<uint16_t>(rw(a4 + 10) - 1);
      if (negB(rb(a4))) {
        a4 = a0.trackStart;
        a0.trackEntry = a4;
        songEndTest(v);
      }
      // lbC00042A
      a2 = rel(rl(a4));
      a0.position = a2;
      a0.transpose = rw(a4 + 6);
      d0_ = setW(d0_, rw(a4 + 10));
      d0_ = setW(d0_, W(d0_) - 1);
      a0.repeat = static_cast<uint16_t>(W(d0_));
      continue;
    }

    // lbC000444: note nn, length ll
    d0_ = setB(d0_, rb(a2 + 1));
    if (B(d0_) == 0) {
      // No length: the note changes, and nothing is struck.
      d0_ = setB(d0_, rb(a2));
      a0.note = static_cast<uint8_t>(B(d0_));
      d0_ = setW(d0_, W(d0_) + a0.transpose);
      d0_ = setW(d0_, W(d0_) + W(d0_));
      a0.period = periodAt(sW(d0_));
      a2 += 2;
      continue;
    }
    // lbC000466
    d0_ = setB(d0_, B(d0_) << 2);
    d0_ = setB(d0_, B(d0_) - 1);
    a0.duration = static_cast<uint8_t>(B(d0_));
    d0_ = setW(d0_, 0);
    a0.envStage = 0;
    d0_ = setB(d0_, rb(a2));
    a0.note = static_cast<uint8_t>(B(d0_));
    d0_ = setW(d0_, W(d0_) + a0.transpose);
    d0_ = setW(d0_, W(d0_) + W(d0_));
    a0.period = periodAt(sW(d0_));
    // lbC00049E: the channel off, and the instrument's sample latched for when it comes back on.
    dmacon(a0.dmaBit);
    const uint32_t a4 = rel(rl(a1));
    a3.lc = rel(rl(a4));
    a3.len = rw(a4 + 4);
    a2 += 2;
    if (rb(a1 + 8)) a0.oneShot = 1;
    // lbC0004C4
    a0.position = a2;
    a3.per = a0.period;
    return;
  }
  // A pattern going round without a note: stop rather than hang.
  stopped_ = true;
  songEnds_++;
}

}  // namespace custommade
