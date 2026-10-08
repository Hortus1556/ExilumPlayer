# libvgm, as vendored here

Upstream [ValleyBell/libvgm](https://github.com/ValleyBell/libvgm) at revision
`c8b998b606895990c409a512b86c5509070f9f0d`.

Changes are recorded below the way `gme/ANDROID-CHANGES.md` records GME's, and
marked in place with `local addition` or `local change`.

## Pitch without tempo

`PlayerBase::SetPitch(factor)`, a no-op by default, implemented by
`VGMPlayer`. Five files: `player/playerbase.hpp`, `player/vgmplayer.hpp`,
`player/vgmplayer.cpp`, `emu/Resampler.h`, `emu/Resampler.c`.

An emulator has no pitch control. It runs the tune's own writes and every
frequency comes out of a register the tune set, so there is nothing to scale -
and libvgm's one speed control moves the command stream with the chips, which
is a tempo change wearing a pitch control's name.

What can be changed is the rate a device's output is *believed* to have been
produced at. Every device is resampled from its chip's rate to the output's, so
declaring the first of those higher than it is raises everything on that device
in exact proportion and touches nothing else: not the chip, and not the stream
that decides when notes start. `Resmpl_ChangeRate` already existed for chips
that change their own rate mid-song, and does the bookkeeping.

`RESMPL_STATE` keeps the chip's own rate (`smpRateNative`) and the pitch apart,
and reads the chip at their product. `Resmpl_SetPitch` changes the second;
`Resmpl_ChangeRate`, which chips call when they change their own rate, now
changes only the first. Before that split, a chip changing its rate mid-song -
an OKIM6295's pin 7, an OPN's prescaler, which PC-98 drivers set on start-up -
wrote its native rate over the pitched one and dropped back to no pitch, and a
pitch set afterwards was worked out from the rate the chip had started at.

Done once here rather than per chip, so it works for all of them - which is why
the pitch control is not restricted to the popular ones the way the channel
traces are.

Not implemented for `S98Player`, `DROPlayer` or `GYMPlayer`; they inherit the
no-op, so pitch does nothing on those three formats.

## Per-channel traces

libvgm only hands back a finished stereo mix. The channel view needs each
voice on its own, so:

- `DEV_DEF::GetChannelOutput` (`emu/EmuStructs.h`), added **last** in the
  struct. Every core initialises its `DEV_DEF` positionally, so it is null in
  all of them except the ones taught to fill it, and a chip whose core is not
  taught simply has no channel view.
- `DEV_DEF::GetChannelPan`, added after it for the same reason: where a channel
  sits between the speakers, 0 left to 255 right, read off the chip's own
  panning state for the channel view's pan cursor. Null means centred, which
  is also the truth for the mono chips that leave it so (YM2203, OKIM6295,
  K051649, uPD7759).
- `emu/ChnOut.h` / `emu/ChnOut.c`: the per-channel history those cores keep,
  and `ChnOut_PanFromSides`, the one scale every core's pan is reported on.
- `PLR_DEV_INFO` gains `devDef`, `dataPtr` and `smplRateLive`, filled by
  `VGMPlayer::GetSongDeviceInfo` for running devices, linked ones included.

Taught, on the core libvgm plays **by default** for each chip - the first in
its `DEV_DECL`; a tap on any other core is never reached:

| chip | default core | file |
|---|---|---|
| SN76489, T6W28 | MAME | `sn76496.c` |
| YM2612 (+ DAC) | MAME/GPGX | `fmopn.c` via `2612intf.c` |
| YM2203/2608/2610/2610B (FM, ADPCM-A, delta-T) | MAME | `fmopn.c` via `opnintf.c` |
| YM2151 | MAME | `ym2151.c` |
| AY8910/YM2149, and the SSG of the OPNs | EMU2149 | `emu2149.c` |
| NES APU (+ FDS) | NSFPlay | `np_nes_apu.c`, `np_nes_dmc.c`, `nesintf.c` |
| Game Boy | SameBoy | `sameboy_apu.c` |
| HuC6280 | Ootake | `Ootake_PSG.c` |
| YM3812, YMF262 | AdLibEmu | `adlibemu_opl_inc.c` via `oplintf.c`, `262intf.c` |
| YM3526, Y8950 (+ ADPCM) | MAME | `fmopl.c` via `oplintf.c` - their only core |
| OKIM6295 | MAME | `okim6295.c` |
| YM2413 (+ rhythm) | EMU2413 | `emu2413.c`, `emu2413_private.h` |
| SegaPCM | MAME | `segapcm.c` - its only core |
| K051649 / SCC | MAME | `k051649.c` - its only core |
| K054539 | MAME | `k054539.c` - its only core |
| C140 | MAME | `c140.c` - its only core |
| C219 | MAME | `c219.c` - its only core |
| QSound (16 PCM + 3 ADPCM) | ctr | `qsound_ctr.c` |
| RF5C68, RF5C164 | MAME | `rf5c68.c` |
| YMZ280B | MAME | `ymz280b.c` - its only core |
| MSM6258 | MAME | `okim6258.c` - its only core |
| uPD7759 | MAME | `upd7759.c` - its only core |

Also taught, though not default: Nuked OPN2, Nuked OPM, Nuked OPL3, MAME
YM3812 (the same `fmopl.c` as the YM3526), MAME HuC6280 - they were done first, before the default-core rule was understood.

Cores run at their native rate, 7.5 kHz to 450 kHz. Each taught core tells its
history that rate (`ChnOut_SetRate`), including when the chip changes it, and
above 64 kHz the history keeps the average of every few samples - so a trace is
the same length of sound on every chip.

## What was left out

`libs/` (Windows prebuilt zlib and iconv — Android has its own), the test
programs, `audio/` (Oboe does the output), and the Visual Studio project
files. 7.8MB upstream, 3.1MB here.

## Licence

Two things to know, because neither is obvious.

**libvgm upstream carries no licence.** There is no `LICENSE` or `COPYING`
file in the repository and GitHub's own metadata reports `license: null`. It is
vendored here on the strength of permission being sought directly from the
author. Until that is granted and recorded, this directory is the one part of
the tree whose distribution is not yet settled — if the answer is no, the
commit that added it is the thing to revert.

**Nine cores inside libvgm are not the author's to license.** `fmopn.c`,
`fmopl.c`, `ym2151.c`, `ym2413.c`, `ymf262.c`, `ymf278b.c`, `nes_apu.c`,
`msm5232.c` and `nukedopll.c` are Jarek Burczynski's and MAME's, and carry
their own GPL marking. No permission from the author of libvgm reaches them.
They are usable here because this project is
**GPL-3.0-or-later**.

That version is not a free choice either:

- Oboe, AndroidX, Compose and Media3 are Apache-2.0, which **GPLv2 cannot
  take**. So it has to be v3.
- `ymf278b.c` (the OPL4) is marked `license:GPL-2.0` with no "or later", which
  **GPLv3 cannot take**. So that one chip is switched off.

Everything else is BSD, LGPL, MIT, or GPL-2.0-**or-later**, and the "or later"
is what lets it into a GPLv3 work.

> The repository root now says so too: `LICENSE` is the GPL-3.0 text, and
> `NOTICE` lists every component with its own licence.

## Which cores are built

Every device libvgm has an `SNDEMU_*_ALL` option for, except `YMF278B`. The
list is written out in the parent `CMakeLists.txt` rather than switched on with
`SNDEMU__ALL`, because that option overrides the individual ones and would pull
the OPL4 back in.

Seventy-seven core objects, which between them is the Yamaha FM family, the
Sega and NEC machines, the Game Boy, the NES, MSX, and forty-odd arcade PCM and
wavetable chips.

## Building

Only `vgm-emu`, `vgm-player` and `vgm-utils` are built. Verified against the
NDK's own toolchain for `arm64-v8a` at `android-26`: configures and compiles
with no errors, and the finished shared library is 4.2MB stripped.
