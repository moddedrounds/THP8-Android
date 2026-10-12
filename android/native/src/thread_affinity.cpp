#include "thread_affinity.h"

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <rex/cvar.h>

#include "live_settings.h"

REXCVAR_DEFINE_BOOL(android_thread_affinity, false, "Android",
                    "Give the busiest threads a core each, fastest cores first, and keep the rest "
                    "off those cores (see thread_affinity.h)");

REXCVAR_DEFINE_INT32(android_affinity_mode, 1, "Android",
                     "0 = pin each hot thread to one performance core (and keep the rest off them); "
                     "1 = let hot threads use every performance core. Qualcomm core_ctl pauses big "
                     "cores (prime: min_cpus 0, gold: one of four), and a thread pinned to a paused "
                     "core waited up to 40 ms in the run queue - the remaining slow frames. "
                     "2 = like 1, but only the GPU command processor may use the fastest core (the "
                     "game's main thread and everything else run on the others), so the command "
                     "processor no longer shares the prime core with the main thread. "
                     "3 = like 2, and the guest vblank thread may use the fastest core too. "
                     "4 = like 2, and the threads that are not hot run on the little cores only "
                     "(the mid cores stay free for the hot threads when the prime core is parked). "
                     "5 = no game thread on the fastest (prime) core: the mid cores are more "
                     "power-efficient, and core_ctl can park the prime core. "
                     "6 = like 5, but the GPU command processor moves to the prime core while it "
                     "needs more than android_cp_prime_up_pct of a mid core (heavy views) and back "
                     "when it needs less than android_cp_prime_down_pct of the prime core.");
REXCVAR_DEFINE_BOOL(android_affinity_rerank_pin, false, "Android",
                    "Diagnostic: the old behaviour of modes 2 / 3, where re-ranking the hot threads "
                    "pinned the swapped threads to single cores")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_BOOL(android_cp_prime_only, false, "Android",
                    "Modes 2 / 3: the GPU command processor runs on the fastest (prime) core only "
                    "instead of on any performance core")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_INT32(android_cp_prime_up_pct, 80, "Android",
                     "Mode 6: command processor CPU time per frame (percent of the frame period, "
                     "smoothed) above which it moves to the prime core");
REXCVAR_DEFINE_INT32(android_cp_prime_down_pct, 45, "Android",
                     "Mode 6: the same on the prime core below which (for 5 s) it moves back");

REXCVAR_DEFINE_BOOL(android_thread_boost, true, "Android",
                    "Raise the scheduling priority of the frame-pacing threads (guest vblank, GPU "
                    "command processor) and of the pinned hot game threads, so a vblank or a fence "
                    "wakes them within microseconds instead of after whatever else holds the core "
                    "(measured: 2.6-5 ms late wakes -> 36-38 ms frames). Needs android_thread_affinity.");

REXCVAR_DEFINE_INT32(android_uclamp_max, 0, "Android",
                     "Highest CPU utilisation (percent, 1-100) the game's threads other than the GPU "
                     "command processor may request from the frequency governor (sched uclamp.max; a "
                     "process may lower its own). 0 = no clamp. Lower = lower clocks on the cores "
                     "those threads run on")
    .range(0, 100);
REXCVAR_DEFINE_INT32(android_uclamp_max_cp, 0, "Android",
                     "Same for the GPU command processor thread (0 = no clamp)")
    .range(0, 100);
REXCVAR_DEFINE_BOOL(android_perf_hint, false, "Android",
                    "Report the GPU command processor's work per frame (CPU time + run-queue wait) "
                    "to Android's performance hints (ADPF) for the command processor, main and "
                    "vblank threads, with the frame period as the target, so the platform raises "
                    "clocks / keeps cores online when frames run long");

