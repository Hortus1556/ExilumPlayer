/*
** Exilum: the PlayStation's processor - a MIPS R3000A, interpreted - for this
** engine. Exilum's own, under the player's licence; see r3000.h for why.
**
** It is written to what the engine counts on, which is not always what the
** chip does, and the differences are deliberate: every song this engine plays
** was heard right on a processor that behaves this way - smf's core in MAME,
** which the engine came with. This one was written from a description of
** that behaviour, not from that code, none of which is here.
**
** - An instruction is a cycle, whatever it does, and mips_execute runs as many
**   as it is asked for: at least one.
** - A load is late only when it is itself in a branch's delay slot: its
**   register is then written when the instruction after it completes. Anywhere
**   else the very next instruction sees it.
** - An interrupt is taken when the status or the cause register is written -
**   by the program, by RFE, or by the engine moving the interrupt line - and at
**   no other time: nothing is looked at between two instructions. The engine
**   moves the line from its memory handlers too, so an exception can be raised
**   in the middle of a load or a store; the instruction then completes on the
**   state the exception left, which is why nothing of the processor's is kept
**   in a local variable across a handler.
** - One pair of fields, delayr and delayv, holds a branch's delay slot (delayr
**   32: after the instruction at pc, go to delayv) and a late load (delayr 1
**   to 31: that register is owed delayv). The engine saves and restores them
**   with the registers when it runs a handler of the game's.
** - The instruction the engine plants for its BIOS, FUNCT_HLECALL, calls
**   psx_bios_hle and does nothing else: where to go on is the engine's to say.
** - Opcodes nobody defined do nothing at all, not even move on.
**
** The geometry coprocessor's registers are kept and its commands do nothing: a
** game's music does not ask for perspective.
*/
#include <stdarg.h>

#include "r3000.h"
#include "psx_internal.h"

/* The PlayStation 2's kernel calls: left out unless the engine is built with them. */
#ifndef AOPSF_PS2
#define AOPSF_PS2 (0)
#endif

/* Called before each instruction, for comparing one processor with another a step at a time. */
#ifdef CPU_TRACE
extern void cpu_trace(void *psx, unsigned pc, const unsigned *r32, unsigned hi, unsigned lo,
                      unsigned delayv, unsigned delayr, const unsigned *cp0r32);
#endif

#define SR       (cpu->cp0r[12])
#define CAUSE    (cpu->cp0r[13])
#define EPC      (cpu->cp0r[14])
#define BADVADDR (cpu->cp0r[8])

/* delayr when the instruction at pc is in a branch's delay slot. */
#define IN_SLOT (32)

#define SR_IEC (0x00000001)
#define SR_KUC (0x00000002)
#define SR_ISC (0x00010000)
#define SR_BEV (0x00400000)
#define SR_RE  (0x02000000)
#define SR_CU0 (0x10000000)
#define SR_CU1 (0x20000000)
#define SR_CU2 (0x40000000)

enum
{
	EXC_INT = 0, EXC_ADEL = 4, EXC_ADES = 5, EXC_SYS = 8, EXC_RI = 10, EXC_CPU = 11, EXC_OVF = 12
};

/* The bits an MTC0 can change of each register of coprocessor 0. */
static const UINT32 mtc0_mask[32] =
{
	0xffffffff, 0x00000000, 0xffffff00, 0x00000000, 0xffe00000, 0x00000000, 0x00000000, 0x00000000,
	0x00000000, 0x00000000, 0xffffffc0, 0x00000000, 0xf27fff3f, 0x00000300, 0x00000000, 0x00000000,
	0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
	0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000
};

/* The top bit, when the program is in user mode: an address with it set is not the user's. */
static INLINE UINT32 user_bit(MIPS_CPU_CONTEXT *cpu)
{
	return (SR & SR_KUC) ? 0x80000000u : 0;
}

/*
** Something the engine cannot run: a line for whoever asks what went wrong,
** while there is room for one, and nothing when there is not.
*/
static void cpu_note(MIPS_CPU_CONTEXT *cpu, const char *fmt, ...)
{
	PSX_STATE *psx = cpu->psx;
	size_t used = (size_t)(psx->error_ptr - psx->error_buffer);
	size_t left;
	int n;
	va_list v;

	if (used >= sizeof(psx->error_buffer) - 1) return;
	left = sizeof(psx->error_buffer) - used;
	va_start(v, fmt);
	n = vsnprintf(psx->error_ptr, left, fmt, v);
	va_end(v);
	if (n > 0) psx->error_ptr += ((size_t)n < left) ? (size_t)n : left - 1;
}

/* An instruction this processor has no way to run: said, and the console marked as stopped. */
static void cpu_unsupported(MIPS_CPU_CONTEXT *cpu, const char *what)
{
	cpu_note(cpu, "%08x: %s not supported\n", cpu->pc, what);
	cpu->psx->stop = 1;
}

