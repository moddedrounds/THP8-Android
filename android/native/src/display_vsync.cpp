// Display vsync timing for the emulator (gpu_vblank_display_lock).
//
// A thread with its own looper takes an AChoreographer frame callback on every display refresh and
// reports the vsync time (CLOCK_MONOTONIC = std::chrono::steady_clock on Android) and the measured
// refresh period to the SDK presenter (rex_ui_set_display_vsync, exported by the runtime). The guest
// vblank grid is then phase-locked to the display, so every guest frame reaches the presenter at the
// same point of a refresh instead of drifting across the moment the presenter takes the newest
// frame (measured before: 7-22 repeated and as many never-shown frames per 10 s at a steady 60).

#include "display_vsync.h"

#include <android/choreographer.h>
#include <android/log.h>
#include <android/looper.h>
#include <dlfcn.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <thread>

namespace thps::display_vsync {
namespace {

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "thps_vsync", __VA_ARGS__)

using SetFn = void (*)(int64_t, int64_t);
SetFn g_set = nullptr;
int64_t g_last_ns = 0;
double g_period_ns = 0.0;
std::atomic<bool> g_started{false};

SetFn FindSetter() {
  void* sym = dlsym(RTLD_DEFAULT, "rex_ui_set_display_vsync");
  for (const char* library : {"librexruntimerd.so", "librexruntime.so", "librexgpu-xenosrd.so",
                              "librexgpu-xenos.so"}) {
    if (sym) break;
    if (void* handle = dlopen(library, RTLD_NOW | RTLD_NOLOAD)) {
      sym = dlsym(handle, "rex_ui_set_display_vsync");
    }
  }
  return reinterpret_cast<SetFn>(sym);
}

void OnFrame(int64_t frame_time_ns, void*) {
  if (g_last_ns) {
    const double d = double(frame_time_ns - g_last_ns);
    if (g_period_ns <= 0.0) {
      if (d > 4e6 && d < 40e6) g_period_ns = d;
    } else {
      // A late callback can skip refreshes: divide by the number of periods it spans.
      const double n = std::round(d / g_period_ns);
      if (n >= 1.0 && n <= 8.0) {
        const double one = d / n;
        if (std::fabs(one - g_period_ns) < g_period_ns * 0.1) {
          g_period_ns += (one - g_period_ns) * 0.05;
        } else {
          g_period_ns = one;  // refresh rate changed (e.g. 60 <-> 120 Hz)
        }
      }
    }
  }
  g_last_ns = frame_time_ns;
  if (g_set && g_period_ns > 0.0) {
    g_set(frame_time_ns, int64_t(g_period_ns));
  }
  AChoreographer_postFrameCallback64(AChoreographer_getInstance(), OnFrame, nullptr);
}

}  // namespace

void Start() {
  if (g_started.exchange(true)) return;
  g_set = FindSetter();
  if (!g_set) {
    LOGI("rex_ui_set_display_vsync not found: no display vsync lock");
    return;
  }
  std::thread([] {
    ALooper_prepare(0);
    AChoreographer* choreographer = AChoreographer_getInstance();
    if (!choreographer) {
      LOGI("no AChoreographer on this thread");
      return;
    }
    AChoreographer_postFrameCallback64(choreographer, OnFrame, nullptr);
    LOGI("display vsync callbacks started");
    while (true) {
      ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
    }
  }).detach();
}

}  // namespace thps::display_vsync