namespace thps::affinity {
namespace {

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "thps_affinity", __VA_ARGS__)

struct Core {
  int index;
  long max_khz;
};

// Which cores are worth reserving for hot threads: those within 80% of the fastest one's clock.
// On a Snapdragon 8 Gen 2 that is the X3 plus the four mid cores, leaving the three little ones.
std::vector<int> FindPerformanceCores(int* total_cores) {
  const int n = int(sysconf(_SC_NPROCESSORS_CONF));
  *total_cores = n;
  std::vector<Core> cores;
  for (int i = 0; i < n; ++i) {
    std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
    long khz = 0;
    f >> khz;
    cores.push_back({i, khz});
  }
  long best = 0;
  for (const Core& c : cores) best = std::max(best, c.max_khz);
  std::vector<Core> fast;
  for (const Core& c : cores) {
    if (best > 0 && c.max_khz * 10 >= best * 8) fast.push_back(c);
  }
  // Fastest first; among equals the higher index first (the prime core is the highest-numbered).
  std::sort(fast.begin(), fast.end(), [](const Core& a, const Core& b) {
    return a.max_khz != b.max_khz ? a.max_khz > b.max_khz : a.index > b.index;
  });
  std::vector<int> out;
  for (const Core& c : fast) out.push_back(c.index);
  if (int(out.size()) == n) out.clear();  // all cores alike: nothing to arrange
  return out;
}

struct ThreadInfo {
  std::string name;
  uint64_t ticks = 0;
};

bool ReadThread(int tid, ThreadInfo* out) {
  std::string base = "/proc/self/task/" + std::to_string(tid);
  std::ifstream comm(base + "/comm");
  if (!comm) return false;
  std::getline(comm, out->name);
  std::ifstream stat(base + "/stat");
  std::string line;
  if (!std::getline(stat, line)) return false;
  // The name is in parentheses and can contain spaces: fields resume after the last ')'.
  const size_t close = line.rfind(')');
  if (close == std::string::npos) return false;
  std::istringstream rest(line.substr(close + 2));
  std::string field;
  uint64_t utime = 0, stime = 0;
  for (int i = 3; i <= 15 && (rest >> field); ++i) {   // field 14 = utime, 15 = stime
    if (i == 14) utime = std::strtoull(field.c_str(), nullptr, 10);
    if (i == 15) stime = std::strtoull(field.c_str(), nullptr, 10);
  }
  out->ticks = utime + stime;
  return true;
}

// Threads this manager is allowed to move: the game's own, not the Java runtime's or the system's.
bool Managed(const std::string& name) {
  // "SDL" also covers SDLActivity (nice -10) and SDLAudioP* (nice -16), "AudioTrack" the platform's
  // audio callback thread (nice -16): they were free to run on the cores reserved for the pacing
  // threads and could preempt them for milliseconds.
  static const char* kPrefixes[] = {"XThread", "GPU ", "Main XThread", "Audio", "XMA", "Vulkan Pipeline",
                                    "SDL", "AudioTrack", "Kernel Dispatch", "Timer", "Worker"};
  for (const char* p : kPrefixes) {
    if (name.rfind(p, 0) == 0) return true;
  }
  return false;
}

// ADPF (APerformanceHint, NDK API 33), loaded at run time (minSdk is lower).
// sched_setattr with only the utilisation clamp changed (policy, nice and the rest kept).
struct SchedAttr {
  uint32_t size;
  uint32_t sched_policy;
  uint64_t sched_flags;
  int32_t sched_nice;
  uint32_t sched_priority;
  uint64_t sched_runtime, sched_deadline, sched_period;
  uint32_t sched_util_min, sched_util_max;
};

bool SetUclampMax(int tid, int percent) {
  SchedAttr attr = {};
  attr.size = sizeof(attr);
  constexpr uint64_t kKeepPolicy = 0x08, kKeepParams = 0x10, kUtilClampMax = 0x40;
  attr.sched_flags = kKeepPolicy | kKeepParams | kUtilClampMax;
  attr.sched_util_max = percent > 0 ? uint32_t(percent * 1024 / 100) : 1024;
  return syscall(SYS_sched_setattr, tid, &attr, 0) == 0;
}

struct PerfHint {
  void* (*get_manager)() = nullptr;
  void* (*create_session)(void* manager, const int32_t* tids, size_t count, int64_t target_ns) =
      nullptr;
  int (*report_actual)(void* session, int64_t actual_ns) = nullptr;
  int (*update_target)(void* session, int64_t target_ns) = nullptr;
  void (*close_session)(void* session) = nullptr;
  void* session = nullptr;
  int64_t target_ns = 0;
  bool failed = false;
  std::chrono::steady_clock::time_point next_try;
};
PerfHint g_perf_hint;

int FindThread(const char* prefix) {
  int found = 0;
  if (DIR* d = opendir("/proc/self/task")) {
    while (dirent* e = readdir(d)) {
      const int tid = std::atoi(e->d_name);
      std::ifstream comm("/proc/self/task/" + std::string(e->d_name) + "/comm");
      std::string name;
      if (tid > 0 && std::getline(comm, name) && name.rfind(prefix, 0) == 0) {
        found = tid;
        break;
      }
    }
    closedir(d);
  }
  return found;
}

// Command processor thread, every swap.
// Command processor CPU time per frame as a fraction of the frame period (smoothed; mode 6).
std::atomic<float> g_cp_frame_load{0.0f};

void ReportFrameWork(uint64_t cpu_ns, uint64_t runq_ns, uint64_t target_ns_u) {
  if (target_ns_u) {
    const float load = float(double(cpu_ns) / double(target_ns_u));
    const float old = g_cp_frame_load.load(std::memory_order_relaxed);
    g_cp_frame_load.store(old * 0.9f + load * 0.1f, std::memory_order_relaxed);
  }
  PerfHint& h = g_perf_hint;
  if (h.failed) return;
  if (!REXCVAR_GET(android_perf_hint)) {
    // Switched off while running: end the session (its boost / clamp goes with it).
    if (h.session && h.close_session) {
      h.close_session(h.session);
      h.session = nullptr;
      LOGI("performance hints: session closed");
    }
    return;
  }
  if (!h.session) {
    const auto now = std::chrono::steady_clock::now();
    if (now < h.next_try) return;
    h.next_try = now + std::chrono::seconds(2);
    if (!h.get_manager) {
      void* lib = dlopen("libandroid.so", RTLD_NOW);
      if (lib) {
        h.get_manager = reinterpret_cast<void* (*)()>(dlsym(lib, "APerformanceHint_getManager"));
        h.create_session = reinterpret_cast<void* (*)(void*, const int32_t*, size_t, int64_t)>(
            dlsym(lib, "APerformanceHint_createSession"));
        h.report_actual = reinterpret_cast<int (*)(void*, int64_t)>(
            dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
        h.update_target = reinterpret_cast<int (*)(void*, int64_t)>(
            dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
        h.close_session = reinterpret_cast<void (*)(void*)>(dlsym(lib, "APerformanceHint_closeSession"));
      }
      if (!h.get_manager || !h.create_session || !h.report_actual) {
        LOGI("performance hints: not available");
        h.failed = true;
        return;
      }
    }
    void* manager = h.get_manager();
    const int32_t main_tid = FindThread("Main XThread");
    const int32_t vsync_tid = FindThread("GPU VSync");
    if (!manager || !main_tid || !vsync_tid) return;  // threads not up yet: retry later
    const int32_t tids[] = {int32_t(gettid()), main_tid, vsync_tid};
    const int64_t target_ns = int64_t(target_ns_u);
    h.session = h.create_session(manager, tids, 3, target_ns);
    h.target_ns = target_ns;
    LOGI("performance hints: session %s (target %.1f ms, threads %d %d %d)",
         h.session ? "created" : "NOT created", target_ns / 1e6, tids[0], tids[1], tids[2]);
    if (!h.session) {
      h.failed = true;
      return;
    }
  }
  if (int64_t(target_ns_u) != h.target_ns && h.update_target) {
    // The frame rate cap changed (in-game menu).
    h.target_ns = int64_t(target_ns_u);
    h.update_target(h.session, h.target_ns);
  }
  const int64_t actual_ns = int64_t(cpu_ns + runq_ns);
  if (actual_ns > 0) {
    h.report_actual(h.session, actual_ns);
  }
}

void SetMask(int tid, const std::vector<int>& cores) {
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int c : cores) CPU_SET(c, &set);
  sched_setaffinity(tid, sizeof(set), &set);
}

class ManagerImpl : public Manager {
 public:
  ManagerImpl(std::vector<int> performance_cores, int total_cores)
      : performance_(std::move(performance_cores)), total_(total_cores) {
    thread_ = std::thread([this] { Run(); });
  }
  ~ManagerImpl() override {
    stop_ = true;
    if (thread_.joinable()) thread_.join();
  }

 private:
  void Run() {
    pthread_setname_np(pthread_self(), "thps affinity");
    const long hz = sysconf(_SC_CLK_TCK);
    std::map<int, uint64_t> last_ticks;
    std::map<int, double> smoothed;          // tid -> smoothed CPU% of one core
    std::map<int, int> pinned;               // tid -> core
    std::map<int, uint64_t> applied_rest;    // tid -> mask last applied as "rest"
    std::map<int, int> applied_nice;         // tid -> nice value last applied
    std::map<int, int> applied_uclamp;       // tid -> uclamp.max percent last applied (0 = none)
    auto last = std::chrono::steady_clock::now();
    // The cap on pinned threads leaves at least one performance core for everything else.
    const size_t max_pinned = performance_.size() > 1 ? performance_.size() - 1 : 1;
    int mode = REXCVAR_GET(android_affinity_mode);

    while (!stop_) {
      std::this_thread::sleep_for(std::chrono::milliseconds(1000));
      thps::live::PollFile();
      const auto now = std::chrono::steady_clock::now();
      const double dt = std::chrono::duration<double>(now - last).count();
      last = now;

      std::map<int, ThreadInfo> threads;
      if (DIR* d = opendir("/proc/self/task")) {
        while (dirent* e = readdir(d)) {
          const int tid = std::atoi(e->d_name);
          ThreadInfo info;
          if (tid > 0 && ReadThread(tid, &info) && Managed(info.name)) threads[tid] = info;
        }
        closedir(d);
      }

      for (auto& [tid, info] : threads) {
        auto it = last_ticks.find(tid);
        if (it != last_ticks.end() && dt > 0) {
          const double pct = double(info.ticks - it->second) * 100.0 / (double(hz) * dt);
          smoothed[tid] = smoothed.count(tid) ? 0.6 * smoothed[tid] + 0.4 * pct : pct;
        }
        last_ticks[tid] = info.ticks;
      }
      // Forget threads that exited.
      for (auto it = pinned.begin(); it != pinned.end();) it = threads.count(it->first) ? std::next(it) : pinned.erase(it);

      // The mode changed while running (in-game menu: 30 fps modes use 1, "up to 60" uses 2):
      // re-apply the pinned threads' masks; the rest are re-applied below.
      static bool prime_only = false;
      if (REXCVAR_GET(android_affinity_mode) != mode ||
          REXCVAR_GET(android_cp_prime_only) != prime_only) {
        mode = REXCVAR_GET(android_affinity_mode);
        prime_only = REXCVAR_GET(android_cp_prime_only);
        for (auto& [t, c] : pinned) {
          SetMask(t, mode >= 2 ? MaskMode2(threads[t].name) : mode == 1 ? performance_ : std::vector<int>{c});
        }
        applied_rest.clear();
        LOGI("affinity mode changed to %d", mode);
      }

      // Mode 6: move the command processor between the mid cores and the prime core by load.
      if (mode == 6) {
        int cp_tid = 0;
        double cp_pct = -1.0;
        for (auto& [tid, info] : threads) {
          if (info.name.rfind("GPU Commands", 0) == 0 && smoothed.count(tid) && smoothed[tid] > cp_pct) {
            cp_tid = tid;
            cp_pct = smoothed[tid];
          }
        }
        static int calm_seconds = 0;
        bool change = false;
        cp_pct = 100.0 * g_cp_frame_load.load(std::memory_order_relaxed);  // % of the frame period
        if (cp_tid && !cp_on_prime_ && cp_pct > REXCVAR_GET(android_cp_prime_up_pct)) {
          cp_on_prime_ = true;
          change = true;
        } else if (cp_tid && cp_on_prime_) {
          calm_seconds = cp_pct < REXCVAR_GET(android_cp_prime_down_pct) ? calm_seconds + 1 : 0;
          if (calm_seconds >= 5) {
            cp_on_prime_ = false;
            change = true;
          }
        }
        if (change) {
          calm_seconds = 0;
          SetMask(cp_tid, MaskMode2(threads[cp_tid].name));
          applied_rest.erase(cp_tid);
          LOGI("command processor (%.0f%%) %s the prime core", cp_pct,
               cp_on_prime_ ? "moved to" : "moved off");
        }
      }

      // Release threads that cooled down; claim cores for new hot ones.
      for (auto it = pinned.begin(); it != pinned.end();) {
        if (smoothed[it->first] < 15.0) {
          SetMask(it->first, All());
          applied_rest.erase(it->first);
          it = pinned.erase(it);
        } else {
          ++it;
        }
      }
      std::vector<std::pair<double, int>> hot;
      for (auto& [tid, info] : threads) {
        if (!pinned.count(tid) && smoothed.count(tid) && smoothed[tid] >= 30.0) hot.push_back({smoothed[tid], tid});
      }
      std::sort(hot.rbegin(), hot.rend());
      for (auto& [pct, tid] : hot) {
        if (pinned.size() >= max_pinned) break;
        for (int core : performance_) {
          bool taken = false;
          for (auto& [t, c] : pinned) taken |= (c == core);
          if (taken) continue;
          pinned[tid] = core;
          SetMask(tid, REXCVAR_GET(android_affinity_mode) >= 2 ? MaskMode2(threads[tid].name)
                       : REXCVAR_GET(android_affinity_mode) == 1 ? performance_
                                                                 : std::vector<int>{core});
          LOGI("pinned '%s' (tid %d, %.0f%%) to cpu%d", threads[tid].name.c_str(), tid, pct, core);
          break;
        }
      }

      // Re-rank: the busiest pinned thread belongs on the fastest core. One swap per round, and only
      // when the gap is clear (>25%), so cores do not ping-pong.
      {
        std::vector<std::pair<int, int>> by_core;  // (position in priority order, tid)
        for (auto& [t, c] : pinned) {
          for (size_t pos = 0; pos < performance_.size(); ++pos) {
            if (performance_[pos] == c) by_core.push_back({int(pos), t});
          }
        }
        std::sort(by_core.begin(), by_core.end());
        bool swapped = false;
        for (size_t i = 0; i < by_core.size() && !swapped; ++i) {
          for (size_t j = i + 1; j < by_core.size() && !swapped; ++j) {
            const int ti = by_core[i].second, tj = by_core[j].second;
            if (smoothed[tj] > smoothed[ti] * 1.25 && smoothed[tj] >= 30.0) {
              const int ci = pinned[ti], cj = pinned[tj];
              pinned[ti] = cj;
              pinned[tj] = ci;
              // Only mode 0 pins threads to single cores. In modes 2 / 3 this used to pin the command
              // processor and the main thread to one core each and swap them every round or two
              // (the CP spent half the time pinned to a mid core, the main thread to the prime core,
              // the reverse of what mode 2 is for); android_affinity_rerank_pin restores that.
              if (REXCVAR_GET(android_affinity_mode) == 0 ||
                  (REXCVAR_GET(android_affinity_rerank_pin) && REXCVAR_GET(android_affinity_mode) != 1 &&
                   REXCVAR_GET(android_affinity_mode) < 5)) {
                SetMask(ti, {cj});
                SetMask(tj, {ci});
              } else if (REXCVAR_GET(android_affinity_mode) >= 2 && REXCVAR_GET(android_affinity_mode) <= 4) {
                SetMask(ti, MaskMode2(threads[ti].name));
                SetMask(tj, MaskMode2(threads[tj].name));
              }
              LOGI("swapped '%s' (%.0f%%) onto cpu%d, '%s' (%.0f%%) to cpu%d", threads[tj].name.c_str(),
                   smoothed[tj], ci, threads[ti].name.c_str(), smoothed[ti], cj);
              swapped = true;
            }
          }
        }
      }

      // Priorities: the pacing threads first, then the pinned hot ones (Linux nice values; apps may
      // lower their own threads' nice, as the audio threads do).
      if (REXCVAR_GET(android_thread_boost)) {
        for (auto& [tid, info] : threads) {
          int want = 0;
          if (info.name.rfind("GPU VSync", 0) == 0) {
            want = -12;
          } else if (info.name.rfind("GPU Commands", 0) == 0) {
            want = -10;
          } else if (pinned.count(tid)) {
            want = -6;
          }
          auto it = applied_nice.find(tid);
          if (it != applied_nice.end() && it->second == want) continue;
          if (setpriority(PRIO_PROCESS, tid, want) == 0) {
            if (want) LOGI("priority of '%s' (tid %d) set to nice %d", info.name.c_str(), tid, want);
          } else {
            LOGI("setpriority(%d) for '%s' failed: %s", want, info.name.c_str(), strerror(errno));
          }
          applied_nice[tid] = want;
        }
      }

      // Utilisation clamps (frequency the threads may ask for), re-applied when the settings change
      // and for new threads.
      for (auto& [tid, info] : threads) {
        const bool cp = info.name.rfind("GPU Commands", 0) == 0;
        const int want = cp ? REXCVAR_GET(android_uclamp_max_cp) : REXCVAR_GET(android_uclamp_max);
        auto it = applied_uclamp.find(tid);
        if (it != applied_uclamp.end() && it->second == want) continue;
        if (it == applied_uclamp.end() && want == 0) {
          applied_uclamp[tid] = 0;
          continue;
        }
        if (SetUclampMax(tid, want)) {
          if (cp || tid == threads.begin()->first) {
            LOGI("uclamp.max of '%s' set to %d%%", info.name.c_str(), want ? want : 100);
          }
        } else {
          static bool logged = false;
          if (!logged) {
            logged = true;
            LOGI("sched_setattr(uclamp) failed: %s", strerror(errno));
          }
        }
        applied_uclamp[tid] = want;
      }

      // Everything else keeps off the cores reserved above.
      uint64_t reserved = 0;
      if (REXCVAR_GET(android_affinity_mode) == 0) {
        for (auto& [t, c] : pinned) reserved |= (uint64_t(1) << c);
      } else if (REXCVAR_GET(android_affinity_mode) == 4) {
        for (int c : performance_) reserved |= uint64_t(1) << c;
      } else if (REXCVAR_GET(android_affinity_mode) >= 2 && !performance_.empty()) {
        reserved = uint64_t(1) << performance_[0];
      }
      std::vector<int> rest;
      for (int c = 0; c < total_; ++c) {
        if (!(reserved & (uint64_t(1) << c))) rest.push_back(c);
      }
      const uint64_t rest_mask = ~reserved;
      for (auto& [tid, info] : threads) {
        if (pinned.count(tid)) continue;
        if (REXCVAR_GET(android_affinity_mode) == 4 && info.name.rfind("GPU VSync", 0) == 0) {
          // Latency-critical even when not hot: keep it on the mid cores.
          if (applied_rest[tid] != 1) {
            SetMask(tid, MaskMode2(info.name));
            applied_rest[tid] = 1;
          }
          continue;
        }
        if (REXCVAR_GET(android_affinity_mode) >= 2 && PrimeAllowed(info.name)) {
          // Not hot (yet): still allowed on the fastest core.
          if (applied_rest[tid] != ~uint64_t(0)) {
            SetMask(tid, performance_);
            applied_rest[tid] = ~uint64_t(0);
          }
          continue;
        }
        auto it = applied_rest.find(tid);
        if (it == applied_rest.end() || it->second != rest_mask) {
          SetMask(tid, rest);
          applied_rest[tid] = rest_mask;
        }
      }
    }
  }

  // Mode 2: the command processor may use every performance core; other hot threads all but the
  // fastest one.
  // Modes 2 / 3: the threads that may use the fastest core.
  static inline bool cp_on_prime_ = false;  // mode 6 state (manager thread only)
  static bool PrimeAllowed(const std::string& name) {
    if (REXCVAR_GET(android_affinity_mode) == 5) return false;
    if (REXCVAR_GET(android_affinity_mode) == 6) {
      return cp_on_prime_ && name.rfind("GPU Commands", 0) == 0;
    }
    return name.rfind("GPU Commands", 0) == 0 ||
           (REXCVAR_GET(android_affinity_mode) == 3 && name.rfind("GPU VSync", 0) == 0);
  }
  std::vector<int> MaskMode2(const std::string& name) const {
    if (REXCVAR_GET(android_cp_prime_only) && !performance_.empty() &&
        name.rfind("GPU Commands", 0) == 0 && PrimeAllowed(name)) {
      return {performance_[0]};
    }
    if (PrimeAllowed(name) || performance_.size() < 2) return performance_;
    return std::vector<int>(performance_.begin() + 1, performance_.end());
  }

  std::vector<int> All() const {
    std::vector<int> v;
    for (int c = 0; c < total_; ++c) v.push_back(c);
    return v;
  }

  std::vector<int> performance_;
  int total_;
  std::atomic<bool> stop_{false};
  std::thread thread_;
};

}  // namespace

std::unique_ptr<Manager> Start() {
  {
    // Exported by the GPU plugin (SDK command_processor.cpp), loaded with local symbols.
    void* set_hook_symbol = dlsym(RTLD_DEFAULT, "rex_gpu_set_frame_work_hook");
    for (const char* library : {"librexgpu-xenosrd.so", "librexgpu-xenos.so"}) {
      if (set_hook_symbol) break;
      if (void* handle = dlopen(library, RTLD_NOW | RTLD_NOLOAD)) {
        set_hook_symbol = dlsym(handle, "rex_gpu_set_frame_work_hook");
      }
    }
    auto set_hook = reinterpret_cast<void (*)(void (*)(uint64_t, uint64_t, uint64_t))>(
        set_hook_symbol);
    if (set_hook) {
      set_hook(&ReportFrameWork);
    } else {
      LOGI("performance hints: rex_gpu_set_frame_work_hook not found");
    }
  }
  if (!REXCVAR_GET(android_thread_affinity)) return nullptr;
  int total = 0;
  std::vector<int> performance = FindPerformanceCores(&total);
  if (performance.empty()) {
    LOGI("all cores look alike; not managing affinity");
    return nullptr;
  }
  std::string list;
  for (int c : performance) list += " cpu" + std::to_string(c);
  LOGI("managing affinity: %d cores, performance cores in priority order:%s", total, list.c_str());
  return std::make_unique<ManagerImpl>(std::move(performance), total);
}

}  // namespace thps::affinity