/* What is owed is paid: a late load to its register - or a branch's target, when another branch takes its place, to nowhere. */
static INLINE void land(MIPS_CPU_CONTEXT *cpu)
{
	if (cpu->delayr != 0)
	{
		if (cpu->delayr == IN_SLOT)
			cpu->cp0r[0] = cpu->delayv;
		else
			cpu->r[cpu->delayr] = cpu->delayv;
		cpu->delayr = 0;
		cpu->delayv = 0;
	}
}

/* On to the next instruction: the branch's target after its delay slot, the one after this otherwise. */
static INLINE void advance(MIPS_CPU_CONTEXT *cpu)
{
	if (cpu->delayr == IN_SLOT)
	{
		cpu->pc = cpu->delayv;
		cpu->delayr = 0;
		cpu->delayv = 0;
	}
	else
	{
		land(cpu);
		cpu->pc += 4;
	}
}

/* On, and the instruction's result into its register. */
static INLINE void put(MIPS_CPU_CONTEXT *cpu, UINT32 reg, UINT32 value)
{
	advance(cpu);
	if (reg != 0) cpu->r[reg] = value;
}

/* On, and a load's value: at once, or owed when the load is in a delay slot. */
static INLINE void load(MIPS_CPU_CONTEXT *cpu, UINT32 reg, UINT32 value)
{
	if (cpu->delayr == IN_SLOT)
	{
		cpu->pc = cpu->delayv;
		cpu->delayr = reg;
		cpu->delayv = value;
	}
	else
	{
		put(cpu, reg, value);
	}
}

/* Into the exception vector: the status stack pushed, the cause and the place to come back to kept. */
static void exception(MIPS_CPU_CONTEXT *cpu, UINT32 code)
{
	SR = (SR & ~0x3fu) | ((SR << 2) & 0x3f);
	if (cpu->delayr == IN_SLOT)
	{
		EPC = cpu->pc - 4;
		CAUSE = (CAUSE & ~0x0000007cu) | 0x80000000u | (code << 2);
	}
	else
	{
		land(cpu);
		EPC = cpu->pc;
		CAUSE = (CAUSE & ~0x8000007cu) | (code << 2);
	}
	cpu->pc = (SR & SR_BEV) ? 0xbfc00180u : 0x80000080u;
	cpu->delayr = 0;
	cpu->delayv = 0;
}

/* A coprocessor the program has not been given. */
static void coprocessor_unusable(MIPS_CPU_CONTEXT *cpu, UINT32 number)
{
	exception(cpu, EXC_CPU);
	CAUSE = (CAUSE & ~0x30000000u) | (number << 28);
}

/*
** After the status or the cause register is written: an interrupt that is
** now both waiting and let in is taken, here and nowhere else. And a program
** counter that is no place to run from is an address error - once: entering
** an exception does not come back here.
*/
static void status_written(MIPS_CPU_CONTEXT *cpu)
{
	if ((SR & SR_IEC) && (SR & CAUSE & 0x0000ff00))
	{
		exception(cpu, EXC_INT);
	}
	else if (cpu->delayr != IN_SLOT && (cpu->pc & (user_bit(cpu) | 3)) != 0)
	{
		exception(cpu, EXC_ADEL);
		BADVADDR = cpu->pc;
	}
}

/* A branch taken: the instruction after it runs first. One to an address that is no place to run from faults here. */
static INLINE void branch(MIPS_CPU_CONTEXT *cpu, UINT32 target)
{
	if ((target & (user_bit(cpu) | 3)) != 0)
	{
		exception(cpu, EXC_ADEL);
		BADVADDR = target;
	}
	else
	{
		land(cpu);
		cpu->delayr = IN_SLOT;
		cpu->delayv = target;
		cpu->pc += 4;
	}
}

/* A data register of coprocessor 2, read: some are narrower than a word, and one is made of three others. */
static UINT32 gte_data_read(MIPS_CPU_CONTEXT *cpu, UINT32 n)
{
	switch (n)
	{
		case 1: case 3: case 5: case 8: case 9: case 10: case 11:
			cpu->cp2dr[n].d = (UINT32)(INT32)(INT16)cpu->cp2dr[n].d;
			break;
		case 17: case 18: case 19:
			cpu->cp2dr[n].d = cpu->cp2dr[n].d & 0xffff;
			break;
		case 29:
			cpu->cp2dr[n].d = ((cpu->cp2dr[9].d >> 7) & 0x1f) | ((cpu->cp2dr[10].d >> 2) & 0x3e0) |
			                  ((cpu->cp2dr[11].d << 3) & 0x7c00);
			break;
		default:
			break;
	}
	return cpu->cp2dr[n].d;
}

