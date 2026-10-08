//
// Recorded music, through FFmpeg. See decoder-ffmpeg.h.
//
#include "decoder-ffmpeg.h"
#include "psf-song.h"
#include "visual-publisher.h"
#include "visual-state.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <system_error>
#include <thread>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/log.h>
#include <libavutil/mathematics.h>
#include <libswresample/swresample.h>
}

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum FFmpeg";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

/** What FFmpeg reads the file through, a piece at a time. */
constexpr int kIoBuffer = 64 * 1024;
/** Frames kept decoded ahead of the callback: a couple of seconds at the usual rates. */
constexpr int64_t kRingFrames = 131072;
/** Output frames kept of each side, for its trace and its level. */
constexpr uint32_t kTapFrames = 16384;
/** How often a side is looked at for its trace and its level. */
constexpr int kLookHz = 16000;
constexpr int kLevelWindowMs = 30;
/** How long the thread sleeps when there is nothing for it to do yet. */
constexpr auto kIdle = std::chrono::milliseconds(5);

const char* const kStereoNames[2] = {"Left", "Right"};
const char* const kMonoName = "Mono";

std::atomic<int> g_stereoPercent{100};

/** FFmpeg says nothing: its log goes to a stderr nobody reads, a line for every odd frame of every odd file. */
void quietOnce() {
  static std::once_flag once;
  std::call_once(once, [] { av_log_set_level(AV_LOG_QUIET); });
}

/** The file's bytes: a descriptor of this decoder's own, read from wherever it is asked, or the whole of it in memory. */
struct Bytes {
  int fd = -1;
  std::vector<uint8_t> memory;
  int64_t size = 0;
  int64_t at = 0;

  ~Bytes() {
    if (fd >= 0) close(fd);
  }

  /** A copy of [from], if it is a file that can be read from anywhere. */
  static std::unique_ptr<Bytes> ofFd(int from) {
    struct stat info {};
    if (fstat(from, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0) return nullptr;
    uint8_t probe;
    if (pread(from, &probe, 1, 0) != 1) return nullptr;
    const int own = dup(from);
    if (own < 0) return nullptr;
    auto bytes = std::make_unique<Bytes>();
    bytes->fd = own;
    bytes->size = info.st_size;
    return bytes;
  }

  static std::unique_ptr<Bytes> ofMemory(const uint8_t* data, size_t size) {
    auto bytes = std::make_unique<Bytes>();
    bytes->memory.assign(data, data + size);
    bytes->size = static_cast<int64_t>(size);
    return bytes;
  }

  static int read(void* opaque, uint8_t* buffer, int want) {
    Bytes& b = *static_cast<Bytes*>(opaque);
    if (want <= 0) return 0;
    int64_t got = 0;
    if (b.fd >= 0) {
      do {
        got = pread(b.fd, buffer, static_cast<size_t>(want), b.at);
      } while (got < 0 && errno == EINTR);
      if (got < 0) return AVERROR(errno);
    } else {
      got = std::clamp<int64_t>(b.size - b.at, 0, want);
      if (got > 0) std::memcpy(buffer, b.memory.data() + b.at, static_cast<size_t>(got));
    }
    if (got <= 0) return AVERROR_EOF;
    b.at += got;
    return static_cast<int>(got);
  }

  static int64_t seek(void* opaque, int64_t offset, int whence) {
    Bytes& b = *static_cast<Bytes*>(opaque);
    if (whence & AVSEEK_SIZE) return b.size;
    int64_t to = -1;
    switch (whence & ~AVSEEK_FORCE) {
      case SEEK_SET: to = offset; break;
      case SEEK_CUR: to = b.at + offset; break;
      case SEEK_END: to = b.size + offset; break;
      default: break;
    }
    if (to < 0) return AVERROR(EINVAL);
    b.at = to;
    return to;
  }
};

/** A file opened: its container read, the first stream of sound this can decode found, and its decoder if asked for. */
struct Input {
  std::unique_ptr<Bytes> bytes;
  AVIOContext* io = nullptr;
  AVFormatContext* format = nullptr;
  AVCodecContext* codec = nullptr;
  int stream = -1;

  ~Input() { close(); }

  bool open(std::unique_ptr<Bytes> from, const char* name, bool toPlay) {
    close();
    quietOnce();
    if (!from) return false;
    bytes = std::move(from);
    auto* buffer = static_cast<uint8_t*>(av_malloc(kIoBuffer));
    if (!buffer) return false;
    io = avio_alloc_context(buffer, kIoBuffer, 0, bytes.get(), Bytes::read, nullptr, Bytes::seek);
    if (!io) {
      av_free(buffer);
      return false;
    }
    format = avformat_alloc_context();
    if (!format) return fail();
    format->pb = io;
    format->flags |= AVFMT_FLAG_CUSTOM_IO;
    // On failure it frees the context it was given.
    if (avformat_open_input(&format, name, nullptr, nullptr) < 0) {
      format = nullptr;
      return fail();
    }
    const AVCodec* decoder = nullptr;
    for (unsigned i = 0; i < format->nb_streams; i++) {
      const AVStream* st = format->streams[i];
      const AVCodecParameters* par = st->codecpar;
      // A film is not music, whatever is on its sound track; a cover is not a film.
      if (par->codec_type == AVMEDIA_TYPE_VIDEO && !(st->disposition & AV_DISPOSITION_ATTACHED_PIC)) return fail();
      if (par->codec_type != AVMEDIA_TYPE_AUDIO) continue;
      if (stream >= 0) continue;
      decoder = avcodec_find_decoder(par->codec_id);
      if (decoder) stream = static_cast<int>(i);
    }
    if (stream < 0) return fail();
    if (!toPlay) return true;

    // Half a second of looking is enough to know a rate and a length; the
    // five it takes unasked is five megabytes read before a note is played.
    format->max_analyze_duration = AV_TIME_BASE / 2;
    avformat_find_stream_info(format, nullptr);
    AVStream* st = format->streams[stream];
    codec = avcodec_alloc_context3(decoder);
    if (!codec) return fail();
    if (avcodec_parameters_to_context(codec, st->codecpar) < 0) return fail();
    codec->pkt_timebase = st->time_base;
    if (avcodec_open2(codec, decoder, nullptr) < 0) return fail();
    // Every other stream - a cover, a second language - is not read at all.
    for (unsigned i = 0; i < format->nb_streams; i++) {
      if (static_cast<int>(i) != stream) format->streams[i]->discard = AVDISCARD_ALL;
    }
    return true;
  }

