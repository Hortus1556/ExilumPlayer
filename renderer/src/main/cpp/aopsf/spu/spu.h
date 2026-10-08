/////////////////////////////////////////////////////////////////////////////
//
// spu - Top-level SPU emulation, for SPU and SPU2
//
/////////////////////////////////////////////////////////////////////////////

#ifndef __PSX_SPU_H__
#define __PSX_SPU_H__

#include "emuconfig.h"

#ifdef __cplusplus
extern "C" {
#endif

sint32 EMU_CALL spu_init(void);
/* version = 1 for PS1, 2 for PS2 */
uint32 EMU_CALL spu_get_state_size(uint8 version);
void   EMU_CALL spu_clear_state(void *state, uint8 version);

void   EMU_CALL spu_set_buffer(void *state, sint16 *buf, uint32 samples);
void   EMU_CALL spu_advance(void *state, uint32 samples);
void   EMU_CALL spu_flush(void *state);

uint16 EMU_CALL spu_lh(void *state, uint32 a);
void   EMU_CALL spu_sh(void *state, uint32 a, uint16 d);

void   EMU_CALL spu_dma(void *state, uint32 core, void *mem, uint32 mem_ofs, uint32 mem_mask, uint32 bytes, int iswrite);

uint32 EMU_CALL spu_cycles_until_interrupt(void *state, uint32 samples);

void   EMU_CALL spu_interrupt_dma4(void *state);
void   EMU_CALL spu_interrupt_dma7(void *state);

/*
** Enable/disable main or reverb
*/
void EMU_CALL spu_enable_main(void *state, uint8 enable);
void EMU_CALL spu_enable_reverb(void *state, uint8 enable);
/* Exilum: spu_enable_reverb(state, 2): the reverb is run exactly as when on, and kept out of the output */

/*
** Exilum: voices to keep out of the mix: bit n = voice n (0..23). Cleared by spu_clear_state.
*/
void   EMU_CALL spu_set_voice_mute(void *state, uint32 mask);
uint32 EMU_CALL spu_get_voice_mute(void *state);
/*
** Exilum: where the voices go, one at a time, as the samples are rendered: 48 sint16 for
** each sample (voice 0 left, voice 0 right, voice 1 left...), for [samples] samples. NULL
** or 0 = nowhere. Follows the output buffer: set it before each run of samples, like
** spu_set_buffer. Cleared by spu_clear_state.
*/
void   EMU_CALL spu_set_voice_buffer(void *state, sint16 *vbuf, uint32 samples);

/*
** Exilum: what a display wants of a voice. Reads only: no flush, no side effects.
*/
struct SPU_VOICE_INFO {
  uint16 pitch;        /* 0x1000 = 44100 Hz */
  sint16 vol_l, vol_r; /* -0x8000..0x7FFE; 0x7FFE = the loudest */
  uint16 envx;         /* 0..0x7FFF */
  uint8  env_state;    /* 0 off, 1 attack, 2 decay, 3 sustain, 4 release */
  uint8  keyon_pending;/* a key-on waiting for the voice to be ramped down */
  uint8  noise, fm, reverb;
  uint32 addr;         /* the ADPCM block being played, a byte address in sound RAM */
};
void   EMU_CALL spu_get_voice_info(void *state, uint32 voice, struct SPU_VOICE_INFO *info);
/* Exilum: main volume, as the mix is scaled by it: -0x8000..0x7FFE. */
void   EMU_CALL spu_get_main_volume(void *state, sint16 *l, sint16 *r);

#ifdef __cplusplus
}
#endif

#endif