/* A data register of coprocessor 2, written: three of them move others. */
static void gte_data_write(MIPS_CPU_CONTEXT *cpu, UINT32 n, UINT32 value)
{
	cpu->cp2dr[n].d = value;
	switch (n)
	{
		case 15:
			cpu->cp2dr[12].d = cpu->cp2dr[13].d;
			cpu->cp2dr[13].d = cpu->cp2dr[14].d;
			cpu->cp2dr[14].d = value;
			break;
		case 28:
			cpu->cp2dr[9].d = (value & 0x1f) << 4;
			cpu->cp2dr[10].d = (value & 0x3e0) >> 1;
			cpu->cp2dr[11].d = (value & 0x7c00) >> 6;
			break;
		case 30:
		{
			/* How many of its top bits are the same as its sign: 1 to 32. */
			const UINT32 sign = value >> 31;
			UINT32 count = 0;
			UINT32 bit;
			for (bit = 0x80000000u; bit != 0 && ((value & bit) != 0) == (sign != 0); bit >>= 1) count++;
			cpu->cp2dr[31].d = count;
			break;
		}
		default:
			break;
	}
}

/* The word at pc. Plain memory is read where it is; anything else is the engine's to answer. */
static INLINE UINT32 fetch(MIPS_CPU_CONTEXT *cpu)
{
#ifdef PSX_PLAIN_MEMORY
	const uint32 *word = psx_plain_memory(cpu->psx, cpu->pc);
	if (word != NULL) return LE32(*word);
#endif
	return program_read_dword_32le(cpu->psx, (offs_t)cpu->pc);
}

/* SPECIAL: the instructions told apart by their last six bits. */
static void special(MIPS_CPU_CONTEXT *cpu, UINT32 op)
{
	const UINT32 rs = (op >> 21) & 31;
	const UINT32 rt = (op >> 16) & 31;
	const UINT32 rd = (op >> 11) & 31;
	const UINT32 sa = (op >> 6) & 31;
	const UINT32 a = cpu->r[rs];
	const UINT32 b = cpu->r[rt];

	switch (op & 63)
	{
		case 0: /* SLL */
			put(cpu, rd, b << sa);
			break;
		case 2: /* SRL */
			put(cpu, rd, b >> sa);
			break;
		case 3: /* SRA */
			put(cpu, rd, (UINT32)((INT32)b >> sa));
			break;
		case 4: /* SLLV */
			put(cpu, rd, b << (a & 31));
			break;
		case 6: /* SRLV */
			put(cpu, rd, b >> (a & 31));
			break;
		case 7: /* SRAV */
			put(cpu, rd, (UINT32)((INT32)b >> (a & 31)));
			break;
		case 8: /* JR */
			if (rd != 0)
				exception(cpu, EXC_RI);
			else
				branch(cpu, a);
			break;
		case 9: /* JALR: the link is written whether the jump faulted or not, and after it */
		{
			const UINT32 link = cpu->pc + 8;
			branch(cpu, a);
			if (rd != 0) cpu->r[rd] = link;
			break;
		}
		case FUNCT_HLECALL:
			psx_bios_hle(cpu->psx, cpu->pc);
			break;
		case 12: /* SYSCALL */
			exception(cpu, EXC_SYS);
			break;
		case 13: /* BREAK */
			cpu_note(cpu, "%08x: BREAK\n", cpu->pc);
			cpu->psx->stop = 1;
			advance(cpu);
			break;
		case 16: /* MFHI */
			put(cpu, rd, cpu->hi);
			break;
		case 17: /* MTHI: the register read after going on, so a late load into it is seen */
			if (rd != 0)
			{
				exception(cpu, EXC_RI);
			}
			else
			{
				advance(cpu);
				cpu->hi = cpu->r[rs];
			}
			break;
		case 18: /* MFLO */
			put(cpu, rd, cpu->lo);
			break;
		case 19: /* MTLO */
			if (rd != 0)
			{
				exception(cpu, EXC_RI);
			}
			else
			{
				advance(cpu);
				cpu->lo = cpu->r[rs];
			}
			break;
		case 24: /* MULT */
			if (rd != 0)
			{
				exception(cpu, EXC_RI);
			}
			else
			{
				const INT64 product = (INT64)(INT32)a * (INT64)(INT32)b;
				advance(cpu);
				cpu->lo = (UINT32)product;
				cpu->hi = (UINT32)((UINT64)product >> 32);
			}
			break;
		case 25: /* MULTU */
			if (rd != 0)
			{
				exception(cpu, EXC_RI);
			}
			else
			{
				const UINT64 product = (UINT64)a * (UINT64)b;
				advance(cpu);
				cpu->lo = (UINT32)product;
				cpu->hi = (UINT32)(product >> 32);
			}
			break;
		case 26: /* DIV */
			if (rd != 0)
			{
				exception(cpu, EXC_RI);
			}
			else if (b == 0)
			{
				advance(cpu);
			}
			else if (a == 0x80000000u && b == 0xffffffffu)
			{
				/* The one quotient that does not fit: as the chip leaves it, where a host's division traps. */
				advance(cpu);
				cpu->lo = 0x80000000u;
				cpu->hi = 0;
			}
			else
			{
				const INT32 quotient = (INT32)a / (INT32)b;
				const INT32 remainder = (INT32)a % (INT32)b;
				advance(cpu);
				cpu->lo = (UINT32)quotient;
				cpu->hi = (UINT32)remainder;
			}
			break;
		case 27: /* DIVU */
			if (rd != 0)
			{
				exception(cpu, EXC_RI);
			}
			else if (b == 0)
			{
				advance(cpu);
			}
			else
			{
				const UINT32 quotient = a / b;
				const UINT32 remainder = a % b;
				advance(cpu);
				cpu->lo = quotient;
				cpu->hi = remainder;
			}
			break;
		case 32: /* ADD */
		{
			const UINT32 sum = a + b;
			if ((~(a ^ b) & (a ^ sum)) & 0x80000000u)
				exception(cpu, EXC_OVF);
			else
				put(cpu, rd, sum);
			break;
		}
		case 33: /* ADDU */
			put(cpu, rd, a + b);
			break;
		case 34: /* SUB */
		{
			const UINT32 difference = a - b;
			if (((a ^ b) & (a ^ difference)) & 0x80000000u)
				exception(cpu, EXC_OVF);
			else
				put(cpu, rd, difference);
			break;
		}
		case 35: /* SUBU */
			put(cpu, rd, a - b);
			break;
		case 36: /* AND */
			put(cpu, rd, a & b);
			break;
		case 37: /* OR */
			put(cpu, rd, a | b);
			break;
		case 38: /* XOR */
			put(cpu, rd, a ^ b);
			break;
		case 39: /* NOR */
			put(cpu, rd, ~(a | b));
			break;
		case 42: /* SLT */
			put(cpu, rd, (INT32)a < (INT32)b ? 1 : 0);
			break;
		case 43: /* SLTU */
			put(cpu, rd, a < b ? 1 : 0);
			break;
		default:
			exception(cpu, EXC_RI);
			break;
	}
}

