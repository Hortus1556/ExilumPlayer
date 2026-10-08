# Local changes to aopsf

Vendored from [kode54/aopsf](https://gitlab.com/kode54/aopsf) at `8213121`:
the PlayStation of the Audio Overload SDK, its BIOS done over by the
emulator so that no file of Sony's is needed. It is what plays PSF files.

Two parts of it are Neill Corlett's Highly Experimental, under the GPL,
version 3: the sound unit under `spu/`, and the console's counters - the
`ioptimer_*` functions and their helpers in `psx_hw.c`, their structures in
`psx_internal.h` - which upstream pasted into files whose headers name only
the BSD licence of the rest.

Only the PlayStation is here. Left out are the PlayStation 2's loader
(`eng_psf2.c`), the project files, and the processor the engine came with.

It is a copy rather than a submodule because what the player needs of it is
inside the engine. Every change is marked `Exilum` where it is made. The
files under `spu/` keep the line endings they came with (CRLF).

## The processor is this player's own — `r3000.c`, `r3000.h`

The engine came with smf's PlayStation processor core from MAME (`psx.c`,
`psx.h`) and two of MAME's headers (`cpuintrf.h`, `osd_cpu.h`), in a version
under MAME's old licence, which keeps them out of anything distributed with
a GPL program. None of the four is here, and no text of theirs is.

`r3000.c` and `r3000.h` are Exilum's own code, GPL-3.0-or-later, and how
they were made is this: that core was read once, apart from the writing, and
what it does was set down in words - when an interrupt is taken, what a load
in a delay slot does, what each coprocessor register keeps of what is
written to it, what the instruction the engine plants for a BIOS call does.
The processor here was written from that description, to behave as that
core does, instruction for instruction, since every tune this engine plays
was ripped and heard on it. What is the same as in MAME's files is what has
to be: the names the engine's files call (`mips_execute`, `mips_get_info`,
`mips_set_info` and the rest, and the constants they pass), and the facts of
the chip.

It was checked against the engine as downloaded: the same registers after
every one of 254 million instructions of each of 32 tunes, the same sound to
the bit, and the same on a program written to reach the corners.

Two things it does that the old one did not, neither of which changes what a
program does:

- **A program waiting is not run.** A jump to itself with nothing in its
  delay slot, in plain memory, uses up the rest of its time at once.
- **Instructions are read straight from memory** where the address is plain
  memory, without going through the hardware's handlers.

The coprocessor that does a game's geometry does nothing here: music has no
use for it.

## Built as it is on every machine

- **`-fwrapv -fno-strict-aliasing`.** The engine reads the console's memory
  and the sound unit's through other types, and its envelopes, its ADPCM and
  its reverb count on signed overflow wrapping.
- **`AOPSF_PS2` is 0.** The PlayStation 2's kernel in `psx_hw.c` - its
  threads, its module services, its two sound transfers and their registers,
  `ps2_hw_slice` and `ps2_hw_frame` - is walled off, and the two megabytes
  its loader kept in every console are gone with it. The second sound unit's
  registers and the countdowns for its threads and timers in
  `psx_hw_runcounters` are still compiled: they do nothing for a
  PlayStation.
- **`ioptimer_lw` is declared before it is called**, which the NDK's
  compiler needs.

## A file is held to what the console has — `eng_psf.c`

A program says where in memory it goes and how long it is, and was taken at
its word:

- A program shorter than its header was read past: the length is checked
  before the signature is.
- The place is brought inside the two megabytes and to a whole word, and the
  length cut to what the file holds and to what is left of the memory.
- Bytes are joined into words unsigned, so a high bit is not a shift into
  the sign.
- A console whose program has stopped the processor is not run on.

## What a program does is held to the console — `psx_hw.c`

A PSF is a program, and can do anything a program can. Where what it did
reached outside the console's memory, or took the phone's time without end,
it is held:

- **Error text is bounded.** Every message `psx_hw.c` writes goes through
  `psx_errf`, which stops at the end of the buffer; the processor's go
  through the same check in `r3000.c`. The loader's one message is written
  to a buffer it has just emptied.
- **The BIOS's string and memory calls** - `strncmp`, `strcpy`, `bzero`,
  `memcpy`, `memset` - stop at the end of memory. `strncmp` is done here
  rather than by the C library, and gives the difference of the two bytes
  that differ, on every machine.
- **The heap.** `malloc`, `free` and `InitHeap` keep their bookkeeping
  inside memory and give up after a fixed number of steps, so a heap that
  loops back on itself does not hang the player. The link to the next block
  was read from four times its address (a division by 4 left out): it is
  read from where it is written.
- **Events.** An event's number is checked against the table in the four
  calls that take one, and a full table refuses a new event.
- **Counters.** `ChangeClearRCnt`'s index is kept inside memory. A counter
  started before its compare register was written has a target of nothing,
  which with the count set to go back at its target went round in
  `counters_advance` for ever: the count goes back to nothing and the loop
  is left.
- **The jump buffer** `setjmp` and `longjmp` use is read and written inside
  memory.
- **Handlers have a budget, and a depth.** An interrupt's or an event's
  handler is run to its end inside the instruction that raised it. One that
  never returned ran for ever; so would a program whose handlers each stop
  just short of any limit of their own. They share one budget - four million
  instructions, filled again with every sample by more than a console's
  handlers have the time for - and a program that runs it out is stopped. A
  handler that ends up back in the exception it was called from, again and
  again, is stopped eight deep, where it used to run the thread's stack out.
- **Work done at once is rationed.** The BIOS's copying and clearing, its
  walks of the heap and a transfer to the sound unit are each one
  instruction to the program and up to megabytes of work to the phone. They
  draw on a budget of bytes - sixteen megabytes at once, the console's whole
  memory ninety times a second after that - and what is asked for beyond it
  is not done. No game comes near it.
- **A transfer to the sound unit** is also cut to the size of its memory.
- **`rand` and `srand`** keep their seed in the console, not in the C
  library's one for the whole process: two consoles at once, as a seek ahead
  makes, do not disturb each other, and a tune plays the same every time.
- **Memory through its mirror** at `0xa0000000` is the same memory, as on
  the console.
- **The sound unit's registers read and written a word at a time** are two
  halves, each to its own register.

At the end of the file are four calls for the player: which voices are kept
out of the mix, where each voice's own sound goes, whether the reverb is
heard, and what a voice is doing.

## The sound unit — `spu/`

- **Each voice can be heard alone and left out.** `spucore_render` takes a
  mask of voices to leave out of the mix and a buffer for each voice's own
  sound. A voice left out is still run, so it is where it would have been
  when it comes back; with no mask and no buffer the sound is what it was,
  to the bit.
- **The reverb can run unheard** (`spu_enable_reverb(state, 2)`), so that
  it is the same reverb when it is heard again.
- **Noise is noise.** The generator shifted by an amount no machine
  defines, and gave a square wave at every setting. It is the console's
  shift register now, at the rate the noise clock asks for.
- **ADPCM is clamped where the console clamps it.** Two of the four samples
  decoded together were let past 16 bits.
- **The reverb's addresses wrap by a remainder**, not by a loop that a
  program could make run for seconds.
- **A value was read that had never been set** when a voice restarted while
  an interrupt address was in force: it is set.
- The display reads a voice through calls that change nothing
  (`spu_get_voice_info`, `spu_get_main_volume`): reading its registers the
  way a program does has effects.

Of these, the noise generator and the ADPCM clamp change the sound, as they
are meant to: on the 32 tunes it was tried with the clamp moves 18 of them
by at most 112 of 32768, and none of them uses noise. Everything else, in
all the files, leaves those tunes as they were, to the bit.
