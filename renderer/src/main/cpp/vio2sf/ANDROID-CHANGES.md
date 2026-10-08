# Local changes to vio2sf

Vendored from [kode54/vio2sf](https://gitlab.com/kode54/vio2sf) at `1d68801`
(2019-05-02): the `src/vio2sf/desmume` directory, which is the whole core.
Left out are the Makefile, the Visual Studio project and `trim2sflib.c`, a
tool for trimming libraries - its 2SF loader is what `decoder-2sf.cpp` follows.

It is a copy rather than a submodule because what the player needs of it is
inside the core. Every change is marked `Exilum` where it is made. For a file
that names no more than its cartridge holds, and with nothing asked of the
additions, the sound is the same sample for sample as before them - compared
over twenty seconds of three tunes at 44100 Hz.

The files keep the line endings they came with, which are not all the same.

## The rate is the caller's — `state.h`, `state.c`, `SPU.cpp`

The core gave out 44100 Hz and nothing else: the number was written into the
timing in `state_render` and into `adjust_channel_timer`. `NDS_state` has a
`dwSampleRate` now, and both read it; 0 is the 44100 it was.

Each voice is resampled from its own timer's rate straight to the rate given
out, so this costs no second resampling - which a fixed 44100 would have,
since the player's output is 48000.

## A muted voice keeps playing — `SPU.cpp`

`dwChannelMute` took a voice out of the mix by not running it at all. It
stood still for as long as it was muted: a one-shot never reached its end,
the busy bit the game reads never cleared, and the voice carried on from
where it had stopped when it was unmuted. A game's sound driver that waits
for a voice to finish played a different song with a voice muted.

A muted voice is run exactly as an unmuted one now and only kept out of the
sum (`____SPU_ChanUpdate`, and the call to it in `SPU_MixAudio`). Unmuted
again it is where it would have been: the output is the same from then on as
if it had never been muted.

This is also what makes a seek exact. A seek runs the console with every
voice muted, to skip the mixing, and what comes after has to be what playing
straight through would have given.

## Each voice's own sound, for showing — `state.h`, `SPU.cpp`

`NDS_state` can be handed sixteen rings, `channel_tap`, one a voice. As a
voice is mixed its sample - after its volume, before its pan - is written to
its ring, at the place `channel_tap_pos` counts up to. A voice that is
stopped, or stops part-way through a block, leaves silence there. The traces
and the level meters are drawn from these.

Nothing is written when `channel_tap` is NULL, which it is unless set.

## A pan of the player's own — `state.h`, `SPU.cpp`

`NDS_state::channel_pan[16]` is added to each voice's pan as it is mixed, and
held to the console's 0 to 127. The voice's own pan is put back afterwards:
it is what the game wrote, and what the registers say.

All zero unless set, which changes nothing.

## Held inside the memory it has — `SPU.h`, `SPU.cpp`, `MMU.c`, `state.c`, `ARM9.h`

A 2SF is a program, and can ask for anything. A file that is damaged, or
made to do harm, could make the core read and write outside the memory it
has. These places took its word for where things were:

- **The memory map.** Every address is turned into a place in one of the
  console's memories by masking it to that memory's size. Two masks were
  larger than their memories: the ARM7's I/O registers, 64 KB under a 16 MB
  mask, and the ARM9's LCD memory, 656 KB under a 1 MB one. Any access past
  the end of either - from the game's code, a DMA, or `NDS_Reset` copying a
  program to wherever the header says - went past the end of the heap block
  it was in. The I/O mask is the 64 KB the registers have (every register
  past them is answered before the mask is applied), and the LCD memory is
  1 MB, as its mask is (`state.c`, `ARM9.h`, and the `memset` that clears
  it in `MMU.c`).
- **Halfwords and words at odd addresses.** The core passed a halfword or a
  word access on at whatever address it came with, so one at the end of a
  memory wrote up to three bytes past it - past the four bytes of
  `UNUSED_RAM`, into the pointers to the memory map. `MMU_read16`,
  `MMU_write16`, `MMU_read32` and `MMU_write32` align the address first, as
  the bus does: the CPU still rotates what an unaligned load reads, from
  the address it had.
- **A saved state's processor number.** `load_setstate` read each
  processor's `proc_ID` from the file, and the memory map is indexed by it.
  Which processor is which is not the file's to say: it is set back after
  the load.
- **A sample's length.** The length registers can say more than there is
  from the sample's start to the end of the memory it starts in, and the
  mixer read on past it. `channel_struct::room` is how many bytes there are,
  known when the voice is keyed on, and `adjust_channel_length` holds the
  length to it wherever the length is worked out. The mixer reads one sample
  past the end on its way round a loop; that one is held to the last sample
  there is (`channel_struct::last`), so a sample that fills its memory
  exactly keeps its length. A sample with too little memory left to start
  in - less than one 16-bit sample, or less than an ADPCM header and its
  first byte - does not start. KeyOn's test for a sample of no length asks
  the length registers rather than the length worked out from them, which is
  what upstream's test came to: clamped against an earlier note's memory, the
  worked-out one could be nothing for a good note.
- **A loop with nothing in it.** A looping voice of no length, with a loop
  start, never left the `while` in `TestForLoop` that takes a position back
  into the loop - it subtracted nothing each time round. It is treated as a
  voice that does not loop, and ends.
- **An ADPCM sample's starting index.** Seven bits of the sample's header,
  into a table of 89 rows. Held to the table (`adpcm_start_index`).
- **A read from the card.** The address in a card read command was used as
  it came, into the cartridge buffer. Masked to the cartridge's size, as the
  mapped reads already are (`MMU.c`, the `0xB7` transfer).

The decoder checks that the header's two programs, which `NDS_Reset` copies
from wherever the header says, lie inside the cartridge; where they are
copied to is held by the memory map.

## Tables filled once — `SPU.cpp`

The ADPCM tables and the resampler's were filled by every console as it was
made, the resampler's at a voice's first note. The decoder makes a second
console on another thread, ahead of a jump, and the two filled them over each
other. `SPU_Init` fills all of them once, for every console, with
`std::call_once`.

## The cartridge is read only — `MMU.c`

The cartridge is mapped at `0x08000000`, where the game can write to it, and
it is the decoder's own buffer: the core reads it in place, and the decoder
starts the console from it again on a loop or a seek back. A write there is
dropped now, at the end of `MMU_write8`, `MMU_write16` and `MMU_write32`, as a
cartridge drops it.

## Words read without a cast — `mem.h`

The `T1` and `T2` accessors read and wrote 16 and 32 bit words by casting the
byte pointer. One of the memories they are used on, `SWIRAM`, sits at an odd
offset inside its structure, so every word in it was misaligned - undefined
in C, and on 32-bit ARM a real fault if the compiler picks an instruction
that needs alignment. They copy with `memcpy` now, which compiles to the same
single load or store without the promise. `T1ReadLong` still reads the word
its address is in, not the four bytes at the address, as it did.

## Shifts C leaves undefined — `armcpu.h`, `thumb_instructions.c`, `MMU.c`

`ROR(i, 0)` shifted left by 32, and so did the Thumb word load's rotation of
an aligned word; the Thumb conditional branch shifted a negative offset, and
a DMA's control test shifted a one into an int's sign bit (`MMU.c`). Each
is written so C defines it, with the answer every machine it ran on already
gave.

## Built with `NDEBUG`

Not a change to the source: `spumuldiv7`, in the mixer's inner loop, asserts
its multiplier, and `state_setrom` asserts that the cartridge's size is a
power of two. The decoder makes it one.