/* Coprocessor 0: the status, the cause and the way back from an exception. */
static void cop0(MIPS_CPU_CONTEXT *cpu, UINT32 op)
{
	const UINT32 rs = (op >> 21) & 31;
	const UINT32 rt = (op >> 16) & 31;
	const UINT32 rd = (op >> 11) & 31;

	if ((SR & SR_KUC) && !(SR & SR_CU0))
	{
		coprocessor_unusable(cpu, 0);
		return;
	}
	if (rs == 0) /* MFC0: a load like any other */
	{
		load(cpu, rt, cpu->cp0r[rd]);
	}
	else if (rs == 4) /* MTC0 */
	{
		const UINT32 value = (cpu->cp0r[rd] & ~mtc0_mask[rd]) | (cpu->r[rt] & mtc0_mask[rd]);
		advance(cpu);
		cpu->cp0r[rd] = value;
		if (rd == 12 || rd == 13) status_written(cpu);
	}
	else if (rs >= 16 && (op & 63) == 16) /* RFE */
	{
		advance(cpu);
		SR = (SR & ~0xfu) | ((SR >> 2) & 0xf);
		status_written(cpu);
	}
	else
	{
		cpu_unsupported(cpu, "this coprocessor 0 instruction is");
		advance(cpu);
	}
}

/* Coprocessor 2, the geometry one: its registers move as they should, and its commands are passed over. */
static void cop2(MIPS_CPU_CONTEXT *cpu, UINT32 op)
{
	const UINT32 rs = (op >> 21) & 31;
	const UINT32 rt = (op >> 16) & 31;
	const UINT32 rd = (op >> 11) & 31;

	if (!(SR & SR_CU2))
	{
		coprocessor_unusable(cpu, 2);
		return;
	}
	if (rs >= 16)
	{
		advance(cpu);
		return;
	}
	switch (rs)
	{
		case 0: /* MFC2 */
			load(cpu, rt, gte_data_read(cpu, rd));
			break;
		case 2: /* CFC2 */
			load(cpu, rt, cpu->cp2cr[rd].d);
			break;
		case 4: /* MTC2 */
			gte_data_write(cpu, rd, cpu->r[rt]);
			advance(cpu);
			break;
		case 6: /* CTC2 */
			cpu->cp2cr[rd].d = cpu->r[rt];
			advance(cpu);
			break;
		default:
			cpu_unsupported(cpu, "this coprocessor 2 instruction is");
			advance(cpu);
			break;
	}
}

