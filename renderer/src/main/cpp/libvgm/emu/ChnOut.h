#ifndef __CHNOUT_H__
#define __CHNOUT_H__

/*
 * Local addition - see ANDROID-NOTES.md.
 *
 * A window of what each channel of a chip put out on its own, for drawing a
 * trace of it.
 *
 * libvgm hands a caller a finished stereo pair. That is the right thing for
 * playing, and useless for a channel view, which wants the waveform each voice
 * is playing rather than its share of the mix. So the cores worth drawing keep
 * one of these and write each channel into it as they produce it - after the
 * envelope and before the panning, which is the same thing a tracker's scope
 * shows.
 *
 * Shared so that the ten or so cores taught this all look alike: a member, one
 * write per channel per sample, an advance, and an accessor. See any of them
 * for the shape.
 *
 * The window is deliberately generous. A trace is drawn from it into a couple
 * of hundred columns, and it has to stay wider than the distance the sound
 * travels between two display frames - a window narrower than that cannot
 * overlap from one frame to the next, and a trace that cannot overlap appears
 * doubled.
 *
 * Generous in time, that is, and chips do not agree on how many samples make a
 * second. libvgm runs each at its own native rate, from under 8 kHz for an OKI
 * ADPCM chip to nearly 450 kHz for the NES APU, so a fixed number of samples is
 * a quarter of a display frame on one chip and a quarter of a second on
 * another. So a history can be told its chip's rate, and above CHNOUT_MAX_RATE
 * it records one sample for every few the chip produces - their average, for
 * the same reason a scope column is an average. What it holds then never runs
 * faster than CHNOUT_MAX_RATE, where CHNOUT_LEN samples are 32 ms: twice a
 * 60 Hz display frame.
 *
 * A history never told its rate records every sample, which is right for any
 * chip at or under CHNOUT_MAX_RATE.
 */

#include "../stdtype.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Enough for the widest chip taught this so far - the OPL3, whose eighteen
   melodic channels are joined by five percussion ones in rhythm mode. */
#define CHNOUT_CHNS 24
#define CHNOUT_LEN 2048
/* The fastest a history records at. CHNOUT_LEN samples at this rate are 32 ms. */
#define CHNOUT_MAX_RATE 64000

typedef struct _channel_output_history
{
	INT16 buf[CHNOUT_CHNS][CHNOUT_LEN];
	UINT32 pos;	/* the recorded sample being built, and where it goes */
	UINT32 div;	/* chip samples per recorded one; 0 means 1 */
	UINT32 phase;	/* chip samples already summed into the one being built */
	INT32 cur[CHNOUT_CHNS];	/* this chip sample, per channel */
	INT32 acc[CHNOUT_CHNS];	/* this recorded sample so far, per channel */
	UINT8 used;	/* channels ever written, so a commit touches no more than those */
} CHNOUT_HIST;

/** How many chip samples one recorded sample stands for, for a chip at [chipRate]. */
UINT32 ChnOut_Divider(UINT32 chipRate);

/**
 * Tells a history the rate its chip produces at.
 *
 * Clears it when that changes how it records and leaves it alone when it does
 * not, so a core can call this wherever it learns its rate - on every reset
 * and every clock change - without blanking the trace each time.
 */
void ChnOut_SetRate(CHNOUT_HIST* hist, UINT32 chipRate);

/** Forgets everything recorded. For a reset. Keeps the rate. */
void ChnOut_Clear(CHNOUT_HIST* hist);

/**
 * Records one channel for the current chip sample. Does not advance.
 *
 * A second write to the same channel before the advance replaces the first,
 * and a channel not written at all for a sample counts as silent for it - so a
 * core that skips a channel on some samples shows silence there rather than
 * whatever the slot held a window ago.
 */
void ChnOut_Write(CHNOUT_HIST* hist, UINT8 channel, INT32 sample);

/** Moves on by one chip sample, once every channel has been written. */
void ChnOut_Advance(CHNOUT_HIST* hist);

/*
 * The four below are for a core that renders one channel across a whole
 * buffer before starting the next, rather than every channel per sample.
 *
 * Such a core clears the run it is about to fill, writes each channel at an
 * offset into it, and moves the position on once at the end. Clearing first is
 * what keeps a channel that produced nothing this buffer - switched off, or
 * muted - from showing whatever it was playing before.
 *
 * At a divided rate these keep the last chip sample of each recorded one rather
 * than their average: a channel-major core writes one channel's whole run
 * before starting the next, and there is nowhere to keep a running sum for
 * every slot of it. Of the cores using this shape only MAME's HuC6280 is fast
 * enough to be divided, and libvgm plays Ootake's by default.
 */
void ChnOut_ClearAhead(CHNOUT_HIST* hist, UINT32 count);
void ChnOut_WriteAt(CHNOUT_HIST* hist, UINT8 channel, UINT32 offset, INT32 sample);
/** Adds to what is already there, for a channel written more than once a sample. */
void ChnOut_AddAt(CHNOUT_HIST* hist, UINT8 channel, UINT32 offset, INT32 sample);
void ChnOut_AdvanceBy(CHNOUT_HIST* hist, UINT32 count);

/**
 * The newest [count] recorded samples of one channel, oldest first.
 *
 * Recorded, not produced: at a divided rate they are ChnOut_Divider chip
 * samples apart, and anything measured off them - a pitch, a length of time -
 * is measured against the chip's rate divided by that.
 *
 * At most CHNOUT_LEN - 1 of them: the last slot of the ring is the one being
 * built.
 *
 * Matches DEV_DEF::GetChannelOutput, so a core's accessor is one line.
 * Returns how many were written, which is zero for a channel out of range.
 */
UINT32 ChnOut_Read(const CHNOUT_HIST* hist, UINT8 channel, INT16* out, UINT32 count);

/**
 * A pan position, 0 hard left to 255 hard right with 128 dead centre, from how
 * much of a channel reaches each side - enable bits or volumes, in any units,
 * signs ignored. A channel reaching neither side is called centred.
 *
 * For DEV_DEF::GetChannelPan, so every core reports on the same scale.
 */
UINT8 ChnOut_PanFromSides(INT32 left, INT32 right);

#ifdef __cplusplus
}
#endif

#endif	/* __CHNOUT_H__ */