  void close() {
    if (codec) avcodec_free_context(&codec);
    if (format) avformat_close_input(&format);
    if (io) {
      av_freep(&io->buffer);
      avio_context_free(&io);
    }
    bytes.reset();
    stream = -1;
  }

 private:
  bool fail() {
    close();
    return false;
  }
};

/** A tag, of the file's or of its stream's - Ogg keeps them on the stream. */
std::string tagOf(const Input& input, const char* key, int flags = 0) {
  const AVDictionaryEntry* entry = av_dict_get(input.format->metadata, key, nullptr, flags);
  if (!entry && input.stream >= 0) {
    entry = av_dict_get(input.format->streams[input.stream]->metadata, key, nullptr, flags);
  }
  return entry && entry->value ? entry->value : "";
}

/** The words: timed ones first where a file has both, then whatever key starts with "lyrics" (ID3's ends in a language). */
std::string lyricsOf(const Input& input) {
  for (const char* key : {"syncedlyrics", "lyrics"}) {
    const std::string text = tagOf(input, key, AV_DICT_IGNORE_SUFFIX);
    if (!text.empty()) return text;
  }
  return tagOf(input, "unsyncedlyrics");
}

bool nameIs(const AVInputFormat* format, const char* name) {
  return format && format->name && std::strstr(format->name, name) != nullptr;
}

/** What the sound is kept as, for the lists and the player's heading. */
std::string typeOf(const Input& input) {
  const AVCodecParameters* par = input.format->streams[input.stream]->codecpar;
  switch (par->codec_id) {
    case AV_CODEC_ID_MP3: return "MP3";
    case AV_CODEC_ID_MP2: return "MP2";
    case AV_CODEC_ID_MP1: return "MP1";
    case AV_CODEC_ID_FLAC: return "FLAC";
    case AV_CODEC_ID_ALAC: return "ALAC";
    case AV_CODEC_ID_AAC:
    case AV_CODEC_ID_AAC_LATM:
      if (par->profile == AV_PROFILE_AAC_HE) return "HE-AAC";
      if (par->profile == AV_PROFILE_AAC_HE_V2) return "HE-AAC v2";
      if (par->profile == AV_PROFILE_AAC_USAC) return "xHE-AAC";
      return "AAC";
    case AV_CODEC_ID_VORBIS: return "Vorbis";
    case AV_CODEC_ID_OPUS: return "Opus";
    case AV_CODEC_ID_MUSEPACK7:
    case AV_CODEC_ID_MUSEPACK8: return "Musepack";
    case AV_CODEC_ID_WAVPACK: return "WavPack";
    default: break;
  }
  if (nameIs(input.format->iformat, "aiff")) return "AIFF";
  if (nameIs(input.format->iformat, "caf")) return "CAF";
  return "WAV";
}

std::string describe(const Input& input) {
  std::string text;
  for (const char* key : {"artist", "album", "date", "genre", "comment"}) {
    const std::string value = tagOf(input, key);
    if (value.empty()) continue;
    if (!text.empty()) text += "\n";
    text += value;
  }
  const AVCodecParameters* par = input.format->streams[input.stream]->codecpar;
  char how[160];
  const int channels = par->ch_layout.nb_channels;
  const char* layout = channels == 1 ? "mono" : channels == 2 ? "stereo" : nullptr;
  char many[24];
  if (!layout) {
    std::snprintf(many, sizeof many, "%d channels", channels);
    layout = many;
  }
  const int64_t rate = par->bit_rate > 0 ? par->bit_rate : input.format->bit_rate;
  if (rate > 0) {
    std::snprintf(how, sizeof how, "%s, %d Hz, %s, %lld kbps", typeOf(input).c_str(), par->sample_rate, layout,
                  static_cast<long long>((rate + 500) / 1000));
  } else {
    std::snprintf(how, sizeof how, "%s, %d Hz, %s", typeOf(input).c_str(), par->sample_rate, layout);
  }
  if (!text.empty()) text += "\n";
  text += how;
  return text;
}

/** No place: a frame that does not say where in the song it is. */
constexpr int64_t kNowhere = INT64_MIN;

struct FfState {
  /** The render and whoever changes what it reads. Never held across the storage. */
  std::mutex mutex;

  // --- The thread that reads and decodes, and what is asked of it.
  std::thread worker;
  std::mutex ask;
  std::condition_variable asked;
  std::condition_variable answered;
  bool stop = false;        // under ask
  int64_t wantMs = -1;      // under ask: a place asked for, or none
  uint64_t asks = 0;        // under ask
  uint64_t answers = 0;     // under ask
  /** The same two, for the thread to look at without the lock while it waits for room. */
  std::atomic<bool> stopping{false};
  std::atomic<bool> seeking{false};