/*
** The loads and the stores. Each reads its operands, then asks the engine for
** the memory - which may raise an exception under it - then completes on the
** state as it is by then.
*/
static void memory(MIPS_CPU_CONTEXT *cpu, UINT32 op)
{
	const UINT32 code = op >> 26;
	const UINT32 rs = (op >> 21) & 31;
	const UINT32 rt = (op >> 16) & 31;
	const UINT32 ea = cpu->r[rs] + (UINT32)(INT32)(INT16)op;
	const UINT32 b = cpu->r[rt];
	const UINT32 user = user_bit(cpu);
	/* The stores are 40 to 46 and, into coprocessor 2's memory, 58; 50 loads from it. */
	const int is_store = code >= 40 && code != 50;
	/* The other endian, which only a program in user mode can ask for: bytes and halves from the word's other end. */
	const int reverse = (SR & (SR_RE | SR_KUC)) == (SR_RE | SR_KUC);
	void *psx = cpu->psx;
	UINT32 check;
	UINT32 value;

	if ((code == 50 || code == 58) && !(SR & SR_CU2))
	{
		coprocessor_unusable(cpu, 2);
		return;
	}
	if (SR & SR_ISC)
	{
		/* The cache cut off from memory: a word stored goes nowhere, as a program flushing the cache means it to. */
		if (code != 43) cpu_unsupported(cpu, "memory with the cache isolated is");
		advance(cpu);
		return;
	}

	switch (code)
	{
		case 33: case 37: case 41: check = user | 1; break; /* LH, LHU, SH */
		case 35: case 43: check = 0; break;                 /* LW, SW: as they come */
		case 50: case 58: check = user | 3; break;          /* LWC2, SWC2 */
		default: check = user; break;
	}
	if ((ea & check) != 0)
	{
		if (code == 42) cpu_note(cpu, "%08x: SWL to %08x\n", cpu->pc, ea);
		exception(cpu, is_store ? EXC_ADES : EXC_ADEL);
		BADVADDR = ea;
		return;
	}

	switch (code)
	{
		case 32: /* LB */
			value = (UINT32)(INT32)(INT8)program_read_byte_32le(psx, (offs_t)(reverse ? ea ^ 3 : ea));
			load(cpu, rt, value);
			break;
		case 36: /* LBU */
			value = program_read_byte_32le(psx, (offs_t)(reverse ? ea ^ 3 : ea));
			load(cpu, rt, value);
			break;
		case 33: /* LH */
			value = (UINT32)(INT32)(INT16)program_read_word_32le(psx, (offs_t)(reverse ? ea ^ 2 : ea));
			load(cpu, rt, value);
			break;
		case 37: /* LHU */
			value = program_read_word_32le(psx, (offs_t)(reverse ? ea ^ 2 : ea));
			load(cpu, rt, value);
			break;
		case 35: /* LW */
			value = program_read_dword_32le(psx, (offs_t)ea);
			load(cpu, rt, value);
			break;
		case 34: /* LWL: the word's top bytes, from an address that need not be a word's */
		{
			UINT32 first, second;
			if (!reverse)
			{
				switch (ea & 3)
				{
					case 0:
						first = program_read_byte_32le(psx, (offs_t)ea);
						value = (b & 0x00ffffff) | (first << 24);
						break;
					case 1:
						first = program_read_word_32le(psx, (offs_t)(ea - 1));
						value = (b & 0x0000ffff) | (first << 16);
						break;
					case 2:
						first = program_read_word_32le(psx, (offs_t)(ea - 2));
						second = program_read_byte_32le(psx, (offs_t)ea);
						value = (b & 0x000000ff) | (first << 8) | (second << 24);
						break;
					default:
						value = program_read_dword_32le(psx, (offs_t)(ea - 3));
						break;
				}
			}
			else
			{
				switch (ea & 3)
				{
					case 0:
						first = program_read_byte_32le(psx, (offs_t)(ea + 3));
						value = (b & 0x00ffffff) | (first << 24);
						break;
					case 1:
						first = program_read_word_32le(psx, (offs_t)(ea + 1));
						value = (b & 0x0000ffff) | (first << 16);
						break;
					case 2:
						first = program_read_byte_32le(psx, (offs_t)(ea - 1));
						second = program_read_word_32le(psx, (offs_t)ea);
						value = (b & 0x000000ff) | (first << 8) | (second << 16);
						break;
					default:
						value = program_read_dword_32le(psx, (offs_t)(ea - 3));
						break;
				}
			}
			load(cpu, rt, value);
			break;
		}
		case 38: /* LWR: its bottom bytes */
		{
			UINT32 first, second;
			if (!reverse)
			{
				switch (ea & 3)
				{
					case 0:
						value = program_read_dword_32le(psx, (offs_t)ea);
						break;
					case 1:
						first = program_read_byte_32le(psx, (offs_t)ea);
						second = program_read_word_32le(psx, (offs_t)(ea + 1));
						value = (b & 0xff000000) | first | (second << 8);
						break;
					case 2:
						first = program_read_word_32le(psx, (offs_t)ea);
						value = (b & 0xffff0000) | first;
						break;
					default:
						first = program_read_byte_32le(psx, (offs_t)ea);
						value = (b & 0xffffff00) | first;
						break;
				}
			}
			else
			{
				switch (ea & 3)
				{
					case 0:
						value = program_read_dword_32le(psx, (offs_t)ea);
						break;
					case 1:
						first = program_read_word_32le(psx, (offs_t)(ea - 1));
						second = program_read_byte_32le(psx, (offs_t)(ea + 1));
						value = (b & 0xff000000) | first | (second << 16);
						break;
					case 2:
						first = program_read_word_32le(psx, (offs_t)(ea - 2));
						value = (b & 0xffff0000) | first;
						break;
					default:
						first = program_read_byte_32le(psx, (offs_t)(ea - 3));
						value = (b & 0xffffff00) | first;
						break;
				}
			}
			load(cpu, rt, value);
			break;
		}
		case 40: /* SB */
			program_write_byte_32le(psx, (offs_t)(reverse ? ea ^ 3 : ea), (uint8)b);
			advance(cpu);
			break;
		case 41: /* SH */
			program_write_word_32le(psx, (offs_t)(reverse ? ea ^ 2 : ea), (uint16)b);
			advance(cpu);
			break;
		case 43: /* SW */
			program_write_dword_32le(psx, (offs_t)ea, b);
			advance(cpu);
			break;
		case 42: /* SWL */
			if (!reverse)
			{
				switch (ea & 3)
				{
					case 0:
						program_write_byte_32le(psx, (offs_t)ea, (uint8)(b >> 24));
						break;
					case 1:
						program_write_word_32le(psx, (offs_t)(ea - 1), (uint16)(b >> 16));
						break;
					case 2:
						program_write_word_32le(psx, (offs_t)(ea - 2), (uint16)(b >> 8));
						program_write_byte_32le(psx, (offs_t)ea, (uint8)(b >> 24));
						break;
					default:
						program_write_dword_32le(psx, (offs_t)(ea - 3), b);
						break;
				}
			}
			else
			{
				switch (ea & 3)
				{
					case 0:
						program_write_byte_32le(psx, (offs_t)(ea + 3), (uint8)(b >> 24));
						break;
					case 1:
						program_write_word_32le(psx, (offs_t)(ea + 1), (uint16)(b >> 16));
						break;
					case 2:
						program_write_byte_32le(psx, (offs_t)(ea - 1), (uint8)(b >> 8));
						program_write_word_32le(psx, (offs_t)ea, (uint16)(b >> 16));
						break;
					default:
						program_write_dword_32le(psx, (offs_t)(ea - 3), b);
						break;
				}
			}
			advance(cpu);
			break;
		case 46: /* SWR */
			if (!reverse)
			{
				switch (ea & 3)
				{
					case 0:
						program_write_dword_32le(psx, (offs_t)ea, b);
						break;
					case 1:
						program_write_byte_32le(psx, (offs_t)ea, (uint8)b);
						program_write_word_32le(psx, (offs_t)(ea + 1), (uint16)(b >> 8));
						break;
					case 2:
						program_write_word_32le(psx, (offs_t)ea, (uint16)b);
						break;
					default:
						program_write_byte_32le(psx, (offs_t)ea, (uint8)b);
						break;
				}
			}
			else
			{
				switch (ea & 3)
				{
					case 0:
						program_write_dword_32le(psx, (offs_t)ea, b);
						break;
					case 1:
						program_write_word_32le(psx, (offs_t)(ea - 1), (uint16)b);
						program_write_byte_32le(psx, (offs_t)(ea + 1), (uint8)(b >> 16));
						break;
					case 2:
						program_write_word_32le(psx, (offs_t)(ea - 2), (uint16)b);
						break;
					default:
						program_write_byte_32le(psx, (offs_t)(ea - 3), (uint8)b);
						break;
				}
			}
			advance(cpu);
			break;
		case 50: /* LWC2: into a register of coprocessor 2, at once */
			value = program_read_dword_32le(psx, (offs_t)ea);
			gte_data_write(cpu, rt, value);
			advance(cpu);
			break;
		default: /* 58, SWC2 */
			program_write_dword_32le(psx, (offs_t)ea, gte_data_read(cpu, rt));
			advance(cpu);
			break;
	}
}

