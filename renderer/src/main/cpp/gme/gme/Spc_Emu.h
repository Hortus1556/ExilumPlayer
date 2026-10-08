// Super Nintendo SPC music file emulator

// Game_Music_Emu https://bitbucket.org/mpyne/game-music-emu/
#ifndef SPC_EMU_H
#define SPC_EMU_H

#include "Fir_Resampler.h"
#include "Music_Emu.h"
#include "Snes_Spc.h"
#include "Spc_Filter.h"

class Spc_Emu : public Music_Emu {
public:
	// The Super Nintendo hardware samples at 32kHz. Other sample rates are
	// handled by resampling the 32kHz output; emulation accuracy is not affected.
	enum { native_sample_rate = 32000 };

	// SPC file header
	enum { header_size = 0x100 };
	struct header_t
	{
		char tag [35];
		byte format;
		byte version;
		byte pc [2];
		byte a, x, y, psw, sp;
		byte unused [2];
		char song [32];
		char game [32];
		char dumper [16];
		char comment [32];
		byte date [11];
		byte len_secs [3];
		byte fade_msec [4];
		char author [32]; // sometimes first char should be skipped (see official SPC spec)
		byte mute_mask;
		byte emulator;
		byte unused2 [46];
	};

	// Header for currently loaded file
	header_t const& header() const { return *(header_t const*) file_data; }

	// Prevents channels and global volumes from being phase-negated
	void disable_surround( bool disable = true );

	static gme_type_t static_type() { return gme_spc_type; }

public:
	// deprecated
	using Music_Emu::load;
	blargg_err_t load( header_t const& h, Data_Reader& in ) // use Remaining_Reader
			{ return load_remaining_( &h, sizeof h, in ); }
	byte const* trailer() const; // use track_info()
	long trailer_size() const;

public:
	Spc_Emu();
	~Spc_Emu();
protected:
	blargg_err_t load_mem_( byte const*, long );
	blargg_err_t track_info_( track_info_t*, int track ) const;
	blargg_err_t set_sample_rate_( long );
	blargg_err_t start_track_( int );
	blargg_err_t play_( long, sample_t* );
	blargg_err_t skip_( long );
	void mute_voices_( int );
	// Local addition - see Music_Emu::voice_history.
	int voice_history( int voice, short* out, int count ) override
	{
		/*
		 * How far past the audio the DSP has got, in its own samples.
		 *
		 * Two parts, and the second is the one that matters. play() can
		 * render ahead into its silence buffer, which is the first - but
		 * during ordinary music it does not, so that term is usually zero.
		 *
		 * The resampler is the other. play_ fills its whole input buffer in
		 * one go and then drains it over several calls, so the DSP does not
		 * advance a little each callback: it leaps by a bufferful and then
		 * stands still while the audio keeps flowing. Taking the newest
		 * samples in the ring therefore gives a window that jumps forward and
		 * then freezes, which is what a trace skipping frames looks like.
		 * written() is exactly the input it has not consumed yet, so
		 * subtracting it lands on the sample the last output came from.
		 */
		const long rate = sample_rate();
		int lead = rate > 0
				? (int) ((int64_t) emulator_lead() * Snes_Spc::sample_rate / rate)
				: 0;
		if ( rate != native_sample_rate )
			lead += resampler.written() / 2;  // stereo pairs, one per DSP sample
		return apu.voice_history( voice, out, count, lead );
	}
	int voice_history_rate() const override { return Snes_Spc::sample_rate; }
	bool voice_volume( int voice, int* left, int* right ) const override
			{ apu.voice_volume( voice, left, right ); return true; }
	void disable_echo_( bool disable );
	void set_interpolation_( int mode ); // local addition
	void apply_pitch_() override;        // local addition
	void set_tempo_( double );
	void enable_accuracy_( bool );
private:
	byte const* file_data;
	long        file_size;
	Fir_Resampler<24> resampler;
	SPC_Filter filter;
	Snes_Spc apu;

	blargg_err_t play_and_filter( long count, sample_t out [] );
};

inline void Spc_Emu::disable_surround( bool b ) { apu.disable_surround( b ); }

#endif
