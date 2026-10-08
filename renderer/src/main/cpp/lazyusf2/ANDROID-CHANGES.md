# Local changes to lazyusf2

Vendored from [kode54/lazyusf2](https://gitlab.com/kode54/lazyusf2) at
`421f00b` ("Fix playback for several USFs"): Mupen64Plus cut down to the
console's sound, which is what plays USF files. Left out are the tests, the
Visual Studio project, the Makefile, the debugger (unused, and under a
licence of its own) and the dynamic recompilers, which are for x86 only -
their headers stay, since the interpreters still include them.
`r4300/empty_dynarec.c` is left out too: it only stands in for a recompiler
that is never called.

It is a copy rather than a submodule because what the player needs of it is
inside the core. Every change is marked `Exilum` where it is made. On the
files it was tried with - 81 Bomberman 64 tunes - none changes the sound.

The files keep the line endings they came with, which are not all the same.

## Built as it is on every machine

- **Its names are its own.** Every library is linked into one shared object,
  where two functions of one name do not fail to link: one of them is taken
  for both. lazyusf2 names its functions as an emulator does - `main_run`,
  `init_memory`, `open_rom`, `trim` - so they are compiled under a prefix,
  `lazyusf_`, by `lazyusf2-symbols.h`, which CMake includes in every source.
  Its resampler and bit arrays are renamed by their own headers' hooks,
  `RESAMPLER_DECORATE` and `BARRAY_DECORATE`. Its interface, `usf_*`, keeps
  its names: two of them are also the names of fields, so a `usf_` prefix
  would have made them twice.
- **The RSP interpreter runs without SIMD.** It can be built for NEON
  (`ARCH_MIN_ARM_NEON`), which is untried; the plain C path is what was
  checked against the HLE and played through. The sound code is done by the
  HLE unless the setting asks for the interpreter.
- **`-fwrapv -fno-strict-aliasing`.** The core reads 32-bit halves of 64-bit
  registers and floats out of integer arrays, and counts on signed overflow
  wrapping, as the compilers it was written for did.

## A shift is masked everywhere — `rsp_lle/su.h`

The RSP shifts by a register's low five bits. `MASK_SA` masked them only
`#if ANDROID`, which nothing defines here. ARM does not mask a shift by a
register; x86, which does, gives the same with the mask. Masked always.

## A task the HLE does not know goes to the interpreter — `rsp_hle/plugin.c`

`HleForwardTask` is what the HLE calls with a task it has no version of. It
had no body and no return value, which the HLE then tested: undefined, and on
arm64 it happened to read as "not forwarded", so the task was dropped, and
its sound with it. It runs the task on the RSP interpreter now, which runs
anything, and says so. `HleShowCFB` returned a value from a `void` function,
which the NDK's compiler refuses.

## A file is held to what it has — `usf/usf.c`

A USF's reserved area is blocks of a length, a place and the bytes for it,
for the cartridge and then for the saved state, and `usf_upload_section`
took each block's word for both:

- **The cartridge.** A block could say it was longer than what was left of
  the file, which was read past; or be placed so that its end wrapped round
  the 32-bit address, which the check on the cartridge's size missed, or so
  far out that doubling the size to fit it never ended. A block has to be
  within what the file has, and end within 64 MB, the largest cartridge.
- **The saved state.** Its blocks were written wherever they said, past the
  end of its 8 MB buffer too. A block has to fit in it.

A section that breaks either is refused, and the decoder refuses the file.

## A saved state is held to what it has — `main/savestates.c`

The saved state - a Project64 one - says how much of the console's memory it
holds, and the loader read that much more of the state after it. The size
was added to in 32 bits, so one near 4 GB wrapped round to a few kilobytes:
the buffer was made that small, and then the 4 GB copied out of it into the
8 MB the memory has. The size has to fit that memory, in whole words, or the
state is refused.

A vertical sync register of all ones made the time a line takes a division
by zero, which traps on x86 and is undefined everywhere; it is no time at all.

## Loops that never end — `r4300/tlb.c`, `rsp_lle/execute.h`

Two places in the core could be made to run for ever, by the saved state or
by the code the game runs, where nothing else would stop them:

- **The TLB.** A mapping is entered page by page, counting the address up a
  page at a time until its end. The count was 32 bits, so a mapping that
  ends at the very top of the address space wrapped back to 0 and went round
  for ever, while the state was loaded or when the game wrote the entry. It
  is counted in 64.
- **The RSP interpreter.** It watched for one kind of runaway: the program
  counter wrapping round its 4 KB of instruction memory. A branch to itself
  never does that, and the sound code is run on it whenever the HLE does not
  know it (see above), or always with the setting off. A task stops after
  64 million instructions; one that makes a sound block takes thousands.
  The game then waits for a task that never finishes, makes no more sound,
  and the watchdog below ends the song.

## A song without sound ends — `usf/usf.c`, `usf/usf_internal.h`, `vi/vi_controller.c`

`usf_render` runs the console until it has made the sound asked of it. A
file whose game never makes any - damaged, or not music at all - ran it for
ever, and the player with it. The state counts the screens drawn since the
last sound (`frames_without_audio`, cleared by each sound and by each render)
and after a minute of the console's own time the render stops with an
error, which the decoder takes as the end of the song.

## A resampler cleared is empty — `usf/resampler.c`

`resampler_clear` put the resampler's places back but left the samples it
had in it, which the next ones were mixed with. It empties them, as a new
one is. This is the resampler `usf_render_resampled` uses, cleared by
`usf_restart`; the decoder resamples with one of its own, made new at the
top of the song, so a song started again sounds the same either way.

## The mixing, watched a voice at a time — `usf/usf.h`, `usf/usf.c`, `usf/usf_internal.h`, `rsp_hle/hle_external.h`, `rsp_hle/plugin.c`, `rsp_hle/alist.c`, `rsp_hle/alist_audio.c`, `rsp_hle/alist_naudio.c`

So the player can show each voice of the music as a channel, and mute it.
`usf_set_voice_watch` gives the console three callbacks, which it keeps
through `usf_restart`:

- **A voice mixed.** The HLE's ENVMIXER is where a voice goes into the
  mix: into the dry buses, left and right, and the wet ones the effects take.
  In the ABI1 lists (`ENVMIXER`, `ENVMIXER_GE`) and the naudio ones it now
  calls `HleMixVoice` before and after, with the buses and the address of the
  voice's state, which tells one voice from another. Between the two the
  watcher may put back what the buses held, which takes the voice out. The
  nead lists keep no such address in the command, and MusyX mixes another
  way: neither is watched, and the player shows their two sides instead.
- **An audio list done** - `HleAudioListDone`, at the end of `alist_process`.
- **Sound handed over** - from `usf_push_audio_samples`, the frames it kept,
  in the order `usf_render` gives them out.

`plugin.c` passes the first two on, as it does the HLE's other calls out.
Nothing is called with no watch set, and a watch that changes nothing changes
nothing: on the 81 Bomberman 64 tunes the sound is the same to the bit,
either way the sound code is run.