  // The thread's own once it runs.
  Input input;
  SwrContext* swr = nullptr;
  int swrRate = 0;
  int swrInRate = 0;
  int swrInFormat = -1;
  AVChannelLayout swrInLayout{};
  AVPacket* packet = nullptr;
  AVFrame* frame = nullptr;
  std::vector<float> converted;
  /** After a seek: the place asked for, until the first frame at or past it has been cut to it. */
  int64_t cutToMs = -1;
  /**
   * Where the frames decoded since a seek have got to, in the file's own
   * samples, or kNowhere before the first of them: what the next frame is
   * taken to start at when it does not say for itself - see give.
   */
  int64_t cutRan = kNowhere;

  // --- Between the two: decoded sound, both sides together, and where in it things happen.
  std::vector<float> ring = std::vector<float>(static_cast<size_t>(kRingFrames) * 2);
  std::atomic<int64_t> pushed{0};
  std::atomic<int64_t> popped{0};
  /** Everything pushed before this is from before a seek; the first frame after it is frame [landsAt] of the song. */
  std::atomic<int64_t> staleBefore{0};
  std::atomic<int64_t> landsAt{0};
  /** The count pushed at which the file ended, or -1; and at which it went round to its top again. */
  std::atomic<int64_t> endsAt{-1};
  std::atomic<int64_t> wrapsAt{-1};
  std::atomic<int> outRate{48000};
  std::atomic<bool> loop{false};
  /** Asked by the render: the song reached the length the listener gave it with repeat on, and goes back to its top. */
  std::atomic<bool> restart{false};

  // --- The render's.
  bool loaded = false;
  bool ended = false;
  int sampleRate = 48000;
  int shown = 2;
  std::string type;
  std::string title;
  std::string comment;
  std::string lyrics;
  psf::SongLength length;
  /** Output frames of the song handed out: where it is. */
  int64_t rendered = 0;
  /** Where a seek asked for is going, for the position to say so until the sound is there. */
  std::atomic<int64_t> headingMs{-1};
  std::atomic<double> positionMs{0.0};

  ShiftStage stage;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;
  std::array<std::vector<float>, 2> out{std::vector<float>(1024), std::vector<float>(1024)};
  std::array<int, 2> muted{};
  std::vector<int16_t> tap = std::vector<int16_t>(static_cast<size_t>(2) * kTapFrames);
  VisualPublisher visuals;