/* One instruction. */
static INLINE void execute(MIPS_CPU_CONTEXT *cpu, UINT32 op)
{
	const UINT32 rs = (op >> 21) & 31;
	const UINT32 rt = (op >> 16) & 31;
	const UINT32 a = cpu->r[rs];
	const UINT32 b = cpu->r[rt];
	const UINT32 imm = op & 0xffff;
	const UINT32 simm = (UINT32)(INT32)(INT16)op;

	switch (op >> 26)
	{
		case 0:
			special(cpu, op);
			break;
		case 1: /* REGIMM: the branches on a register's sign, two of which link whether they are taken or not */
		{
			const UINT32 target = cpu->pc + 4 + (simm << 2);
			const UINT32 link = cpu->pc + 8;
			if (rt == 0 || rt == 16) /* BLTZ, BLTZAL */
			{
				if ((INT32)a < 0) branch(cpu, target); else advance(cpu);
				if (rt == 16) cpu->r[31] = link;
			}
			else if (rt == 1 || rt == 17) /* BGEZ, BGEZAL */
			{
				if ((INT32)a >= 0) branch(cpu, target); else advance(cpu);
				if (rt == 17) cpu->r[31] = link;
			}
			break;
		}
		case 2: /* J */
			branch(cpu, ((cpu->pc + 4) & 0xf0000000u) + ((op & 0x03ffffff) << 2));
			break;
		case 3: /* JAL */
		{
			const UINT32 link = cpu->pc + 8;
			branch(cpu, ((cpu->pc + 4) & 0xf0000000u) + ((op & 0x03ffffff) << 2));
			cpu->r[31] = link;
			break;
		}
		case 4: /* BEQ */
			if (a == b) branch(cpu, cpu->pc + 4 + (simm << 2)); else advance(cpu);
			break;
		case 5: /* BNE */
			if (a != b) branch(cpu, cpu->pc + 4 + (simm << 2)); else advance(cpu);
			break;
		case 6: /* BLEZ */
			if (rt != 0)
				exception(cpu, EXC_RI);
			else if ((INT32)a <= 0)
				branch(cpu, cpu->pc + 4 + (simm << 2));
			else
				advance(cpu);
			break;
		case 7: /* BGTZ */
			if (rt != 0)
				exception(cpu, EXC_RI);
			else if ((INT32)a > 0)
				branch(cpu, cpu->pc + 4 + (simm << 2));
			else
				advance(cpu);
			break;
		case 8: /* ADDI */
		{
			const UINT32 sum = a + simm;
			if ((~(a ^ simm) & (a ^ sum)) & 0x80000000u)
				exception(cpu, EXC_OVF);
			else
				put(cpu, rt, sum);
			break;
		}
		case 9: /* ADDIU */
#if AOPSF_PS2
			/* To register nought, it is how a PlayStation 2 program calls its kernel. */
			if (rt == 0)
			{
				psx_iop_call(cpu->psx, cpu->pc, imm);
				advance(cpu);
				if (!cpu->psx->irq_mutex && cpu->psx->rescheduleNeeded) ps2_reschedule(cpu->psx);
				break;
			}
#endif
			put(cpu, rt, a + simm);
			break;
		case 10: /* SLTI */
			put(cpu, rt, (INT32)a < (INT32)simm ? 1 : 0);
			break;
		case 11: /* SLTIU */
			put(cpu, rt, a < simm ? 1 : 0);
			break;
		case 12: /* ANDI */
			put(cpu, rt, a & imm);
			break;
		case 13: /* ORI */
			put(cpu, rt, a | imm);
			break;
		case 14: /* XORI */
			put(cpu, rt, a ^ imm);
			break;
		case 15: /* LUI */
			put(cpu, rt, imm << 16);
			break;
		case 16:
			cop0(cpu, op);
			break;
		case 17: /* COP1: the console has none */
			if (!(SR & SR_CU1))
			{
				coprocessor_unusable(cpu, 1);
			}
			else
			{
				cpu_unsupported(cpu, "coprocessor 1 is");
				advance(cpu);
			}
			break;
		case 18:
			cop2(cpu, op);
			break;
		case 49: case 57: /* LWC1, SWC1 */
			cpu_unsupported(cpu, "coprocessor 1 is");
			advance(cpu);
			break;
		case 32: case 33: case 34: case 35: case 36: case 37: case 38:
		case 40: case 41: case 42: case 43: case 46:
		case 50: case 58:
			memory(cpu, op);
			break;
		default:
			/* An opcode nobody defined: nothing, and no moving on. */
			break;
	}
}

