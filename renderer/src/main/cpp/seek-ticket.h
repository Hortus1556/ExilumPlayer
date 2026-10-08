#pragma once

/**
 * Whether the seek the listener asked for, and a decoder is carrying out,
 * has been overtaken: by a later seek, another subsong, the next track, a
 * stop.
 *
 * A console's tune is a program, and a seek runs it to its place - for
 * minutes into a long one, many seconds, with the decoder's mutex and the
 * player's own lock held throughout. Whatever overtook the seek waited all
 * of that out: a Next that did nothing, a drag back along the bar that was
 * carried out only after the drag forward had been. A decoder that asks
 * this as it goes - once a block - stops where it has got to instead. Its
 * song is left at that place, whole, and what overtook the seek says where
 * it goes from there.
 *
 * Only for the listener's own seek. The way back to a loop's start is a seek
 * nobody asked for and nothing overtakes.
 */
bool engine_seekOvertaken();