  static FfState& instance() {
    static FfState s;
    return s;
  }
};

// ---------------------------------------------------------------------------
// The thread: reads, decodes, converts, keeps the ring full.
// ---------------------------------------------------------------------------

bool interrupted(const FfState& state) {
  return state.stopping.load(std::memory_order_acquire) || state.seeking.load(std::memory_order_acquire);
}

/** [frames] frames of [samples], both sides together, into the ring; waits for room. False when called away meanwhile. */
bool push(FfState& state, const float* samples, int frames) {
  int done = 0;
  while (done < frames) {
    if (interrupted(state)) return false;
    const int64_t at = state.pushed.load(std::memory_order_relaxed);
    const int64_t room = kRingFrames - (at - state.popped.load(std::memory_order_acquire));
    if (room <= 0) {
      std::this_thread::sleep_for(kIdle);
      continue;
    }
    const int64_t slot = at % kRingFrames;
    const int n = static_cast<int>(std::min<int64_t>({frames - done, room, kRingFrames - slot}));
    std::memcpy(state.ring.data() + slot * 2, samples + static_cast<size_t>(done) * 2, static_cast<size_t>(n) * 2 * sizeof(float));
    state.pushed.store(at + n, std::memory_order_release);
    done += n;
  }
  return true;
}

/** The converter set up for what [frame] is, to two channels at the output's rate. */
bool convertFor(FfState& state, const AVFrame* frame) {
  const int rate = state.outRate.load(std::memory_order_relaxed);
  if (state.swr && state.swrRate == rate && state.swrInRate == frame->sample_rate &&
      state.swrInFormat == frame->format && av_channel_layout_compare(&state.swrInLayout, &frame->ch_layout) == 0) {
    return true;
  }
  swr_free(&state.swr);
  AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
  AVChannelLayout in{};
  if (frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || frame->ch_layout.nb_channels <= 0) {
    av_channel_layout_default(&in, std::max(1, frame->ch_layout.nb_channels));
  } else {
    av_channel_layout_copy(&in, &frame->ch_layout);
  }
  const int made = swr_alloc_set_opts2(&state.swr, &stereo, AV_SAMPLE_FMT_FLT, rate, &in,
                                       static_cast<AVSampleFormat>(frame->format), frame->sample_rate, 0, nullptr);
  av_channel_layout_uninit(&in);
  if (made < 0 || swr_init(state.swr) < 0) {
    swr_free(&state.swr);
    return false;
  }
  state.swrRate = rate;
  state.swrInRate = frame->sample_rate;
  state.swrInFormat = frame->format;
  av_channel_layout_uninit(&state.swrInLayout);
  av_channel_layout_copy(&state.swrInLayout, &frame->ch_layout);
  return true;
}

/** One decoded frame, converted and pushed - cut to the place a seek asked for, where it is the one that straddles it. */
bool give(FfState& state, AVFrame* frame) {
  int skip = 0;
  if (state.cutToMs >= 0 && frame->sample_rate > 0) {
    const AVStream* st = state.input.format->streams[state.input.stream];
    int64_t pts = frame->best_effort_timestamp;
    // Before the song's own start is a place too - an Opus stream's first
    // frames are - so "not said" is a value of its own.
    int64_t first = kNowhere;
    if (pts != AV_NOPTS_VALUE) {
      if (st->start_time != AV_NOPTS_VALUE) pts -= st->start_time;
      first = av_rescale_q(pts, st->time_base, AVRational{1, frame->sample_rate});
    }
    // A packet that holds many frames gives its time to the first of them
    // and none, or the same again, to the rest - a Musepack SV8 packet is
    // sixty-four frames, a second and two thirds. Counted on from the first,
    // they are where they are; taken at their word, the cut stopped at the
    // second frame of the packet the seek landed in, and the song played on
    // from up to that much before the place the clock said.
    if (first < state.cutRan) first = state.cutRan;
    if (first == kNowhere) {
      // Nothing to go by at all: taken to be where it was asked for.
      state.cutToMs = -1;
    } else {
      state.cutRan = first + frame->nb_samples;
      const int64_t wanted = state.cutToMs * frame->sample_rate / 1000;
      if (first + frame->nb_samples <= wanted) return true;
      skip = static_cast<int>(std::clamp<int64_t>(wanted - first, 0, frame->nb_samples));
      state.cutToMs = -1;
    }
  }
  if (!convertFor(state, frame)) return true;
  const int samples = frame->nb_samples - skip;
  if (samples <= 0) return true;

  const AVSampleFormat format = static_cast<AVSampleFormat>(frame->format);
  const int bytes = av_get_bytes_per_sample(format);
  const int channels = frame->ch_layout.nb_channels;
  const bool planar = av_sample_fmt_is_planar(format) != 0;
  const uint8_t* in[AV_NUM_DATA_POINTERS] = {};
  const uint8_t** planes = const_cast<const uint8_t**>(frame->extended_data);
  std::vector<const uint8_t*> wide;
  if (skip > 0) {
    const int count = planar ? channels : 1;
    const size_t step = static_cast<size_t>(skip) * bytes * (planar ? 1 : channels);
    if (count <= AV_NUM_DATA_POINTERS) {
      for (int c = 0; c < count; c++) in[c] = frame->extended_data[c] + step;
      planes = in;
    } else {
      wide.resize(static_cast<size_t>(count));
      for (int c = 0; c < count; c++) wide[static_cast<size_t>(c)] = frame->extended_data[c] + step;
      planes = wide.data();
    }
  }

  const int room = swr_get_out_samples(state.swr, samples);
  if (room <= 0) return true;
  if (state.converted.size() < static_cast<size_t>(room) * 2) state.converted.resize(static_cast<size_t>(room) * 2);
  uint8_t* outPlane = reinterpret_cast<uint8_t*>(state.converted.data());
  const int got = swr_convert(state.swr, &outPlane, room, planes, samples);
  if (got <= 0) return true;
  return push(state, state.converted.data(), got);
}

/** What the converter still holds, at the end of the file. */
void drainConverter(FfState& state) {
  if (!state.swr) return;
  for (;;) {
    if (state.converted.size() < 4096 * 2) state.converted.resize(4096 * 2);
    uint8_t* outPlane = reinterpret_cast<uint8_t*>(state.converted.data());
    const int got = swr_convert(state.swr, &outPlane, 4096, nullptr, 0);
    if (got <= 0 || !push(state, state.converted.data(), got)) return;
  }
}

/** Every frame the decoder has ready. False when called away. */
bool drainDecoder(FfState& state) {
  for (;;) {
    const int result = avcodec_receive_frame(state.input.codec, state.frame);
    if (result < 0) return true;
    const bool on = give(state, state.frame);
    av_frame_unref(state.frame);
    if (!on) return false;
  }
}

/** The file put at [ms], its decoder and converter emptied; the next sound pushed is from there. */
void seekTo(FfState& state, int64_t ms) {
  AVFormatContext* format = state.input.format;
  int64_t target = ms * (AV_TIME_BASE / 1000);
  if (format->start_time != AV_NOPTS_VALUE) target += format->start_time;
  if (av_seek_frame(format, -1, target, AVSEEK_FLAG_BACKWARD) < 0) {
    // A file with no index and no times - a bare stream of frames: its top can still be found.
    if (ms == 0) av_seek_frame(format, -1, 0, AVSEEK_FLAG_BYTE);
  }
  avcodec_flush_buffers(state.input.codec);
  // Whatever it was half way through is from the other place.
  swr_free(&state.swr);
  state.cutToMs = ms > 0 ? ms : -1;
  state.cutRan = kNowhere;
}

void work(FfState& state) {
  bool atEnd = false;
  for (;;) {
    int64_t want = -1;
    uint64_t mine = 0;
    {
      std::unique_lock<std::mutex> lock(state.ask);
      if (state.stop) return;
      if (state.wantMs < 0 && atEnd && !state.loop.load(std::memory_order_relaxed) &&
          !state.restart.load(std::memory_order_relaxed)) {
        // Played out: nothing to do until something is asked, or repeat is switched on.
        state.asked.wait_for(lock, std::chrono::milliseconds(20));
        if (state.stop) return;
      }
      if (state.wantMs >= 0) {
        want = state.wantMs;
        state.wantMs = -1;
        mine = state.asks;
      }
    }

    if (want >= 0) {
      seekTo(state, want);
      atEnd = false;
      const int rate = state.outRate.load(std::memory_order_relaxed);
      state.endsAt.store(-1, std::memory_order_release);
      state.wrapsAt.store(-1, std::memory_order_release);
      state.landsAt.store(want * rate / 1000, std::memory_order_release);
      state.staleBefore.store(state.pushed.load(std::memory_order_relaxed), std::memory_order_release);
      // After the mark, as below: the render asks again if it finds the
      // song past its end with nothing asked.
      state.restart.store(false, std::memory_order_release);
      {
        std::lock_guard<std::mutex> lock(state.ask);
        state.answers = mine;
        // Not if another was asked for meanwhile: that one is still to do.
        if (state.wantMs < 0) state.seeking.store(false, std::memory_order_release);
      }
      state.answered.notify_all();
      continue;
    }

    const bool asked = state.restart.load(std::memory_order_acquire);
    // Played out, and nothing wants it round again: waited above, and again.
    if (atEnd && !asked && !state.loop.load(std::memory_order_relaxed)) continue;
    if (atEnd || asked) {
      // Round again from the top: for repeat, once what was pushed before
      // has been told apart from what comes now; or because the render asked.
      if (!asked && state.wrapsAt.load(std::memory_order_acquire) >= 0) {
        std::this_thread::sleep_for(kIdle);
        continue;
      }
      seekTo(state, 0);
      atEnd = false;
      const int64_t at = state.pushed.load(std::memory_order_relaxed);
      if (asked) {
        state.landsAt.store(0, std::memory_order_release);
        state.endsAt.store(-1, std::memory_order_release);
        state.wrapsAt.store(-1, std::memory_order_release);
        state.staleBefore.store(at, std::memory_order_release);
        // Only now: taken back before the mark was there, the render found
        // the song still past its end and asked again, for ever.
        state.restart.store(false, std::memory_order_release);
      } else {
        // The mark first: an end still standing with no mark is the end.
        state.wrapsAt.store(at, std::memory_order_release);
        state.endsAt.store(-1, std::memory_order_release);
      }
      continue;
    }

    const int read = av_read_frame(state.input.format, state.packet);
    if (read < 0) {
      // The end, or a file that stops making sense: either way what is decoded is played out.
      avcodec_send_packet(state.input.codec, nullptr);
      if (!drainDecoder(state)) continue;
      drainConverter(state);
      if (interrupted(state)) continue;
      atEnd = true;
      if (!state.loop.load(std::memory_order_relaxed)) {
        state.endsAt.store(state.pushed.load(std::memory_order_relaxed), std::memory_order_release);
      }
      continue;
    }
    if (state.packet->stream_index == state.input.stream) {
      if (avcodec_send_packet(state.input.codec, state.packet) >= 0) drainDecoder(state);
    }
    av_packet_unref(state.packet);
  }
}

/** Asks the thread for [ms] and waits until it has put the file there. Not for the audio thread. */
void askFor(FfState& state, int64_t ms) {
  std::unique_lock<std::mutex> lock(state.ask);
  if (!state.worker.joinable() || state.stop) return;
  state.wantMs = ms;
  const uint64_t mine = ++state.asks;
  state.seeking.store(true, std::memory_order_release);
  state.headingMs.store(ms, std::memory_order_release);
  state.asked.notify_all();
  // Never for ever: a storage that has stopped answering is not to hold the seek thread with it.
  state.answered.wait_for(lock, std::chrono::seconds(3), [&] { return state.answers >= mine || state.stop; });
}

void stopWorker(FfState& state) {
  {
    std::lock_guard<std::mutex> lock(state.ask);
    state.stop = true;
    state.stopping.store(true, std::memory_order_release);
  }
  state.asked.notify_all();
  state.answered.notify_all();
  if (state.worker.joinable()) state.worker.join();
  swr_free(&state.swr);
  av_channel_layout_uninit(&state.swrInLayout);
  state.swrRate = 0;
  av_packet_free(&state.packet);
  av_frame_free(&state.frame);
  state.input.close();
}

// ---------------------------------------------------------------------------
// The render: takes from the ring.
// ---------------------------------------------------------------------------

int64_t endFrame(const FfState& state) {
  return state.length.overrideMs > 0 ? psf::msToFrames(state.length.overrideMs, state.sampleRate) : INT64_MAX;
}

/**
 * Up to [frames] frames from where the song has got to; fewer when it ends.
 * Sound not decoded yet - the thread behind, the storage slow - is silence
 * that does not move the song on. Caller holds the mutex.
 */
int produceLocked(FfState& state, float* left, float* right, int frames) {
  const float width = g_stereoPercent.load(std::memory_order_relaxed) / 100.0f;
  const float keepLeft = state.muted[0] ? 0.0f : 1.0f;
  const float keepRight = state.muted[state.shown == 1 ? 0 : 1] ? 0.0f : 1.0f;
  int done = 0;
  const auto silence = [&] {
    std::fill(left + done, left + frames, 0.0f);
    std::fill(right + done, right + frames, 0.0f);
    return frames;
  };
  while (done < frames) {
    // On its way somewhere: nothing of where it was is played meanwhile.
    if (state.seeking.load(std::memory_order_acquire)) return silence();
    int64_t at = state.popped.load(std::memory_order_relaxed);
    const int64_t stale = state.staleBefore.load(std::memory_order_acquire);
    if (at < stale) {
      at = stale;
      state.popped.store(at, std::memory_order_release);
      state.rendered = state.landsAt.load(std::memory_order_acquire);
      state.headingMs.store(-1, std::memory_order_release);
    }
    if (state.restart.load(std::memory_order_acquire)) return silence();
    // The listener's own length for it: the end, or with repeat on the top again.
    if (state.rendered >= endFrame(state)) {
      if (!state.loop.load(std::memory_order_relaxed)) break;
      state.restart.store(true, std::memory_order_release);
      return silence();
    }
    const int64_t wraps = state.wrapsAt.load(std::memory_order_acquire);
    if (wraps >= 0 && at >= wraps) {
      state.rendered = 0;
      state.wrapsAt.store(-1, std::memory_order_release);
    }
    const int64_t have = state.pushed.load(std::memory_order_acquire) - at;
    if (have <= 0) {
      if (state.endsAt.load(std::memory_order_acquire) == at) break;
      return silence();
    }
    int64_t take = std::min<int64_t>({frames - done, have, endFrame(state) - state.rendered});
    const int64_t next = state.wrapsAt.load(std::memory_order_acquire);
    if (next > at) take = std::min(take, next - at);
    for (int64_t i = 0; i < take; i++) {
      const float* frame = state.ring.data() + ((at + i) % kRingFrames) * 2;
      float l = frame[0] * keepLeft;
      float r = frame[1] * keepRight;
      const uint32_t slot = static_cast<uint32_t>(state.rendered + i) & (kTapFrames - 1);
      state.tap[slot] = static_cast<int16_t>(std::clamp(std::lround(frame[0] * 32767.0f), -32768L, 32767L));
      state.tap[kTapFrames + slot] = static_cast<int16_t>(std::clamp(std::lround(frame[1] * 32767.0f), -32768L, 32767L));
      psf::spread(l, r, width);
      left[done + i] = l;
      right[done + i] = r;
    }
    state.popped.store(at + take, std::memory_order_release);
    state.rendered += take;
    done += static_cast<int>(take);
  }
  return done;
}

void publishVisuals(FfState& state, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  const int channels = state.shown;
  frame.scalars[kScalarNumChannels] = channels;
  frame.scalars[kScalarTimeMs] = static_cast<int32_t>(state.rendered * 1000 / std::max(1, state.sampleRate));
  frame.scalars[kScalarTotalTimeMs] = state.length.totalMs();
  frame.scalars[kScalarGlobalVolume] = kGlobalVolumeUnity;

  const uint32_t heard = static_cast<uint32_t>(state.rendered);
  const uint32_t step = static_cast<uint32_t>(std::max(1, state.sampleRate / kLookHz));
  const int levelLooks = kLookHz * kLevelWindowMs / 1000;
  int sounding = 0;
  for (int c = 0; c < channels; c++) {
    int32_t* f = frame.channels.data() + c * kChannelFields;
    const bool muted = state.muted[static_cast<size_t>(c)] != 0;
    const int16_t* ring = state.tap.data() + static_cast<size_t>(c) * kTapFrames;
    const auto look = [&](uint32_t back) { return ring[(heard - 1 - back * step) & (kTapFrames - 1)] / 32768.0f; };

    float loudest = 0.0f;
    for (int i = 0; i < levelLooks; i++) loudest = std::max(loudest, std::fabs(look(static_cast<uint32_t>(i))));
    f[kChPeak] = muted ? 0 : std::clamp(static_cast<int32_t>(loudest * kPeakScale), 0, kPeakScale);
    if (f[kChPeak] > 0) sounding++;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    f[kChVolume] = muted ? 0 : 64;
    f[kChPan] = channels == 1 ? 128 : (c == 0 ? 0 : 255);
    f[kChInstrument] = c;
    f[kChSample] = 0;

    if (muted) continue;
    float span = 1.0f / 64.0f;
    for (int x = 0; x < kScopeColumns; x++) span = std::max(span, std::fabs(look(static_cast<uint32_t>(x))));
    int8_t* out = frame.scopes.data() + c * kScopeColumns;
    for (int x = 0; x < kScopeColumns; x++) {
      const float sample = look(static_cast<uint32_t>(kScopeColumns - 1 - x));
      out[x] = static_cast<int8_t>(std::clamp(static_cast<int>(sample / span * 127.0f), -127, 127));
    }
  }
  frame.scalars[kScalarPlayingChannels] = sounding;
  state.visuals.publish(frameStamp);
}

/** Everything let go of. Caller holds the mutex. */
void releaseLocked(FfState& state) {
  state.loaded = false;
  stopWorker(state);
  state.visuals.reset();
}

bool loadLocked(FfState& state, std::unique_ptr<Bytes> bytes, const char* name, int sampleRate) {
  releaseLocked(state);
  if (!state.input.open(std::move(bytes), name ? name : "", true)) return false;
  state.packet = av_packet_alloc();
  state.frame = av_frame_alloc();
  if (!state.packet || !state.frame) {
    stopWorker(state);
    return false;
  }
  if (sampleRate > 0) state.sampleRate = sampleRate;
  state.outRate.store(state.sampleRate, std::memory_order_relaxed);
  state.stage.configure(state.sampleRate);
  state.stage.setPitch(state.pitchSemitones);
  state.stage.setTempo(state.tempoFactor);

  const Input& input = state.input;
  const AVStream* st = input.format->streams[input.stream];
  state.type = typeOf(input);
  state.title = tagOf(input, "title");
  state.comment = describe(input);
  state.lyrics = lyricsOf(input);
  state.shown = st->codecpar->ch_layout.nb_channels == 1 ? 1 : 2;

  int64_t micros = input.format->duration;
  if (micros == AV_NOPTS_VALUE && st->duration != AV_NOPTS_VALUE) {
    micros = av_rescale_q(st->duration, st->time_base, AVRational{1, AV_TIME_BASE});
  }
  state.length = psf::SongLength();
  state.length.known = micros != AV_NOPTS_VALUE && micros > 0;
  state.length.lengthMs = state.length.known ? static_cast<int>(std::min<int64_t>(micros / 1000, INT32_MAX)) : 0;
  state.length.fadeMs = 0;

  state.muted.fill(0);
  std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
  state.rendered = 0;
  state.ended = false;
  state.cutToMs = -1;
  state.pushed.store(0, std::memory_order_relaxed);
  state.popped.store(0, std::memory_order_relaxed);
  state.staleBefore.store(0, std::memory_order_relaxed);
  state.landsAt.store(0, std::memory_order_relaxed);
  state.endsAt.store(-1, std::memory_order_relaxed);
  state.wrapsAt.store(-1, std::memory_order_relaxed);
  state.restart.store(false, std::memory_order_relaxed);
  state.headingMs.store(-1, std::memory_order_relaxed);
  state.positionMs.store(0.0, std::memory_order_relaxed);
  {
    std::lock_guard<std::mutex> lock(state.ask);
    state.stop = false;
    state.wantMs = -1;
    state.asks = state.answers = 0;
  }
  state.stopping.store(false, std::memory_order_relaxed);
  state.seeking.store(false, std::memory_order_relaxed);
  state.visuals.reset();
  try {
    state.worker = std::thread([&state] { work(state); });
  } catch (const std::system_error&) {
    stopWorker(state);
    return false;
  }
  state.loaded = true;
  LOG_INFO("loaded %s: %d Hz, %d channels, %d ms%s", state.type.c_str(), st->codecpar->sample_rate,
           st->codecpar->ch_layout.nb_channels, state.length.lengthMs, state.lyrics.empty() ? "" : ", with words");
  return true;
}

bool testWith(std::unique_ptr<Bytes> bytes, const char* name, std::string& title, std::string& type) {
  Input input;
  if (!input.open(std::move(bytes), name ? name : "", false)) return false;
  title = tagOf(input, "title");
  type = typeOf(input);
  return true;
}

bool hasExtension(const char* name, std::initializer_list<const char*> extensions) {
  if (!name) return false;
  const char* dot = std::strrchr(name, '.');
  if (!dot) return false;
  std::string extension(dot + 1);
  for (char& c : extension) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  for (const char* candidate : extensions) {
    if (extension == candidate) return true;
  }
  return false;
}

bool starts(const uint8_t* head, size_t size, size_t at, const char* magic) {
  const size_t length = std::strlen(magic);
  return size >= at + length && std::memcmp(head + at, magic, length) == 0;
}

/** Whether a RIFF or RF64 WAVE's sound is kept in a way FFmpeg is asked to decode here; not a console's stream in a WAVE's clothes. */
bool plainWave(const uint8_t* head, size_t size) {
  for (size_t at = 12; at + 8 <= size;) {
    const uint32_t length = head[at + 4] | head[at + 5] << 8 | head[at + 6] << 16 | static_cast<uint32_t>(head[at + 7]) << 24;
    if (std::memcmp(head + at, "fmt ", 4) == 0) {
      if (at + 10 > size) return false;
      const int tag = head[at + 8] | head[at + 9] << 8;
      // PCM, MS ADPCM, float, A-law, mu-law, IMA ADPCM, MPEG, MP3, and "see the rest of the header".
      for (const int known : {0x0001, 0x0002, 0x0003, 0x0006, 0x0007, 0x0011, 0x0050, 0x0055, 0xFFFE}) {
        if (tag == known) return true;
      }
      return false;
    }
    at += 8 + static_cast<size_t>(length) + (length & 1);
  }
  return false;
}

}  // namespace

