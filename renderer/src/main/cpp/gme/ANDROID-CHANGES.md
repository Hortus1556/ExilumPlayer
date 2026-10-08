# Local changes to Game Music Emu

Vendored from [libgme/game-music-emu](https://github.com/libgme/game-music-emu)
at `f68963b` (0.6.3-249). It was a submodule until this change; it is a copy
now because the change cannot live outside the library.

Every change is additive, and nothing that worked before behaves differently.

## `src/gme/Vgm_Emu.cpp` — VGM may split into voices again

Kept, though nothing uses it now: `USE_GME_VGM` is off, because GME plays a
VGM against three chip cores — the two Genesis FM chips and a PSG — which is
one machine out of the forty-odd a VGM can address. libvgm has that job.

The change itself: `Vgm_Emu::set_multi_channel` refused every request, and the
comment above it said what it wanted to do instead —

> we acutally should check here whether this is classic emu or not
> however set_multi_channel() is called before setup_fm() resulting in uninited
> is_classic_emu()
> hard code it to unsupported

The check really is impossible there. Multi-channel has to be settled before
the sample rate is set, which is before the file is loaded, and whether a tune
uses FM is only known once `setup_fm()` has run. The caller can answer cheaply
from the header, though, so the decision moves there and this accepts either
answer.

## The SNES hands over its voices

`Spc_Emu` derives from `Music_Emu` rather than `Classic_Emu`, so it has no
multi-channel buffer to reroute: the DSP mixes its eight voices into one
output and that output is all a caller could see. Rendering the voices
separately is not the answer either - everything between the DSP and the
output is stereo, including the resampler, and there is only one of it.

A trace does not need to go through the audio path, though. `SPC_DSP` now
keeps the newest 2048 samples each voice put out, recorded where the voice's
`output` is already computed on its way into the mix - after its envelope,
before its volume and panning, which is the waveform the voice is playing
rather than its share of the mix. One store per voice per sample, in a member
outside `state_t` because it is a window and not state, so save states are
untouched.

| added | what |
| --- | --- |
| `SPC_DSP::voice_history(v, out, n, behind)` | n samples of one voice, oldest first, ending `behind` short of the newest |
| `Music_Emu::emulator_lead()` | how far the emulator has run past what was handed out |
| `Snes_Spc::voice_history` | forwards it |
| `Music_Emu::voice_history` | asks; returns 0 by default |
| `Music_Emu::voice_history_rate` | the rate they come at - the chip's, 32000 here |
| `SPC_DSP::voice_volume(v, &l, &r)` | the volume each side is played at, which is where the panning is |
| `Music_Emu::voice_volume` | asks; false by default |
| `gme_voice_history`, `gme_voice_history_rate`, `gme_voice_volume` | the same, in the C interface |

The recording sits beside `pmon_input = output`, which runs for every voice on
every sample, rather than beside the mix a few lines above it. The mix is only
reached while a voice's envelope is open, so a voice that has gone quiet
writes nothing there - and its window would then keep whatever was in it two
thousand samples earlier while the write position carried on past it, which
draws as a frozen piece of some older note.

`behind` is not decoration, and the reason is arithmetic rather than
principle. `Spc_Emu` sizes its resampler input buffer at
`native_sample_rate / 20 * 2` - fifty milliseconds of chip time - and `play_`
fills the whole of it in one call, then drains it over the next several. So
the DSP does not advance a little per callback. It leaps fifty milliseconds
and then stands still while two or three callbacks' worth of audio is served
out of the buffer.

Reading "the newest samples in the ring" therefore gives a window that freezes
for two or three display frames and then jumps, which is exactly what a trace
skipping frames looks like. `written()` is the input the resampler has not
consumed yet, so taking it off lands on the sample the last output actually
came from, and the window then moves as smoothly as the audio does.
`Music_Emu::emulator_lead` covers the other, rarer case: `play()` renders
ahead into a silence buffer, which during ordinary music it does not.

A caller therefore has to ask for fewer samples than the ring holds. `behind`
is clamped to what is left after `count`, so asking for the whole ring leaves
nowhere to look back from and the offset silently becomes nothing.

The recording is written through a plain index rather than `v - m.voices`.
`voice_t` is not a power of two in size, so that subtraction compiles to a
division, and it would run eight times for every sample the chip produces -
in the hottest loop the emulator has. Reading it back is two `memcpy`s rather
than a walk with a wrap test per sample, for the same reason at the other end:
a caller asking for a thousand samples several times a second should not pay
for a thousand branches to find out the window wrapped once.

Every emulator that hands out its voices through the multi-channel buffer
answers 0 to this and is unaffected; it exists for the ones that have no such
buffer. `Gym_Emu` is in that position too and could be given the same
treatment through `Ym2612_*`, and has not been - GYM is not built here.

## Where muting happens

Not a change to this tree, but worth recording next to it, because it splits
along the same line as everything above.

`gme_mute_voices` mutes by unhooking a voice's output buffer inside the core,
and putting it back does not put back what the core knew about it. On the NES,
where the triangle, noise and DMC mix on a nonlinear curve, rehooking the DMC
delivers its whole DAC level as one step, which then has to bleed off through
the buffer's high-pass — several seconds of the music riding on a DC offset
outside the output range.

So where the voices arrive separately, muting is a subtraction in the mix on
our side and the emulator is never told. Where they do not, the emulator's own
muting is the only one there is, and `gme_mute_voices` is still what gets
called — on the SNES that is a mask inside the DSP, which is nothing like an
unhooked buffer.

Cleanly in principle, but not in two details, both fixed:

- `Spc_Dsp::mute_voices` zeroed a voice's volume from one sample to the next,
  which clicked on every toggle and crackled when toggled quickly. It now
  leaves the volume alone, and `voice_t::mute_gain` fades the voice's share of
  the mix towards its mute bit over 128 samples (4 ms) instead. Only the mix:
  pitch modulation and OUTX still see the voice as the chip plays it. `load`
  starts each gain at its mute state rather than fading into it.
- Muting inside the emulator makes its output silent, and `Music_Emu` treats
  silence as the tune going quiet: it emulates up to three times ahead into a
  buffer to find the end of it, so an unmute was heard only after that buffer,
  made with the old mask, had played out — and after six seconds it ended the
  track. Not a change to this tree: `decoder-gme.cpp` calls
  `gme_ignore_silence` whenever the listener has any voice muted.

## Cores that are not built

`USE_GME_AY`, `USE_GME_GYM`, `USE_GME_HES`, `USE_GME_KSS`, `USE_GME_SAP` and
`USE_GME_VGM` are forced off from the parent CMakeLists, so ZX Spectrum, Sega
Genesis, PC Engine, MSX and Atari are not compiled in and their types do not
appear in `gme_type_list`. What is kept is NSF, NSFE, SPC and GBS — the NES,
the SNES and the Game Boy. This is a switch, not a patch: nothing in this tree
is changed for it.

## The SNES echo can be turned off without flattening the music

`Spc_Dsp::disable_echo` was already there. What it did was more than its name:

```c
if ( !m.echo_enable ) { mvoll = 127; mvolr = 127; evoll = 0; evolr = 0; }
```

Zeroing the echo volumes is the echo going away. Forcing the master volume to
full is something else entirely — MVOL is where a tune writes its own fades
and its own balance, and a great many SPCs end by fading through it. Turning
the echo off would have turned every one of those into a hard stop at full
volume.

Now only `evoll` and `evolr` are zeroed. Nothing else about the DSP changes,
the echo buffer is still written, and a tune that reads its own echo region
back still reads what it wrote.

Note that `Spc_Dsp::init` calls `disable_echo(false)`, so this is put back on
every time an SPC is loaded — the caller has to say it again after each
`gme_start_track`.

## A choice of interpolation on the SNES

The SNES reads a voice between its samples through a four-tap gaussian table.
That table is not a neutral resampler: it rolls the top off hard, and that
softness is a real part of how the machine sounds. It is still the default and
the only accurate answer.

Two alternatives are offered beside it, over the same four taps and scaled to
the same 2048 so they are interchangeable in the mix:

| mode | what |
| --- | --- |
| `Spc_Dsp::interp_none` | the nearer of the two middle taps — the raw BRR, edges and all |
| `Spc_Dsp::interp_gaussian` | the hardware table, unchanged, and the default |
| `Spc_Dsp::interp_sinc` | Lanczos-2 windowed sinc, built once into a 256×4 table on first use |

Neither of the new two takes the slow-gaussian path. That path exists to get
the exact sample a pitch-modulated voice needs, and a voice being read through
something the SNES never had is already not going to give it.

The mode lives outside `state_t` — that struct is memset on construction and
serialised by `copy_state`, and this is a listener's choice rather than any
part of the chip. It therefore survives `load()` and `reset()`, unlike the
echo flag.

| added | what |
| --- | --- |
| `Spc_Dsp::set_interpolation(mode)` | the mode |
| `Snes_Spc::set_interpolation` | forwards it |
| `Music_Emu::set_interpolation` | asks; ignored by default |
| `gme_set_interpolation` | the same, in the C interface |

## Pitch without tempo

An emulator has no pitch control. It runs the tune's own code, and every
frequency comes out of a register the tune wrote — there is nothing to scale.
What can be changed is how fast the machine is told it runs, and that moves
pitch and tempo together.

So `Music_Emu::set_pitch(factor)` does both halves and lets them cancel: the
core is handed a tempo divided by the factor, and then the clock is multiplied
by it. The play routine ends up firing at the same wall-clock rate it did
before, and every period the chip counts in machine clocks comes out scaled.

The two families need it in different places.

`Classic_Emu` — the NES and the Game Boy — carries it in the clock rate it
gives `Blip_Buffer`, which is the number that turns machine clocks into
seconds. `base_clock_` is new and holds what `setup_buffer` was told, kept
apart from `clock_rate_` because that one now carries the pitch and would
compound every time the tempo was set again.

`Spc_Emu` has no clock to scale: the DSP produces 32000 samples a second
whatever happens. The pitch goes into the resampler's ratio instead — reading
more of the chip per output sample raises everything on it. The one case this
cannot cover is a stream already running at 32000, where there is no resampler
in the way to put it in.

Two smaller things fall out of it. `Music_Emu::set_tempo` has to put `tempo_`
back after calling `set_tempo_`, since the default implementation of that
stores whatever it is handed and what it is handed is now divided by the
pitch — `tempo()` has to keep meaning what the listener asked for, because
`Nsf_Emu::load_` and `post_load_` both re-apply it and would otherwise divide
it again on every load.

And `effective_tempo()` is new for the other side of that: a core working out
how often to call the tune's play routine wants the divided figure, not the
asked-for one. `Gbs_Emu::update_timer` is the one place that reads it rather
than taking it as an argument, and it is called again on a timer register
write — so reading `tempo()` there would have dropped the compensation partway
through a tune and let it run fast.

| added | what |
| --- | --- |
| `Music_Emu::set_pitch(factor)`, `Music_Emu::pitch()` | the factor, clamped to 0.25–4.0 |
| `Music_Emu::apply_pitch_()` | where a core scales its clock; nothing by default |
| `Classic_Emu::apply_pitch_`, `Classic_Emu::base_clock_` | the Blip_Buffer clock |
| `Spc_Emu::apply_pitch_` | the resampler ratio |
| `gme_set_pitch` | the same, in the C interface |

## Updating

Re-apply the changes above on top of the new release. They touch thirteen files
— `Vgm_Emu.cpp`, `SPC_DSP.h`, `SPC_DSP.cpp`, `Snes_Spc.h`, `Spc_Emu.h`,
`Spc_Emu.cpp`, `Classic_Emu.h`, `Classic_Emu.cpp`, `Gbs_Emu.cpp`,
`Music_Emu.h`, `Music_Emu.cpp`, `gme.h`, `gme.cpp` — and `git diff` against a fresh checkout
of the revision above shows them exactly. Every one of them is marked in place
with `local addition` or `local change`, so grepping for those finds the lot.

## Voices a multi-channel buffer keeps apart

`gme.cpp` built every multi-channel emulator's `Effects_Buffer` with room for
eight voices. An NSF's expansion chips go past that - a VRC7 brings six voices
on top of the APU's five, a Namco 163 eight - and `Effects_Buffer::channel`
wraps past its last buffer (`i % max_voices`), so those voices were mixed into
the APU's own: panning one moved another, and a scope showed two at once.

The buffer is built for sixteen now (`gme_multi_voices`), which covers the
widest of them, and `Effects_Buffer::set_used_voices` - called through the new
`gme_set_used_voices` once a track is loaded - tells it how many that track
really has. The rest are written as silence rather than read three blip
buffers at a time, so a three-voice Game Boy costs less than it did at eight.

`Music_Emu::out_channels` is two for each of those sixteen as well. It stayed
at `2*8` when the buffer was widened, and everything `Music_Emu` times is
counted in it - `tell`, `tell_scaled`, `msec_to_samples`, the fade, the
silence it waits out, `seek_scaled` - so with thirty-two channels a frame
played and sixteen counted, a multi-channel track's clock ran twice as fast as
its sound: an NSF or a GBS was over in half its length, and a seek landed at
half the place asked for.