void mips_init(MIPS_CPU_CONTEXT *cpu)
{
	/* Nothing: the engine sets everything it needs, and counts on the rest being left. */
	(void)cpu;
}

void mips_reset(MIPS_CPU_CONTEXT *cpu, void *param)
{
	(void)param;
	/* Kernel mode, interrupts off, the boot vectors. The registers keep what they hold. */
	SR = (SR & ~0x00220003u) | SR_BEV;
	status_written(cpu);
	cpu->cp0r[1] = 63;
	cpu->cp0r[15] = 0x00000200;
	cpu->pc = 0xbfc00000u;
	cpu->delayr = 0;
	cpu->delayv = 0;
	cpu->prevpc = 0xffffffffu;
}

int mips_execute(MIPS_CPU_CONTEXT *cpu, int cycles)
{
	cpu->mips_ICount = cycles;
	do
	{
		const UINT32 op = fetch(cpu);
		cpu->op = op;
#ifdef CPU_TRACE
		cpu_trace(cpu->psx, cpu->pc, cpu->r, cpu->hi, cpu->lo, cpu->delayv, cpu->delayr, cpu->cp0r);
#elif defined(PSX_PLAIN_MEMORY)
		/*
		** A game with nothing to do until its next interrupt jumps to where it
		** is, over and over: most of what a song's processor ever runs. Nothing
		** but the processor's own place changes while it does, and no
		** interrupt is taken between two instructions, so the rest of this
		** run is counted off at once - to where it would have got to, the
		** jump or the instruction after it.
		*/
		if ((op >> 26) == 2 && cpu->delayr == 0 && cpu->mips_ICount >= 2 &&
		    ((cpu->pc + 4) & 0xf0000000u) + ((op & 0x03ffffff) << 2) == cpu->pc &&
		    (cpu->pc & (user_bit(cpu) | 3)) == 0)
		{
			const uint32 *self = psx_plain_memory(cpu->psx, cpu->pc);
			const uint32 *after = psx_plain_memory(cpu->psx, cpu->pc + 4);
			if (self != NULL && after != NULL && *after == 0)
			{
				if (cpu->mips_ICount & 1)
				{
					/* An odd count ends on the jump: the instruction after it is next. */
					cpu->delayr = IN_SLOT;
					cpu->delayv = cpu->pc;
					cpu->pc += 4;
				}
				else
				{
					cpu->delayv = 0;
					cpu->op = 0;
				}
				cpu->mips_ICount = 0;
				break;
			}
		}
#endif
		execute(cpu, op);
		cpu->mips_ICount--;
	} while (cpu->mips_ICount > 0);
	return cycles - cpu->mips_ICount;
}

