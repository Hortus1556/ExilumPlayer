#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

/**
 * CustomMade: Ivo Zoer's replay routine for Ron Klaren's music, on the Amiga
 * (1989-90), found in games as .cm, .rk or .rkb files.
 *
 * A .cm is the module alone. A .rk - a "Ron Klaren sound module" - is the
 * same module saved as an AmigaDOS program: a hunk header, then one hunk of
 * code that opens with moveq #-1,d0 / rts, so that running it does nothing,
 * and the words RON_KLAREN_SOUNDMODULE!, and then the module as a .cm has it.
 * Both are found here; UADE plays the second kind only by trusting the file's
 * name over its Eagleplayer's check, which it fails.
 *
 * Not a data format either, strictly: a module is the replayer's own code
 * with its tables and samples behind it, exactly as it was linked into the
 * game, at whatever address that was. This follows the Eagleplayer
 * adaptation by Andy Silva and Wanted Team (CustomMade_v1.asm, as UADE
 * carries it) - which does not run the module's code either, but finds the
 * tables in it by the instructions that use them, works out the address the
 * module was linked at from two of those, and plays the tables with its own
 * copy of the routine.
 *
 * The routine is ported here at the level of the 68000's registers: several
 * of its effects read a data register that an earlier voice, or an earlier
 * effect, left a value in, and the music depends on those values.
 *
 * Paula, the Amiga's sound chip, is emulated as far as the routine uses it:
 * four channels of 8-bit samples played by DMA at a period, looping on the
 * address and length latched when a loop ends, and channels switched on and
 * off through DMACON. The synth instruments rewrite their waveforms while
 * they play, and Paula reads them from the same memory.
 */
namespace custommade {

/**
 * Whether the bytes are a CustomMade module, alone or inside a program: the
 * Eagleplayer's check, made on the module wherever it is.
 */
bool check(const uint8_t* data, size_t size);

/** One Paula channel: its registers and where its DMA is. */
struct PaulaChannel {
  // Registers, as the replay routine writes them.
  uint32_t lc = 0;
  uint16_t len = 0;
  uint16_t per = 0;
  int vol = 0;

  bool dmaOn = false;
  bool playing = false;
  /** Switched on, and not yet out of the two lines Paula takes to start. */
  bool starting = false;
  uint32_t pt = 0;
  uint32_t remaining = 0;
  uint16_t dat = 0;
  bool lowByte = false;
  int8_t sample = 0;
  /** Paula clock ticks left before the next byte. */
  double left = 0.0;
};

/** One voice of the replay routine - the 62-byte structure, field by field. */
struct Voice {
  uint32_t instrument = 0;  // $04: instrument record, as a file offset
  uint32_t trackStart = 0;  // $08: first entry of the track list
  uint32_t trackEntry = 0;  // $0C: entry playing
  uint32_t position = 0;    // $10: next byte of pattern data
  std::array<uint8_t, 12> arpeggio{};  // $14
  uint16_t transpose = 0;   // $20
  uint16_t repeat = 0;      // $22
  uint16_t period = 0;      // $24
  uint16_t target = 0;      // $26: portamento
  uint16_t envStage = 0;    // $28
  uint16_t vibPos = 0;      // $2A
  uint16_t arpPos = 0;      // $2C
  uint16_t dmaBit = 1;      // $2E
  uint8_t note = 0;         // $30
  uint8_t duration = 0;     // $31
  uint8_t portaNote = 0;    // $32
  uint8_t envSpeed = 0;     // $33
  uint8_t envCount = 0;     // $34
  uint8_t vibDelay = 0;     // $35
  uint8_t synthDelay = 0;   // $36
  uint8_t portaSpeed = 0;   // $37
  uint8_t volume = 0;       // $39
  uint8_t oneShot = 0;      // $3A
};

struct Module {
  std::vector<uint8_t> image;
  /** The address the module was linked at: an address in it less this is a file offset. */
  uint32_t origin = 0;
  uint32_t songs = 0;
  uint32_t table = 0;
  uint32_t samples = 0;
  /** CIA timer value the routine was driven by; 0 when the module does not set one. */
  uint16_t timer = 0;
  int subsongs = 0;
  int instruments = 0;
};

/**
 * Finds the tables, as the Eagleplayer's InitPlayer does, in the module alone
 * or inside a program. False when they are not all there.
 */
bool parse(const uint8_t* data, size_t size, Module& out);

class Player {
 public:
  explicit Player(const Module& module);

