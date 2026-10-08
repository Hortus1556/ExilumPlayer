# HVL / AHX replayer

Vendored from [HivelyTracker](https://github.com/pete-gordon/hivelytracker),
`hvl2wav/` — the portable command-line replayer, which is the same core the
tracker itself uses with none of its UI.

Three files (`replay.c`, `replay.h`, `types.h`) rather than a submodule: the
upstream repository is a complete GUI application and this is the only part of
it that is wanted. BSD 3-Clause, see LICENSE.

Plays `.ahx` and `.thx` (AHX 1/2) and `.hvl` (Hively).
