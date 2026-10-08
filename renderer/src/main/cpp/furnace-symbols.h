#pragma once
//
// Force-included into every Furnace source - see furnace_static in
// CMakeLists.txt.
//
// Furnace carries its own copy of Nuked OPL3, and so does AdPlug, with a local
// addition of ours (OPL3_GenerateResampledChannels). The two copies differ but
// share every name - the functions, and the structs they work on - and both
// end up in one library, where the functions collide at link time and the
// structs would quietly be two different types under one name. So Furnace's
// copy is compiled under names of its own. Nothing outside the Furnace sources
// uses them.
//
#define OPL3_Generate Furnace_OPL3_Generate
#define OPL3_GenerateResampled Furnace_OPL3_GenerateResampled
#define OPL3_Reset Furnace_OPL3_Reset
#define OPL3_Resample Furnace_OPL3_Resample
#define OPL3_WriteReg Furnace_OPL3_WriteReg
#define OPL3_WriteRegBuffered Furnace_OPL3_WriteRegBuffered
#define OPL3_GenerateStream Furnace_OPL3_GenerateStream
#define OPL3_Generate4Ch Furnace_OPL3_Generate4Ch
#define OPL3_Generate4ChResampled Furnace_OPL3_Generate4ChResampled
#define OPL3_Generate4ChStream Furnace_OPL3_Generate4ChStream

#define _opl3_slot furnace_opl3_slot_struct
#define _opl3_channel furnace_opl3_channel_struct
#define _opl3_chip furnace_opl3_chip_struct
#define _opl3_writebuf furnace_opl3_writebuf_struct
#define opl3_slot furnace_opl3_slot
#define opl3_channel furnace_opl3_channel
#define opl3_chip furnace_opl3_chip
#define opl3_writebuf furnace_opl3_writebuf
