# VGM

A VGM file is not a tune and not a program. It is the register traffic a real
machine produced while the music played, with the gaps between writes measured
in samples at 44100Hz. Playing one means writing the same bytes to the same
emulated chips at the same moments — nothing interprets a score, and the file
does not know what a note is.

`decoder-vgm.cpp` is the bridge: loading, tags, seeking, muting, the visual
frames and the shape of the output buffer. Everything below that — the chips,
the command stream, the data blocks, the sample-rate conversion — is libvgm's.
See `../libvgm/ANDROID-NOTES.md` for what is built and why one core is not.

Four formats come with it, all register logs of the same kind: **VGM** and
**VGZ** (gzipped), **S98** from the PC-98, **DRO** from a DOSBox capture of an
AdLib card, and **GYM** from the Mega Drive. The last three were a line each,
since the same library already had them.

## What is here

`strutils-android.c` — the codepage conversion libvgm expects from iconv.

Android has no iconv to link against below API 31, so libvgm's own
`StrUtils-CPConv_IConv.c` compiles into nothing and its three `CPConv_`
symbols come up undefined. libvgm only ever asks for three conversions and they
all go one way: UTF-16LE for a VGM's GD3 tags, CP932 for an S98's, CP1252 for a
GYM's, all to UTF-8. A character-set library to do that would be several
hundred kilobytes of tables for text that is only displayed.

One known gap: CP932's double-byte range needs a seven-thousand-entry
Shift-JIS table that is not carried here, so those characters become a
replacement mark. Only Japanese S98 tags reach it. If they turn out to matter,
the table is what to add.

## Not vgmstream

vgmstream is permissively licensed and worth having, but it is a different
library for a different job: it decodes *streamed* game audio — ADPCM, HCA,
ATRAC, container formats. It has no chip emulation and does not read register
logs. Its format list contains no `.vgz`, `.nsf`, `.gbs`, `.minipsf`, `.usf` or
`.2sf` at all, and the four entries that look like matches are other games'
containers sharing an extension: `"vgm"` is commented
`//txth/reserved [Maximo (PS2)]`, and `"spc"` belongs to Electronic Arts'
`ea_eacs`. If vgmstream is ever added for the streamed formats, this decoder
has to keep first refusal on `.vgm`.

## Still to do: the channel view

libvgm hands over a finished stereo pair. It offers muting per channel but not
*output* per channel, so every row of the channel view currently reads the same
mix level and no trace at all.

Getting the real ones back means teaching each core to keep a window of its own
output, which is what was done to `SPC_DSP` and the OPL3 already — see
`gme/ANDROID-CHANGES.md` for how that looks. It is chip by chip, so it will
arrive a few at a time, most-used first.

Before libvgm there was a hand-written decoder here that did have per-channel
traces, for the two Sega chips it knew. It is in the history if it is ever
wanted: the commit that replaced it is the one that vendored libvgm.
