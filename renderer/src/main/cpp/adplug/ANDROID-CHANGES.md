# Local changes to AdPlug

Vendored from [adplug/adplug](https://github.com/adplug/adplug) at tag
`adplug-2.4` (`e491e38`). It was a submodule until these changes; it is a copy
now because they cannot live outside the library.

Every change is additive. Nothing upstream behaves differently, and no
existing declaration changed shape — the additions are new virtuals on
`CPlayer` with defaults that report exactly what the interface reported
before, which is nothing.

## Why

AdPlug's formats are mostly trackers: an order list, patterns, a grid of
notes, named instruments. Its *interface* is not. `CPlayer` reports which
pattern and row a tune is on, and how many instruments it has, and there it
stops — there is no way to ask which instrument is playing on a channel, or
what is in a given cell. The players all know; several of them keep the whole
grid in memory. It just never reached the outside.

Working around that from outside is not possible safely. `CmodPlayer` keeps a
per-channel instrument in a protected member, but the number of channels it
allocated is private, and formats like FMC and MTRACK read that number out of
the file — so any bound a caller picked would be a guess at the size of a heap
array. `Crad2Player` is worse: its whole replayer lives inside `rad2.cpp` and
is not declared in any header.

So the accessors are added where the data is.

## What was added

`src/player.h` — four virtuals on `CPlayer`, all defaulting to "I do not know":

| method | returns |
| --- | --- |
| `getchannels()` | channels this player drives, 0 if it does not think in channels |
| `getpatternrows()` | rows in every pattern, 0 if the format has no grid |
| `getchannelinstrument(chan)` | the instrument sounding on a channel, one-based, 0 for none |
| `getcell(pattern, row, chan, Cell&)` | one cell; false when there is nothing there |

`CPlayer::Cell` is a new nested struct: note, instrument, command and its two
parameter digits, in the format's own numbering, and a second command with its
two digits for the formats with a second effect column (AdLib Tracker II).

`src/protrack.h`, `src/protrack.cpp` — `CmodPlayer` implements all four. Each
one reads a member it already keeps; `getcell` resolves a cell the same way
`update()` resolves the row it is about to play, through `trackord` into
`tracks`. Covers adtrack, amd, cff, dfm, dtm, fmc, mad, mtr and sa2.

Those loaders translate every effect into `CmodPlayer`'s own command numbers
as they load, and rewrite parameters to suit - a set volume turned upside down,
a remembered value filled in - which is what `update()` plays and nothing a
person reads. So `CmodPlayer` also keeps `dtracks`, the same cells as the
tracker that wrote the file shows them, and `getcell` reads those whenever a
loader filled them: through `display(track, row)` beside its own translation,
or `keep_display_tracks()` between reading and a translation pass of its own
(AMD). amd, cff, dfm, dtm, fmc, mad, mtr and sa2 fill them; adtrack has no
effects to translate. Checked against real AMD files, where the grid had shown
set volume 4 as H, effect 8 as 3 and effect 7 as I.

`src/a2m.h`, `src/a2m.cpp` — `Ca2mLoader` (AdLib Tracker versions 1-8)
overrides `getcell`. Its loader converts every effect into `CmodPlayer`'s own
command numbers, which the inherited `getcell` handed straight to the pattern
view - a portamento shown as N, a volume slide as H. The file's own cell bytes
are now kept beside the converted tracks (`rawcells`), and `getcell` reads
those: effects in the tracker's numbering, parameters untouched.

`src/rad2.h`, `src/rad2.cpp` — `Crad2Player` implements all four, on top of
three new methods on `RADPlayer`. `GetCell` walks a packed track the way the
replay code does, because RAD stores a track as a stream of lines with no
index to seek into. It uses `UnpackNote`, which leaves its results in members
the replay code reads back, so it must not run while the tune is playing — the
caller holds the same lock the render callback takes.

`src/hsc.h`, `src/hsc.cpp` — `ChscPlayer` implements all four. HSC is a fixed
nine channels by sixty-four rows and the grid is already a member. Covers
`.hsc`, and through its two subclasses `.hsp` and `.mtk`.

`src/a2m-v2.h`, `src/a2m-v2.cpp` — `Ca2mv2Player` implements all four plus the
position accessors it never had (`getpatterns`, `getpattern`, `getorders`,
`getorder`, `getrow`, `getspeed`), all reading state it already keeps.
`get_event_p` was already the accessor for a cell; it just had nobody outside
to hand one to. Covers `.a2m` and `.a2t` in their version 2 form. A cell's
note is decoded the way `play_line` decodes it - 0xFF a key off, past
`fixed_note_flag` a fixed note - and both effect columns are handed back.

`src/nukedopl.h`, `src/nukedopl.c` — the OPL3 core hands back what each
channel put into the mix. `OPL3_Generate4Ch` already computes that value per
channel on its way to summing them; it now keeps it, with the same one-sample
delay the right half of the mix has, so the eighteen pairs sum to the mix
exactly. `OPL3_GenerateResampledChannels` returns both, resampling the
channels alongside the mix.

Without it a caller has to read the slot outputs behind the resampler's back
and add them up itself — which gives an unresampled approximation of a
channel, and a second, different mix to switch to whenever one is muted. With
it, muting is subtracting a channel's own contribution from the core's own
output, and a scope trace is that contribution.

### Note numbering, and where a row is

`getcell` hands back notes on one numbering, where one is C-0, whatever the
format stores - so the grid names the note that sounds. HSC, RAD and the two
`CmodPlayer` loaders that take RAD's frequency table (cff, dtm) count their
note one as C#: `note_table`, `NoteFreq` and that table all start at 0x16B. As
stored, every note there showed a semitone flat - B where C played. Their
`getcell` adds the semitone (not for `.mtk`, whose player takes it back off
itself); `CmodPlayer` decides by its `notetable`, so the SA2 table (0x154, C)
is left alone.

Every one of these players plays a row and moves `getrow` on in the same tick,
so between rows it names the row about to play, not the one playing.
`decoder-adplug.cpp` keeps the position from before the last tick that moved
it instead (`tickLocked`).

Both checked by playing 48 real AMD, HSC and RAD tunes into a chip that records
key-ons: a key-on found a note in the row shown 99-100% of the time (0-90%
before), and the note shown matched the frequency written to the chip in all of
them. The shown notes left without a key-on are all tone portamento - AMD
effect 8, RAD effect 3 - which slides to its note rather than striking it.

### Formats this reaches

Pattern grid and instrument names: `.hsc`, `.hsp`, `.mtk`, `.rad`, `.a2m`,
`.a2t`, `.sng`, `.amd`, `.xms`, `.cff`, `.dfm`, `.dtm`, `.mad`, `.mtr`,
`.sat`, `.sa2`.

Everything else keeps the defaults and is played without a grid — either
because the format is a register log with no grid to show (`.dro`, `.imf`,
`.mid`, `.cmf`, `.raw`), or because its player has not been taught these four
methods yet (`.lds`, `.d00`, `.ksm`, `.rol`, `.mus`, the `.xad` family). The
second group is a matter of doing the same work again per player; the data is
there.

## What was removed

`test/` (forty-six megabytes of music files) and `packaging/` (distribution
archives), with the two `add_subdirectory` calls that reached them. Neither is
built here. Also `contrib/`, `m4/`, `adplugdb/` and the autotools files, none
of which this build uses.

## Updating

Re-apply these changes on top of the new release. They are small and all in
four files; `git diff` against a fresh checkout of the tag above will show
them exactly.