bool ffmpeg_decoder_claims(const uint8_t* head, size_t size, const char* name) {
  if (!head || size < 12) return false;
  if (starts(head, size, 0, "fLaC") || starts(head, size, 0, "OggS") || starts(head, size, 0, "MPCK") ||
      starts(head, size, 0, "MP+") || starts(head, size, 0, "wvpk") || starts(head, size, 0, "caff")) {
    return true;
  }
  if ((starts(head, size, 0, "RIFF") || starts(head, size, 0, "RF64")) && starts(head, size, 8, "WAVE")) {
    return plainWave(head, size);
  }
  if (starts(head, size, 0, "FORM") && (starts(head, size, 8, "AIFF") || starts(head, size, 8, "AIFC"))) return true;
  // An MPEG-4 file - .m4a, .mp4, .m4b: whether there is sound in it this plays is found on opening it.
  if (starts(head, size, 4, "ftyp")) return true;
  // A tag in front says nothing of what follows it, and bare frames of MP3
  // or AAC have no name of their own: two bits of sync that any file may
  // start with. Those are taken by what the file is called.
  const bool tagged = starts(head, size, 0, "ID3");
  const bool framed = head[0] == 0xFF && (head[1] & 0xE0) == 0xE0;
  if (tagged || framed) {
    return hasExtension(name, {"mp3", "mp2", "mp1", "mpga", "aac", "adts", "flac", "wav", "aiff", "aif", "mpc", "wv"});
  }
  return false;
}