  /** Starts subsong [index] (from 0) on a fresh copy of the module. */
  void start(int index);

  /**
   * Renders [frames] of stereo audio at [rate]: left is channels 0 and 3,
   * right 1 and 2, as on the Amiga. [channels] receives each channel's own
   * output, [frames] values each, when not null.
   */
  void render(float* left, float* right, int frames, int rate, float* channels[4]);

  /** Runs the replay routine and Paula for [ticks] ticks without making audio. */
  void skipTicks(long ticks);
  /** The same for [ms] of song time, which need not be whole ticks. */
  void skipMs(double ms);

  /**
   * Runs only the replay routine for [ticks] ticks, from a start: exact for
   * the routine, which never reads Paula back, and far quicker. Paula is left
   * silent, and the channels start again at the routine's next note.
   */
  void skipTicksSilently(long ticks);

  /** Paula's clock, and the routine's timer, as factors of their own: pitch and tempo. */
  void setSpeed(double pitch, double tempo);

  /** Ticks per second the routine is driven at. */
  double tickRate() const { return tickRate_; }
  long ticks() const { return ticks_; }
  /** Times every voice has come round to the start of its track list together, or the song has stopped. */
  int songEnds() const { return songEnds_; }
  bool stopped() const { return stopped_; }

  const Voice& voice(int v) const { return voices_[v]; }
  const PaulaChannel& channel(int c) const { return paula_[c]; }
  /** Which instrument voice [v] plays, counted from 0. */
  int instrumentOf(int v) const;
  /** Whether instrument [n] is a synth waveform rather than a sample. */
  bool isSynth(int n) const;

 private:
  uint8_t rb(uint32_t at) const { return at < mem_.size() ? mem_[at] : 0; }
  uint16_t rw(uint32_t at) const { return static_cast<uint16_t>((rb(at) << 8) | rb(at + 1)); }
  uint32_t rl(uint32_t at) const { return (static_cast<uint32_t>(rw(at)) << 16) | rw(at + 2); }
  void wb(uint32_t at, uint8_t v) {
    if (at < mem_.size()) mem_[at] = v;
  }
  uint32_t rel(uint32_t address) const { return address - module_.origin; }
  uint16_t periodAt(int32_t index) const;

  /** Runs Paula and the routine for [clocks]; adds each channel's output over it to [sums]. */
  double run(double clocks, double* sums);
  void event();
  void voiceTick(int v);
  void readPattern(int v, uint32_t a1, uint32_t a2);
  void songEndTest(int v);
  void dmacon(uint16_t value);
  void fetch(PaulaChannel& ch);

  const Module& module_;
  std::vector<uint8_t> mem_;
  std::array<Voice, 4> voices_{};
  std::array<PaulaChannel, 4> paula_{};
  /** The data registers, which the routine carries values in from voice to voice. */
  uint32_t d0_ = 0, d1_ = 0, d2_ = 0, d3_ = 0;
  std::array<bool, 4> endFlags_{};
  bool stopped_ = false;
  int songEnds_ = 0;
  long ticks_ = 0;
  double tickRate_ = 50.0;
  double clocksPerTick_ = 70940.0;
  double pitch_ = 1.0;
  double tempo_ = 1.0;
  /** Paula clock ticks until the next voice's part of a tick; which voice that is. */
  double untilEvent_ = 0.0;
  int nextVoice_ = 0;
  /** Whether the song had stopped when the tick being worked through began. */
  bool tickStopped_ = false;
};

}  // namespace custommade