void mips_shorten_frame(MIPS_CPU_CONTEXT *cpu)
{
	cpu->mips_ICount = 0;
}

int mips_get_icount(MIPS_CPU_CONTEXT *cpu)
{
	return cpu->mips_ICount;
}

void mips_set_icount(MIPS_CPU_CONTEXT *cpu, int count)
{
	cpu->mips_ICount = count;
}

UINT32 mips_get_cause(MIPS_CPU_CONTEXT *cpu)
{
	return CAUSE;
}

UINT32 mips_get_status(MIPS_CPU_CONTEXT *cpu)
{
	return SR;
}

/* As it is given: an interrupt it lets in waits for the next write that looks. */
void mips_set_status(MIPS_CPU_CONTEXT *cpu, UINT32 status)
{
	SR = status;
}

UINT32 mips_get_ePC(MIPS_CPU_CONTEXT *cpu)
{
	return EPC;
}

void mips_get_info(MIPS_CPU_CONTEXT *cpu, UINT32 state, union cpuinfo *info)
{
	if (state == CPUINFO_INT_PC || state == CPUINFO_INT_REGISTER + MIPS_PC)
	{
		info->i = cpu->pc;
	}
	else if (state >= CPUINFO_INT_REGISTER + MIPS_R0 && state <= CPUINFO_INT_REGISTER + MIPS_R31)
	{
		info->i = cpu->r[state - (CPUINFO_INT_REGISTER + MIPS_R0)];
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_HI)
	{
		info->i = cpu->hi;
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_LO)
	{
		info->i = cpu->lo;
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_DELAYV)
	{
		info->i = cpu->delayv;
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_DELAYR)
	{
		info->i = cpu->delayr;
	}
	else if (state == CPUINFO_INT_INPUT_STATE + MIPS_IRQ0)
	{
		info->i = (CAUSE & 0x400) ? 1 : 0;
	}
}

void mips_set_info(MIPS_CPU_CONTEXT *cpu, UINT32 state, union cpuinfo *info)
{
	const UINT32 value = (UINT32)info->i;

	if (state == CPUINFO_INT_PC || state == CPUINFO_INT_REGISTER + MIPS_PC)
	{
		/* A new place to run from: whatever was owed - a branch, a late load - is dropped. */
		cpu->pc = value;
		cpu->delayr = 0;
		cpu->delayv = 0;
	}
	else if (state >= CPUINFO_INT_REGISTER + MIPS_R0 && state <= CPUINFO_INT_REGISTER + MIPS_R31)
	{
		cpu->r[state - (CPUINFO_INT_REGISTER + MIPS_R0)] = value;
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_HI)
	{
		cpu->hi = value;
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_LO)
	{
		cpu->lo = value;
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_DELAYV)
	{
		cpu->delayv = value;
	}
	else if (state == CPUINFO_INT_REGISTER + MIPS_DELAYR)
	{
		/* A register, or a branch's slot: anything else is no state the processor can be in. */
		if (info->i >= 0 && info->i <= IN_SLOT) cpu->delayr = value;
	}
	else if (state == CPUINFO_INT_INPUT_STATE + MIPS_IRQ0)
	{
		/* The interrupt line, up or down: the one moment besides the program's own writes an interrupt is taken. */
		if (info->i == ASSERT_LINE)
		{
			CAUSE |= 0x400;
			status_written(cpu);
		}
		else if (info->i == CLEAR_LINE)
		{
			CAUSE &= ~0x400u;
			status_written(cpu);
		}
	}
}