bool ffmpeg_decoder_test_fd(int fd, const char* name, std::string& title, std::string& type) {
  return testWith(Bytes::ofFd(fd), name, title, type);
}

bool ffmpeg_decoder_test_memory(const uint8_t* data, size_t size, const char* name, std::string& title,
                                std::string& type) {
  try {
    return testWith(Bytes::ofMemory(data, size), name, title, type);
  } catch (const std::bad_alloc&) {
    return false;
  }
}

bool ffmpeg_decoder_load_fd(int fd, const char* name, int sampleRate) {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return loadLocked(state, Bytes::ofFd(fd), name, sampleRate);
}

bool ffmpeg_decoder_load_memory(const uint8_t* data, size_t size, const char* name, int sampleRate) {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  try {
    return loadLocked(state, Bytes::ofMemory(data, size), name, sampleRate);
  } catch (const std::bad_alloc&) {
    LOG_INFO("no memory for the file");
    return false;
  }
}

void ffmpeg_decoder_release() {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool ffmpeg_decoder_loaded() { return FfState::instance().loaded; }
const char* ffmpeg_decoder_type() { return FfState::instance().type.c_str(); }
const char* ffmpeg_decoder_title() { return FfState::instance().title.c_str(); }
const char* ffmpeg_decoder_comment() { return FfState::instance().comment.c_str(); }
std::string ffmpeg_decoder_lyrics() {
  // A copy, under the lock: asked from a thread of the app's at any moment, a load's included.
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.loaded ? state.lyrics : std::string();
}

void ffmpeg_decoder_prepare_start(int rate) {
  ffmpeg_decoder_set_sample_rate(rate);
  FfState::instance().visuals.reset();
}

void ffmpeg_decoder_set_sample_rate(int rate) {
  FfState& state = FfState::instance();
  int64_t atMs = -1;
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (rate <= 0 || rate == state.sampleRate) return;
    atMs = state.rendered * 1000 / std::max(1, state.sampleRate);
    state.sampleRate = rate;
    state.outRate.store(rate, std::memory_order_relaxed);
    state.stage.configure(rate);
    if (!state.loaded) return;
  }
  // Decoded again from where it was, at the new rate: what is in the ring is at the old one.
  askFor(state, atMs);
}

