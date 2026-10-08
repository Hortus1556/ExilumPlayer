/*
** Exilum: the PlayStation's processor, as the rest of this engine asks for it.
**
** The engine came with smf's PlayStation processor core from MAME and two of
** MAME's headers, under a licence that keeps them out of anything sold.
** Neither is here, and no text of theirs. This header and r3000.c are Exilum's
** own, under the player's licence, written to the interface the engine's
** files use - the names below are that interface - and, from a description
** in words of how that core behaves, to behave as it does: see r3000.c, and
** ANDROID-CHANGES.md for how it was made and checked.
*/
#ifndef _R3000_H_
#define _R3000_H_

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "ao.h"
#include "psx_external.h"

/* An interrupt line's two states. */
#define CLEAR_LINE  (0)
#define ASSERT_LINE (1)

/* A coprocessor 2 register: four bytes, read as one word. */
typedef union
{
	UINT32 d;
} PAIR;

typedef struct mips_cpu_context MIPS_CPU_CONTEXT;

/* An address, handed to the memory handlers with its bits as they are. */
typedef int offs_t;

/* What mips_get_info and mips_set_info pass: the value, in i. */
union cpuinfo
{
	int64 i;
};

/* What to get or to set: one of these, or one with a register or a line added. */
enum
{
	CPUINFO_INT_PC = 20,
	CPUINFO_INT_INPUT_STATE = 22,
	CPUINFO_INT_REGISTER = 89
};

/* The registers, for CPUINFO_INT_REGISTER: the 32 general ones are in a row. */
enum
{
	MIPS_PC = 1,
	MIPS_DELAYV, MIPS_DELAYR,
	MIPS_HI, MIPS_LO,
	MIPS_R0, MIPS_R1, MIPS_R2, MIPS_R3, MIPS_R4, MIPS_R5, MIPS_R6, MIPS_R7,
	MIPS_R8, MIPS_R9, MIPS_R10, MIPS_R11, MIPS_R12, MIPS_R13, MIPS_R14, MIPS_R15,
	MIPS_R16, MIPS_R17, MIPS_R18, MIPS_R19, MIPS_R20, MIPS_R21, MIPS_R22, MIPS_R23,
	MIPS_R24, MIPS_R25, MIPS_R26, MIPS_R27, MIPS_R28, MIPS_R29, MIPS_R30, MIPS_R31
};

/* The one interrupt line the engine drives, for CPUINFO_INT_INPUT_STATE. */
#define MIPS_IRQ0 (0)

/*
** The instruction the engine plants where a BIOS call or the exception vector
** would be: SPECIAL with this function, which the processor answers by calling
** psx_bios_hle and nothing else.
*/
#define FUNCT_HLECALL (11)

extern void mips_get_info(MIPS_CPU_CONTEXT *cpu, UINT32 state, union cpuinfo *info);
extern void mips_set_info(MIPS_CPU_CONTEXT *cpu, UINT32 state, union cpuinfo *info);
extern int mips_execute(MIPS_CPU_CONTEXT *cpu, int cycles);
extern void mips_shorten_frame(MIPS_CPU_CONTEXT *cpu);
extern int mips_get_icount(MIPS_CPU_CONTEXT *cpu);
extern void mips_set_icount(MIPS_CPU_CONTEXT *cpu, int count);

/* The engine's, which its files expect to find declared here. */
extern void psx_hw_runcounters(PSX_STATE *psx);

#endif
