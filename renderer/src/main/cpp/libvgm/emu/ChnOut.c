/* Local addition - see ChnOut.h and ANDROID-NOTES.md. */
#include <string.h>

#include "../stdtype.h"
#include "ChnOut.h"

/* Clamped rather than wrapped: a trace that folded over on a loud note would
   draw a shape the chip never played. */
static INT16 ChnOut_Clamp(INT32 sample)
{
	if (sample > 32767)
		return 32767;
	if (sample < -32768)
		return -32768;
	return (INT16)sample;
}

static UINT32 ChnOut_Div(const CHNOUT_HIST* hist)
{
	return hist->div ? hist->div : 1;
}

UINT32 ChnOut_Divider(UINT32 chipRate)
{
	if (chipRate <= CHNOUT_MAX_RATE)
		return 1;
	/* Rounded up, so what is recorded never runs faster than the limit. */
	return (chipRate + CHNOUT_MAX_RATE - 1) / CHNOUT_MAX_RATE;
}

void ChnOut_SetRate(CHNOUT_HIST* hist, UINT32 chipRate)
{
	UINT32 div = ChnOut_Divider(chipRate);

	if (div == ChnOut_Div(hist))
		return;
	hist->div = div;
	ChnOut_Clear(hist);
	return;
}

void ChnOut_Clear(CHNOUT_HIST* hist)
{
	memset(hist->buf, 0x00, sizeof(hist->buf));
	memset(hist->cur, 0x00, sizeof(hist->cur));
	memset(hist->acc, 0x00, sizeof(hist->acc));
	hist->pos = 0;
	hist->phase = 0;
	hist->used = 0;
	return;
}

void ChnOut_Write(CHNOUT_HIST* hist, UINT8 channel, INT32 sample)
{
	if (channel >= CHNOUT_CHNS)
		return;
	hist->cur[channel] = sample;
	if (channel >= hist->used)
		hist->used = (UINT8)(channel + 1);
	return;
}

void ChnOut_Advance(CHNOUT_HIST* hist)
{
	UINT32 div = ChnOut_Div(hist);
	UINT8 chn;

	for (chn = 0; chn < hist->used; chn ++)
	{
		hist->acc[chn] += hist->cur[chn];
		hist->cur[chn] = 0;
	}
	hist->phase ++;
	if (hist->phase < div)
		return;

	for (chn = 0; chn < hist->used; chn ++)
	{
		hist->buf[chn][hist->pos] = ChnOut_Clamp(hist->acc[chn] / (INT32)div);
		hist->acc[chn] = 0;
	}
	hist->phase = 0;
	hist->pos ++;
	if (hist->pos >= CHNOUT_LEN)
		hist->pos = 0;
	return;
}

void ChnOut_ClearAhead(CHNOUT_HIST* hist, UINT32 count)
{
	UINT32 div = ChnOut_Div(hist);
	UINT32 slots;
	UINT8 chn;
	UINT32 i;

	/* Every recorded sample the run reaches, including one it only starts -
	   that one is cleared again, and finished, by the run that reaches its
	   end. */
	slots = (hist->phase + count + div - 1) / div;
	if (slots > CHNOUT_LEN)
		slots = CHNOUT_LEN;
	for (chn = 0; chn < CHNOUT_CHNS; chn ++)
	{
		for (i = 0; i < slots; i ++)
			hist->buf[chn][(hist->pos + i) % CHNOUT_LEN] = 0;
	}
	return;
}

/* Where the chip sample [offset] into the current run is recorded, or -1 when
   it is not the one kept for its slot. The last chip sample of each slot is the
   one kept, so a slot is only ever written by the run that completes it - never
   by an earlier run whose clearing the later one would undo. */
static INT32 ChnOut_SlotOf(const CHNOUT_HIST* hist, UINT32 offset)
{
	UINT32 div = ChnOut_Div(hist);
	UINT32 at;

	if (div == 1)
		return (INT32)((hist->pos + offset) % CHNOUT_LEN);
	at = hist->phase + offset;
	if (at % div != div - 1)
		return -1;
	return (INT32)((hist->pos + at / div) % CHNOUT_LEN);
}

void ChnOut_WriteAt(CHNOUT_HIST* hist, UINT8 channel, UINT32 offset, INT32 sample)
{
	INT32 slot;

	if (channel >= CHNOUT_CHNS)
		return;
	slot = ChnOut_SlotOf(hist, offset);
	if (slot < 0)
		return;
	hist->buf[channel][slot] = ChnOut_Clamp(sample);
	return;
}

void ChnOut_AddAt(CHNOUT_HIST* hist, UINT8 channel, UINT32 offset, INT32 sample)
{
	INT16* cell;
	INT32 slot;

	if (channel >= CHNOUT_CHNS)
		return;
	slot = ChnOut_SlotOf(hist, offset);
	if (slot < 0)
		return;
	cell = &hist->buf[channel][slot];
	*cell = ChnOut_Clamp(sample + *cell);
	return;
}

void ChnOut_AdvanceBy(CHNOUT_HIST* hist, UINT32 count)
{
	UINT32 div = ChnOut_Div(hist);
	UINT32 at = hist->phase + count;

	hist->pos = (hist->pos + at / div) % CHNOUT_LEN;
	hist->phase = at % div;
	return;
}

UINT8 ChnOut_PanFromSides(INT32 left, INT32 right)
{
	UINT32 l = (left < 0) ? (UINT32)-left : (UINT32)left;
	UINT32 r = (right < 0) ? (UINT32)-right : (UINT32)right;
	UINT32 pos;

	if (l + r == 0)
		return 128;
	/* Out of 256 rather than 255, so that equal sides land on 128 exactly. */
	pos = (UINT32)(((UINT64)r * 256) / ((UINT64)l + r));
	return (UINT8)(pos > 255 ? 255 : pos);
}

UINT32 ChnOut_Read(const CHNOUT_HIST* hist, UINT8 channel, INT16* out, UINT32 count)
{
	UINT32 first;
	UINT32 start;

	if (channel >= CHNOUT_CHNS || out == NULL || count == 0)
		return 0;
	/* One short of the whole ring: the slot at pos is the oldest sample and,
	   at a divided rate, also the one being built - which a channel-major core
	   has already cleared for its next run. */
	if (count > CHNOUT_LEN - 1)
		count = CHNOUT_LEN - 1;

	/* The newest [count] samples end just before pos, so the run starts that
	   far back - and wraps at most once, which is two copies rather than a
	   test per sample. */
	start = (hist->pos + CHNOUT_LEN - count) % CHNOUT_LEN;
	first = CHNOUT_LEN - start;
	if (first >= count)
	{
		memcpy(out, &hist->buf[channel][start], count * sizeof(INT16));
	}
	else
	{
		memcpy(out, &hist->buf[channel][start], first * sizeof(INT16));
		memcpy(&out[first], &hist->buf[channel][0], (count - first) * sizeof(INT16));
	}
	return count;
}