int ffmpeg_decoder_duration_ms() { return FfState::instance().length.totalMs(); }
bool ffmpeg_decoder_duration_known() { return FfState::instance().length.known; }

int ffmpeg_decoder_position_ms() {
  FfState& state = FfState::instance();
  const int64_t heading = state.headingMs.load(std::memory_order_acquire);
  if (heading >= 0) return static_cast<int>(heading);
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

void ffmpeg_decoder_seek_ms(int ms) {
  FfState& state = FfState::instance();
  int total = 0;
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.loaded) return;
    total = state.length.totalMs();
    state.stage.reset();
    state.ended = false;
    state.visuals.reset();
  }
  askFor(state, total > 0 ? std::clamp(ms, 0, total - 1) : std::max(0, ms));
}

bool ffmpeg_decoder_ended() { return FfState::instance().ended; }

void ffmpeg_decoder_set_loop(bool loop) {
  FfState& state = FfState::instance();
  state.loop.store(loop, std::memory_order_release);
  state.asked.notify_all();
}

double ffmpeg_decoder_render_position_ms() {
  // Asked from the audio callback at every piece of a buffer: no lock, and never the storage.
  FfState& state = FfState::instance();
  const int64_t heading = state.headingMs.load(std::memory_order_acquire);
  return heading >= 0 ? static_cast<double>(heading) : state.positionMs.load(std::memory_order_relaxed);
}

