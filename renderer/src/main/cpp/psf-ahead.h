#pragma once
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

namespace psf {

/**
 * A console made ahead, on a thread of its own, for a jump that is coming:
 * the start of the A-B loop, or the top of the song when it repeats.
 *
 * An emulated console has no way to start part-way, so getting to a place is
 * running it there from the top - for the start of a loop seconds of work,
 * which the audio thread, making the jump, cannot spend. It swaps in the one
 * made here instead, and asks for the next.
 *
 * [Console] owns one console and frees it when it goes - a unique_ptr with
 * the emulator's own deleter. What making one takes is the decoder's: it
 * hands over a job, which makes a console and runs it to where it is wanted,
 * asking between steps whether it still is.
 *
 * The worker takes the mutex here and no other, so a decoder can hold its
 * own while it calls in, never the other way round.
 */
template <typename Console>
class Ahead {
 public:
  /** A console run to [frame] of the song, or none. */
  struct Made {
    Console console;
    int64_t frame = 0;
  };
  /** Makes the console, asking [wanted] between steps whether it is still wanted; none when it is not. */
  using Job = std::function<Made(const std::function<bool()>& wanted)>;

  ~Ahead() { stop(); }

  /** Where the console asked for is wanted, -1 for nowhere. */
  int wantedMs() {
    std::lock_guard<std::mutex> lock(mutex_);
    return wantedMs_;
  }

  /**
   * Asks for a console for [ms], made by [job]: -1 asks for none. Whatever
   * was made before, or is on its way, is dropped.
   */
  void ask(int ms, Job job) {
    std::lock_guard<std::mutex> lock(mutex_);
    wantedMs_ = ms;
    job_ = ms >= 0 ? std::move(job) : Job();
    asked_++;
    if (made_.console) trash_.push_back(std::move(made_.console));
    if (!thread_.joinable()) thread_ = std::thread([this] { work(); });
    wake_.notify_one();
  }

  /** The console made for [ms], if there is one and it is no further on than [atMost]: the caller's now. */
  bool take(int ms, int64_t atMost, Made& out) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!made_.console || madeMs_ != ms || madeFor_ != asked_ || made_.frame > atMost) return false;
    out = std::move(made_);
    made_ = Made();
    return true;
  }

  /** Frees [console] on the worker's time rather than the caller's - which is the audio thread's. */
  void discard(Console console) {
    if (!console) return;
    std::lock_guard<std::mutex> lock(mutex_);
    trash_.push_back(std::move(console));
    if (!thread_.joinable()) thread_ = std::thread([this] { work(); });
    wake_.notify_one();
  }

  /** Stops the worker and frees what it made. What its jobs read must outlive this. */
  void stop() {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      quit_ = true;
      wantedMs_ = -1;
    }
    wake_.notify_one();
    if (thread_.joinable()) thread_.join();
    made_ = Made();
    trash_.clear();
    job_ = Job();
    quit_ = false;
    madeFor_ = asked_;
    madeMs_ = -1;
  }

 private:
  void work() {
    std::unique_lock<std::mutex> lock(mutex_);
    for (;;) {
      wake_.wait(lock, [this] {
        return quit_ || !trash_.empty() || (wantedMs_ >= 0 && madeFor_ != asked_);
      });
      if (!trash_.empty()) {
        std::vector<Console> old = std::move(trash_);
        trash_.clear();
        lock.unlock();
        old.clear();
        lock.lock();
        continue;
      }
      if (quit_) break;
      const uint64_t request = asked_;
      const int targetMs = wantedMs_;
      const Job job = job_;
      lock.unlock();

      const std::function<bool()> wanted = [this, request] {
        std::lock_guard<std::mutex> check(mutex_);
        return !quit_ && asked_ == request;
      };
      Made made = job(wanted);

      lock.lock();
      if (asked_ == request) {
        // Answered, even with nothing when there was no memory: not tried
        // again until asked again.
        madeFor_ = request;
        if (made.console && !quit_) {
          made_ = std::move(made);
          madeMs_ = targetMs;
        }
      }
      if (made.console) {
        lock.unlock();
        made = Made();
        lock.lock();
      }
    }
  }

  std::mutex mutex_;
  std::condition_variable wake_;
  std::thread thread_;
  bool quit_ = false;
  Job job_;
  int wantedMs_ = -1;
  /** Bumped by every request; [madeFor_] is the request [made_] answers. */
  uint64_t asked_ = 0;
  uint64_t madeFor_ = 0;
  Made made_;
  int madeMs_ = -1;
  /** Consoles swapped out, freed on the worker's time rather than the audio thread's. */
  std::vector<Console> trash_;
};

}  // namespace psf