void ffmpeg_decoder_loop_seek_ms(int ms) {
  FfState& state = FfState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.loaded) return;
    state.stage.reset();
    state.ended = false;
  }
  askFor(state, std::max(0, ms));
}

void ffmpeg_decoder_set_duration_override_ms(int ms) {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.length.overrideMs = ms > 0 ? ms : 0;
}

void ffmpeg_decoder_set_pitch(double semitones) {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  state.stage.setPitch(state.pitchSemitones);
}

void ffmpeg_decoder_set_tempo(double percent) {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  state.stage.setTempo(state.tempoFactor);
}

void ffmpeg_decoder_set_stereo(int percent) { g_stereoPercent.store(std::clamp(percent, 0, 200), std::memory_order_relaxed); }

int ffmpeg_decoder_channels() {
  const FfState& state = FfState::instance();
  return state.loaded ? state.shown : 0;
}

const char* ffmpeg_decoder_channel_name(int index) {
  const FfState& state = FfState::instance();
  if (index < 0 || index >= state.shown) return "";
  return state.shown == 1 ? kMonoName : kStereoNames[index];
}

void ffmpeg_decoder_mute(int channel, int muted) {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= 2) return;
  state.muted[static_cast<size_t>(channel)] = muted ? 1 : 0;
}

int ffmpeg_decoder_muted(int channel) {
  const FfState& state = FfState::instance();
  return channel >= 0 && channel < 2 ? state.muted[static_cast<size_t>(channel)] : 0;
}

bool ffmpeg_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return FfState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int ffmpeg_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  FfState& state = FfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  if (!state.loaded || state.ended) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * numChannels * bytesPerSample);
    return state.loaded ? 1 : 0;
  }

  const int64_t base = get_frames_written();
  const ShiftStage::Source source = [&state](float* left, float* right, int frames) {
    return produceLocked(state, left, right, frames);
  };
  const auto publish = [&state, base](int32_t written) {
    const double delay = state.stage.latencyFrames() / std::max(state.tempoFactor, 0.1);
    publishVisuals(state, base + written + static_cast<int64_t>(delay));
  };
  const int ended = psf::renderSong(state.stage, state.out, state.sampleRate, source, publish, audioData, numFrames,
                                    numChannels, bytesPerSample);
  state.positionMs.store((state.rendered - state.stage.latencyFrames()) * 1000.0 / std::max(1, state.sampleRate),
                         std::memory_order_relaxed);
  if (ended) {
    state.ended = true;
    return 1;
  }
  return 0;
}
